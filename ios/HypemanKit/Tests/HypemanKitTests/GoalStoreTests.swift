import Foundation
import SwiftData
import Testing
@testable import HypemanKit

@MainActor
struct GoalStoreTests {
    // The context doesn't retain its container, so keep it alive for the whole test.
    let container: ModelContainer
    var context: ModelContext { container.mainContext }

    init() throws {
        container = try ModelContainer(for: Goal.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
    }

    @Test func addTrimsAndCapsTitle() throws {
        try GoalStore.add(title: "  Leg day  ", in: context)
        try GoalStore.add(title: String(repeating: "a", count: 100), in: context)
        try GoalStore.add(title: "   ", in: context)

        let titles = try GoalStore.remaining(in: context).map(\.title)
        #expect(titles == ["Leg day", String(repeating: "a", count: Goal.maxTitleLength)])
    }

    @Test func completingTheNextGoalMovesTheNextOneUp() throws {
        try GoalStore.add(title: "Leg day", in: context)
        try GoalStore.add(title: "Finish chapter 4", in: context)
        let next = try #require(try GoalStore.remaining(in: context).first)

        try GoalStore.complete(next.id, in: context)

        #expect(try GoalStore.remaining(in: context).map(\.title) == ["Finish chapter 4"])
    }

    @Test func startKeepsTheFirstStartTime() throws {
        try GoalStore.add(title: "Leg day", in: context)
        let goal = try #require(try GoalStore.remaining(in: context).first)

        try GoalStore.start(goal.id, in: context)
        let firstStart = try #require(goal.startedAt)
        try GoalStore.start(goal.id, in: context)

        #expect(goal.startedAt == firstStart)
    }

    @Test func reorderChangesTheNextGoal() throws {
        try GoalStore.add(title: "Leg day", in: context)
        try GoalStore.add(title: "Call mom", in: context)

        try GoalStore.reorder(try GoalStore.remaining(in: context).reversed(), in: context)

        #expect(try GoalStore.remaining(in: context).first?.title == "Call mom")
    }

    @Test func newGoalsGoToTheEndAfterAReorder() throws {
        try GoalStore.add(title: "Leg day", in: context)
        try GoalStore.add(title: "Call mom", in: context)
        try GoalStore.reorder(try GoalStore.remaining(in: context).reversed(), in: context)

        try GoalStore.add(title: "Reply to recruiter", in: context)

        #expect(try GoalStore.remaining(in: context).map(\.title) == ["Call mom", "Leg day", "Reply to recruiter"])
    }

    @Test func staleStartDoesNotRestartCompletedGoal() throws {
        try GoalStore.add(title: "Leg day", in: context)
        let goal = try #require(try GoalStore.remaining(in: context).first)
        try GoalStore.complete(goal.id, in: context)
        try GoalStore.start(goal.id, in: context)
        #expect(goal.startedAt == nil)
        #expect(goal.completedAt != nil)
    }

    @Test func undoPreservesCurrentQueueAndResetsTimer() throws {
        try GoalStore.add(title: "Leg day", in: context)
        let goal = try #require(try GoalStore.remaining(in: context).first)
        try GoalStore.start(goal.id, in: context)
        try GoalStore.complete(goal.id, in: context)
        try GoalStore.add(title: "Call mom", in: context)
        try GoalStore.undoCompletion(goal.id, in: context)
        try GoalStore.undoCompletion(goal.id, in: context)
        #expect(try GoalStore.remaining(in: context).map(\.title) == ["Call mom", "Leg day"])
        #expect(goal.startedAt == nil)
        #expect(goal.completedAt == nil)
    }

    @Test func renameRejectsBlankAndPreservesGoalIdentity() throws {
        try GoalStore.add(title: "Leg day", in: context)
        let goal = try #require(try GoalStore.remaining(in: context).first)
        let id = goal.id
        try GoalStore.rename(id, title: " \n ", in: context)
        #expect(goal.title == "Leg day")
        try GoalStore.rename(id, title: "  Call mom  ", in: context)
        #expect(goal.title == "Call mom")
        #expect(goal.id == id)
    }
}
