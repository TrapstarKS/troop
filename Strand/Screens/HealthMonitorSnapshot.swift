import Foundation
import StrandAnalytics
import WhoopStore

struct HealthMonitorRow: Identifiable {
    let reading: BodyVitalReading
    let assessment: HealthMonitorAssessment.Result
    let isCurrent: Bool
    var id: String { reading.key }
    var formattedValue: String? {
        guard let value = reading.value, value.isFinite else { return nil }
        let absolute = reading.key == "skin" && VitalBands.isAbsoluteSkinTemp(value)
        let cfg = HealthMonitorSnapshot.config(key: reading.key, absoluteSkin: absolute)
        guard value >= cfg.minVal, value <= cfg.maxVal else { return nil }
        return reading.formattedValue
    }
}

enum HealthMonitorSnapshot {
    static let keys = ["hrv", "rhr", "resp", "spo2", "skin"]

    static func dayKey(days: [DailyMetric], now: Date) -> String {
        let logical = BodyVitalSigns.logicalDayKey(now)
        return Repository.resolveToday(days: days, logicalKey: logical, localKey: Repository.localDayKey(now))?.day ?? logical
    }

    static func dayLabel(_ day: String, todayKey: String, locale: Locale = .current) -> String {
        if day == todayKey { return String(localized: "Today") }
        guard let date = BodyVitalSigns.dayParser.date(from: day) else { return day }
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "d MMM"
        return formatter.string(from: date)
    }

    static func reportSkinKind(value: Double?, preferred: SkinTempDisplay.Kind) -> SkinTempDisplay.Kind {
        guard let value, value.isFinite else { return preferred }
        let absolute = VitalBands.isAbsoluteSkinTemp(value)
        let cfg = config(key: "skin", absoluteSkin: absolute)
        guard value >= cfg.minVal, value <= cfg.maxVal else { return preferred }
        return absolute ? .absolute : .deviation
    }

    static func rows(sourceRows: [SourcedDailyMetric],
                     temperatureUnit: TemperatureUnit = .celsius,
                     now: Date = Date(),
                     todayKey: String? = nil,
                     hrvReliabilityByDay: [String: HealthSignalReliability.Record]? = nil,
                     respReliabilityByDay: [String: HealthSignalReliability.Record]? = nil,
                     skinTempPreferred: SkinTempDisplay.Kind = .absolute,
                     hrvBaselineEpoch: Double = Baselines.hrvBaselineEpoch(),
                     recoveryBaselineEpoch: Double = Baselines.recoveryBaselineEpoch()) -> [HealthMonitorRow] {
        let today = todayKey ?? dayKey(days: sourceRows.map(\.metric), now: now)
        let eligibleRows = sourceRows.filter { $0.metric.day <= today }
        let readings = BodyVitalSigns.readings(sourceRows: eligibleRows, temperatureUnit: temperatureUnit,
                                               now: now,
                                               skinTempPreferred: skinTempPreferred, todayKey: today)
        return keys.compactMap { key in
            guard let reading = readings.first(where: { $0.key == key }) else { return nil }
            let computed = reading.source == .noopComputed || reading.source == .localCache
            let verified: Bool
            switch key {
            case "hrv":
                verified = computed ? reading.day.flatMap { hrvReliabilityByDay?[$0] }?.matches(reading.value) == true
                    : HealthSignalReliability.hrv(reading.value, computed: false) != nil
            case "resp":
                verified = computed ? reading.day.flatMap { respReliabilityByDay?[$0] }?.matches(reading.value) == true
                    : HealthSignalReliability.respiration(reading.value, computed: false) != nil
            default: verified = true
            }
            let current = reading.day == today
            let absolute = key == "skin" && (reading.value.map(VitalBands.isAbsoluteSkinTemp) ?? true)
            let history = history(key: key, sourceRows: sourceRows, before: today,
                                  absoluteSkin: absolute, hrvReliabilityByDay: hrvReliabilityByDay,
                                  respReliabilityByDay: respReliabilityByDay, baselineEpoch: key == "hrv" ? hrvBaselineEpoch : (key == "spo2" ? 0 : recoveryBaselineEpoch))
            let result = HealthMonitorAssessment.assess(value: current ? reading.value : nil,
                                                       history: history, cfg: config(key: key, absoluteSkin: absolute),
                                                       verified: verified)
            return HealthMonitorRow(reading: reading, assessment: result, isCurrent: current)
        }
    }

    static func config(key: String, absoluteSkin: Bool) -> MetricCfg {
        switch key {
        case "hrv": return Baselines.hrvCfg
        case "rhr": return Baselines.restingHRCfg
        case "resp": return Baselines.respCfg
        case "spo2": return HealthMonitorAssessment.bloodOxygenCfg
        default: return absoluteSkin ? Baselines.metricCfg["skin_temp"]! : VitalBands.skinTempDeviationCfg
        }
    }

    static func value(key: String, metric: DailyMetric, absoluteSkin: Bool) -> Double? {
        switch key {
        case "hrv": return metric.avgHrv
        case "rhr": return metric.restingHr.map(Double.init)
        case "resp": return metric.respRateBpm
        case "spo2": return metric.spo2Pct
        default:
            if absoluteSkin {
                return metric.skinTempC ?? metric.skinTempDevC.flatMap { VitalBands.isAbsoluteSkinTemp($0) ? $0 : nil }
            }
            return metric.skinTempDevC.flatMap { VitalBands.isAbsoluteSkinTemp($0) ? nil : $0 }
        }
    }

    static func resolvedValues(key: String, sourceRows: [SourcedDailyMetric], absoluteSkin: Bool,
                               hrvReliabilityByDay: [String: HealthSignalReliability.Record]? = nil,
                               respReliabilityByDay: [String: HealthSignalReliability.Record]? = nil) -> [String: Double] {
        var byDay: [String: Double] = [:]
        var resolvedDays = Set<String>()
        for source in DailyMetricSource.vitalPrecedence(for: key) {
            for row in sourceRows where row.source == source {
                guard !resolvedDays.contains(row.metric.day),
                      let number = value(key: key, metric: row.metric, absoluteSkin: absoluteSkin) else { continue }
                resolvedDays.insert(row.metric.day)
                guard number.isFinite else { continue }
                if key == "hrv" || key == "resp" {
                    let computed = source == .noopComputed || source == .localCache
                    let records = key == "hrv" ? hrvReliabilityByDay : respReliabilityByDay
                    let importedEligible = key == "hrv"
                        ? HealthSignalReliability.hrv(number, computed: false) != nil
                        : HealthSignalReliability.respiration(number, computed: false) != nil
                    let verified = computed ? records?[row.metric.day]?.matches(number) == true : importedEligible
                    if !verified { continue }
                }
                byDay[row.metric.day] = number
            }
        }
        return byDay
    }

    private static func history(key: String, sourceRows: [SourcedDailyMetric], before day: String,
                                absoluteSkin: Bool, hrvReliabilityByDay: [String: HealthSignalReliability.Record]?,
                                respReliabilityByDay: [String: HealthSignalReliability.Record]?, baselineEpoch: Double) -> [Double?] {
        let byDay = resolvedValues(key: key, sourceRows: sourceRows, absoluteSkin: absoluteSkin,
                                  hrvReliabilityByDay: hrvReliabilityByDay, respReliabilityByDay: respReliabilityByDay)
        let epoch = baselineEpoch
        let epochCutoff = epoch > 0 ? String(ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: ceil(epoch / 86_400) * 86_400)).prefix(10)) : ""
        let cutoff = max(epochCutoff, Baselines.cutoffKey(todayKey: day, carryDays: 180))
        var points = byDay.filter { $0.key < day && $0.key >= cutoff }.map { (day: $0.key, value: Optional($0.value)) }
        let previous = Baselines.cutoffKey(todayKey: day, carryDays: 1)
        if !points.contains(where: { $0.day == previous }) { points.append((previous, nil)) }
        return VitalBands.calendarSeries(points)
    }
}
