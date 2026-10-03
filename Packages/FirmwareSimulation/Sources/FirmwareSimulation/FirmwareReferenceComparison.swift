/// A local label comparison, never a vendor update-availability check.
public enum FirmwareReferenceComparison: String, Sendable {
    case older, equal, newer, unknown

    public static func compare(observed: String?, reference: String) -> Self {
        guard let observed, let left = components(observed), let right = components(reference),
              left.count == right.count, left.first == right.first else { return .unknown }
        for (observed, reference) in zip(left, right) {
            if observed < reference { return .older }
            if observed > reference { return .newer }
        }
        return .equal
    }

    private static func components(_ label: String) -> [Int64]? {
        let pieces = label.split(separator: ".", omittingEmptySubsequences: false)
        guard pieces.count >= 2 else { return nil }
        var values: [Int64] = []
        for piece in pieces {
            guard !piece.isEmpty, piece.utf8.allSatisfy({ (48...57).contains($0) }),
                  let value = Int64(piece) else { return nil }
            values.append(value)
        }
        return values
    }
}
