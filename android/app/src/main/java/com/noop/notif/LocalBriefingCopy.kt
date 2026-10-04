package com.noop.notif

import android.content.Context
import com.noop.R

object LocalBriefingCopy {
    fun summary(context: Context, recovery: Int?, sleepMinutes: Int?, strainTenths: Int?, streak: Int): String {
        val parts = buildList {
            recovery?.let { add(context.getString(R.string.local_summary_recovery, it)) }
            sleepMinutes?.let { add(context.getString(R.string.local_summary_sleep, it)) }
            strainTenths?.let { add(context.getString(R.string.local_summary_strain, it / 10, it % 10)) }
            if (streak > 0) add(context.getString(R.string.local_summary_streak, streak))
        }
        return parts.joinToString(" · ").ifEmpty { context.getString(R.string.local_summary_empty) }
    }
}
