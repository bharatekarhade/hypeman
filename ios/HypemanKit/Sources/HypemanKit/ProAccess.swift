import Foundation

/// Whether Pro is on, for the app and its extensions. The Store keeps the end of the current Pro period here, from
/// StoreKit on this phone or from the server, so Pro features stop at that date by themselves, even offline (PY-06).
public enum ProAccess {
    private static let untilKey = "ProAccess.until"

    /// When the current Pro period ends; nil for someone who never had Pro.
    public static var until: Date? {
        get { AppGroup.defaults?.object(forKey: untilKey) as? Date }
        set { AppGroup.defaults?.set(newValue, forKey: untilKey) }
    }

    public static func isActive(now: Date = .now) -> Bool {
        until.map { $0 > now } ?? false
    }
}
