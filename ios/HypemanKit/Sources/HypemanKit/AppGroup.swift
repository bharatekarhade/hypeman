import Foundation

/// The container shared by the app and its extensions, which run as separate processes.
public enum AppGroup {
    public static let identifier = "group.com.hypeman.app"

    public static var containerURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier)
    }

    public static var defaults: UserDefaults? {
        UserDefaults(suiteName: identifier)
    }
}
