/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import mozilla.components.browser.state.state.ContainerState

// Profiles: ARGB values of the Firefox container colors.
private const val BLUE = 0xFF37ADFF
private const val TURQUOISE = 0xFF00C79A
private const val GREEN = 0xFF51CD00
private const val YELLOW = 0xFFFFCB00
private const val ORANGE = 0xFFFF9F00
private const val RED = 0xFFFF613D
private const val PINK = 0xFFFF4BDA
private const val PURPLE = 0xFFAF51F5
private const val TOOLBAR = 0xFF7C7C7D

/** Returns the ARGB color used to draw this container color. */
fun ContainerState.Color.toArgb(): Int = when (this) {
    ContainerState.Color.BLUE -> BLUE
    ContainerState.Color.TURQUOISE -> TURQUOISE
    ContainerState.Color.GREEN -> GREEN
    ContainerState.Color.YELLOW -> YELLOW
    ContainerState.Color.ORANGE -> ORANGE
    ContainerState.Color.RED -> RED
    ContainerState.Color.PINK -> PINK
    ContainerState.Color.PURPLE -> PURPLE
    ContainerState.Color.TOOLBAR -> TOOLBAR
}.toInt()

/** Human readable (English) name of the color. */
fun ContainerState.Color.displayName(): String =
    name.lowercase().replaceFirstChar { it.uppercase() }
