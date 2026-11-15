import Foundation
import SwiftData
import Testing
@testable import HypemanKit

/// Goals pulled from the server meeting the goals on this device.
@MainActor
struct GoalMergeTests {
    // The context doesn't retain its container, so keep it alive for the whole test.
    let container: ModelContainer
    var context: ModelContext { container.mainContext }

    init() throws {
        container = try ModelContainer(for: GoalStore.schema, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
    }

    @Test func aGoalFromAnotherDeviceIsAdded() throws {
        let id = UUID()
        let reminder = Date(timeIntervalSince1970: 1_800_000_000)

        try GoalMerge.apply([remote(id, "Leg day", changedAt: .now, reminderAt: reminder)], in: context)

        let goal = try #require(try goals().first)
        #expect(goal.id == id)
        #expect(goal.title == "Leg day")
        #expect(goal.reminderAt == reminder)
        #expect(!goal.needsUpload, "it came from the server")
    }

    @Test func aChangeFromAnotherDeviceReplacesAGoalNotChangedHere() throws {
        let goal = try synced("Leg day", changedAt: .now.addingTimeInterval(-60))

        try GoalMerge.apply([remote(goal.id, "Leg day, heavy", changedAt: .now)], in: context)

        #expect(goal.title == "Leg day, heavy")
    }

    @Test func aNewerChangeMadeHereIsKept() throws {
        let goal = try synced("Leg day", changedAt: .now.addingTimeInterval(-60))
        try GoalStore.rename(goal.id, title: "Leg day, heavy", in: context)

        try GoalMerge.apply([remote(goal.id, "Leg day, light", changedAt: .now.addingTimeInterval(-30))], in: context)

        #expect(goal.title == "Leg day, heavy")
        #expect(goal.needsUpload, "it still has to go up")
    }

    @Test func anOlderChangeMadeHereLosesToANewerOne() throws {
        let goal = try synced("Leg day", changedAt: .now.addingTimeInterval(-60))
        try GoalStore.rename(goal.id, title: "Leg day, heavy", in: context)

        try GoalMerge.apply([remote(goal.id, "Leg day, light", changedAt: .now.addingTimeInterval(60))], in: context)

        #expect(goal.title == "Leg day, light")
        #expect(!goal.needsUpload)
    }

    @Test func aDeletionFromAnotherDeviceRemovesTheGoal() throws {
        let goal = try synced("Leg day", changedAt: .now.addingTimeInterval(-60))

        try GoalMerge.apply([deletion(goal.id)], in: context)

        #expect(try goals().isEmpty)
        #expect(try context.fetch(FetchDescriptor<GoalDeletion>()).isEmpty, "the server already knows")
    }

    @Test func aGoalDeletedHereIsNotBroughtBack() throws {
        let goal = try synced("Leg day", changedAt: .now.addingTimeInterval(-60))
        let id = goal.id
        try GoalStore.delete(goal, in: context)

        try GoalMerge.apply([remote(id, "Leg day, heavy", changedAt: .now.addingTimeInterval(60))], in: context)

        #expect(try goals().isEmpty)
    }

    /// A goal as it is after a sync: on the server and unchanged here since.
    private func synced(_ title: String, changedAt: Date) throws -> Goal {
        let goal = Goal(title: title, sortIndex: 0)
        goal.changedAt = changedAt
        goal.needsUpload = false
        context.insert(goal)
        try context.save()
        return goal
    }

    private func goals() throws -> [Goal] {
        try context.fetch(FetchDescriptor<Goal>())
    }

    private func remote(_ id: UUID, _ title: String, changedAt: Date, reminderAt: Date? = nil) -> RemoteGoal {
        RemoteGoal(id: id, title: title, sortIndex: 0, reminderAt: reminderAt, startedAt: nil, completedAt: nil, motivatorId: nil,
                   changedAt: changedAt, deleted: false)
    }

    private func deletion(_ id: UUID) -> RemoteGoal {
        RemoteGoal(id: id, title: nil, sortIndex: nil, reminderAt: nil, startedAt: nil, completedAt: nil, motivatorId: nil,
                   changedAt: .now, deleted: true)
    }
}
