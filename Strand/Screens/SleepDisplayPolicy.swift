struct SleepDisplayAmounts {
    let asleepMin: Double?
    let needMin: Double?
    let sufficiencyPct: Double?
    let efficiencyPct: Double?

    // Presentation only: recorded stages own durations; scoring keeps its incumbent inputs.
    static func resolve(recordedAsleep: Double?, recordedTotal: Double?, dailyAsleep: Double?,
                        dailySufficiencyPct: Double?, importedNeed: Double?, storedEfficiency: Double?) -> Self {
        let recorded = recordedAsleep.flatMap { asleep in
            recordedTotal.flatMap { total in
                total.isFinite && total > 0 && asleep.isFinite && asleep >= 0 && asleep <= total
                    ? (asleep, total) : nil
            }
        }
        let daily = dailyAsleep.flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
        let asleep = recorded?.0 ?? daily
        let imported = importedNeed.flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
        let inferred = dailySufficiencyPct.flatMap { ratio in
            ratio.isFinite && ratio > 0 ? daily.map { $0 / ratio * 100 } : nil
        }.flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
        let need = imported ?? inferred
        let sufficiency = need.flatMap { need in asleep.map { $0 / need * 100 } }
        let stored = storedEfficiency.flatMap { $0.isFinite ? ($0 <= 1 ? $0 * 100 : $0) : nil }
        let efficiency = recorded.map { $0.0 / $0.1 * 100 } ?? stored
        return Self(asleepMin: asleep, needMin: need, sufficiencyPct: sufficiency,
                    efficiencyPct: efficiency.map { Swift.min(100, Swift.max(0, $0)) })
    }
}
