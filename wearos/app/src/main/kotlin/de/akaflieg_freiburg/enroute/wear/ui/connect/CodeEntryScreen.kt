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

package de.akaflieg_freiburg.enroute.wear.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.PickerGroup
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.rememberPickerState
import de.akaflieg_freiburg.enroute.wear.ui.theme.CockpitColors

private const val CODE_DIGITS = 6

/**
 * Enters the six-digit pairing code that the phone shows.
 *
 * Six rotary pickers rather than a keyboard: text entry on a watch is miserable, and
 * this is a once-per-phone chore. Note that a picker does nothing at all unless
 * something in the hierarchy holds focus, which is what the FocusRequester per digit
 * is for.
 */
@Composable
fun CodeEntryScreen(
    initialCode: String,
    onCodeEntered: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val digits = remember(initialCode) {
        val padded = initialCode.filter { it.isDigit() }.padStart(CODE_DIGITS, '0').takeLast(CODE_DIGITS)
        padded.map { it - '0' }
    }

    val states = List(CODE_DIGITS) { index ->
        rememberPickerState(
            initialNumberOfOptions = 10,
            initiallySelectedIndex = digits[index],
            shouldRepeatOptions = true,
        )
    }
    val focusRequesters = remember { List(CODE_DIGITS) { FocusRequester() } }
    // Which digit the crown drives. A plain index rather than a PickerState,
    // because moving a PickerState means calling a suspend function.
    var selectedDigit by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CockpitColors.Background)
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Pairing code",
            color = CockpitColors.Muted,
            fontSize = 13.sp,
        )

        Spacer(Modifier.height(6.dp))

        PickerGroup(
            selectedPickerState = states[selectedDigit],
            modifier = Modifier.testTag(TAG_CODE_PICKERS),
            autoCenter = false,
        ) {
            states.forEachIndexed { index, state ->
                PickerGroupItem(
                    pickerState = state,
                    selected = selectedDigit == index,
                    onSelected = { selectedDigit = index },
                    modifier = Modifier.width(26.dp),
                    focusRequester = focusRequesters[index],
                    contentDescription = { "digit " + (index + 1) },
                ) { optionIndex, pickerSelected ->
                    Text(
                        text = optionIndex.toString(),
                        color = if (pickerSelected) {
                            CockpitColors.Primary
                        } else {
                            CockpitColors.OnBackground
                        },
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                onCodeEntered(states.joinToString("") { it.selectedOptionIndex.toString() })
            },
            modifier = Modifier.testTag(TAG_CODE_CONFIRM),
        ) {
            Text(text = "Done", fontSize = 15.sp)
        }
    }
}

const val TAG_CODE_PICKERS = "code.pickers"
const val TAG_CODE_CONFIRM = "code.confirm"
