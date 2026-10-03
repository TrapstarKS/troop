/// Display-only assessment against a trusted personal baseline. No population-range fallback.
public enum HealthMonitorAssessment {
    public enum Status: String, Equatable, Sendable {
        case unavailable, unverified, calibrating, withinRange, outsideRange, farOutsideRange
    }

    public struct Result: Equatable, Sendable {
        public let status: Status
        public let lower: Double?
        public let upper: Double?
        public let nights: Int

        public init(status: Status, lower: Double? = nil, upper: Double? = nil, nights: Int = 0) {
            self.status = status
            self.lower = lower
            self.upper = upper
            self.nights = nights
        }
    }

    /// Local display configuration; does not change recovery scoring or stored values.
    public static let bloodOxygenCfg = MetricCfg(
        minVal: 70.0, maxVal: 100.0, floorSpread: 0.5, halfLifeB: 14.0, halfLifeS: 21.0
    )

    /// History is oldest first, excludes the displayed day, and includes missing calendar nights.
    public static func assess(value: Double?, history: [Double?], cfg: MetricCfg,
                              verified: Bool = true) -> Result {
        guard let value, value.isFinite else { return Result(status: .unavailable) }
        let finiteHistory = history.map { $0?.isFinite == true ? $0 : nil }
        let state = Baselines.foldHistory(finiteHistory, cfg: cfg)
        guard verified, cfg.minVal <= value && value <= cfg.maxVal else {
            return Result(status: .unverified, nights: state.nValid)
        }
        guard state.trusted else { return Result(status: .calibrating, nights: state.nValid) }

        let width = VitalBands.sigmaK * Baselines.sigma(state)
        let lower = max(cfg.minVal, state.baseline - width)
        let upper = min(cfg.maxVal, state.baseline + width)
        let z = abs(Baselines.deviation(value, state: state).z)
        let status: Status = z <= VitalBands.sigmaK ? .withinRange
            : z <= 3.0 ? .outsideRange : .farOutsideRange
        return Result(status: status, lower: lower, upper: upper, nights: state.nValid)
    }
}
