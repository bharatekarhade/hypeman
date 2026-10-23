import DeviceActivity
import HypemanKit

/// Runs at each goal's reminder time, which the app scheduled; raises the shield if the goal still isn't started (HM-04).
final class ActivityMonitorExtension: DeviceActivityMonitor {
    override func intervalDidStart(for activity: DeviceActivityName) {
        super.intervalDidStart(for: activity)
        AppLock.intervalStarted(activity)
    }
}
