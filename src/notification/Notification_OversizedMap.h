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

#pragma once

#include "notification/Notification.h"

namespace Notifications {

/*! \brief Notification for oversized aviation maps
 *
 *  This implementation of Notifications::Notification warns the user that one
 *  or more installed aviation maps are too large to be used and will be
 *  ignored until they are updated. It sets proper button texts, reacts to
 *  button clicks and deletes itself when a map and data update starts or when
 *  no oversized maps remain.
 */


class Notification_OversizedMap : public Notification
{
    Q_OBJECT

public:
    //
    // Constructors and destructors
    //

    /*! \brief Standard constructor
     *
     *  @param parent The standard QObject parent pointer
     */
    explicit Notification_OversizedMap(QObject* parent = nullptr);

    // No default constructor, always want a parent
    explicit Notification_OversizedMap() = delete;

    /*! \brief Standard destructor */
    ~Notification_OversizedMap() override = default;

public slots:
    /*! \brief Reimplemented from Notifications::Notification */
    void onButton1Clicked() override;

private slots:
    // Check if this notification is still useful and delete it if not.
    void update();

private:
    Q_DISABLE_COPY_MOVE(Notification_OversizedMap)

};

} // namespace Notifications
