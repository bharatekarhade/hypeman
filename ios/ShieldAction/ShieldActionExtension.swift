import HypemanKit
import ManagedSettings

/// The shield's DO IT button. An extension can't open the app, so it sends a notification that starts the goal when
/// tapped, and closes the locked app (HM-05).
final class ShieldActionExtension: ShieldActionDelegate {
    override func handle(action: ShieldAction, for application: ApplicationToken, completionHandler: @escaping (ShieldActionResponse) -> Void) {
        handle(action, completionHandler)
    }

    override func handle(action: ShieldAction, for webDomain: WebDomainToken, completionHandler: @escaping (ShieldActionResponse) -> Void) {
        handle(action, completionHandler)
    }

    override func handle(action: ShieldAction, for category: ActivityCategoryToken, completionHandler: @escaping (ShieldActionResponse) -> Void) {
        handle(action, completionHandler)
    }

    private func handle(_ action: ShieldAction, _ completionHandler: @escaping (ShieldActionResponse) -> Void) {
        guard action == .primaryButtonPressed, let goal = AppLock.overdueGoal() else {
            completionHandler(.close)
            return
        }
        // Answer only once the notification is in: the system can end the extension as soon as it has its response.
        nonisolated(unsafe) let completionHandler = completionHandler
        Task {
            await Reminders.notifyNow(goalID: goal.id, title: goal.title)
            completionHandler(.close)
        }
    }
}
