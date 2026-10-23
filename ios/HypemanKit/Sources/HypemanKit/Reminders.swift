import Foundation
import os
import OSLog
import SwiftData
import UserNotifications

/// Local notifications for goals with a reminder time: the pack's REMINDER at that time, then its NAG twice while the goal
/// still isn't started. Rebuilt from the goal store after every change, so they can't drift from the goals. Each one has a
/// Snooze button; tapping it starts the goal.
public enum Reminders {
    /// After the reminder, how long until each nag.
    public static let nagDelays: [TimeInterval] = [10 * 60, 30 * 60]
    public static let snoozeInterval: TimeInterval = 10 * 60
    /// The notification category every reminder and nag uses, for its Snooze button.
    public static let category = "GOAL_REMINDER"
    public static let snoozeAction = "SNOOZE"

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

    /// Registers the Snooze button. Call once at launch. It runs in the background, without opening the app.
    public static func registerActions() {
        guard let center else { return }
        let snooze = UNNotificationAction(identifier: snoozeAction, title: "Snooze \(Int(snoozeInterval / 60)) min", options: [])
        center.setNotificationCategories([UNNotificationCategory(identifier: category, actions: [snooze], intentIdentifiers: [])])
    }

    /// The goal a delivered reminder or nag is about.
    public static func goalID(of userInfo: [AnyHashable: Any]) -> UUID? {
        (userInfo["goalID"] as? String).flatMap(UUID.init(uuidString:))
    }

    /// Replaces every pending notification with what the goals call for now, and clears delivered ones for goals that
    /// were started, finished or deleted. The notification center works asynchronously; see `scheduled()`.
    public static func sync(in context: ModelContext, library: PackLibrary = .shared) {
        guard center != nil, let goals = try? GoalStore.remaining(in: context) else { return }
        let requests = Requests(all: requests(for: goals, pack: library.active, soundName: library.soundName(for:), now: .now))
        let waiting = Set(goals.filter { $0.startedAt == nil }.map(\.id.uuidString))
        latest.withLock { latest in
            latest = Task { [previous = latest] in
                await previous?.value
                await replace(with: requests, keepingDeliveredFor: waiting)
            }
        }
    }

    /// Waits until the notification center has taken every sync so far. Background handlers (Snooze, widget buttons)
    /// await this before returning, since iOS can suspend the process as soon as they do.
    public static func scheduled() async {
        await latest.withLock { $0 }?.value
    }

    /// The latest sync's work. Each one waits for the one before, so the center sees them in order.
    private static let latest = OSAllocatedUnfairLock<Task<Void, Never>?>(initialState: nil)

    /// UNNotificationRequest is immutable but not marked Sendable; these are built once and handed to one task.
    private struct Requests: @unchecked Sendable {
        let all: [UNNotificationRequest]
    }

    private static func replace(with requests: Requests, keepingDeliveredFor waiting: Set<String>) async {
        let center = UNUserNotificationCenter.current()
        center.removeAllPendingNotificationRequests()
        for request in requests.all {
            do {
                try await center.add(request)
            } catch {
                log.error("Could not schedule \(request.identifier): \(error)")
            }
        }
        let delivered = await center.deliveredNotifications()
        let stale = delivered.map(\.request).filter { !waiting.contains($0.content.threadIdentifier) }.map(\.identifier)
        center.removeDeliveredNotifications(withIdentifiers: stale)
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
        content.categoryIdentifier = category
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
