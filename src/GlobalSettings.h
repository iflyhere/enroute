/***************************************************************************
 *   Copyright (C) 2019-2024 by Stefan Kebekus                             *
 *   stefan.kebekus@gmail.com                                              *
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

#pragma once

#include <QProperty>
#include <QQmlEngine>
#include <QSettings>

#include "GlobalObject.h"
#include "notification/Notification.h"
#include "units/ByteSize.h"
#include "units/Distance.h"


/*! \brief Global Settings Manager
 *
 * This class holds a few data items and exposes them via QObject properties, so
 * that they can be used in QML.  All data stored in this class is saved via
 * QSettings on destruction.
 *
 * There exists one static instance of this class, which can be accessed via the
 * Global functions.  No other instance of this class should be used.
 *
 * The methods in this class are reentrant, but not thread safe.
 */

class GlobalSettings : public QObject
{
    Q_OBJECT
    QML_ELEMENT
    QML_SINGLETON

public:
    //
    // Constructor and destructor
    //

    /*! \brief Standard constructor
     *
     * @param parent The standard QObject parent pointer
     */
    explicit GlobalSettings(QObject *parent = nullptr);

    // No default constructor, important for QML singleton
    explicit GlobalSettings() = delete;

    // Standard Destructor
    ~GlobalSettings() override = default;

    // factory function for QML singleton
    static GlobalSettings* create(QQmlEngine * /*unused*/, QJSEngine * /*unused*/)
    {
        return GlobalObject::globalSettings();
    }


    //
    // Properties
    //

    /*! \brief Privacy setting: always open external web sites */
    Q_PROPERTY(bool alwaysOpenExternalWebsites READ alwaysOpenExternalWebsites WRITE setAlwaysOpenExternalWebsites NOTIFY alwaysOpenExternalWebsitesChanged)

    /*! \brief Find out if Terms & Conditions have been accepted
     *
     * This property says which version of our "terms and conditions" have been
     * accepted by the user; this is used to determine whether the
     * first-use-dialog should be shown.  If nothing has been accepted yet, 0 is
     * returned.
     */
    Q_PROPERTY(int acceptedTerms READ acceptedTerms WRITE setAcceptedTerms NOTIFY acceptedTermsChanged)

    /*! \brief Airspace altitude limit for map display
     *
     * This property holds an altitude. The moving map will ony display airspaces whose lower
     * boundary is lower than this altitude. The altitude value lies in the range [airspaceHeightLimit_min, airspaceHeightLimit_max]
     * or takes a non-finite value to indicate that all airspaces shall be shown.
     */
    Q_PROPERTY(Units::Distance airspaceAltitudeLimit READ airspaceAltitudeLimit WRITE setAirspaceAltitudeLimit NOTIFY airspaceAltitudeLimitChanged)

    /*! \brief Minimum acceptable value for property airspaceAltitudeLimit
     *
     *  This is currently set to 3.000 ft
     */
    Q_PROPERTY(Units::Distance airspaceAltitudeLimit_min MEMBER airspaceAltitudeLimit_min CONSTANT)

    /*! \brief Maximum acceptable value for property airspaceAltitudeLimit
     *
     *  This is currently set to 15.000 ft
     */
    Q_PROPERTY(Units::Distance airspaceAltitudeLimit_max MEMBER airspaceAltitudeLimit_max CONSTANT)

    /*! \brief Automatic flight detection enabled for FlightLog start and landing time */
    Q_PROPERTY(bool autoFlightDetection READ autoFlightDetection WRITE setAutoFlightDetection NOTIFY autoFlightDetectionChanged)

    /*! \brief Should we expand notam abbreviations */
    Q_PROPERTY(bool expandNotamAbbreviations READ expandNotamAbbreviations WRITE setExpandNotamAbbreviations NOTIFY expandNotamAbbreviationsChanged)

    /*! \brief Font size
     *
     *  This is a value between 14 (normal font size) and 20 (giant fonts)
     */
    Q_PROPERTY(int fontSize READ fontSize WRITE setFontSize NOTIFY fontSizeChanged)

    /*! \brief Hide gliding sectors */
    Q_PROPERTY(bool hideGlidingSectors READ hideGlidingSectors WRITE setHideGlidingSectors NOTIFY hideGlidingSectorsChanged)

    /*! \brief Ignore SSL security problems */
    Q_PROPERTY(bool ignoreSSLProblems READ ignoreSSLProblems WRITE setIgnoreSSLProblems NOTIFY ignoreSSLProblemsChanged)

    /*! \brief Last finite value of airspaceAltitudeLimit */
    Q_PROPERTY(Units::Distance lastValidAirspaceAltitudeLimit READ lastValidAirspaceAltitudeLimit NOTIFY lastValidAirspaceAltitudeLimitChanged)

    /*! \brief Hash of the last "what's new" message that was shown to the user
     *
     * This property is used in the app to determine if the message has been
     * shown or not.
     */
    Q_PROPERTY(Units::ByteSize lastWhatsNewHash READ lastWhatsNewHash WRITE setLastWhatsNewHash NOTIFY lastWhatsNewHashChanged)

    /*! \brief Hash of the last "what's new in maps" message that was shown to the user
     *
     * This property is used in the app to determine if the message has been
     * shown or not.
     */
    Q_PROPERTY(Units::ByteSize lastWhatsNewInMapsHash READ lastWhatsNewInMapsHash WRITE setLastWhatsNewInMapsHash NOTIFY lastWhatsNewInMapsHashChanged)

    /*! \brief Night mode */
    Q_PROPERTY(bool nightMode READ nightMode WRITE setNightMode BINDABLE bindableNightMode NOTIFY nightModeChanged)

    /*! \brief Publish route and navigation state to companion devices over Wi-Fi */
    Q_PROPERTY(bool companionNetworkEnabled READ companionNetworkEnabled WRITE setCompanionNetworkEnabled BINDABLE bindableCompanionNetworkEnabled NOTIFY companionNetworkEnabledChanged)

    /*! \brief Publish route and navigation state to companion devices over Bluetooth
     *
     *  Independent of the Wi-Fi switch rather than exclusive with it. The two are not
     *  alternatives: Wi-Fi is the transport that works on a bench with a laptop on the
     *  same network, Bluetooth is the one that works in an aircraft, and there is no
     *  reason a pilot may not have both.
     */
    Q_PROPERTY(bool companionBluetoothEnabled READ companionBluetoothEnabled WRITE setCompanionBluetoothEnabled BINDABLE bindableCompanionBluetoothEnabled NOTIFY companionBluetoothEnabledChanged)

    /*! \brief Six-digit code that a companion device must present */
    Q_PROPERTY(QString companionPairingCode READ companionPairingCode WRITE setCompanionPairingCode NOTIFY companionPairingCodeChanged)

    /*! \brief Screens the companion shows, in order, as comma-separated identifiers
     *
     *  Empty means the companion's own default. A companion that does not know one of
     *  these identifiers ignores it, and inserts any screen of its own that is missing
     *  here, so a phone and a watch of different versions still agree on the rest.
     */
    Q_PROPERTY(QString companionPageOrder READ companionPageOrder WRITE setCompanionPageOrder NOTIFY companionPreferencesChanged)

    /*! \brief Screens the companion hides, as comma-separated identifiers */
    Q_PROPERTY(QString companionHiddenPages READ companionHiddenPages WRITE setCompanionHiddenPages NOTIFY companionPreferencesChanged)

    /*! \brief What the companion's bezel does: "pages" or "zoom" */
    Q_PROPERTY(QString companionBezelAction READ companionBezelAction WRITE setCompanionBezelAction NOTIFY companionPreferencesChanged)

    /*! \brief Approach charts on the companion: "auto", "on" or "off" */
    Q_PROPERTY(QString companionChartMode READ companionChartMode WRITE setCompanionChartMode NOTIFY companionPreferencesChanged)

    /*! \brief Whether the companion vibrates on a collision alarm */
    Q_PROPERTY(bool companionAlarmVibration READ companionAlarmVibration WRITE setCompanionAlarmVibration NOTIFY companionPreferencesChanged)

    /*! \brief Which link the companion uses: "auto", "wifi" or "ble" */
    Q_PROPERTY(QString companionTransportMode READ companionTransportMode WRITE setCompanionTransportMode NOTIFY companionPreferencesChanged)

    /*! \brief Use traffic data receiver for positioning */
    Q_PROPERTY(bool positioningByTrafficDataReceiver READ positioningByTrafficDataReceiver WRITE setPositioningByTrafficDataReceiver BINDABLE bindablePositioningByTrafficDataReceiver)

    /*! \brief Hash of the last "privacy" message that was accepted by the user
     *
     * This property is used in the app to determine if the message has been
     * shown or not.
     */
    Q_PROPERTY(Units::ByteSize privacyHash READ privacyHash WRITE setPrivacyHash NOTIFY privacyHashChanged)

    /*! \brief Show Altitude AGL */
    Q_PROPERTY(bool showAltitudeAGL READ showAltitudeAGL WRITE setShowAltitudeAGL NOTIFY showAltitudeAGLChanged)

    /*! \brief Show current flight trace on map */
    Q_PROPERTY(bool showCurrentFlightTrace READ showCurrentFlightTrace WRITE setShowCurrentFlightTrace NOTIFY showCurrentFlightTraceChanged)

    /*! \brief Flight track recording enabled */
    Q_PROPERTY(bool trackRecording READ trackRecording WRITE setTrackRecording NOTIFY trackRecordingChanged)

    /*! \brief Voice notifications that should be played
     *
     *  This property is an "or" of the entries of Notifications::Notification::Importance. It determines
     *  which notifications should be spoken.
     */
    Q_PROPERTY(uint voiceNotifications READ voiceNotifications WRITE setVoiceNotifications NOTIFY voiceNotificationsChanged)


    //
    // Getter Methods
    //

    /*! \brief Getter function for property of the same name
     *
     * @returns Property acceptedTerms
     */
    [[nodiscard]] auto acceptedTerms() const -> int { return m_settings.value(QStringLiteral("acceptedTerms"), 0).toInt(); }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property alwaysOpenExternalWebsites
     */
    [[nodiscard]] bool alwaysOpenExternalWebsites() const { return m_settings.value(QStringLiteral("alwaysOpenExternalWebsites"), false).toBool(); }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property airspaceAltitudeLimit
     */
    [[nodiscard]] auto airspaceAltitudeLimit() const -> Units::Distance;

    /*! \brief Getter function for property of the same name
     *
     * @returns Property autoFlightDetection
     */
    [[nodiscard]] auto autoFlightDetection() const -> bool { return m_settings.value(QStringLiteral("FlightLog/autoFlightDetection"), false).toBool(); }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property expandNotamAbbreviations
     */
    [[nodiscard]] bool expandNotamAbbreviations() const { return m_settings.value(QStringLiteral("expandNotamAbbreviations"), false).toBool(); }

    /*! \brief Getter function for property with the same name
     *
     * @returns Property largeFonts
     */
    [[nodiscard]] auto fontSize() const -> int;

    /*! \brief Getter function for property of the same name
     *
     * @returns Property hideGlidingSectors
     */
    [[nodiscard]] auto hideGlidingSectors() const -> bool { return m_settings.value(QStringLiteral("Map/hideGlidingSectors"), true).toBool(); }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property ignoreSSLProblems
     */
    [[nodiscard]] auto ignoreSSLProblems() const -> bool { return m_settings.value(QStringLiteral("ignoreSSLProblems"), false).toBool(); }

    /*! \brief Getter function for property with the same name
     *
     * @returns Property lastValidAirspaceAltitudeLimit
     */
    [[nodiscard]] auto lastValidAirspaceAltitudeLimit() const -> Units::Distance;

    /*! \brief Getter function for property of the same name
     *
     * @returns Property lastWhatsNewHash
     */
    [[nodiscard]] auto lastWhatsNewHash() const -> Units::ByteSize
    {
        return m_settings.value(QStringLiteral("lastWhatsNewHash"), 0).value<size_t>();
    }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property lastWhatsNewInMapsHash
     */
    [[nodiscard]] auto lastWhatsNewInMapsHash() const -> Units::ByteSize
    {
        return m_settings.value(QStringLiteral("lastWhatsNewInMapsHash"), 0).value<size_t>();
    }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property night mode
     */
    /*! \brief Getter function for property of the same name
     *
     * @returns Property companionNetworkEnabled
     */
    [[nodiscard]] auto companionNetworkEnabled() const -> bool { return m_companionNetworkEnabled.value(); }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property companionNetworkEnabled
     */
    [[nodiscard]] QBindable<bool> bindableCompanionNetworkEnabled() { return &m_companionNetworkEnabled; }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property companionBluetoothEnabled
     */
    [[nodiscard]] auto companionBluetoothEnabled() const -> bool { return m_companionBluetoothEnabled.value(); }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property companionBluetoothEnabled
     */
    [[nodiscard]] QBindable<bool> bindableCompanionBluetoothEnabled() { return &m_companionBluetoothEnabled; }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property companionPairingCode
     */
    [[nodiscard]] auto companionPairingCode() const -> QString { return m_companionPairingCode; }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property companionPageOrder
     */
    [[nodiscard]] auto companionPageOrder() const -> QString { return m_companionPageOrder; }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property companionHiddenPages
     */
    [[nodiscard]] auto companionHiddenPages() const -> QString { return m_companionHiddenPages; }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property companionBezelAction
     */
    [[nodiscard]] auto companionBezelAction() const -> QString { return m_companionBezelAction; }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property companionChartMode
     */
    [[nodiscard]] auto companionChartMode() const -> QString { return m_companionChartMode; }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property companionAlarmVibration
     */
    [[nodiscard]] auto companionAlarmVibration() const -> bool { return m_companionAlarmVibration; }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property companionTransportMode
     */
    [[nodiscard]] auto companionTransportMode() const -> QString { return m_companionTransportMode; }

    [[nodiscard]] auto nightMode() const -> bool { return m_nightMode.value(); }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property night mode
     */
    [[nodiscard]] QBindable<bool> bindableNightMode() { return &m_nightMode; }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property positioningByTrafficDataReceiver
     */
    [[nodiscard]] bool positioningByTrafficDataReceiver() const { return m_positioningByTrafficDataReceiver.value(); }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property positioningByTrafficDataReceiver
     */
    [[nodiscard]] QBindable<bool> bindablePositioningByTrafficDataReceiver() const { return &m_positioningByTrafficDataReceiver; }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property privacyHash
     */
    [[nodiscard]] auto privacyHash() const -> Units::ByteSize  { return m_settings.value(QStringLiteral("privacyHash"), 0).value<size_t>(); }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property positioningByTrafficDataReceiver
     */
    [[nodiscard]] auto showAltitudeAGL() const -> bool { return m_settings.value(QStringLiteral("showAltitudeAGL"), false).toBool(); }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property showCurrentFlightTrace
     */
    [[nodiscard]] auto showCurrentFlightTrace() const -> bool { return m_settings.value(QStringLiteral("FlightLog/showCurrentFlightTrace"), true).toBool(); }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property trackRecording
     */
    [[nodiscard]] auto trackRecording() const -> bool { return m_settings.value(QStringLiteral("FlightLog/trackRecording"), true).toBool(); }

    /*! \brief Getter function for property of the same name
     *
     * @returns Property voiceNotifications
     */
    [[nodiscard]] auto voiceNotifications() const -> uint
    {
        return m_settings.value(QStringLiteral("voiceNotifications"),
                              Notifications::Notification::Info_Navigation |
                                  Notifications::Notification::Warning |
                                  Notifications::Notification::Warning_Navigation |
                                  Notifications::Notification::Alert).toUInt();
    }


    //
    // Setter Methods
    //

    /*! \brief Setter function for property of the same name
     *
     * @param terms Property acceptedTerms
     */
    void setAcceptedTerms(int terms);

    /*! \brief Setter function for property of the same name
     *
     * @param alwaysOpen Property alwaysOpenExternalWebsites
     */
    void setAlwaysOpenExternalWebsites(bool alwaysOpen);

    /*! \brief Setter function for property of the same name
     *
     *  If newAirspaceAltitudeLimit is less than airspaceHeightLimit_min, then
     *  airspaceHeightLimit_min will be set. If newAirspaceAltitudeLimit is higher than
     *  airspaceHeightLimit_max, then airspaceHeightLimit_max will be set.
     *
     * @param newAirspaceAltitudeLimit Property airspaceAltitudeLimit
     */
    void setAirspaceAltitudeLimit(Units::Distance newAirspaceAltitudeLimit);

    /*! \brief Setter function for property of the same name
     *
     * @param newAutoFlightDetection Property autoFlightDetection
     */
    void setAutoFlightDetection(bool newAutoFlightDetection);

    /*! \brief Setter function for property of the same name
     *
     * @param newExpandNotamAbbreviations Property expandNotamAbbreviations
     */
    void setExpandNotamAbbreviations(bool newExpandNotamAbbreviations);

    /*! \brief Setter function for property of the same name
     *
     * @param hide Property hideGlidingSectors
     */
    void setHideGlidingSectors(bool hide);

    /*! \brief Setter function for property of the same name
     *
     * @param newFontSize Property fontSize
     */
    void setFontSize(int newFontSize);

    /*! \brief Setter function for property of the same name
     *
     * @param ignore Property ignoreSSLProblems
     */
    void setIgnoreSSLProblems(bool ignore);

    /*! \brief Getter function for property of the same name
     *
     * @param newLargeFonts Property largeFonts
     */
    void setLargeFonts(bool newLargeFonts);

    /*! \brief Getter function for property of the same name
     *
     * @param lwnh Property lastWhatsNewHash
     */
    void setLastWhatsNewHash(Units::ByteSize lwnh);

    /*! \brief Getter function for property of the same name
     *
     * @param lwnh Property lastWhatsNewInMapsHash
     */
    void setLastWhatsNewInMapsHash(Units::ByteSize lwnh);

    /*! \brief Setter function for property of the same name
     *
     * @param newNightMode Property nightMode
     */
    /*! \brief Setter function for property of the same name
     *
     * @param newCompanionNetworkEnabled Property companionNetworkEnabled
     */
    void setCompanionNetworkEnabled(bool newCompanionNetworkEnabled);

    /*! \brief Setter function for property of the same name
     *
     *  @param newCompanionBluetoothEnabled Property companionBluetoothEnabled
     */
    void setCompanionBluetoothEnabled(bool newCompanionBluetoothEnabled);

    /*! \brief Setter function for property of the same name
     *
     * @param newCompanionPairingCode Property companionPairingCode
     */
    void setCompanionPairingCode(const QString& newCompanionPairingCode);

    /*! \brief Setter function for property of the same name
     *
     *  @param newCompanionPageOrder Property companionPageOrder
     */
    void setCompanionPageOrder(const QString& newCompanionPageOrder);

    /*! \brief Setter function for property of the same name
     *
     *  @param newCompanionHiddenPages Property companionHiddenPages
     */
    void setCompanionHiddenPages(const QString& newCompanionHiddenPages);

    /*! \brief Setter function for property of the same name
     *
     *  @param newCompanionBezelAction Property companionBezelAction
     */
    void setCompanionBezelAction(const QString& newCompanionBezelAction);

    /*! \brief Setter function for property of the same name
     *
     *  @param newCompanionChartMode Property companionChartMode
     */
    void setCompanionChartMode(const QString& newCompanionChartMode);

    /*! \brief Setter function for property of the same name
     *
     *  @param newCompanionAlarmVibration Property companionAlarmVibration
     */
    void setCompanionAlarmVibration(bool newCompanionAlarmVibration);

    /*! \brief Setter function for property of the same name
     *
     *  @param newCompanionTransportMode Property companionTransportMode
     */
    void setCompanionTransportMode(const QString& newCompanionTransportMode);

    void setNightMode(bool newNightMode);

    /*! \brief Setter function for property of the same name
     *
     * @param newPositioningByTrafficDataReceiver Property positioningByTrafficDataReceiver
     */
    void setPositioningByTrafficDataReceiver(bool newPositioningByTrafficDataReceiver);

    /*! \brief Getter function for property of the same name
     *
     * @param newHash Property privacyHash
     */
    void setPrivacyHash(Units::ByteSize newHash);

    /*! \brief Setter function for property of the same name
     *
     * @param newShowAltitudeAGL Property showAltitudeAGL
     */
    void setShowAltitudeAGL(bool newShowAltitudeAGL);

    /*! \brief Setter function for property of the same name
     *
     * @param newShowCurrentFlightTrace Property showCurrentFlightTrace
     */
    void setShowCurrentFlightTrace(bool newShowCurrentFlightTrace);

    /*! \brief Setter function for property of the same name
     *
     * @param newTrackRecording Property trackRecording
     */
    void setTrackRecording(bool newTrackRecording);

    /*! \brief Setter function for property of the same name
     *
     * @param newVoiceNotifications Property voiceNotifications
     */
    void setVoiceNotifications(uint newVoiceNotifications);


    //
    // Constants
    //

    static constexpr Units::Distance airspaceAltitudeLimit_min = Units::Distance::fromFT(3000);
    static constexpr Units::Distance airspaceAltitudeLimit_max = Units::Distance::fromFT(15000);

signals:
    /*! \brief Notifier signal */
    void acceptedTermsChanged();

    /*! \brief Notifier signal */
    void alwaysOpenExternalWebsitesChanged();

    /*! \brief Notifier signal */
    void airspaceAltitudeLimitChanged();

    /*! \brief Notifier signal */
    void autoFlightDetectionChanged();

    /*! \brief Notifier signal */
    void expandNotamAbbreviationsChanged();

    /*! \brief Notifier signal */
    void fontSizeChanged();

    /*! \brief Notifier signal */
    void hideGlidingSectorsChanged();

    /*! \brief Notifier signal */
    void ignoreSSLProblemsChanged();

    /*! \brief Notifier signal */
    void lastValidAirspaceAltitudeLimitChanged();

    /*! \brief Notifier signal */
    void lastWhatsNewHashChanged();

    /*! \brief Notifier signal */
    void lastWhatsNewInMapsHashChanged();

    /*! \brief Notifier signal */
    /*! \brief Notifier signal */
    void companionNetworkEnabledChanged();

    /*! \brief Notifier signal
     *
     *  One signal for every companion preference rather than one each: they are
     *  published together in a single document, so a separate signal per property would
     *  only mean six ways to trigger the same republication.
     */
    void companionPreferencesChanged();

    /*! \brief Notifier signal */
    void companionBluetoothEnabledChanged();

    /*! \brief Notifier signal */
    void companionPairingCodeChanged();

    void nightModeChanged();

    /*! \brief Notifier signal */
    void privacyHashChanged();

    /*! \brief Notifier signal */
    void showAltitudeAGLChanged();

    /*! \brief Notifier signal */
    void showCurrentFlightTraceChanged();

    /*! \brief Notifier signal */
    void trackRecordingChanged();

    /*! \brief Notifier signal */
    void voiceNotificationsChanged();

private:
    Q_DISABLE_COPY_MOVE(GlobalSettings)

    QSettings m_settings;

    // Property-backed night mode setting, so that C++ bindings can depend on
    // it. Initialized from m_settings, which is declared above on purpose.
    QProperty<bool> m_nightMode {m_settings.value(QStringLiteral("Map/nightMode"), false).toBool()};

    QProperty<bool> m_companionNetworkEnabled {m_settings.value(QStringLiteral("companion/networkEnabled"), false).toBool()};
    QProperty<bool> m_companionBluetoothEnabled {m_settings.value(QStringLiteral("companion/bluetoothEnabled"), false).toBool()};
    QString m_companionPairingCode {m_settings.value(QStringLiteral("companion/pairingCode")).toString()};
    QString m_companionPageOrder {m_settings.value(QStringLiteral("companion/pageOrder")).toString()};
    QString m_companionHiddenPages {m_settings.value(QStringLiteral("companion/hiddenPages")).toString()};
    QString m_companionBezelAction {m_settings.value(QStringLiteral("companion/bezelAction"), QStringLiteral("pages")).toString()};
    QString m_companionChartMode {m_settings.value(QStringLiteral("companion/chartMode"), QStringLiteral("auto")).toString()};
    bool m_companionAlarmVibration {m_settings.value(QStringLiteral("companion/alarmVibration"), true).toBool()};
    QString m_companionTransportMode {m_settings.value(QStringLiteral("companion/transportMode"), QStringLiteral("auto")).toString()};

    QProperty<bool> m_positioningByTrafficDataReceiver;
};
