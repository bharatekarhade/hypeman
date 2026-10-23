import DeviceActivity
import FamilyControls
import Foundation
import ManagedSettings
import OSLog
import SwiftData

/// Locks the apps the user picked while a goal is overdue: its reminder went off and it still isn't started (HM-04).
/// The app may not be running at reminder time, so it schedules a DeviceActivity interval at each reminder and the
/// ActivityMonitor extension raises the shield when one starts. Only the app lowers it, once nothing is overdue.
public enum AppLock {
    /// A goal that can lock the apps, as the extensions see it. They get this list rather than opening the goal store,
    /// which is too heavy for their memory limits.
    public struct Entry: Codable, Equatable, Sendable {
        public var id: UUID
        public var title: String
        public var reminderAt: Date
    }

    /// DeviceActivity intervals must be at least 15 minutes. The shield stays up after one ends.
    static let intervalLength: TimeInterval = 15 * 60
    /// DeviceActivityCenter watches at most 20 activities at once; later reminders get scheduled as earlier ones pass.
    static let maxScheduled = 20

    private static let selectionKey = "AppLock.selection"
    private static let entriesKey = "AppLock.entries"
    /// A computed property because ManagedSettingsStore.Name isn't Sendable.
    private static var store: ManagedSettingsStore {
        ManagedSettingsStore(named: ManagedSettingsStore.Name("hypeman"))
    }
    private static let log = Logger(subsystem: "com.hypeman.app", category: "AppLock")

    /// The apps, categories and sites to lock. Empty means the lock is off.
    public static var selection: FamilyActivitySelection {
        get { load(FamilyActivitySelection.self, selectionKey) ?? FamilyActivitySelection() }
        set { save(newValue, selectionKey) }
    }

    public static var isAuthorized: Bool {
        isAvailable && AuthorizationCenter.shared.authorizationStatus == .approved
    }

    public static var isOn: Bool {
        isAuthorized && !selection.isEmpty
    }

    /// Screen Time permission for this device's own user. Throws when the user declines or Screen Time is restricted.
    @MainActor
    public static func requestAuthorization() async throws {
        try await AuthorizationCenter.shared.requestAuthorization(for: .individual)
    }

    /// Reschedules the lock for every waiting goal and raises or lowers the shield for now.
    public static func sync(in context: ModelContext, now: Date = .now) {
        guard isAvailable, let goals = try? GoalStore.remaining(in: context) else { return }
        let entries = isOn ? entries(for: goals) : []
        save(entries, entriesKey)
        let center = DeviceActivityCenter()
        center.stopMonitoring()
        for entry in entries.filter({ $0.reminderAt > now }).prefix(maxScheduled) {
            do {
                try center.startMonitoring(activityName(entry.id), during: schedule(for: entry))
            } catch {
                log.error("Could not schedule the lock for \(entry.id): \(error)")
            }
        }
        if overdue(in: entries, now: now) != nil {
            lock()
        } else {
            store.clearAllSettings()
        }
    }

    /// For the ActivityMonitor extension: a reminder's interval started, so lock if that goal is still waiting.
    public static func intervalStarted(_ activity: DeviceActivityName) {
        let entries = load([Entry].self, entriesKey) ?? []
        if entries.contains(where: { activityName($0.id) == activity }) {
            lock()
        }
    }

    /// For the shield: the goal it's up for, the longest overdue one.
    public static func overdueGoal(now: Date = .now) -> Entry? {
        overdue(in: load([Entry].self, entriesKey) ?? [], now: now)
    }

    /// Unstarted, unfinished goals with a reminder, soonest first.
    static func entries(for goals: [Goal]) -> [Entry] {
        goals.filter { $0.completedAt == nil && $0.startedAt == nil }
            .compactMap { goal in goal.reminderAt.map { Entry(id: goal.id, title: goal.title, reminderAt: $0) } }
            .sorted { $0.reminderAt < $1.reminderAt }
    }

    static func overdue(in entries: [Entry], now: Date) -> Entry? {
        entries.filter { $0.reminderAt <= now }.min { $0.reminderAt < $1.reminderAt }
    }

    static func schedule(for entry: Entry) -> DeviceActivitySchedule {
        let components: Set<Calendar.Component> = [.year, .month, .day, .hour, .minute, .second]
        return DeviceActivitySchedule(
            intervalStart: Calendar.current.dateComponents(components, from: entry.reminderAt),
            intervalEnd: Calendar.current.dateComponents(components, from: entry.reminderAt.addingTimeInterval(intervalLength)),
            repeats: false
        )
    }

    private static func activityName(_ goalID: UUID) -> DeviceActivityName {
        DeviceActivityName(goalID.uuidString)
    }

    private static func lock() {
        let selection = selection
        let store = store
        store.shield.applications = selection.applicationTokens.isEmpty ? nil : selection.applicationTokens
        store.shield.applicationCategories = selection.categoryTokens.isEmpty ? nil : .specific(selection.categoryTokens)
        store.shield.webDomains = selection.webDomainTokens.isEmpty ? nil : selection.webDomainTokens
    }

    private static func load<T: Decodable>(_ type: T.Type, _ key: String) -> T? {
        guard let data = AppGroup.defaults?.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    private static func save(_ value: some Encodable, _ key: String) {
        AppGroup.defaults?.set(try? JSONEncoder().encode(value), forKey: key)
    }

    /// Screen Time APIs need an app or extension; unit tests have neither.
    private static var isAvailable: Bool {
        ["app", "appex"].contains(Bundle.main.bundleURL.pathExtension)
    }
}

public extension FamilyActivitySelection {
    var isEmpty: Bool {
        applicationTokens.isEmpty && categoryTokens.isEmpty && webDomainTokens.isEmpty
    }
}
