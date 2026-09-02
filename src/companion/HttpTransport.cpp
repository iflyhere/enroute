/***************************************************************************
 *   Copyright (C) 2026 by Soeren Gutbrod                                  *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 3 of the License, or     *
 *   (at your option) any later version.                                   *
 *                                                                         *
 *   This program is distributed in the hope that it will be useful,       *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of        *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
 *   GNU General Public License for more details.                          *
 *                                                                         *
 *   You should have received a copy of the GNU General Public License     *
 *   along with this program; if not, write to the                         *
 *   Free Software Foundation, Inc.,                                       *
 *   59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.             *
 ***************************************************************************/

#include <QGuiApplication>
#include <QHostAddress>
#include <QHttpHeaders>
#include <QHttpServerRequest>
#include <QHttpServerResponder>
#include <QTcpServer>
#include <QUrlQuery>

#include "GlobalObject.h"
#include "companion/CompanionServer.h"
#include "companion/HttpTransport.h"
#include "companion/Protocol.h"
#include "navigation/FlightRoute.h"
#include "navigation/Navigator.h"

using namespace Qt::Literals::StringLiterals;

namespace
{
    constexpr int maxAuthFailures = 10;
    constexpr int authLockoutSeconds = 60;

    // A small page that polls the navigation frame, so that the link can be checked
    // from a desktop browser. That matters more than it looks: the C++ side cannot
    // be built on every developer machine, and "open this URL and watch the numbers
    // move" is the fastest way to see whether the feature works at all.
    constexpr auto debugPage = QLatin1StringView(
        R"(<!doctype html><meta charset="utf-8"><title>Enroute companion</title>)"
        R"(<style>body{font:13px ui-monospace,monospace;background:#111;color:#eee;margin:1rem})"
        R"(pre{white-space:pre-wrap}h1{font-size:14px;color:#8cf}</style>)"
        R"(<h1>Enroute Flight Navigation &mdash; companion link</h1><pre id="o">loading&hellip;</pre>)"
        R"(<script>const k=new URLSearchParams(location.search).get('k')||'';)"
        R"(async function t(){try{const r=await fetch('/enroute/v1/nav?k='+encodeURIComponent(k));)"
        R"(document.getElementById('o').textContent=r.ok?JSON.stringify(await r.json(),null,2))"
        R"(:r.status+' '+r.statusText}catch(e){document.getElementById('o').textContent=e}})"
        R"(t();setInterval(t,1000);</script>)");
} // namespace


Companion::HttpTransport::HttpTransport(Companion::CompanionServer* server, QObject* parent)
    : QAbstractHttpServer(parent), m_server(server)
{
#if defined(Q_OS_IOS)
    connect(qGuiApp, &QGuiApplication::applicationStateChanged, this,
            [this](Qt::ApplicationState state)
            {
                if (state == Qt::ApplicationSuspended)
                {
                    m_suspended = true;
                    return;
                }
                if (m_suspended && (state == Qt::ApplicationActive))
                {
                    m_suspended = false;
                    stop();
                    start();
                }
            });
#endif
}


Companion::HttpTransport::~HttpTransport()
{
    stop();
}


//
// Public Slots
//

void Companion::HttpTransport::start()
{
    if (!serverPorts().isEmpty())
    {
        return;
    }

    auto* tcpServer = new QTcpServer();
    if (!tcpServer->listen(QHostAddress::Any, Companion::defaultPort))
    {
        setErrorString(tr("Cannot listen on port %1: %2")
                           .arg(Companion::defaultPort)
                           .arg(tcpServer->errorString()));
        delete tcpServer;
        return;
    }

    // bind() adopts the QTcpServer, so it must not be deleted here on either path.
    bind(tcpServer);

    if (serverPorts().isEmpty())
    {
        setErrorString(tr("Cannot bind to port %1.").arg(Companion::defaultPort));
        return;
    }

    setErrorString();
}


void Companion::HttpTransport::stop()
{
    const auto tcpServers = servers();
    for (auto* tcpServer : tcpServers)
    {
        if (tcpServer != nullptr)
        {
            tcpServer->close();
            tcpServer->deleteLater();
        }
    }
    m_authFailures.clear();
}


//
// Private Methods
//

bool Companion::HttpTransport::authorized(const QHttpServerRequest& request)
{
    if (m_server.isNull())
    {
        return false;
    }

    // The server listens on the IPv6 wildcard, so a request from an IPv4 client
    // arrives as an IPv4-mapped address. Normalise it before classifying.
    auto peerAddress = request.remoteAddress();
    bool mappedFromIPv4 = false;
    const auto asIPv4 = peerAddress.toIPv4Address(&mappedFromIPv4);
    if (mappedFromIPv4)
    {
        peerAddress = QHostAddress(asIPv4);
    }
    const auto peer = peerAddress.toString();

    // Only answer a client on a local network, so that the endpoint stays
    // unreachable from the internet even if the device holds a routable address.
    //
    // Note that this cannot be written as a test for isGlobal(): Qt classifies only
    // loopback, multicast, broadcast, link-local and 0.0.0.0/8 specially, and reports
    // the private-use ranges of RFC 1918 as global. That is precisely why
    // isPrivateUse() exists as a separate test.
    const auto isLocal = peerAddress.isLoopback() || peerAddress.isLinkLocal()
                         || peerAddress.isPrivateUse() || peerAddress.isUniqueLocalUnicast();
    if (!isLocal)
    {
        return false;
    }

    // Brute-force lockout.
    const auto now = QDateTime::currentDateTimeUtc();
    if (m_authFailures.contains(peer))
    {
        auto record = m_authFailures.value(peer);
        if (record.second.secsTo(now) > authLockoutSeconds)
        {
            m_authFailures.remove(peer);
        }
        else if (record.first >= maxAuthFailures)
        {
            return false;
        }
    }

    // The header is the proper channel. The query parameter exists only for the
    // browser page above, where a header cannot be set by hand.
    auto supplied = request.headers().value(QHttpHeaders::WellKnownHeader::Authorization).toByteArray();
    if (supplied.startsWith("Bearer "))
    {
        supplied = supplied.mid(7);
    }
    else
    {
        supplied = QUrlQuery(request.url()).queryItemValue(u"k"_s).toLatin1();
    }

    if (m_server->checkPairingCode(supplied))
    {
        m_authFailures.remove(peer);
        return true;
    }

    auto record = m_authFailures.value(peer, {0, now});
    record.first++;
    m_authFailures.insert(peer, record);
    return false;
}


bool Companion::HttpTransport::handleRequest(const QHttpServerRequest& request,
                                             QHttpServerResponder& responder)
{
    if (m_server.isNull())
    {
        return false;
    }

    if (request.method() != QHttpServerRequest::Method::Get)
    {
        QHttpHeaders headers;
        headers.append("Allow", "GET");
        responder.write(headers, QHttpServerResponder::StatusCode::MethodNotAllowed);
        return true;
    }

    if (!authorized(request))
    {
        // Identical in every failure case, so that a client learns nothing about
        // why it was refused.
        QHttpHeaders headers;
        headers.append(QHttpHeaders::WellKnownHeader::WWWAuthenticate, "Bearer");
        responder.write(headers, QHttpServerResponder::StatusCode::Unauthorized);
        return true;
    }

    const auto path = request.url().path();

    if (path == u"/"_s)
    {
        responder.write(QByteArray(debugPage.data(), debugPage.size()), "text/html; charset=utf-8");
        return true;
    }

    if (!path.startsWith(Companion::pathPrefix))
    {
        return false;
    }
    const auto endpoint = path.mid(Companion::pathPrefix.size());

    const auto respond = [&responder, &request](const QByteArray& document, quint32 revision)
    {
        const auto eTag = u"W/\"%1\""_s.arg(revision).toLatin1();

        if (request.headers().value(QHttpHeaders::WellKnownHeader::IfNoneMatch) == eTag)
        {
            responder.write(QHttpServerResponder::StatusCode::NotModified);
            return;
        }

        QHttpHeaders headers;
        headers.append(QHttpHeaders::WellKnownHeader::ContentType, "application/json");
        headers.append(QHttpHeaders::WellKnownHeader::CacheControl, "no-store");
        headers.append(QHttpHeaders::WellKnownHeader::ETag, eTag);
        headers.append("X-Content-Type-Options", "nosniff");
        // Deliberately no CORS header, so that a web page in the pilot's browser
        // cannot read this across origins.
        responder.write(document, headers);
    };

    const auto revisions = m_server->revisions();

    if (endpoint == u"/hello"_s)
    {
        respond(m_server->helloDocument(), revisions.route);
        return true;
    }
    if (endpoint == u"/route"_s)
    {
        respond(m_server->routeDocument(), revisions.route);
        return true;
    }
    if (endpoint == u"/nav"_s)
    {
        const auto compact = (QUrlQuery(request.url()).queryItemValue(u"fmt"_s) == u"0"_s);
        respond(compact ? m_server->navDocumentCompact() : m_server->navDocument(), revisions.nav);
        return true;
    }
    if (endpoint == u"/route.geojson"_s)
    {
        // The app's own full-fidelity route, for debugging and desktop clients.
        responder.write(GlobalObject::navigator()->flightRoute()->toGeoJSON(),
                        "application/geo+json");
        return true;
    }

    return false;
}


void Companion::HttpTransport::missingHandler(const QHttpServerRequest& request,
                                              QHttpServerResponder& responder)
{
    Q_UNUSED(request)
    responder.write(QHttpServerResponder::StatusCode::NotFound);
}


void Companion::HttpTransport::setErrorString(const QString& newErrorString)
{
    if (newErrorString == m_errorString)
    {
        return;
    }
    m_errorString = newErrorString;
    emit errorStringChanged();
}
