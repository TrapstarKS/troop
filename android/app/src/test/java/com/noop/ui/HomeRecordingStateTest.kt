package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRecordingStateTest {
    @Test
    fun statusRequiresCurrentLinkAndObservedCapture() {
        val cases = listOf(
            listOf(false, false, false, false, false) to HomeRecordingState.Disconnected,
            listOf(false, false, true, true, true) to HomeRecordingState.Disconnected,
            listOf(false, true, false, false, false) to HomeRecordingState.Scanning,
            listOf(true, false, true, true, true) to HomeRecordingState.Backfill,
            listOf(true, false, false, true, false) to HomeRecordingState.Capturing,
            listOf(true, false, false, false, false) to HomeRecordingState.ConnectedNoData,
            listOf(true, false, false, false, true) to HomeRecordingState.Idle,
        )
        assertEquals(HomeRecordingState.CapturingExperimental,
            homeRecordingState(true, false, false, true, false, historySyncExperimental = true))
        assertEquals(HomeRecordingState.ExperimentalHistory,
            homeRecordingState(true, false, false, false, false, historySyncExperimental = true))
        assertEquals(HomeRecordingState.Backfill,
            homeRecordingState(true, false, true, true, false, historySyncExperimental = true))
        cases.forEach { (input, expected) ->
            assertEquals(expected, homeRecordingState(input[0], input[1], input[2], input[3], input[4]))
        }
    }
}
