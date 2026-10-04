package com.noop.data

import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

data class WeeklyPlanRecoveryDay(val day: String, val recovery: Double?, val sleepProcessed: Boolean)

data class WeeklyPlanEligibility(val completedRecoveries: Int) {
    val remainingRecoveries: Int get() = (REQUIRED_RECOVERIES - completedRecoveries).coerceAtLeast(0)
    val isEligible: Boolean get() = remainingRecoveries == 0

    companion object {
        const val REQUIRED_RECOVERIES = 7

        // Swift twin: `WeeklyPlanEligibility.resolve`.
        fun resolve(recoveries: List<WeeklyPlanRecoveryDay>, today: String): WeeklyPlanEligibility {
            if (WeeklyPlanCalendar.date(today) == null) return WeeklyPlanEligibility(0)
            val completed = recoveries.filter { row ->
                row.sleepProcessed && WeeklyPlanCalendar.date(row.day) != null && row.day <= today &&
                    row.recovery?.let { it.isFinite() && it in 0.0..100.0 } == true
            }.map { it.day }.toSet()
            return WeeklyPlanEligibility(completed.size)
        }
    }
}

enum class WeeklyPlanPreset(val key: String) {
    RestRoutine("restRoutine"), ActiveWeek("activeWeek"), BalancedWeek("balancedWeek");

    val goals: WeeklyPlanGoals get() = when (this) {
        RestRoutine -> WeeklyPlanGoals()
        ActiveWeek -> WeeklyPlanGoals(strainMinimum = 60, strainDays = 4)
        BalancedWeek -> WeeklyPlanGoals(sleepMinutes = 450, strainMinimum = 40)
    }
}

data class WeeklyPlanGoals(
    val sleepMinutes: Int = 480,
    val sleepDays: Int = 5,
    val strainMinimum: Int = 50,
    val strainDays: Int = 3,
    val journalDays: Int = 5,
    val journalQuestion: String = "",
    val journalAnswer: String = "any",
) {
    val normalized: WeeklyPlanGoals get() = copy(
        sleepMinutes = sleepMinutes.coerceIn(240, 720),
        sleepDays = sleepDays.coerceIn(1, 7),
        strainMinimum = strainMinimum.coerceIn(1, 100),
        strainDays = strainDays.coerceIn(1, 7),
        journalDays = journalDays.coerceIn(1, 7),
        journalAnswer = if (journalQuestion.isEmpty()) "any" else if (journalAnswer in listOf("yes", "no")) journalAnswer else "yes",
    )
}

data class WeeklyPlanDay(val day: String, val sleepMinutes: Double? = null, val strain: Double? = null)
data class WeeklyPlanJournalDay(val day: String, val question: String, val answeredYes: Boolean)

data class WeeklyPlanProgress(val completedDays: Int, val observedDays: Int, val targetDays: Int) {
    val percent: Int? get() = if (observedDays == 0) null else (completedDays * 100 / targetDays).coerceAtMost(100)
}

data class WeeklyPlanSnapshot(
    val weekStart: String,
    val weekEnd: String,
    val sleep: WeeklyPlanProgress,
    val strain: WeeklyPlanProgress,
    val journal: WeeklyPlanProgress,
) {
    val overallPercent: Int? get() {
        val sleep = sleep.percent ?: return null
        val strain = strain.percent ?: return null
        val journal = journal.percent ?: return null
        return (sleep + strain + journal) / 3
    }
}

object WeeklyPlanCalendar {
    private val dayPattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private fun calendar() = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US).apply {
        isLenient = false
        clear()
    }

    // Swift twin: `WeeklyPlanCalendar.date`.
    fun date(day: String): Date? {
        if (!dayPattern.matches(day)) return null
        val parts = day.split('-').map { it.toInt() }
        if (parts[0] !in 1..9999) return null
        val calendar = calendar()
        calendar.set(parts[0], parts[1] - 1, parts[2])
        return runCatching { calendar.time }.getOrNull()?.takeIf { key(calendar) == day }
    }

    // Swift twin: `WeeklyPlanCalendar.adding`.
    fun adding(days: Int, to: String): String? {
        val calendar = calendar().apply { time = date(to) ?: return null }
        calendar.add(Calendar.DAY_OF_MONTH, days)
        if (calendar.get(Calendar.ERA) != GregorianCalendar.AD || calendar.get(Calendar.YEAR) !in 1..9999) return null
        return key(calendar)
    }

    // Swift twin: `WeeklyPlanCalendar.weekStart`.
    fun weekStart(day: String): String? = weekday(day)?.let { adding(-(it - 1), day) }
    // Swift twin: `WeeklyPlanCalendar.weekday`.
    fun weekday(day: String): Int? = date(day)?.let { date ->
        val calendar = calendar().apply { time = date }
        (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
    }

    // Swift twin: `WeeklyPlanCalendar.key`.
    private fun key(calendar: Calendar): String = String.format(Locale.US, "%04d-%02d-%02d",
        calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH))
}

object WeeklyPlanEngine {
    // Swift twin: `WeeklyPlanEngine.suggestedGoals`.
    fun suggestedGoals(days: List<WeeklyPlanDay>, today: String): WeeklyPlanGoals {
        val from = WeeklyPlanCalendar.adding(-30, today) ?: return WeeklyPlanGoals()
        val window = uniqueDays(days.filter { it.day >= from && it.day < today })
        val sleep = window.mapNotNull { it.sleepMinutes }.filter { it.isFinite() && it > 0 }
        val strain = window.mapNotNull { it.strain }.filter { it.isFinite() && it in 0.0..100.0 }
        return WeeklyPlanGoals(
            sleepMinutes = if (sleep.isEmpty()) 480 else sleep.average().coerceIn(240.0, 720.0).roundToInt(),
            strainMinimum = if (strain.isEmpty()) 50 else strain.average().roundToInt(),
        ).normalized
    }

    // Swift twin: `WeeklyPlanEngine.snapshot`.
    fun snapshot(
        goals: WeeklyPlanGoals,
        weekStart: String,
        today: String,
        days: List<WeeklyPlanDay>,
        journal: List<WeeklyPlanJournalDay>,
    ): WeeklyPlanSnapshot? {
        if (WeeklyPlanCalendar.weekStart(weekStart) != weekStart || WeeklyPlanCalendar.date(today) == null) return null
        val weekEnd = WeeklyPlanCalendar.adding(6, weekStart) ?: return null
        val targets = goals.normalized
        val end = minOf(today, weekEnd)
        val window = uniqueDays(days.filter { it.day >= weekStart && it.day <= end })
        val sleep = window.mapNotNull { it.sleepMinutes }.filter { it.isFinite() && it >= 0 }
        val strain = window.mapNotNull { it.strain }.filter { it.isFinite() && it in 0.0..100.0 }
        val entries = journal.filter { it.day >= weekStart && it.day <= end && WeeklyPlanCalendar.date(it.day) != null }
            .associateBy { it.day to it.question }.values
        val answered = entries.filter { targets.journalQuestion.isEmpty() || it.question == targets.journalQuestion }.map { it.day }.toSet()
        val completed = entries.filter {
            targets.journalQuestion.isEmpty() || (it.question == targets.journalQuestion && it.answeredYes == (targets.journalAnswer == "yes"))
        }.map { it.day }.toSet()
        val elapsed = (0 until 7).count { WeeklyPlanCalendar.adding(it, weekStart)?.let { day -> day <= end } == true }
        return WeeklyPlanSnapshot(weekStart, weekEnd,
            WeeklyPlanProgress(sleep.count { it >= targets.sleepMinutes }, sleep.size, targets.sleepDays),
            WeeklyPlanProgress(strain.count { it >= targets.strainMinimum }, strain.size, targets.strainDays),
            WeeklyPlanProgress(completed.size, if (targets.journalQuestion.isEmpty()) elapsed else answered.size, targets.journalDays),
        )
    }

    // Swift twin: `WeeklyPlanEngine.uniqueDays`.
    private fun uniqueDays(days: List<WeeklyPlanDay>): List<WeeklyPlanDay> =
        days.filter { WeeklyPlanCalendar.date(it.day) != null }.associateBy { it.day }.toSortedMap().values.toList()
}

data class WeeklyPlanNotice(val kind: Kind, val weekStart: String) {
    enum class Kind(val key: String) { CheckIn("checkIn"), Recap("recap") }
    val id: String get() = "${kind.key}:$weekStart"
}

object WeeklyPlanNoticeResolver {
    // Swift twin: `WeeklyPlanNoticeResolver.resolve`.
    fun resolve(today: String, availableWeeks: Set<String>, dismissedIDs: Set<String> = emptySet()): WeeklyPlanNotice? {
        val week = WeeklyPlanCalendar.weekStart(today) ?: return null
        val notice = when (WeeklyPlanCalendar.weekday(today)) {
            5 -> WeeklyPlanNotice(WeeklyPlanNotice.Kind.CheckIn, week)
            1 -> WeeklyPlanCalendar.adding(-7, week)?.let { WeeklyPlanNotice(WeeklyPlanNotice.Kind.Recap, it) } ?: return null
            else -> return null
        }
        return notice.takeIf { it.weekStart in availableWeeks && it.id !in dismissedIDs }
    }
}
