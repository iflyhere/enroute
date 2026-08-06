/***************************************************************************
 *   Copyright (C) 2026 by Stefan Kebekus                                  *
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

#include "dataManagement/DataManager.h"
#include "geomaps/GeoMapProvider.h"
#include "notification/Notification_OversizedMap.h"



//
// Constructors and destructors
//

Notifications::Notification_OversizedMap::Notification_OversizedMap(QObject* parent)
    : Notification(tr("Defective maps"), Notifications::Notification::Warning, parent)
{
    setButton1Text(tr("Update"));
    setButton2Text(tr("Dismiss"));
    setTextBodyAction(OpenMapsAndDataPage);
    update();

    connect(GlobalObject::geoMapProvider(), &GeoMaps::GeoMapProvider::oversizedMapsChanged, this, &Notifications::Notification_OversizedMap::update);
    connect(GlobalObject::dataManager()->mapsAndData(), &DataManagement::Downloadable_MultiFile::downloadingChanged, this, &Notifications::Notification_OversizedMap::update);
}



//
// Methods
//

void Notifications::Notification_OversizedMap::onButton1Clicked()
{
    GlobalObject::dataManager()->mapsAndData()->update();
    deleteLater();
}

void Notifications::Notification_OversizedMap::update()
{
    auto oversizedMaps = GlobalObject::geoMapProvider()->oversizedMaps();
    if (oversizedMaps.isEmpty())
    {
        deleteLater();
        return;
    }
    if (GlobalObject::dataManager()->mapsAndData()->downloading())
    {
        deleteLater();
        return;
    }

    setText(tr("The following maps are unreasonably large and will be ignored "
               "until they are updated: %1.").arg(oversizedMaps.join(QStringLiteral(", "))));
}
