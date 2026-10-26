import Foundation
import SwiftData
import WidgetKit

/// One SwiftData store in the App Group, so the app, widgets and intents all see the same goals.
public enum GoalStore {
    public static let container: ModelContainer = {
        let configuration = if let url = AppGroup.containerURL {
            ModelConfiguration(url: url.appending(path: "Goals.store"))
        } else {
            ModelConfiguration()
        }
        do {
            return try ModelContainer(for: Goal.self, configurations: configuration)
        } catch {
            fatalError("Could not open the goal store: \(error)")
        }
    }()

    /// Unfinished goals in list order; the first one is the next goal.
    public static func remaining(in context: ModelContext) throws -> [Goal] {
        let descriptor = FetchDescriptor<Goal>(
            predicate: #Predicate { $0.completedAt == nil },
            sortBy: [SortDescriptor(\.sortIndex)]
        )
        return try context.fetch(descriptor)
    }

    public static func add(title: String, in context: ModelContext) throws {
        let title = String(title.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Goal.maxTitleLength))
        guard !title.isEmpty else { return }
        let lastIndex = try remaining(in: context).last?.sortIndex ?? -1
        context.insert(Goal(title: title, sortIndex: lastIndex + 1))
        try save(context)
    }

    /// Records the first start only; tapping DO IT again just yells again.
    public static func start(_ id: UUID, in context: ModelContext) throws {
        guard let goal = try goal(id, in: context), goal.completedAt == nil, goal.startedAt == nil else { return }
        goal.startedAt = .now
        try save(context)
    }

    public static func complete(_ id: UUID, in context: ModelContext) throws {
        guard let goal = try goal(id, in: context), goal.completedAt == nil else { return }
        goal.completedAt = .now
        try save(context)
    }

    /// Nil clears the reminder. Reminders are notifications, so ask for permission before the first one.
    public static func setReminder(_ id: UUID, at date: Date?, in context: ModelContext) throws {
        guard let goal = try goal(id, in: context), goal.completedAt == nil else { return }
        goal.reminderAt = date
        try save(context)
    }

    /// The Snooze button: the reminder moves to ten minutes from now and the nags follow from there.
    /// Nil goes back to the default motivator.
    public static func setMotivator(_ id: UUID, to motivatorID: String?, in context: ModelContext) throws {
        guard let goal = try goal(id, in: context) else { return }
        goal.motivatorID = motivatorID
        try save(context)
    }

    public static func snooze(_ id: UUID, in context: ModelContext) throws {
        guard let goal = try goal(id, in: context), goal.completedAt == nil, goal.startedAt == nil else { return }
        goal.reminderAt = .now.addingTimeInterval(Reminders.snoozeInterval)
        try save(context)
    }

    /// Waits until the notifications and Live Activities match the last save. Code running in the background (the
    /// Snooze button, widget buttons) awaits this before returning, since iOS can suspend the process as soon as it does.
    public static func synced() async {
        await Reminders.scheduled()
        await LiveActivities.updated()
    }

    public static func rename(_ id: UUID, title: String, in context: ModelContext) throws {
        let title = String(title.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Goal.maxTitleLength))
        guard !title.isEmpty, let goal = try goal(id, in: context) else { return }
        goal.title = title
        try save(context)
    }

    /// Restored goals go to the end, preserving the user's current next goal and order.
    public static func undoCompletion(_ id: UUID, in context: ModelContext) throws {
        guard let goal = try goal(id, in: context), goal.completedAt != nil else { return }
        goal.sortIndex = (try remaining(in: context).last?.sortIndex ?? -1) + 1
        goal.completedAt = nil
        goal.startedAt = nil
        try save(context)
    }

    /// Saves a new order for the unfinished goals.
    public static func reorder(_ goals: [Goal], in context: ModelContext) throws {
        for (index, goal) in goals.enumerated() {
            goal.sortIndex = index
        }
        try save(context)
    }

    public static func delete(_ goal: Goal, in context: ModelContext) throws {
        context.delete(goal)
        try save(context)
    }

    private static func goal(_ id: UUID, in context: ModelContext) throws -> Goal? {
        var descriptor = FetchDescriptor<Goal>(predicate: #Predicate { $0.id == id })
        descriptor.fetchLimit = 1
        return try context.fetch(descriptor).first
    }

    private static func save(_ context: ModelContext) throws {
        do {
            try context.save()
        } catch {
            context.rollback()
            throw error
        }
        WidgetCenter.shared.reloadAllTimelines()
        Reminders.sync(in: context)
        LiveActivities.sync(in: context)
        AppLock.sync(in: context)
    }
}
