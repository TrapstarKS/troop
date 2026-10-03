package com.noop.notif

object LocalNotificationPolicy {
    fun shouldDeliver(
        enabled: Boolean,
        authorized: Boolean,
        quiet: Boolean,
        eventKey: String?,
        lastEventKey: String?,
        occurrenceSec: Long?,
        nowSec: Long,
        maxAgeSec: Long = 86400,
    ): Boolean = enabled && authorized && !quiet && !eventKey.isNullOrEmpty() &&
        eventKey != lastEventKey && occurrenceSec != null && occurrenceSec >= 0 && nowSec >= 0 && occurrenceSec <= nowSec &&
        nowSec - occurrenceSec <= maxAgeSec

    fun isQuiet(minute: Int, start: Int, end: Int, enabled: Boolean): Boolean {
        if (!enabled) return false
        val now = minute.coerceIn(0, 1439)
        val from = start.coerceIn(0, 1439)
        val until = end.coerceIn(0, 1439)
        return when {
            from == until -> false
            from < until -> now >= from && now < until
            else -> now >= from || now < until
        }
    }

}
