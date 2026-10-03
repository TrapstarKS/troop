import WhoopStore

extension ChargeBaselines {
    /// Resolve HRV, resting-HR and respiration baselines from stored rows: `imported` are the imported
    /// vendor rows, `own` the NOOP-computed ("-noop") rows. HRV folds on `hrvEpoch`, resting HR and
    /// respiration on `recoveryEpoch`, exactly as the engine does. The engine additionally cuts respiration
    /// at a device-era boundary (#459), which needs a per-night source the stored rows do not carry; the
    /// two agree for every single-brand history. Kotlin twin: `ChargeBaselines.resolve`.
    public static func resolve(imported: [DailyMetric], own: [DailyMetric], anchorDay: String,
                               hrvEpoch: Double, recoveryEpoch: Double) -> Resolved {
        let hrvCfg = Baselines.hrvCfg, rhrCfg = Baselines.restingHRCfg, respCfg = Baselines.respCfg
        let hrvHistory = history(imported: imported.map { (day: $0.day, value: $0.avgHrv) },
                                 own: own.map { (day: $0.day, value: $0.avgHrv) },
                                 anchorDay: anchorDay, cfg: hrvCfg, baselineEpoch: hrvEpoch)
        let rhrHistory = history(imported: imported.map { (day: $0.day, value: $0.restingHr.map(Double.init)) },
                                 own: own.map { (day: $0.day, value: $0.restingHr.map(Double.init)) },
                                 anchorDay: anchorDay, cfg: rhrCfg, baselineEpoch: recoveryEpoch)
        let respHistory = history(imported: imported.map { (day: $0.day, value: $0.respRateBpm) },
                                  own: own.map { (day: $0.day, value: $0.respRateBpm) },
                                  anchorDay: anchorDay, cfg: respCfg, baselineEpoch: recoveryEpoch)
        return Resolved(
            hrvHistory: hrvHistory, restingHRHistory: rhrHistory, respHistory: respHistory,
            hrv: Baselines.foldHistory(hrvHistory.values, dayKeys: hrvHistory.dayKeys, cfg: hrvCfg,
                                       baselineEpoch: hrvEpoch),
            restingHR: Baselines.foldHistory(rhrHistory.values, dayKeys: rhrHistory.dayKeys, cfg: rhrCfg,
                                             baselineEpoch: recoveryEpoch),
            resp: Baselines.foldHistory(respHistory.values, dayKeys: respHistory.dayKeys, cfg: respCfg,
                                        baselineEpoch: recoveryEpoch))
    }

}
