#!/usr/bin/env node
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

// Mock phone for the companion protocol described in doc/companion-protocol.md.
//
// Stands in for Enroute Flight Navigation so that a companion app can be built and
// tested without a Qt toolchain. Flies a canned route and serves the same documents
// the app serves, with the same rounding and the same field-omission rules.
//
// Node standard library only -- no package.json, nothing to install, nothing to audit.
//
//   node wearos/tools/mock-server.mjs --gs 90 --units nm
//
// Reach it from a watch or the emulator with adb reverse, which works on both:
//
//   adb -s <serial> reverse tcp:8973 tcp:8973
//
// then point the client at 127.0.0.1:8973.

import http from 'node:http';
import zlib from 'node:zlib';
import dgram from 'node:dgram';
import process from 'node:process';

const PROTOCOL_VERSION = 1;
const APP_VERSION = '4.0.0-mock';

// ---------------------------------------------------------------- command line

function parseArgs(argv) {
    const out = {
        port: 8973, gs: 90, units: 'nm', vunits: 'ft',
        code: '418302', beacon: true, period: 1000,
    };
    for (let i = 0; i < argv.length; i++) {
        // Both "--port 8973" and "--port=8973" are accepted, because rejecting
        // the second is the kind of papercut that costs a minute every time.
        const [key, inlineValue] = argv[i].replace(/^--/, '').split(/=(.*)/s, 2);
        if (key === 'no-beacon') { out.beacon = false; continue; }
        if (key === 'help' || key === 'h') { out.help = true; continue; }
        const value = inlineValue ?? argv[++i];
        switch (key) {
        case 'port':   out.port = Number(value); break;
        case 'gs':     out.gs = Number(value); break;
        case 'units':  out.units = value; break;
        case 'vunits': out.vunits = value; break;
        case 'code':   out.code = value; break;
        case 'period': out.period = Number(value); break;
        case 'map':    out.map = value; break;
        default: console.error(`unknown option --${key}`); process.exit(2);
        }
    }
    return out;
}

const opts = parseArgs(process.argv.slice(2));

if (opts.help) {
    console.log(`Mock Enroute companion server

  --port <n>      listen port                        (default 8973)
  --gs <kn>       simulated ground speed in knots    (default 90)
  --units <u>     nm | km | mil                      (default nm)
  --vunits <u>    ft | m                             (default ft)
  --code <nnnnnn> pairing code                       (default 418302)
  --period <ms>   nav frame period                   (default 1000)
  --no-beacon     do not broadcast UDP discovery
  --map host:port forward every /enroute/v1/map request to a real phone, and copy
                  its map fields into the capability document

The --map option exists because the map page is the one screen this mock cannot fake:
it needs real vector tiles, a real style and real aviation data. Pointing it at a phone
gives a client a moving aircraft on a flown route from here, and the map underneath it
from there -- which is the combination that is otherwise only testable in an aeroplane.

Fault injection, for exercising a client's unhappy paths:

  GET /enroute/v1/debug/status?s=offRoute   force a status value; 'bogus' for an
                                            unknown one, to prove the fallback
  GET /enroute/v1/debug/stall?s=30          stop publishing for 30 s (stale render)
  GET /enroute/v1/debug/nan                 emit a frame with every optional key absent
  GET /enroute/v1/debug/route?n=100         regenerate as a 100-waypoint route
  GET /enroute/v1/debug/notams?m=none       no NOTAM data for any waypoint
  GET /enroute/v1/debug/notams?m=warn       add the "not current" warning
  GET /enroute/v1/debug/notams?m=cap        cap at 2, so groups report "cut"
  GET /enroute/v1/debug/weather?m=none      no stations at all
  GET /enroute/v1/debug/weather?m=loading   the "downloading" flag set
  GET /enroute/v1/debug/vacs?m=none         library reachable but empty
  GET /enroute/v1/debug/vacs?m=unavailable  library could not be reached
  GET /enroute/v1/debug/log?s=Idle          force a flight detector state
  GET /enroute/v1/debug/log?m=empty         an empty logbook
  GET /enroute/v1/debug/traffic?m=silent    no receiver heartbeat
  GET /enroute/v1/debug/traffic?m=empty     receiver present, nothing around
  GET /enroute/v1/debug/traffic?a=2         raise every target to alarm level 2
  GET /enroute/v1/debug/reset               back to normal
`);
    process.exit(0);
}

// ------------------------------------------------------------------ geo helpers

const R_EARTH = 6371008.8;
const D2R = Math.PI / 180;
const R2D = 180 / Math.PI;

function distanceTo(a, b) {
    const phi1 = a.lat * D2R, phi2 = b.lat * D2R;
    const dPhi = phi2 - phi1;
    const dLam = (b.lon - a.lon) * D2R;
    const s = Math.sin(dPhi / 2) ** 2
        + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLam / 2) ** 2;
    return 2 * R_EARTH * Math.asin(Math.min(1, Math.sqrt(s)));
}

function azimuthTo(a, b) {
    const phi1 = a.lat * D2R, phi2 = b.lat * D2R;
    const dLam = (b.lon - a.lon) * D2R;
    const y = Math.sin(dLam) * Math.cos(phi2);
    const x = Math.cos(phi1) * Math.sin(phi2)
        - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLam);
    return (Math.atan2(y, x) * R2D + 360) % 360;
}

function interpolate(a, b, fraction) {
    return { lat: a.lat + (b.lat - a.lat) * fraction, lon: a.lon + (b.lon - a.lon) * fraction };
}

// --------------------------------------------------------------- the formatters
//
// These mirror Navigation::Aircraft and Units::Timespan exactly. Any divergence here
// is a bug in the mock, not a licence to differ: the whole point of shipping formatted
// strings over the wire is that the phone and the client never disagree.
//
//   Aircraft::horizontalDistanceToString  -- 0 decimals above 10 units, else 1
//   Aircraft::horizontalSpeedToString     -- rounded to a whole knot
//   Aircraft::verticalDistanceToString    -- rounded to a whole foot
//   Units::Timespan::toHoursAndMinutes    -- rounds to the nearest minute, "h:mm"
//
// Qt's %L1 applies locale digit grouping, hence toLocaleString for the integer cases.

const M_PER_NM = 1852.0;
const M_PER_MIL = 1609.344;
const M_PER_FT = 0.3048;
const MPS_PER_KN = 0.514444;

function group(n) { return n.toLocaleString('en-US'); }

function fmtHDist(metres, unit) {
    if (metres === null || !Number.isFinite(metres)) { return '-'; }
    const [value, suffix] = unit === 'km' ? [metres / 1000.0, 'km']
        : unit === 'mil' ? [metres / M_PER_MIL, 'mil']
            : [metres / M_PER_NM, 'nm'];
    // Note: the app compares the *distance*, not the rounded value, against 10 units.
    return value > 10.0 ? `${group(Math.round(value))} ${suffix}`
        : `${value.toFixed(1)} ${suffix}`;
}

function fmtSpeed(mps, unit) {
    if (mps === null || !Number.isFinite(mps)) { return '-'; }
    if (unit === 'km') { return `${group(Math.round(mps * 3.6))} km/h`; }
    if (unit === 'mil') { return `${group(Math.round(mps * 3600 / M_PER_MIL))} mph`; }
    return `${group(Math.round(mps / MPS_PER_KN))} kn`;
}

function fmtVDist(metres, unit) {
    if (metres === null || !Number.isFinite(metres)) { return '-'; }
    return unit === 'm' ? `${group(Math.round(metres))} m`
        : `${group(Math.round(metres / M_PER_FT))} ft`;
}

function fmtHoursMinutes(seconds) {
    if (seconds === null || !Number.isFinite(seconds)) { return '-:--'; }
    const sign = seconds < 0 ? '-' : '';
    const minutes = Math.round(Math.abs(seconds) / 60.0);
    return `${sign}${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, '0')}`;
}

function fmtUtcHm(epochSeconds) {
    if (epochSeconds === null || !Number.isFinite(epochSeconds)) { return '-:--'; }
    const d = new Date(epochSeconds * 1000);
    return `${d.getUTCHours()}:${String(d.getUTCMinutes()).padStart(2, '0')}`;
}

// Translated status messages, as QCoreApplication::translate("RemainingRouteBar", ...)
// would produce them. English only here; the real app reuses the app's own catalogue.
function statusText(status, offRouteThresholdM, unit) {
    switch (status) {
    case 'positionUnknown':  return 'Position unknown.';
    case 'offRoute':         return `More than ${fmtHDist(offRouteThresholdM, unit)} off route.`;
    case 'nearDestination':  return 'Near destination.';
    default:                 return '';
    }
}

// -------------------------------------------------------------------- the route

const SHORT_ROUTE = [
    { n: 'EDTF', en: 'EDTF (FREIBURG)',  lat: 48.02265, lon: 7.83258, e: 244, t: 'AD',  cat: 'AD-GLD' },
    { n: 'KIRCHZARTEN',                  lat: 47.96667, lon: 7.95000,         t: 'WP',  cat: 'WP' },
    { n: 'EDSB', en: 'EDSB (KARLSRUHE)', lat: 48.77939, lon: 8.08049, e: 122, t: 'AD',  cat: 'AD' },
    { n: 'EDTL', en: 'EDTL (LAHR)',      lat: 48.36917, lon: 7.82778, e: 156, t: 'AD',  cat: 'AD' },
];

// A synthetic long route, for load-testing a client's route rendering.
function longRoute(count) {
    const wp = [];
    for (let i = 0; i < count; i++) {
        const angle = (i / count) * 4 * Math.PI;
        wp.push({
            n: i === 0 ? 'EDTF' : `WP${String(i).padStart(2, '0')}`,
            lat: 48.02 + 0.9 * (i / count) + 0.06 * Math.sin(angle),
            lon: 7.83 + 0.5 * Math.cos(angle),
            t: i % 7 === 0 ? 'AD' : (i % 5 === 0 ? 'NAV' : 'WP'),
            cat: i % 7 === 0 ? 'AD-GRASS' : (i % 5 === 0 ? 'VOR-DME' : 'WP'),
        });
    }
    return wp;
}

// --------------------------------------------------------------- session state

const state = {
    sid: Math.floor(Math.random() * 0xffffffff),
    routeRev: 1,
    navRev: 0,
    waypoints: SHORT_ROUTE,
    forcedStatus: null,
    stallUntil: 0,
    allAbsent: false,
    notamRev: 1,
    notamsAbsent: false,
    notamWarning: null,
    notamCap: 60,
    notamsRetrievedAt: Date.now() - 42 * 60 * 1000,
    weatherRev: 1,
    weatherAbsent: false,
    weatherDownloading: false,
    vacRev: 1,
    vacsAbsent: false,
    vacsUnavailable: false,
    logRev: 1,
    logState: null,
    logEmpty: false,
    trafficRev: 1,
    trafficSilent: false,
    trafficEmpty: false,
    trafficAlarm: 0,
    startedAt: Date.now(),
};

const OFF_ROUTE_THRESHOLD_M = 5 * M_PER_NM;   // Leg::nearThreshold

function legs() {
    const out = [];
    for (let i = 0; i + 1 < state.waypoints.length; i++) {
        const a = state.waypoints[i];
        const b = state.waypoints[i + 1];
        const d = distanceTo(a, b);
        const leg = { d: Math.round(d) };
        if (d >= 100.0) { leg.tc = Number(azimuthTo(a, b).toFixed(1)); }
        out.push(leg);
    }
    return out;
}

// Where the aircraft is: progress along the route at the configured ground speed.
function flightState() {
    const groundSpeedMps = opts.gs * MPS_PER_KN;
    const elapsedS = (Date.now() - state.startedAt) / 1000.0;
    let flown = elapsedS * groundSpeedMps;

    const allLegs = legs();
    const total = allLegs.reduce((sum, l) => sum + l.d, 0);
    if (total === 0) { return null; }
    flown %= total;   // loop the flight, so a bench session never ends

    let index = 0;
    let remainingOnLeg = 0;
    for (; index < allLegs.length; index++) {
        if (flown < allLegs[index].d) { remainingOnLeg = allLegs[index].d - flown; break; }
        flown -= allLegs[index].d;
    }
    if (index >= allLegs.length) { index = allLegs.length - 1; remainingOnLeg = 0; }

    const from = state.waypoints[index];
    const to = state.waypoints[index + 1];
    const position = interpolate(from, to, allLegs[index].d ? flown / allLegs[index].d : 0);
    const distToFinal = remainingOnLeg
        + allLegs.slice(index + 1).reduce((sum, l) => sum + l.d, 0);

    return {
        legIndex: index, position, groundSpeedMps,
        track: azimuthTo(from, to),
        nextWp: to, nextDist: remainingOnLeg,
        finalWp: state.waypoints[state.waypoints.length - 1], finalDist: distToFinal,
        altitudeM: 1143 + 60 * Math.sin(elapsedS / 90),
        verticalSpeedMps: 0.66 * Math.cos(elapsedS / 90),
    };
}

// ----------------------------------------------------------------- the documents

function unitsBlock() { return { hDist: opts.units, vDist: opts.vunits }; }

function helloDocument() {
    return {
        v: PROTOCOL_VERSION, app: APP_VERSION, sid: state.sid,
        routeRev: state.routeRev,
        navPeriodMs: opts.period, units: unitsBlock(),
    };
}

function routeDocument() {
    const first = state.waypoints[0];
    const last = state.waypoints[state.waypoints.length - 1];
    const totalM = legs().reduce((sum, l) => sum + l.d, 0);
    const eteS = totalM / (opts.gs * MPS_PER_KN);

    return {
        v: PROTOCOL_VERSION, sid: state.sid, routeRev: state.routeRev,
        name: `${first.en ?? first.n} - ${last.en ?? last.n}`,
        // Plain text. The real FlightRoute::summary() may contain rich text and the
        // phone strips it before sending -- see doc/companion-protocol.md.
        summary: `Total: ${fmtHDist(totalM, opts.units)} • ETE ${fmtHoursMinutes(eteS)} h`,
        units: unitsBlock(),
        wp: state.waypoints.map((w) => {
            const out = { n: w.n };
            if (w.en && w.en !== w.n) { out.en = w.en; }
            out.c = [Number(w.lon.toFixed(5)), Number(w.lat.toFixed(5))];
            if (w.e !== undefined) { out.e = w.e; }
            out.t = w.t;
            out.cat = w.cat;
            return out;
        }),
        legs: legs(),
    };
}

function navDocument(withFmt) {
    const now = Math.floor(Date.now() / 1000);
    const doc = {
        v: PROTOCOL_VERSION, sid: state.sid,
        navRev: state.navRev, routeRev: state.routeRev, t: now,
    };

    // The "everything absent" case: a valid frame in which no optional key is present.
    // A client that crashes here has not honoured the omission rule.
    if (state.allAbsent) {
        doc.status = 'positionUnknown';
        doc.flightStatus = 'unknown';
        doc.note = '';
        if (withFmt) {
            doc.fmt = {
                nextName: '-', nextDist: '-', nextETE: '-:--', nextETA: '-:--', nextTC: '-',
                alt: '-', gs: '-',
                statusText: statusText('positionUnknown', OFF_ROUTE_THRESHOLD_M, opts.units),
            };
        }
        return doc;
    }

    const flight = flightState();
    const status = state.forcedStatus ?? (flight ? 'onRoute' : 'noRoute');
    doc.status = status;
    doc.flightStatus = opts.gs > 10 ? 'flight' : 'ground';
    doc.note = '';

    if (flight) {
        doc.leg = flight.legIndex;
        doc.own = {
            c: [Number(flight.position.lon.toFixed(5)), Number(flight.position.lat.toFixed(5))],
            alt: Math.round(flight.altitudeM),
            agl: Math.round(flight.altitudeM - 250),
            gs: Number(flight.groundSpeedMps.toFixed(1)),
            tt: Number(flight.track.toFixed(1)),
            vs: Number(flight.verticalSpeedMps.toFixed(1)),
        };
    }

    // next and final appear only while onRoute: RemainingRouteInfo guarantees its
    // nextWP* fields only in that state, so a client must not render them otherwise.
    if (status === 'onRoute' && flight) {
        const nextEte = flight.nextDist / flight.groundSpeedMps;
        const finalEte = flight.finalDist / flight.groundSpeedMps;

        doc.next = {
            n: flight.nextWp.n,
            dist: Math.round(flight.nextDist),
            ete: Math.round(nextEte),
            eta: now + Math.round(nextEte),
            tc: Number(azimuthTo(flight.position, flight.nextWp).toFixed(1)),
        };
        if (flight.finalWp.n !== flight.nextWp.n) {
            doc.final = {
                n: flight.finalWp.n,
                dist: Math.round(flight.finalDist),
                ete: Math.round(finalEte),
                eta: now + Math.round(finalEte),
            };
        }

        if (withFmt) {
            doc.fmt = {
                nextName: doc.next.n,
                nextDist: fmtHDist(flight.nextDist, opts.units),
                nextETE: fmtHoursMinutes(nextEte),
                nextETA: fmtUtcHm(doc.next.eta),
                nextTC: `${Math.round(doc.next.tc)}°`,
                alt: fmtVDist(flight.altitudeM, opts.vunits),
                gs: fmtSpeed(flight.groundSpeedMps, opts.units),
                statusText: '',
            };
            if (doc.final) {
                doc.fmt.finalName = doc.final.n;
                doc.fmt.finalDist = fmtHDist(flight.finalDist, opts.units);
                doc.fmt.finalETE = fmtHoursMinutes(finalEte);
                doc.fmt.finalETA = fmtUtcHm(doc.final.eta);
            }
        }
    } else if (withFmt) {
        doc.fmt = {
            alt: flight ? fmtVDist(flight.altitudeM, opts.vunits) : '-',
            gs: flight ? fmtSpeed(flight.groundSpeedMps, opts.units) : '-',
            statusText: statusText(status, OFF_ROUTE_THRESHOLD_M, opts.units),
        };
    }

    return doc;
}

// Publish on a timer, so navRev advances the way the real app's throttle does.
setInterval(() => {
    if (Date.now() < state.stallUntil) { return; }
    state.navRev++;
}, opts.period);

// ------------------------------------------------------------------- the server

function authorized(req, url) {
    const header = req.headers.authorization ?? '';
    const bearer = header.startsWith('Bearer ') ? header.slice(7) : null;
    const supplied = bearer ?? url.searchParams.get('k');
    if (supplied === null) { return false; }
    // Constant-time-ish: the real app uses a constant-time compare, and a mock that
    // short-circuits would let a client's tests pass against a weaker check.
    const a = Buffer.from(String(supplied));
    const b = Buffer.from(opts.code);
    if (a.length !== b.length) { return false; }
    let diff = 0;
    for (let i = 0; i < a.length; i++) { diff |= a[i] ^ b[i]; }
    return diff === 0;
}

function sendJson(res, body, etag) {
    const payload = JSON.stringify(body);
    const headers = {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(payload),
        'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff',
    };
    if (etag) { headers.ETag = etag; }
    res.writeHead(200, headers);
    res.end(payload);
}

//
// NOTAMs
//
// Deliberately awkward fixtures rather than tidy ones: a permanent NOTAM with no
// end date, one already marked read, one whose text is long enough to need
// scrolling on a watch, and one waypoint with no data at all. Those are the cases
// that break a client, and a mock that only serves the happy path is worth
// very little.
//

const NOTAM_FIXTURES = [
    {
        n: 'A1234/26', icao: 'EDNY', cat: 'NOTAM', sect: 'Current', traffic: 'IV', read: false,
        txt: 'RWY 06/24 CLSD DUE TO WIP. TWY A AVBL FOR TAXI ONLY BTN APRON 1 AND RWY 06 THR.',
        from: '2026-09-01T06:00:00Z', to: '2026-09-30T16:00:00Z',
        offset: { east: 0.010, north: 0.004 }, radius: 9260,
    },
    {
        // No "to": a permanent NOTAM. A client that assumes both dates exist
        // renders "until Invalid Date" here.
        n: 'A0087/26', icao: 'EDNY', cat: 'NOTAM-OBST', sect: 'Current', traffic: 'IV', read: false,
        txt: 'CRANE ERECTED 850M SW ARP, ELEV 1580FT AMSL, LGTD.',
        from: '2026-08-14T00:00:00Z',
        offset: { east: -0.012, north: -0.006 }, radius: 3704,
    },
    {
        n: 'A0912/26', icao: 'EDNY', cat: 'NOTAM-PJE', sect: 'Marked as read', traffic: 'IV', read: true,
        txt: 'PARACHUTE JUMPING EXERCISE WI 2NM RADIUS OF 474012N 0093021E, SFC-FL130.',
        from: '2026-09-03T07:00:00Z', to: '2026-09-03T17:00:00Z',
        offset: { east: 0.004, north: -0.014 }, radius: 3704,
    },
    {
        // No area at all, and a text long enough to need scrolling.
        n: 'W0455/26', icao: 'EDMM', cat: 'NOTAM-RA', sect: 'Next 24h', read: false,
        txt: 'TEMPO RESTRICTED AREA ESTABLISHED DUE TO AIR DISPLAY. ENTRY PROHIBITED FOR ALL '
           + 'TFC EXC ACFT PARTICIPATING IN THE DISPLAY AND ACFT ON IFR FLIGHT PLAN CLEARED BY ATC. '
           + 'CTC MUNICH INFORMATION 129.045 PRIOR TO ENTERING.',
        from: '2026-09-04T08:00:00Z', to: '2026-09-04T18:00:00Z',
    },
];

// One NOTAM with one region, sized and placed to contain the first two waypoints of the
// default route and neither of the others. That makes it genuinely shared -- the case a
// client's deduplication exists for -- while leaving the last waypoint free to stay
// confirmed empty, so all four knowledge states still appear in one pass.
const REGIONAL_NOTAM = {
    n: 'W0100/26', icao: 'EDGG', cat: 'NOTAM-RA', sect: 'Current', traffic: 'IV', read: false,
    txt: 'RESTRICTED AREA ED-R 137 ACT SFC-FL100 DUE TO MIL EXERCISE.',
    from: '2026-09-02T06:00:00Z', to: '2026-09-05T18:00:00Z',
    area: { c: [7.89000, 47.99000], r: 15000 },
};

/**
 * Puts a fixture's area near the waypoint it is listed under.
 *
 * The real phone can only ever return a NOTAM whose region contains the waypoint --
 * NOTAMList::restricted() checks exactly that -- so a fixture with a fixed centre far
 * from the route produces a document the phone could never produce, and a client's
 * culling then looks broken when it is right.
 */
/** Whether a NOTAM's circle contains a waypoint, the way the phone's filter asks. */
function contains(area, waypoint) {
    return distanceTo({ lat: area.c[1], lon: area.c[0] }, waypoint) <= area.r;
}

function at(fixture, waypoint, index) {
    const { offset, radius, ...rest } = fixture;
    if (offset === undefined) {
        return rest;
    }

    // The number is made distinct per waypoint as well. A NOTAM has exactly one
    // region, so the same number appearing twice with two different areas is another
    // document the phone cannot produce -- and a client that deduplicates by number,
    // which is the correct thing to do, would then look like it was losing data.
    return {
        ...rest,
        n: index === 0 ? rest.n : rest.n.replace('/26', `${index}/26`),
        area: {
            c: [
                Number((waypoint.lon + offset.east).toFixed(5)),
                Number((waypoint.lat + offset.north).toFixed(5)),
            ],
            r: radius,
        },
    };
}

function notamDocument() {
    notamDocument.emitted = 0;
    const groups = state.waypoints.map((waypoint, index) => {
        const group = { wp: index, n: waypoint.n };

        // The four waypoints of the default route are arranged so that all four
        // knowledge states appear at once: listed, listed, nothing known, and
        // confirmed empty. The last one is the state most worth looking at, and it
        // was unreachable here until it was put in on purpose.
        if (state.notamsAbsent || index % 4 === 2) {
            group.data = false;
            return group;
        }

        group.data = true;
        group.retrieved = new Date(state.notamsRetrievedAt).toISOString().replace(/\.\d{3}Z$/, 'Z');

        const forWaypoint = (index === 0 ? NOTAM_FIXTURES
            : index % 4 === 3 ? []
            : NOTAM_FIXTURES.slice(0, 1)).map((fixture) => at(fixture, waypoint, index));

        // Added only where the region actually contains the waypoint, because that is
        // the test NOTAMList::restricted() applies. Handing a client a NOTAM whose
        // circle does not contain the waypoint it is listed under would be a document
        // the phone cannot produce.
        if (contains(REGIONAL_NOTAM.area, waypoint)) {
            forWaypoint.push(REGIONAL_NOTAM);
        }
        const budget = Math.max(0, state.notamCap - notamDocument.emitted);
        const entries = forWaypoint.slice(0, budget);
        notamDocument.emitted += entries.length;

        if (entries.length > 0) { group.notams = entries; }
        const cut = forWaypoint.length - entries.length;
        if (cut > 0) { group.cut = cut; }
        return group;
    });

    const document = {
        v: 1,
        sid: state.sid,
        notamRev: state.notamRev,
        filter: { radius: 37040, horizontalOnly: true, flightLevelApplied: false },
        groups,
        n: notamDocument.emitted,
    };

    if (state.notamWarning) { document.warning = state.notamWarning; }

    const dropped = groups.reduce((sum, group) => sum + (group.cut ?? 0), 0);
    if (dropped > 0) { document.dropped = dropped; }

    const withData = groups.filter((group) => group.data);
    if (withData.length > 0) {
        document.retrieved = withData
            .map((group) => group.retrieved)
            .sort()[0];
    }

    return document;
}

const DEBUG_PAGE = `<!doctype html><meta charset="utf-8"><title>Enroute companion (mock)</title>
<style>body{font:13px ui-monospace,monospace;background:#111;color:#eee;margin:1rem}
pre{white-space:pre-wrap}h1{font-size:14px;color:#8cf}</style>
<h1>Enroute companion &mdash; mock phone</h1><pre id="o">loading&hellip;</pre><script>
const k=new URLSearchParams(location.search).get('k')||'';
async function tick(){try{
const r=await fetch('/enroute/v1/nav?k='+encodeURIComponent(k));
document.getElementById('o').textContent=r.ok?JSON.stringify(await r.json(),null,2):r.status+' '+r.statusText;
}catch(e){document.getElementById('o').textContent=e}}
tick();setInterval(tick,1000);</script>`;

// Fields copied from the phone's own capability document, so a client learns that a map
// is available and where to point its camera. Fetched once, lazily.
const mapPeer = opts.map ? { host: opts.map.split(':')[0], port: Number(opts.map.split(':')[1] ?? 8973) } : null;
let mapFields = null;

async function fetchMapFields() {
    if (!mapPeer || mapFields !== null) { return; }
    mapFields = {};
    try {
        const body = await new Promise((resolve, reject) => {
            const request = http.request(
                { host: mapPeer.host, port: mapPeer.port, path: '/enroute/v1/hello',
                  headers: { Authorization: `Bearer ${opts.code}` } },
                (response) => {
                    let text = '';
                    response.on('data', (chunk) => { text += chunk; });
                    response.on('end', () => resolve(text));
                });
            request.on('error', reject);
            request.end();
        });
        const hello = JSON.parse(body);
        for (const key of ['mapRev', 'mapAttribution', 'mapCentre']) {
            if (hello[key] !== undefined) { mapFields[key] = hello[key]; }
        }
        console.log('map fields from the phone:', JSON.stringify(mapFields));
    } catch (error) {
        console.log('could not reach the map peer:', error.message);
    }
}

await fetchMapFields();

// ---------------------------------------------------------------------------
// Weather
//
// One station per flight category, so the colour mapping and the "unknown" case are
// all exercised in a single pass. The colours are the app's own strings, not a guess:
// METAR::flightCategoryColor() returns exactly these four.
const WEATHER_FIXTURES = [
    {
        wp: { n: 'EDNY', en: 'FRIEDRICHSHAFEN', c: [9.51139, 47.67139], e: 417, t: 'AD', cat: 'AD-PAVED' },
        metar: {
            raw: 'METAR EDNY 031420Z 24005KT CAVOK 21/12 Q1018',
            sum: 'METAR 14 min ago: CAVOK',
            cat: 'VFR', col: 'green', obs: null,
        },
        taf: { raw: 'TAF EDNY 031100Z 0312/0412 25008KT CAVOK', iss: null },
    },
    {
        wp: { n: 'EDTL', en: 'LAHR', c: [7.8275, 48.3692], e: 156, t: 'AD', cat: 'AD-PAVED' },
        metar: {
            raw: 'METAR EDTL 031420Z 19007KT 6000 BKN012 18/14 Q1016',
            sum: 'METAR 14 min ago: MVMC',
            cat: 'MVFR', col: 'yellow', obs: null,
        },
        taf: null,
    },
    {
        // No TAF, and a low ceiling: the pair a client is most likely to mishandle.
        wp: { n: 'EDDS', en: 'STUTTGART', c: [9.22196, 48.68987], e: 1276, t: 'AD', cat: 'AD-PAVED' },
        metar: {
            raw: 'METAR EDDS 031420Z 09012KT 1200 -RA OVC004 14/13 Q1014',
            sum: 'METAR 14 min ago: IMC',
            cat: 'IFR', col: 'red', obs: null,
        },
        taf: null,
    },
    {
        // A TAF with no METAR at all, which must still produce a row.
        wp: { n: 'EDTG', en: 'BREMGARTEN', c: [7.6339, 47.9061], e: 249, t: 'AD', cat: 'AD-GRASS' },
        metar: null,
        taf: { raw: 'TAF EDTG 031100Z 0312/0412 VRB03KT 9999 SCT040', iss: null },
    },
];

function weatherDocument() {
    const now = Date.now();
    const flight = flightState();
    const stations = state.weatherAbsent ? [] : WEATHER_FIXTURES.map((fixture) => {
        const station = { wp: fixture.wp };

        // The bearing line the app writes. Computed from the simulated position and
        // through the same formatter the navigation frame uses, so it moves as the
        // aircraft flies and honours the --units flag -- which is what a client's
        // "did this actually update" check needs.
        const here = flight ? flight.position : null;
        const there = { lat: fixture.wp.c[1], lon: fixture.wp.c[0] };
        if (here) {
            station.way = 'DIST ' + fmtHDist(distanceTo(here, there), opts.units)
                + ' · QUJ ' + Math.round(azimuthTo(here, there)) + '°';
        }

        if (fixture.metar) {
            station.metar = {
                ...fixture.metar,
                obs: new Date(now - 14 * 60 * 1000).toISOString().replace(/\.\d+Z$/, 'Z'),
            };
        }
        if (fixture.taf) {
            station.taf = {
                ...fixture.taf,
                iss: new Date(now - 3 * 60 * 60 * 1000).toISOString().replace(/\.\d+Z$/, 'Z'),
            };
        }
        return station;
    });

    return {
        v: 1,
        sid: state.sid,
        weatherRev: state.weatherRev,
        qnh: '1018 hPa in EDNY, 14 min ago',
        sun: 'SS 20:12, SR 06:41',
        downloading: state.weatherDownloading,
        st: stations,
    };
}

// ---------------------------------------------------------------------------
// Approach charts
//
// The image is generated rather than shipped, because a real chart is copyrighted
// aeronautical data and a fixture must not carry any. What is drawn instead is a
// georeferencing test card: a blue square on the chart's top left corner, a green one
// on its bottom right, and a red border. If the corner order or the axis order is
// wrong anywhere between here and the renderer, the squares land in the wrong place
// and say so at a glance -- which a realistic-looking chart would not.

/** CRC-32, for the PNG chunks. */
const CRC_TABLE = (() => {
    const table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) { c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; }
        table[n] = c;
    }
    return table;
})();

function crc32(buffer) {
    let c = 0xffffffff;
    for (const byte of buffer) { c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8); }
    return (c ^ 0xffffffff) >>> 0;
}

/** One PNG chunk: length, type, payload, CRC. */
function pngChunk(type, payload) {
    const length = Buffer.alloc(4);
    length.writeUInt32BE(payload.length);
    const body = Buffer.concat([Buffer.from(type, 'latin1'), payload]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body));
    return Buffer.concat([length, body, crc]);
}

function testCardPng(size = 256) {
    const stride = size * 4 + 1;           // one filter byte per row
    const raw = Buffer.alloc(stride * size);
    const quarter = Math.floor(size / 4);

    for (let y = 0; y < size; y++) {
        const row = y * stride;
        raw[row] = 0;                      // filter: none
        for (let x = 0; x < size; x++) {
            const at = row + 1 + x * 4;
            const edge = x < 4 || y < 4 || x >= size - 4 || y >= size - 4;
            const topLeft = x < quarter && y < quarter;
            const bottomRight = x >= size - quarter && y >= size - quarter;

            let rgba;
            if (edge) { rgba = [220, 40, 40, 255]; }
            else if (topLeft) { rgba = [40, 90, 230, 255]; }
            else if (bottomRight) { rgba = [40, 190, 90, 255]; }
            // Elsewhere a light wash, deliberately semi-transparent: a chart drawn in
            // the wrong place then shows what is underneath instead of hiding it.
            else { rgba = [250, 250, 245, 150]; }
            raw[at] = rgba[0];
            raw[at + 1] = rgba[1];
            raw[at + 2] = rgba[2];
            raw[at + 3] = rgba[3];
        }
    }

    const header = Buffer.alloc(13);
    header.writeUInt32BE(size, 0);
    header.writeUInt32BE(size, 4);
    header[8] = 8;                         // 8 bits per channel
    header[9] = 6;                         // RGBA
    return Buffer.concat([
        Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
        pngChunk('IHDR', header),
        pngChunk('IDAT', zlib.deflateSync(raw)),
        pngChunk('IEND', Buffer.alloc(0)),
    ]);
}

/**
 * One chart per leg midpoint, sized so the flown route passes through it.
 *
 * Built from the route rather than hard-coded, so that --route and --long produce
 * charts the simulated aircraft actually flies into. That is the only way to watch a
 * chart appear and disappear without waiting for a real approach.
 */
function vacFixtures() {
    if (state.vacsAbsent) { return []; }
    const out = [];
    for (let i = 0; i + 1 < state.waypoints.length; i++) {
        const a = state.waypoints[i];
        const b = state.waypoints[i + 1];
        const lat = (a.lat + b.lat) / 2;
        const lon = (a.lon + b.lon) / 2;
        const dLat = 0.06;
        const dLon = 0.06 / Math.max(0.2, Math.cos(lat * Math.PI / 180));
        const north = lat + dLat;
        const south = lat - dLat;
        const west = lon - dLon;
        const east = lon + dLon;
        out.push({
            n: 'TEST-' + a.n + '-' + b.n,
            d: 'Test card between ' + a.n + ' and ' + b.n,
            sect: 'Bench fixtures',
            // Top left, top right, bottom right, bottom left.
            q: [[west, north], [east, north], [east, south], [west, south]],
            bbox: [west, south, east, north],
        });
    }
    return out;
}

function vacDocument() {
    if (state.vacsUnavailable) {
        return { v: 1, sid: state.sid, vacRev: state.vacRev, available: false, vac: [] };
    }
    return {
        v: 1, sid: state.sid, vacRev: state.vacRev,
        available: true,
        vac: vacFixtures(),
    };
}

// ---------------------------------------------------------------------------
// Flight log
//
// Entries chosen for the shapes a client is most likely to get wrong: a flight still
// running with no landing time, one that landed away from an ICAO field, one with no
// times at all, and enough of them to trip the "older flights not sent" line.

const LOG_FIXTURES = [
    {
        id: '7f1c2d90-0000-4000-8000-000000000001',
        dep: 'EDTF', arr: 'EDTL',
        hoursAgo: 2, durationMinutes: 47, cs: 'D-EABC', ldg: 1, track: true,
    },
    {
        // Landed away from an ICAO field: the arrival is genuinely unknown, and a
        // client must show a placeholder rather than an empty gap.
        id: '7f1c2d90-0000-4000-8000-000000000002',
        dep: 'EDTF', arr: null,
        hoursAgo: 26, durationMinutes: 95, cs: 'D-EABC', ldg: 3, track: false,
    },
    {
        // Still in the air: no landing time, and therefore no duration.
        id: '7f1c2d90-0000-4000-8000-000000000003',
        dep: 'EDDS', arr: null,
        hoursAgo: 0.4, durationMinutes: null, cs: 'D-EFGH', ldg: 0, track: true,
    },
    {
        // No times at all. The phone allows a hand-written entry, and a client that
        // assumes a start time crashes on this one.
        id: '7f1c2d90-0000-4000-8000-000000000004',
        dep: 'EDNY', arr: 'EDMO',
        hoursAgo: null, durationMinutes: null, cs: null, ldg: 1, track: false,
    },
];

function isoMinus(hours) {
    return new Date(Date.now() - hours * 3600 * 1000).toISOString().replace(/\.\d+Z$/, 'Z');
}

function hoursMinutes(minutes) {
    return Math.floor(minutes / 60) + ':' + String(minutes % 60).padStart(2, '0');
}

function flightLogDocument() {
    const entries = state.logEmpty ? [] : LOG_FIXTURES.map((fixture) => {
        const entry = { id: fixture.id, ldg: fixture.ldg };
        if (fixture.dep) { entry.dep = fixture.dep; }
        if (fixture.arr) { entry.arr = fixture.arr; }
        if (fixture.hoursAgo !== null) {
            entry.start = isoMinus(fixture.hoursAgo);
            entry.off = isoMinus(fixture.hoursAgo + 0.2);
            if (fixture.durationMinutes !== null) {
                entry.land = isoMinus(fixture.hoursAgo - fixture.durationMinutes / 60);
                entry.on = isoMinus(fixture.hoursAgo - fixture.durationMinutes / 60 - 0.15);
                entry.ft = hoursMinutes(fixture.durationMinutes);
                entry.bt = hoursMinutes(fixture.durationMinutes + 21);
            }
        }
        if (fixture.cs) { entry.cs = fixture.cs; }
        if (fixture.track) { entry.track = true; }
        return entry;
    });

    return {
        v: 1,
        sid: state.sid,
        logRev: state.logRev,
        // Follows the simulated flight unless forced: the mock is always flying, so
        // Idle would contradict the navigation frame it publishes in the same second.
        state: state.logState ?? 'InFlight',
        recording: true,
        // Deliberately larger than the fixture count, so the "older flights not sent"
        // line is exercised without shipping forty fixtures.
        n: state.logEmpty ? 0 : 57,
        flights: entries,
        dropped: state.logEmpty ? 0 : 57 - entries.length,
    };
}

// ---------------------------------------------------------------------------
// Traffic
//
// Targets are placed relative to the simulated aircraft, so they move with it and a
// client's map can be checked without an aeroplane. The set covers the shapes that
// break a display: an alarm-level target, one above and one below, one with no
// callsign, and one whose bearing the receiver does not know at all.

const TRAFFIC_FIXTURES = [
    { id: 'DD4711', cs: 'D-KABC', t: 'Glider', bearing: 40, rangeM: 2400, vd: 220, lvl: 0 },
    { id: 'DD0815', cs: 'D-EFGH', t: 'Aircraft', bearing: 210, rangeM: 5200, vd: -430, lvl: 0 },
    // No callsign: the receiver has an id and nothing else, which is the common case
    // for a Mode-S target.
    { id: 'A1B2C3', cs: null, t: 'Jet', bearing: 315, rangeM: 9000, vd: 1500, lvl: 0 },
    { id: 'DD2222', cs: 'D-1234', t: 'Glider', bearing: 95, rangeM: 900, vd: 30, lvl: 2 },
];

/** The app's own colours for the three alarm levels, day mode. */
function alarmColour(level) {
    if (level === 0) { return 'green'; }
    if (level === 1) { return 'yellow'; }
    return 'red';
}

function offsetFrom(origin, bearingDeg, distanceM) {
    const bearing = bearingDeg * Math.PI / 180;
    const north = distanceM * Math.cos(bearing);
    const east = distanceM * Math.sin(bearing);
    return {
        lat: origin.lat + north / 111320.0,
        lon: origin.lon + east / (111320.0 * Math.max(0.01, Math.cos(origin.lat * Math.PI / 180))),
    };
}

function trafficDocument() {
    state.trafficRev++;

    if (state.trafficSilent) {
        return {
            v: 1, sid: state.sid, trafficRev: state.trafficRev,
            rx: false,
            status: 'Not receiving traffic receiver heartbeat through any of the '
                + 'configured data connections.',
            tfc: [],
        };
    }

    const flight = flightState();
    const here = flight ? flight.position : null;
    const document = {
        v: 1, sid: state.sid, trafficRev: state.trafficRev,
        rx: true,
        status: 'Receiving traffic data from a mock receiver.',
        tfc: [],
    };
    if (!here || state.trafficEmpty) {
        return document;
    }

    document.tfc = TRAFFIC_FIXTURES.map((fixture) => {
        const level = fixture.lvl > 0 ? Math.max(fixture.lvl, state.trafficAlarm) : state.trafficAlarm;
        const at = offsetFrom(here, fixture.bearing + (flight.track ?? 0), fixture.rangeM);
        const target = {
            lvl: level,
            col: alarmColour(level),
            t: fixture.t,
            hd: fixture.rangeM,
            vd: fixture.vd,
            rel: fixture.rangeM < 6000,
            c: [at.lon, at.lat],
            // Flying roughly towards the aircraft, so the direction ticks point
            // somewhere meaningful rather than all the same way.
            trk: (fixture.bearing + 180) % 360,
            unc: 60,
            d: fixture.t + ', ' + (fixture.vd >= 0 ? '+' : '') + Math.round(fixture.vd / 0.3048)
                + ' ft relative',
        };
        if (fixture.id) { target.id = fixture.id; }
        if (fixture.cs) { target.cs = fixture.cs; }
        return target;
    });

    // Range without bearing. FLARM reports this often, it cannot go on a map, and a
    // client that silently drops it hides traffic.
    document.noBearing = {
        id: 'DD9999',
        lvl: 1,
        col: alarmColour(1),
        t: 'Aircraft',
        hd: 3100,
        vd: -90,
        rel: true,
        d: 'Traffic nearby, bearing unknown',
    };

    if (state.trafficAlarm > 0) {
        document.warning = {
            lvl: state.trafficAlarm,
            type: 2,
            d: 'Traffic, 2 o\'clock, same altitude',
            hd: 900,
            vd: 30,
        };
    }

    return document;
}

const server = http.createServer((req, res) => {
    const url = new URL(req.url, `http://${req.headers.host ?? 'localhost'}`);

    // Attached before anything can return, or a forwarded request never shows up in
    // the log and looks like a request the client never made.
    res.on('finish', () => {
        const from = req.socket.remoteAddress ?? '?';
        console.log(`${req.method} ${url.pathname} -> ${res.statusCode}  ${from}`);
    });

    // Ahead of the map forwarding on purpose: with --map a real phone answers 404 for
    // a fixture name it has never heard of, and a missing image is indistinguishable
    // from a chart drawn in the wrong place.
    if (url.pathname.startsWith('/enroute/v1/map/vac/')) {
        const body = testCardPng();
        res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': body.length });
        res.end(body);
        return;
    }

    if (mapPeer && url.pathname.startsWith('/enroute/v1/map')) {
        const upstream = http.request(
            { host: mapPeer.host, port: mapPeer.port, path: req.url,
              method: req.method, headers: req.headers },
            (response) => {
                res.writeHead(response.statusCode ?? 502, response.headers);
                response.pipe(res);
            });
        upstream.on('error', (error) => {
            res.writeHead(502, { 'Content-Type': 'text/plain' });
            res.end('map peer: ' + error.message);
        });
        req.pipe(upstream);
        return;
    }

    if (req.method !== 'GET') {
        res.writeHead(405, { Allow: 'GET' }).end();
        return;
    }
    if (!authorized(req, url)) {
        res.writeHead(401, { 'WWW-Authenticate': 'Bearer' }).end();
        return;
    }

    const path = url.pathname;

    if (path === '/') {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(DEBUG_PAGE);
        return;
    }

    if (path === '/enroute/v1/hello') {
        sendJson(res, { ...helloDocument(), ...(mapFields ?? {}) }, `W/"${state.routeRev}"`);
        return;
    }

    if (path === '/enroute/v1/route') {
        const etag = `W/"${state.routeRev}"`;
        if (req.headers['if-none-match'] === etag) { res.writeHead(304).end(); return; }
        sendJson(res, routeDocument(), etag);
        return;
    }

    if (path === '/enroute/v1/nav') {
        const etag = `W/"${state.navRev}"`;
        if (req.headers['if-none-match'] === etag) { res.writeHead(304).end(); return; }
        sendJson(res, navDocument(url.searchParams.get('fmt') !== '0'), etag);
        return;
    }

    if (path === '/enroute/v1/notams') {
        const etag = `W/"${state.notamRev}"`;
        if (req.headers['if-none-match'] === etag) { res.writeHead(304).end(); return; }
        sendJson(res, notamDocument(), etag);
        return;
    }

    if (path === '/enroute/v1/weather') {
        const etag = `W/"${state.weatherRev}"`;
        if (req.headers['if-none-match'] === etag) { res.writeHead(304).end(); return; }
        sendJson(res, weatherDocument(), etag);
        return;
    }

    if (path === '/enroute/v1/vacs') {
        const etag = `W/"${state.vacRev}"`;
        if (req.headers['if-none-match'] === etag) { res.writeHead(304).end(); return; }
        sendJson(res, vacDocument(), etag);
        return;
    }

    if (path === '/enroute/v1/traffic') {
        // Encoded per request rather than cached: the revision moves every time, the
        // way the phone's does, so a client's ETag never matches and the frame is
        // always fresh. That is deliberate for traffic and wrong for everything else.
        const body = trafficDocument();
        sendJson(res, body, `W/"${body.trafficRev}"`);
        return;
    }

    if (path === '/enroute/v1/log') {
        const etag = `W/"${state.logRev}"`;
        if (req.headers['if-none-match'] === etag) { res.writeHead(304).end(); return; }
        sendJson(res, flightLogDocument(), etag);
        return;
    }

    // Fault injection
    if (path === '/enroute/v1/debug/status') {
        const s = url.searchParams.get('s');
        state.forcedStatus = (s === null || s === 'onRoute') ? null : s;
        state.allAbsent = false;
        sendJson(res, { ok: true, forcedStatus: state.forcedStatus });
        return;
    }
    if (path === '/enroute/v1/debug/stall') {
        const seconds = Number(url.searchParams.get('s') ?? 30);
        state.stallUntil = Date.now() + seconds * 1000;
        sendJson(res, { ok: true, stallSeconds: seconds });
        return;
    }
    if (path === '/enroute/v1/debug/nan') {
        state.allAbsent = true;
        sendJson(res, { ok: true, allAbsent: true });
        return;
    }
    if (path === '/enroute/v1/debug/route') {
        const n = Math.max(2, Math.min(100, Number(url.searchParams.get('n') ?? 100)));
        state.waypoints = n <= SHORT_ROUTE.length ? SHORT_ROUTE : longRoute(n);
        state.routeRev++;
        state.startedAt = Date.now();
        sendJson(res, { ok: true, waypoints: state.waypoints.length, routeRev: state.routeRev });
        return;
    }
    if (path === '/enroute/v1/debug/notams') {
        const mode = url.searchParams.get('m') ?? 'normal';
        state.notamsAbsent = (mode === 'none');
        state.notamWarning = (mode === 'warn')
            ? 'NOTAMs not current around waypoint, requesting update'
            : null;
        state.notamCap = (mode === 'cap') ? 2 : 60;
        state.notamRev++;
        sendJson(res, { ok: true, mode, notamRev: state.notamRev });
        return;
    }
    if (path === '/enroute/v1/debug/weather') {
        const mode = url.searchParams.get('m');
        state.weatherAbsent = mode === 'none';
        state.weatherDownloading = mode === 'loading';
        state.weatherRev++;
        res.writeHead(204).end();
        return;
    }

    if (path === '/enroute/v1/debug/vacs') {
        const mode = url.searchParams.get('m');
        state.vacsAbsent = mode === 'none';
        state.vacsUnavailable = mode === 'unavailable';
        state.vacRev++;
        res.writeHead(204).end();
        return;
    }

    if (path === '/enroute/v1/debug/log') {
        const forced = url.searchParams.get('s');
        state.logState = forced && forced !== '' ? forced : null;
        state.logEmpty = url.searchParams.get('m') === 'empty';
        state.logRev++;
        res.writeHead(204).end();
        return;
    }

    if (path === '/enroute/v1/debug/traffic') {
        const mode = url.searchParams.get('m');
        state.trafficSilent = mode === 'silent';
        state.trafficEmpty = mode === 'empty';
        state.trafficAlarm = Number(url.searchParams.get('a') ?? 0) || 0;
        res.writeHead(204).end();
        return;
    }

    if (path === '/enroute/v1/debug/reset') {
        state.forcedStatus = null;
        state.stallUntil = 0;
        state.allAbsent = false;
        state.notamsAbsent = false;
        state.notamWarning = null;
        state.notamCap = 60;
        state.notamRev++;
        state.waypoints = SHORT_ROUTE;
        state.routeRev++;
        state.startedAt = Date.now();
        sendJson(res, { ok: true, routeRev: state.routeRev });
        return;
    }

    res.writeHead(404).end();
});

server.listen(opts.port, () => {
    console.log(`mock phone on http://0.0.0.0:${opts.port}  pairing code ${opts.code}`);
    console.log(`  browser: http://127.0.0.1:${opts.port}/?k=${opts.code}`);
    console.log(`  route:   ${state.waypoints.map((w) => w.n).join(' -> ')}  @ ${opts.gs} kn`);
});

// --------------------------------------------------------------- UDP discovery

if (opts.beacon) {
    const socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    socket.bind(() => {
        socket.setBroadcast(true);
        setInterval(() => {
            const datagram = Buffer.from(JSON.stringify({
                App: 'Enroute Flight Navigation',
                companion: { port: opts.port, v: PROTOCOL_VERSION, sid: state.sid },
            }));
            socket.send(datagram, opts.port, '255.255.255.255', (err) => {
                if (err) { console.error(`beacon: ${err.message}`); }
            });
        }, 5000);
        console.log(`  beacon:  255.255.255.255:${opts.port} every 5 s`);
    });
}

for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => { server.close(); process.exit(0); });
}
