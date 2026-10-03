// Stable on-device copy provenance and natural-key allocation; mirrored by Kotlin.
public enum WorkoutCopyIdentity {
    public static let source = "manual-copy"

    public static func isCopy(_ source: String) -> Bool { source.lowercased() == Self.source }

    public static func sport(_ original: String, occupied: [String]) -> String {
        // SQLite compares these keys bytewise, without Unicode normalization.
        let keys = Set(occupied.map { Array($0.utf8) })
        var ordinal = 1
        while true {
            let suffix = ordinal == 1 ? " (manual copy)" : " (manual copy \(ordinal))"
            let candidate = original + suffix
            if !keys.contains(Array(candidate.utf8)) { return candidate }
            ordinal += 1
        }
    }
}
