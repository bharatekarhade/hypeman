import Foundation
import OSLog
import SwiftData
import UserNotifications

/// Local notifications for goals with a reminder time: the pack's REMINDER at that time, then its NAG twice while the goal
/// still isn't started. Rebuilt from the goal store after every change, so they can't drift from the goals.
public enum Reminders {
    /// After the reminder, how long until each nag.
    public static let nagDelays: [TimeInterval] = [10 * 60, 30 * 60]

    private static let log = Logger(subsystem: "com.hypeman.app", category: "Reminders")

    /// Asks once; afterwards iOS answers from the user's choice without showing anything.
    @discardableResult
    public static func requestPermission() async -> Bool {
        guard let center else { return false }
        do {
            return try await center.requestAuthorization(options: [.alert, .sound])
        } catch {
            log.error("Could not ask for notification permission: \(error)")
            return false
        }
    }

    /// Replaces every pending notification with what the goals call for now, and clears delivered ones for goals that
    /// were started, finished or deleted.
    public static func sync(in context: ModelContext, library: PackLibrary = .shared) {
        guard let center, let goals = try? GoalStore.remaining(in: context) else { return }
        let requests = requests(for: goals, pack: library.active, soundName: library.soundName(for:), now: .now)
        center.removeAllPendingNotificationRequests()
        for request in requests {
            let identifier = request.identifier
            center.add(request) { error in
                if let error {
                    log.error("Could not schedule \(identifier): \(error)")
                }
            }
        }
        let waiting = Set(goals.filter { $0.startedAt == nil }.map(\.id.uuidString))
        center.getDeliveredNotifications { delivered in
            let stale = delivered.map(\.request).filter { !waiting.contains($0.content.threadIdentifier) }.map(\.identifier)
            // Not the captured center: it isn't Sendable.
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: stale)
        }
    }

    /// The notifications for unfinished, unstarted goals whose reminder is still ahead, grouped by goal.
    static func requests(for goals: [Goal], pack: InstalledPack?, soundName: (ClipRole) -> String?, now: Date) -> [UNNotificationRequest] {
        goals.filter { $0.completedAt == nil && $0.startedAt == nil }.flatMap { goal -> [UNNotificationRequest] in
            guard let reminderAt = goal.reminderAt else { return [] }
            let reminder = request(goal, role: .reminder, at: reminderAt, id: "reminder",
                                   body: pack?.caption(for: .reminder) ?? "Time to do it.", sound: soundName(.reminder), now: now)
            let nags = nagDelays.enumerated().map { index, delay in
                request(goal, role: .nag, at: reminderAt.addingTimeInterval(delay), id: "nag-\(index + 1)",
                        body: pack?.caption(for: .nag) ?? "Still not started. Get up.", sound: soundName(.nag), now: now)
            }
            return ([reminder] + nags).compactMap { $0 }
        }
    }

    private static func request(_ goal: Goal, role: ClipRole, at date: Date, id: String, body: String, sound: String?,
                                now: Date) -> UNNotificationRequest? {
        guard date > now else { return nil }
        let content = UNMutableNotificationContent()
        content.title = goal.title
        content.body = body
        content.sound = sound.map { UNNotificationSound(named: UNNotificationSoundName($0)) } ?? .default
        content.threadIdentifier = goal.id.uuidString
        content.userInfo = ["goalID": goal.id.uuidString, "role": role.rawValue]
        let components = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        return UNNotificationRequest(identifier: "\(goal.id.uuidString)-\(id)", content: content,
                                     trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false))
    }

    /// Nil in unit tests, which run without an app bundle; UNUserNotificationCenter crashes there.
    private static var center: UNUserNotificationCenter? {
        ["app", "appex"].contains(Bundle.main.bundleURL.pathExtension) ? .current() : nil
    }
}
