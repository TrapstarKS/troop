enum HomeScoreValue {
    // Imported values can bypass the local scorer's bounds. Invalid scores have no display value.
    static func resolve(_ value: Double?) -> Double? {
        guard let value, value.isFinite, (0...100).contains(value) else { return nil }
        return value
    }
}
