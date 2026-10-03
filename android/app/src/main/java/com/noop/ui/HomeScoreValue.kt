package com.noop.ui

// Imported values can bypass the local scorer's bounds. Invalid scores have no display value.
internal fun homeScoreValue(value: Double?): Double? = value?.takeIf { it.isFinite() && it in 0.0..100.0 }
