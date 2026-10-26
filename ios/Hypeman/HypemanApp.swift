import HypemanKit
import OSLog
import SwiftUI
import UserNotifications

@main
struct HypemanApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @UIApplicationDelegateAdaptor private var appDelegate: AppDelegate

    init() {
        Theme.registerFonts()
        UNUserNotificationCenter.current().delegate = NotificationPresenter.shared
        Reminders.registerActions()
        Store.shared.startListening()
        MotivatorLibrary.shared.migrateActivePack()
        // Catch up on anything that changed while the app wasn't running, such as a reminder that already went off.
        Reminders.sync(in: GoalStore.container.mainContext)
    }

    var body: some Scene {
        WindowGroup {
            GoalsView()
        }
        .modelContainer(GoalStore.container)
        .onChange(of: scenePhase) { _, phase in
            // A Live Activity can only start while the app is in front, so a goal started from the widget gets its
            // activity here.
            if phase == .active {
                LiveActivities.sync(in: GoalStore.container.mainContext)
                // Lowers the lock if Screen Time permission was revoked in Settings meanwhile.
                AppLock.sync(in: GoalStore.container.mainContext)
                Task {
                    await Account.shared.checkAppleCredential()
                    await PushRegistration.registerIfAllowed()
                }
            }
        }
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
        let userInfo = response.notification.request.content.userInfo
        if userInfo["yellId"] != nil {
            await MainActor.run { NotificationRouter.shared.openFriends = true }
            return
        }
        guard let goalID = Reminders.goalID(of: userInfo) else { return }
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
        await GoalStore.synced()
    }
}

/// What a tapped notification asks for, until GoalsView acts on it: DO IT for a goal, or the friends inbox for a yell.
/// A tap can arrive before the goal list is on screen, so it waits here.
@MainActor
@Observable
final class NotificationRouter {
    static let shared = NotificationRouter()

    var goalToStart: UUID?
    var openFriends = false
}

/// Hands the push token to the server, so friends' yells reach this phone.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Task { await Friends.shared.registerPushToken(deviceToken) }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        Logger(subsystem: "com.hypeman.app", category: "AppDelegate").error("No push token: \(error)")
    }
}
