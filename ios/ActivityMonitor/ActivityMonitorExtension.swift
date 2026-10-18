import DeviceActivity

final class ActivityMonitorExtension: DeviceActivityMonitor {
    override func intervalDidStart(for activity: DeviceActivityName) {
        super.intervalDidStart(for: activity)
        // TODO: block the picked apps while the goal is overdue (HM-04).
    }
}
