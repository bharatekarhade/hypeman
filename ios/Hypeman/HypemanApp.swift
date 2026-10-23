import HypemanKit
import SwiftUI
import UserNotifications

@main
struct HypemanApp: App {
    init() {
        Theme.registerFonts()
        UNUserNotificationCenter.current().delegate = NotificationPresenter.shared
        // Catch up on anything that changed while the app wasn't running, such as a reminder that already went off.
        Reminders.sync(in: GoalStore.container.mainContext)
    }

    var body: some Scene {
        WindowGroup {
            GoalsView()
        }
        .modelContainer(GoalStore.container)
    }
}

/// Shows reminders and nags even while the app is open; iOS hides them by default.
final class NotificationPresenter: NSObject, UNUserNotificationCenterDelegate, Sendable {
    static let shared = NotificationPresenter()

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async
        -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }
}
