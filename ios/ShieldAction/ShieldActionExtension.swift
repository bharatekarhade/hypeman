import ManagedSettings

final class ShieldActionExtension: ShieldActionDelegate {
    override func handle(action: ShieldAction, for application: ApplicationToken, completionHandler: @escaping (ShieldActionResponse) -> Void) {
        // TODO: send the notification that opens the DO IT screen (HM-05).
        completionHandler(.close)
    }
}
