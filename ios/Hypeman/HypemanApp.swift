import HypemanKit
import OSLog
import SwiftUI
import UserNotifications

@main
struct HypemanApp: App {
    init() {
        Theme.registerFonts()
        UNUserNotificationCenter.current().delegate = NotificationPresenter.shared
        Reminders.registerActions()
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

/// Shows reminders and nags even while the app is open (iOS hides them by default), and acts on them: Snooze moves the
/// reminder, a tap starts the goal.
final class NotificationPresenter: NSObject, UNUserNotificationCenterDelegate, Sendable {
    static let shared = NotificationPresenter()

    private let log = Logger(subsystem: "com.hypeman.app", category: "NotificationPresenter")

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async
        -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard let goalID = Reminders.goalID(of: response.notification.request.content.userInfo) else { return }
        let action = response.actionIdentifier
        await MainActor.run {
            switch action {
            case Reminders.snoozeAction:
                do {
                    try GoalStore.snooze(goalID, in: GoalStore.container.mainContext)
                } catch {
                    log.error("Could not snooze \(goalID): \(error)")
                }
            case UNNotificationDefaultActionIdentifier:
                NotificationRouter.shared.goalToStart = goalID
            default:
                break
            }
        }
        // Snooze runs in the background; returning lets iOS suspend the app before the new reminders are in.
        await Reminders.scheduled()
    }
}

/// The goal a tapped notification is about, until GoalsView opens DO IT for it. A tap can arrive before the goal list is
/// on screen, so it waits here.
@MainActor
@Observable
final class NotificationRouter {
    static let shared = NotificationRouter()

    var goalToStart: UUID?
}
