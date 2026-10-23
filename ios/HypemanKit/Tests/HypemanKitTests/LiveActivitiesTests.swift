import Foundation
import Testing
@testable import HypemanKit

/// Which Live Activities the goals call for. Deciding needs no ActivityKit, so this runs without an app.
struct LiveActivitiesTests {
    let startedAt = Date(timeIntervalSinceReferenceDate: 800_000_000)

    @Test func onlyStartedUnfinishedGoalsGetAnActivity() {
        let started = Goal(title: "Leg day", sortIndex: 0)
        started.startedAt = startedAt
        let waiting = Goal(title: "Call mom", sortIndex: 1)
        let finished = Goal(title: "Taxes", sortIndex: 2)
        finished.startedAt = startedAt
        finished.completedAt = startedAt.addingTimeInterval(60)

        let wanted = LiveActivities.wanted(from: [started, waiting, finished])

        #expect(wanted == [started.id: .init(title: "Leg day", startedAt: startedAt)])
    }

    @Test func aNewlyStartedGoalStartsOne() {
        let id = UUID()

        let plan = LiveActivities.plan(wanted: [id: state("Leg day")], running: [:], shown: [])

        #expect(plan == .init(start: [id: state("Leg day")]))
    }

    @Test func aGoalThatIsNoLongerStartedEndsItsActivity() {
        let id = UUID()

        let plan = LiveActivities.plan(wanted: [:], running: [id: state("Leg day")], shown: [session(id)])

        #expect(plan == .init(end: [id]))
    }

    @Test func aRenamedGoalUpdatesItsActivity() {
        let id = UUID()

        let plan = LiveActivities.plan(wanted: [id: state("Leg day!")], running: [id: state("Leg day")], shown: [session(id)])

        #expect(plan == .init(update: [id: state("Leg day!")]))
    }

    @Test func anUnchangedGoalIsLeftAlone() {
        let id = UUID()

        #expect(LiveActivities.plan(wanted: [id: state("Leg day")], running: [id: state("Leg day")], shown: [session(id)]) == .init())
    }

    @Test func aDismissedActivityIsNotRecreated() {
        let id = UUID()

        #expect(LiveActivities.plan(wanted: [id: state("Leg day")], running: [:], shown: [session(id)]) == .init())
    }

    @Test func startingTheGoalAgainBringsItBack() {
        let id = UUID()
        let restarted = GoalActivityAttributes.ContentState(title: "Leg day", startedAt: startedAt.addingTimeInterval(3600))

        let plan = LiveActivities.plan(wanted: [id: restarted], running: [:], shown: [session(id)])

        #expect(plan == .init(start: [id: restarted]))
    }

    private func session(_ id: UUID) -> LiveActivities.Session {
        .init(goalID: id, startedAt: startedAt)
    }

    private func state(_ title: String) -> GoalActivityAttributes.ContentState {
        .init(title: title, startedAt: startedAt)
    }
}
