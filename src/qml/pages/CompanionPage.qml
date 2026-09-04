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
