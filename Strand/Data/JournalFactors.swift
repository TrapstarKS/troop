import Foundation

struct JournalFactor {
    let canonical: String
    let groupKey: String
    let unit: String?
    let label: String

    static let all: [JournalFactor] = [
        JournalFactor(canonical: "Did you drink any alcohol?", groupKey: "nutrition", unit: nil, label: String(localized: "Alcohol")),
        JournalFactor(canonical: "Did you have caffeine late in the day?", groupKey: "nutrition", unit: nil, label: String(localized: "Late caffeine")),
        JournalFactor(canonical: "Did you view a screen in bed?", groupKey: "lifestyle", unit: nil, label: String(localized: "Screen in bed")),
        JournalFactor(canonical: "Did you eat close to bedtime?", groupKey: "nutrition", unit: nil, label: String(localized: "Late meal")),
        JournalFactor(canonical: "Did you feel stressed?", groupKey: "behaviour", unit: nil, label: String(localized: "Felt stressed")),
        JournalFactor(canonical: "Did you use a sauna?", groupKey: "lifestyle", unit: nil, label: String(localized: "Sauna")),
        JournalFactor(canonical: "Did you share your bed?", groupKey: "lifestyle", unit: nil, label: String(localized: "Shared bed")),
        JournalFactor(canonical: "Did you feel sick or ill?", groupKey: "health", unit: nil, label: String(localized: "Felt unwell")),
        JournalFactor(canonical: "Did you take magnesium?", groupKey: "supplements", unit: nil, label: String(localized: "Magnesium")),
        JournalFactor(canonical: "Did you read before bed?", groupKey: "lifestyle", unit: nil, label: String(localized: "Reading before bed")),
        JournalFactor(canonical: "Did you meditate?", groupKey: "behaviour", unit: nil, label: String(localized: "Meditation")),
        JournalFactor(canonical: "Did you do breathing exercises?", groupKey: "behaviour", unit: nil, label: String(localized: "Breathing exercises")),
        JournalFactor(canonical: "Did you spend time outdoors?", groupKey: "lifestyle", unit: nil, label: String(localized: "Time outdoors")),
        JournalFactor(canonical: "Did you travel?", groupKey: "lifestyle", unit: nil, label: String(localized: "Travel")),
        JournalFactor(canonical: "Was your bedroom noisy?", groupKey: "environment", unit: nil, label: String(localized: "Bedroom noise")),
        JournalFactor(canonical: "Was your bedroom too warm?", groupKey: "environment", unit: nil, label: String(localized: "Warm bedroom")),
        JournalFactor(canonical: "Did you use blackout curtains?", groupKey: "environment", unit: nil, label: String(localized: "Darkened bedroom")),
        JournalFactor(canonical: "Did you sleep at high altitude?", groupKey: "environment", unit: nil, label: String(localized: "High altitude")),
        JournalFactor(canonical: "Did you have allergy symptoms?", groupKey: "health", unit: nil, label: String(localized: "Allergy symptoms")),
        JournalFactor(canonical: "Did you take prescribed medication?", groupKey: "health", unit: nil, label: String(localized: "Prescribed medication")),
        JournalFactor(canonical: "Did you take vitamin D?", groupKey: "supplements", unit: nil, label: String(localized: "Vitamin D")),
        JournalFactor(canonical: "How much caffeine did you consume?", groupKey: "nutrition", unit: "mg", label: String(localized: "Caffeine amount")),
        JournalFactor(canonical: "How much water did you drink?", groupKey: "nutrition", unit: "mL", label: String(localized: "Water intake")),
        JournalFactor(canonical: "How many minutes did you meditate?", groupKey: "behaviour", unit: "min", label: String(localized: "Meditation duration")),
        JournalFactor(canonical: "What was your bedroom temperature?", groupKey: "environment", unit: "°C", label: String(localized: "Bedroom temperature")),
        JournalFactor(canonical: "How many hours did you work?", groupKey: "lifestyle", unit: "h", label: String(localized: "Work duration")),
    ]

    static func find(_ question: String) -> JournalFactor? {
        let key = normalized(question)
        return all.first { normalized($0.canonical) == key }
    }

    private static func normalized(_ value: String) -> String {
        value.components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }.joined(separator: " ").lowercased()
    }
}
