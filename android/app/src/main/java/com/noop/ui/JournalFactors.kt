package com.noop.ui

import com.noop.R

data class JournalFactor(val canonical: String, val groupKey: String, val unit: String?, val labelKey: Int) {
    companion object {
        val all = listOf(
            JournalFactor("Did you drink any alcohol?", "nutrition", null, R.string.plan_factor_0),
            JournalFactor("Did you have caffeine late in the day?", "nutrition", null, R.string.plan_factor_1),
            JournalFactor("Did you view a screen in bed?", "lifestyle", null, R.string.plan_factor_2),
            JournalFactor("Did you eat close to bedtime?", "nutrition", null, R.string.plan_factor_3),
            JournalFactor("Did you feel stressed?", "behaviour", null, R.string.plan_factor_4),
            JournalFactor("Did you use a sauna?", "lifestyle", null, R.string.plan_factor_5),
            JournalFactor("Did you share your bed?", "lifestyle", null, R.string.plan_factor_6),
            JournalFactor("Did you feel sick or ill?", "health", null, R.string.plan_factor_7),
            JournalFactor("Did you take magnesium?", "supplements", null, R.string.plan_factor_8),
            JournalFactor("Did you read before bed?", "lifestyle", null, R.string.plan_factor_9),
            JournalFactor("Did you meditate?", "behaviour", null, R.string.plan_factor_10),
            JournalFactor("Did you do breathing exercises?", "behaviour", null, R.string.plan_factor_11),
            JournalFactor("Did you spend time outdoors?", "lifestyle", null, R.string.plan_factor_12),
            JournalFactor("Did you travel?", "lifestyle", null, R.string.plan_factor_13),
            JournalFactor("Was your bedroom noisy?", "environment", null, R.string.plan_factor_14),
            JournalFactor("Was your bedroom too warm?", "environment", null, R.string.plan_factor_15),
            JournalFactor("Did you use blackout curtains?", "environment", null, R.string.plan_factor_16),
            JournalFactor("Did you sleep at high altitude?", "environment", null, R.string.plan_factor_17),
            JournalFactor("Did you have allergy symptoms?", "health", null, R.string.plan_factor_18),
            JournalFactor("Did you take prescribed medication?", "health", null, R.string.plan_factor_19),
            JournalFactor("Did you take vitamin D?", "supplements", null, R.string.plan_factor_20),
            JournalFactor("How much caffeine did you consume?", "nutrition", "mg", R.string.plan_factor_21),
            JournalFactor("How much water did you drink?", "nutrition", "mL", R.string.plan_factor_22),
            JournalFactor("How many minutes did you meditate?", "behaviour", "min", R.string.plan_factor_23),
            JournalFactor("What was your bedroom temperature?", "environment", "°C", R.string.plan_factor_24),
            JournalFactor("How many hours did you work?", "lifestyle", "h", R.string.plan_factor_25),
        )

        fun find(question: String): JournalFactor? = all.firstOrNull { normJournalKey(it.canonical) == normJournalKey(question) }
    }
}

internal fun journalLocalizedLabel(item: JournalCatalogItem): String =
    item.displayName ?: JournalFactor.find(item.canonical)?.let { uiString(it.labelKey) } ?: item.canonical
