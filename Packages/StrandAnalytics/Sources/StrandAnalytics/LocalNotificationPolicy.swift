import Foundation

public enum LocalNotificationPolicy {
    public static func shouldDeliver(enabled: Bool, authorized: Bool, quiet: Bool,
                                     eventKey: String?, lastEventKey: String?,
                                     occurrenceSec: Int?, nowSec: Int,
                                     maxAgeSec: Int = 86_400) -> Bool {
        guard enabled, authorized, !quiet, let eventKey, !eventKey.isEmpty,
              eventKey != lastEventKey, let occurrenceSec,
              occurrenceSec >= 0, nowSec >= 0, occurrenceSec <= nowSec, maxAgeSec >= 0 else { return false }
        return nowSec - occurrenceSec <= maxAgeSec
    }

    public static func isQuiet(minute: Int, start: Int, end: Int, enabled: Bool) -> Bool {
        guard enabled else { return false }
        let minute = min(max(minute, 0), 1439)
        let start = min(max(start, 0), 1439)
        let end = min(max(end, 0), 1439)
        if start == end { return false }
        return start < end ? minute >= start && minute < end : minute >= start || minute < end
    }

}
