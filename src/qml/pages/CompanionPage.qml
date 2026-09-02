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
                text: qsTr("Enroute Flight Navigation can publish your flight route and your current position to a companion device on the same Wi-Fi network, such as a smartwatch. The companion device needs to know the pairing code shown below.")
            }

            WordWrappingSwitchDelegate {
                id: networkSwitch

                Layout.fillWidth: true
                text: qsTr("Publish over Wi-Fi")
                icon.source: "/icons/material/ic_wifi.svg"
                checked: GlobalSettings.companionNetworkEnabled

                onCheckedChanged: {
                    if (checked === GlobalSettings.companionNetworkEnabled) {
                        return
                    }
                    PlatformAdaptor.vibrateBrief()
                    if (checked) {
                        // Ask before anything starts listening. This makes the
                        // aircraft position readable to other devices on the
                        // network, which the pilot must actively agree to.
                        privacyWarning.open()
                    } else {
                        GlobalSettings.companionNetworkEnabled = false
                    }
                }
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
        }

        onRejected: {
            PlatformAdaptor.vibrateBrief()
            networkSwitch.checked = false
        }
    }
}
