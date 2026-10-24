import ActivityKit
import Foundation
import os
import OSLog
import SwiftData

/// A started goal on the Lock Screen and in the Dynamic Island: its title, a running timer and a DONE button (DI-05).
public struct GoalActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable, Sendable {
        public var title: String
        public var startedAt: Date

        public init(title: String, startedAt: Date) {
            self.title = title
            self.startedAt = startedAt
        }
    }

    public var goalID: UUID

    public init(goalID: UUID) {
        self.goalID = goalID
    }
}

/// Keeps one Live Activity per started, unfinished goal. Rebuilt from the goal store after every change, like Reminders,
/// so finishing, undoing or deleting a goal anywhere ends its activity. Each start of a goal gets one activity: once the
/// user swipes it away (or iOS ends it after hours), it stays gone until the goal is started afresh. A Pro feature
/// (DI-04): without Pro, every activity ends.
public enum LiveActivities {
    private static let log = Logger(subsystem: "com.hypeman.app", category: "LiveActivities")
    private static let shownKey = "LiveActivities.shown"

    /// One start of one goal. Undoing a completion clears startedAt, so starting again is a new session.
    struct Session: Codable, Hashable {
        var goalID: UUID
        var startedAt: Date
    }

    /// What should change, given the started goals and the goals that have an activity now.
    struct Plan: Equatable {
        var start: [UUID: GoalActivityAttributes.ContentState] = [:]
        var update: [UUID: GoalActivityAttributes.ContentState] = [:]
        var end: Set<UUID> = []
    }

    public static func sync(in context: ModelContext) {
        guard isAvailable, let goals = try? GoalStore.remaining(in: context) else { return }
        let wanted = ProAccess.isActive() ? wanted(from: goals) : [:]
        latest.withLock { latest in
            latest = Task { [previous = latest] in
                await previous?.value
                await apply(wanted)
            }
        }
    }

    /// Waits until every sync so far has reached ActivityKit. See Reminders.scheduled().
    public static func updated() async {
        await latest.withLock { $0 }?.value
    }

    private static let latest = OSAllocatedUnfairLock<Task<Void, Never>?>(initialState: nil)

    static func wanted(from goals: [Goal]) -> [UUID: GoalActivityAttributes.ContentState] {
        var wanted: [UUID: GoalActivityAttributes.ContentState] = [:]
        for goal in goals where goal.completedAt == nil {
            if let startedAt = goal.startedAt {
                wanted[goal.id] = .init(title: goal.title, startedAt: startedAt)
            }
        }
        return wanted
    }

    /// `shown` is every session that already had an activity. One that's no longer running was dismissed, so it isn't
    /// started again.
    static func plan(wanted: [UUID: GoalActivityAttributes.ContentState],
                     running: [UUID: GoalActivityAttributes.ContentState], shown: Set<Session>) -> Plan {
        var plan = Plan()
        for (id, state) in wanted {
            if let current = running[id] {
                if current != state {
                    plan.update[id] = state
                }
            } else if !shown.contains(Session(goalID: id, startedAt: state.startedAt)) {
                plan.start[id] = state
            }
        }
        plan.end = Set(running.keys).subtracting(wanted.keys)
        return plan
    }

    private static func apply(_ wanted: [UUID: GoalActivityAttributes.ContentState]) async {
        var activities: [UUID: Activity<GoalActivityAttributes>] = [:]
        for activity in Activity<GoalActivityAttributes>.activities where activity.activityState == .active || activity.activityState == .stale {
            if activities[activity.attributes.goalID] == nil {
                activities[activity.attributes.goalID] = activity
            } else {
                // A duplicate from an earlier race; keep one.
                await activity.end(nil, dismissalPolicy: .immediate)
            }
        }
        var shown = loadShown()
        // Covers activities started before sessions were tracked.
        for (id, activity) in activities {
            shown.insert(Session(goalID: id, startedAt: activity.content.state.startedAt))
        }
        let plan = plan(wanted: wanted, running: activities.mapValues(\.content.state), shown: shown)
        for id in plan.end {
            await activities[id]?.end(nil, dismissalPolicy: .immediate)
        }
        for (id, state) in plan.update {
            await activities[id]?.update(ActivityContent(state: state, staleDate: nil))
        }
        for (id, state) in plan.start {
            do {
                _ = try Activity.request(attributes: GoalActivityAttributes(goalID: id), content: ActivityContent(state: state, staleDate: nil))
                shown.insert(Session(goalID: id, startedAt: state.startedAt))
            } catch {
                // Expected when the app isn't in the foreground or the user turned Live Activities off; the next sync
                // after the app opens tries again.
                log.info("Could not start the Live Activity for \(id): \(error)")
            }
        }
        // Sessions that are over can't come back, so forgetting them keeps the list to the goals in progress.
        saveShown(shown.filter { wanted[$0.goalID]?.startedAt == $0.startedAt })
    }

    /// In the app's own defaults: only the app starts activities. Kept across launches, since activities outlive the app.
    private static func loadShown() -> Set<Session> {
        guard let data = UserDefaults.standard.data(forKey: shownKey) else { return [] }
        return (try? JSONDecoder().decode(Set<Session>.self, from: data)) ?? []
    }

    private static func saveShown(_ shown: Set<Session>) {
        UserDefaults.standard.set(try? JSONEncoder().encode(shown), forKey: shownKey)
    }

    /// Only the app may start activities, and ActivityKit isn't usable from unit tests.
    private static var isAvailable: Bool {
        Bundle.main.bundleURL.pathExtension == "app"
    }
}
