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

import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

import akaflieg_freiburg.enroute
import "../dialogs"
import "../items"

Page {
    id: companionPage
    title: qsTr("Companion Devices")

    // The screens a companion may show, in the order it lists them itself.
    //
    // Hard-coded rather than asked for, because the wire runs one way. A companion
    // ignores an identifier it does not know and inserts any screen of its own that is
    // missing here, so a phone and a watch of different versions still agree about the
    // rest -- which is why this list being out of date is a cosmetic problem and not a
    // broken one.
    readonly property var knownScreens: [
        {id: "data", name: qsTr("Data")},
        {id: "map", name: qsTr("Map")},
        {id: "instruments", name: qsTr("Instruments")},
        {id: "traffic", name: qsTr("Traffic")},
        {id: "notam", name: qsTr("NOTAM")},
        {id: "nearby", name: qsTr("Nearby waypoints")},
        {id: "weather", name: qsTr("Weather")},
        {id: "log", name: qsTr("Flight log")},
        {id: "settings", name: qsTr("Settings")}
    ]

    // The stored order with every missing screen put back where the companion would put
    // it: before the first stored screen that follows it in the canonical list. The same
    // rule the companion uses, so that what is shown here is what it will do.
    readonly property var screenOrder: {
        var canonical = knownScreens.map(function(s) { return s.id })
        var stored = GlobalSettings.companionPageOrder.split(",").filter(function(id) {
            return canonical.indexOf(id) >= 0
        })
        var result = stored.slice()
        canonical.forEach(function(id) {
            if (result.indexOf(id) >= 0) {
                return
            }
            var at = result.length
            for (var i = 0; i < result.length; i++) {
                if (canonical.indexOf(result[i]) > canonical.indexOf(id)) {
                    at = i
                    break
                }
            }
            result.splice(at, 0, id)
        })
        return result
    }

    function screenName(id) {
        for (var i = 0; i < knownScreens.length; i++) {
            if (knownScreens[i].id === id) {
                return knownScreens[i].name
            }
        }
        return id
    }

    function isHidden(id) {
        return GlobalSettings.companionHiddenPages.split(",").indexOf(id) >= 0
    }

    function setHidden(id, hidden) {
        var ids = GlobalSettings.companionHiddenPages.split(",").filter(function(entry) {
            return entry !== "" && entry !== id
        })
        if (hidden) {
            ids.push(id)
        }
        GlobalSettings.companionHiddenPages = ids.join(",")
    }

    function moveScreen(index, delta) {
        var ids = screenOrder.slice()
        var to = index + delta
        if (to < 0 || to >= ids.length) {
            return
        }
        var moved = ids.splice(index, 1)[0]
        ids.splice(to, 0, moved)
        // Written out in full, so that the order stops depending on the rule above and
        // a later version adding a screen cannot silently rearrange what the pilot set.
        GlobalSettings.companionPageOrder = ids.join(",")
    }

    function bezelName(value) {
        return value === "zoom" ? qsTr("Zoom the map") : qsTr("Switch screens")
    }

    function chartName(value) {
        if (value === "on") {
            return qsTr("Always")
        }
        if (value === "off") {
            return qsTr("Never")
        }
        return qsTr("Automatic")
    }

    function transportName(value) {
        if (value === "wifi") {
            return qsTr("Wi-Fi only")
        }
        if (value === "ble") {
            return qsTr("Bluetooth only")
        }
        return qsTr("Automatic")
    }

    header: StandardHeader {}

    DecoratedScrollView {
        id: view

        anchors.fill: parent
        contentWidth: availableWidth

        clip: true
        bottomPadding: SafeInsets.bottom
        leftPadding: SafeInsets.left
        rightPadding: SafeInsets.right

        ColumnLayout {
            width: view.availableWidth

            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: statusLabel.implicitHeight + companionPage.font.pixelSize
                color: GlobalSettings.companionNetworkEnabled
                       ? (CompanionServer.errorString === "" ? "green" : "red")
                       : "gray"

                Label {
                    id: statusLabel

                    anchors.centerIn: parent
                    width: parent.width - companionPage.font.pixelSize

                    color: "white"
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                    text: CompanionServer.statusString
                }
            }

            Label {
                Layout.fillWidth: true
                Layout.margins: companionPage.font.pixelSize
                wrapMode: Text.Wrap
                textFormat: Text.StyledText
                text: qsTr("Enroute Flight Navigation can publish your flight route and your current position to a companion device, such as a smartwatch. The companion device needs to know the pairing code shown below.")
            }

            Label {
                Layout.fillWidth: true
                Layout.margins: companionPage.font.pixelSize
                wrapMode: Text.Wrap
                textFormat: Text.StyledText
                text: qsTr("Wi-Fi needs both devices on one network, which is convenient at home and unreliable in an aircraft: a smartwatch powers its Wi-Fi radio down whenever a Bluetooth link is available. Bluetooth needs no network at all.")
            }

            WordWrappingSwitchDelegate {
                id: networkSwitch

                Layout.fillWidth: true
                text: qsTr("Publish over Wi-Fi")
                icon.source: "/icons/material/ic_wifi.svg"

                // Assigned rather than bound, and driven by onToggled rather than
                // onCheckedChanged, which is the idiom the rest of this app uses. A
                // binding on checked would be broken the moment the control is
                // toggled or the state is corrected after the dialog is cancelled,
                // and the switch would stop following the setting from then on.
                // onCheckedChanged would also fire for those corrections, not just
                // for what the pilot did.
                Component.onCompleted: {
                    networkSwitch.checked = GlobalSettings.companionNetworkEnabled
                }

                onToggled: {
                    PlatformAdaptor.vibrateBrief()
                    if (networkSwitch.checked) {
                        // Ask before anything starts listening. This makes the
                        // aircraft position readable to other devices on the
                        // network, which the pilot must actively agree to.
                        privacyWarning.open()
                    } else {
                        GlobalSettings.companionNetworkEnabled = false
                    }
                }
            }

            WordWrappingSwitchDelegate {
                id: bluetoothSwitch

                Layout.fillWidth: true
                text: qsTr("Publish over Bluetooth")
                icon.source: "/icons/material/ic_bluetooth.svg"

                // The same assignment-and-onToggled idiom as the switch above, and for
                // the same reason: a binding on checked breaks the moment the control
                // is toggled.
                Component.onCompleted: {
                    bluetoothSwitch.checked = GlobalSettings.companionBluetoothEnabled
                }

                onToggled: {
                    PlatformAdaptor.vibrateBrief()
                    if (bluetoothSwitch.checked) {
                        // Asked separately from the Wi-Fi switch. Consenting to publish
                        // on a home network is not consenting to advertise to whatever
                        // is within Bluetooth range of the cockpit.
                        bluetoothWarning.open()
                    } else {
                        GlobalSettings.companionBluetoothEnabled = false
                    }
                }
            }

            Label {
                Layout.fillWidth: true
                Layout.margins: companionPage.font.pixelSize
                wrapMode: Text.Wrap
                textFormat: Text.StyledText
                visible: GlobalSettings.companionBluetoothEnabled && (CompanionServer.errorString !== "")
                color: "red"
                // Never silent. A Bluetooth stack that refuses to advertise looks
                // exactly like a watch that cannot find the phone, and the pilot would
                // have no way to tell the two apart.
                text: CompanionServer.errorString
            }

            Label {
                Layout.fillWidth: true
                Layout.margins: companionPage.font.pixelSize
                visible: GlobalSettings.companionNetworkEnabled
                wrapMode: Text.Wrap
                text: qsTr("Pairing code")
                font.bold: true
            }

            RowLayout {
                Layout.fillWidth: true
                Layout.leftMargin: companionPage.font.pixelSize
                Layout.rightMargin: companionPage.font.pixelSize
                visible: GlobalSettings.companionNetworkEnabled

                Label {
                    Layout.fillWidth: true
                    text: CompanionServer.pairingCode
                    font.pixelSize: companionPage.font.pixelSize*1.6
                    font.family: "Courier"
                    font.bold: true
                }

                ToolButton {
                    icon.source: "/icons/material/ic_refresh.svg"
                    onClicked: {
                        PlatformAdaptor.vibrateBrief()
                        CompanionServer.regeneratePairingCode()
                    }
                }
            }

            Label {
                Layout.fillWidth: true
                Layout.margins: companionPage.font.pixelSize
                visible: GlobalSettings.companionNetworkEnabled && (CompanionServer.serverUrls.length > 0)
                wrapMode: Text.Wrap
                text: qsTr("Addresses")
                font.bold: true
            }

            Repeater {
                model: GlobalSettings.companionNetworkEnabled ? CompanionServer.serverUrls : []

                Label {
                    required property string modelData

                    Layout.fillWidth: true
                    Layout.leftMargin: companionPage.font.pixelSize
                    Layout.rightMargin: companionPage.font.pixelSize
                    text: modelData
                    font.family: "Courier"
                }
            }

            Label {
                Layout.fillWidth: true
                Layout.margins: companionPage.font.pixelSize
                visible: CompanionServer.errorString !== ""
                wrapMode: Text.Wrap
                color: "red"
                text: CompanionServer.errorString
            }

            Label {
                Layout.fillWidth: true
                Layout.margins: companionPage.font.pixelSize
                wrapMode: Text.Wrap
                text: qsTr("Watch display")
                font.bold: true
                font.pixelSize: companionPage.font.pixelSize*1.2
            }

            Label {
                Layout.fillWidth: true
                Layout.margins: companionPage.font.pixelSize
                wrapMode: Text.Wrap
                textFormat: Text.StyledText
                // Says plainly which way the wire runs. The watch can still change these
                // itself, and a change made here wins the next time it is made -- that
                // is an asymmetry a pilot should be told about rather than discover.
                text: qsTr("These are the companion device's own settings. It applies them whenever you change something here; between those moments it may be set differently on the watch itself.")
            }

            Label {
                Layout.fillWidth: true
                Layout.leftMargin: companionPage.font.pixelSize
                Layout.rightMargin: companionPage.font.pixelSize
                wrapMode: Text.Wrap
                text: qsTr("Screens")
                font.bold: true
            }

            Repeater {
                model: companionPage.screenOrder

                RowLayout {
                    // Named so that the elements inside can reach these: a delegate's
                    // required properties are not in scope for its children unqualified.
                    id: screenRow

                    required property string modelData
                    required property int index

                    Layout.fillWidth: true
                    Layout.leftMargin: companionPage.font.pixelSize
                    Layout.rightMargin: companionPage.font.pixelSize

                    Label {
                        Layout.fillWidth: true
                        text: companionPage.screenName(screenRow.modelData)
                        color: companionPage.isHidden(screenRow.modelData)
                               ? Qt.rgba(0.5, 0.5, 0.5, 1.0)
                               : companionPage.palette.text
                    }

                    ToolButton {
                        icon.source: "/icons/material/ic_keyboard_arrow_up.svg"
                        enabled: screenRow.index > 0
                        onClicked: {
                            PlatformAdaptor.vibrateBrief()
                            companionPage.moveScreen(screenRow.index, -1)
                        }
                    }

                    ToolButton {
                        icon.source: "/icons/material/ic_keyboard_arrow_down.svg"
                        enabled: screenRow.index < companionPage.screenOrder.length - 1
                        onClicked: {
                            PlatformAdaptor.vibrateBrief()
                            companionPage.moveScreen(screenRow.index, 1)
                        }
                    }

                    Switch {
                        id: visibleSwitch
                        checked: !companionPage.isHidden(screenRow.modelData)
                        onToggled: {
                            PlatformAdaptor.vibrateBrief()
                            companionPage.setHidden(screenRow.modelData, !visibleSwitch.checked)
                        }
                    }
                }
            }

            WordWrappingItemDelegate {
                Layout.fillWidth: true
                icon.source: "/icons/material/ic_settings.svg"
                text: qsTr("Bezel") + `<br><font color="#606060" size="2">`
                      + companionPage.bezelName(GlobalSettings.companionBezelAction)
                      + `</font>`
                onClicked: {
                    PlatformAdaptor.vibrateBrief()
                    bezelMenu.open()
                }

                AutoSizingMenu {
                    id: bezelMenu

                    MenuItem {
                        text: qsTr("Switch screens")
                        onTriggered: {
                            PlatformAdaptor.vibrateBrief()
                            GlobalSettings.companionBezelAction = "pages"
                        }
                    }
                    MenuItem {
                        text: qsTr("Zoom the map")
                        onTriggered: {
                            PlatformAdaptor.vibrateBrief()
                            GlobalSettings.companionBezelAction = "zoom"
                        }
                    }
                }
            }

            WordWrappingItemDelegate {
                Layout.fillWidth: true
                icon.source: "/icons/material/ic_map.svg"
                text: qsTr("Approach charts") + `<br><font color="#606060" size="2">`
                      + companionPage.chartName(GlobalSettings.companionChartMode)
                      + `</font>`
                onClicked: {
                    PlatformAdaptor.vibrateBrief()
                    chartMenu.open()
                }

                AutoSizingMenu {
                    id: chartMenu

                    MenuItem {
                        text: qsTr("Automatic")
                        onTriggered: {
                            PlatformAdaptor.vibrateBrief()
                            GlobalSettings.companionChartMode = "auto"
                        }
                    }
                    MenuItem {
                        text: qsTr("Always")
                        onTriggered: {
                            PlatformAdaptor.vibrateBrief()
                            GlobalSettings.companionChartMode = "on"
                        }
                    }
                    MenuItem {
                        text: qsTr("Never")
                        onTriggered: {
                            PlatformAdaptor.vibrateBrief()
                            GlobalSettings.companionChartMode = "off"
                        }
                    }
                }
            }

            WordWrappingItemDelegate {
                Layout.fillWidth: true
                icon.source: "/icons/material/ic_bluetooth.svg"
                text: qsTr("Link") + `<br><font color="#606060" size="2">`
                      + companionPage.transportName(GlobalSettings.companionTransportMode)
                      + `</font>`
                onClicked: {
                    PlatformAdaptor.vibrateBrief()
                    transportMenu.open()
                }

                AutoSizingMenu {
                    id: transportMenu

                    MenuItem {
                        text: qsTr("Automatic")
                        onTriggered: {
                            PlatformAdaptor.vibrateBrief()
                            GlobalSettings.companionTransportMode = "auto"
                        }
                    }
                    MenuItem {
                        text: qsTr("Wi-Fi only")
                        onTriggered: {
                            PlatformAdaptor.vibrateBrief()
                            GlobalSettings.companionTransportMode = "wifi"
                        }
                    }
                    MenuItem {
                        text: qsTr("Bluetooth only")
                        onTriggered: {
                            PlatformAdaptor.vibrateBrief()
                            GlobalSettings.companionTransportMode = "ble"
                        }
                    }
                }
            }

            WordWrappingSwitchDelegate {
                Layout.fillWidth: true
                text: qsTr("Vibrate on traffic alarm")
                icon.source: "/icons/material/ic_warning.svg"

                Component.onCompleted: {
                    checked = GlobalSettings.companionAlarmVibration
                }

                onToggled: {
                    PlatformAdaptor.vibrateBrief()
                    GlobalSettings.companionAlarmVibration = checked
                }
            }

            Item {
                Layout.preferredHeight: companionPage.font.pixelSize
            }
        }
    }

    CenteringDialog {
        id: privacyWarning

        modal: true
        title: qsTr("Publish over Wi-Fi?")
        standardButtons: Dialog.Ok|Dialog.Cancel

        Label {
            width: privacyWarning.availableWidth
            wrapMode: Text.Wrap
            textFormat: Text.StyledText
            text: "<p>" + qsTr("Once enabled, any device on the same Wi-Fi network that knows your pairing code can read your flight route and your current position.") + "</p>"
                  + "<p>" + qsTr("Do not enable this on a public Wi-Fi network.") + "</p>"
        }

        onAccepted: {
            PlatformAdaptor.vibrateBrief()
            GlobalSettings.companionNetworkEnabled = true
            networkSwitch.checked = true
        }

        onRejected: {
            PlatformAdaptor.vibrateBrief()
            networkSwitch.checked = false
        }
    }

    CenteringDialog {
        id: bluetoothWarning

        modal: true
        title: qsTr("Publish over Bluetooth?")
        standardButtons: Dialog.Ok|Dialog.Cancel

        Label {
            width: bluetoothWarning.availableWidth
            wrapMode: Text.Wrap
            textFormat: Text.StyledText
            // Says what is different from the Wi-Fi case rather than repeating it. A
            // network has a boundary a pilot can reason about; Bluetooth range is
            // whoever is nearby, which is a different thing to agree to.
            text: "<p>" + qsTr("Once enabled, this device advertises itself to anything within Bluetooth range. A companion device that knows your pairing code can then read your flight route and your current position.") + "</p>"
                  + "<p>" + qsTr("Unlike Wi-Fi, this works without any network, which is what makes it useful in flight.") + "</p>"
        }

        onAccepted: {
            PlatformAdaptor.vibrateBrief()
            GlobalSettings.companionBluetoothEnabled = true
            bluetoothSwitch.checked = true
        }

        onRejected: {
            PlatformAdaptor.vibrateBrief()
            bluetoothSwitch.checked = false
        }
    }
}
