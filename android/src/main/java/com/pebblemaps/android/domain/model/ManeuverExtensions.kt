package com.pebblemaps.android.domain.model

fun Maneuver.toTurnDirection(): TurnDirection {
    return when {
        type == "depart" -> TurnDirection.STRAIGHT
        type == "arrive" -> TurnDirection.NONE
        modifier == "uturn" -> TurnDirection.UTURN
        modifier == "sharp left" -> TurnDirection.SHARP_LEFT
        modifier == "left" -> TurnDirection.LEFT
        modifier == "slight left" -> TurnDirection.SLIGHT_LEFT
        modifier == "sharp right" -> TurnDirection.SHARP_RIGHT
        modifier == "right" -> TurnDirection.RIGHT
        modifier == "slight right" -> TurnDirection.SLIGHT_RIGHT
        type == "continue" || type == "new name" -> TurnDirection.STRAIGHT
        else -> TurnDirection.STRAIGHT
    }
}

fun TurnDirection.toArrow(): String {
    return when (this) {
        TurnDirection.NONE -> ""
        TurnDirection.STRAIGHT -> "↑"
        TurnDirection.SLIGHT_LEFT -> "↖"
        TurnDirection.LEFT -> "←"
        TurnDirection.SHARP_LEFT -> "⇐"
        TurnDirection.SLIGHT_RIGHT -> "↗"
        TurnDirection.RIGHT -> "→"
        TurnDirection.SHARP_RIGHT -> "⇒"
        TurnDirection.UTURN -> "↺"
    }
}

fun TurnDirection.toDescription(): String {
    return when (this) {
        TurnDirection.NONE -> ""
        TurnDirection.STRAIGHT -> "Continue"
        TurnDirection.SLIGHT_LEFT -> "Slight left"
        TurnDirection.LEFT -> "Turn left"
        TurnDirection.SHARP_LEFT -> "Sharp left"
        TurnDirection.SLIGHT_RIGHT -> "Slight right"
        TurnDirection.RIGHT -> "Turn right"
        TurnDirection.SHARP_RIGHT -> "Sharp right"
        TurnDirection.UTURN -> "U-turn"
    }
}