import Foundation
import Testing
@testable import HypemanKit

/// Which goals can lock the apps, and when. Deciding needs no Screen Time, so this runs without an app.
struct AppLockTests {
    let now = Date(timeIntervalSinceReferenceDate: 800_000_000)

    @Test func onlyWaitingGoalsWithAReminderCanLock() {
        let later = goal("Leg day", remindIn: 3600)
        let sooner = goal("Call mom", remindIn: 60)
        let started = goal("Taxes", remindIn: -60)
        started.startedAt = now
        let finished = goal("Dishes", remindIn: -60)
        finished.completedAt = now
        let noReminder = Goal(title: "Read", sortIndex: 0)

        let entries = AppLock.entries(for: [later, sooner, started, finished, noReminder])

        #expect(entries.map(\.id) == [sooner.id, later.id])
        #expect(entries.first == .init(id: sooner.id, title: "Call mom", reminderAt: now.addingTimeInterval(60)))
    }

    @Test func theShieldIsForTheLongestOverdueGoal() {
        let entries = AppLock.entries(for: [goal("Leg day", remindIn: -60), goal("Call mom", remindIn: -600), goal("Taxes", remindIn: 60)])

        #expect(AppLock.overdue(in: entries, now: now)?.title == "Call mom")
    }

    @Test func nothingIsOverdueBeforeTheFirstReminder() {
        #expect(AppLock.overdue(in: AppLock.entries(for: [goal("Leg day", remindIn: 60)]), now: now) == nil)
    }

    @Test func theIntervalStartsAtTheReminderAndLastsTheMinimum() throws {
        let entry = try #require(AppLock.entries(for: [goal("Leg day", remindIn: 60)]).first)

        let schedule = AppLock.schedule(for: entry)

        #expect(schedule.intervalStart.date == entry.reminderAt)
        #expect(schedule.intervalEnd.date == entry.reminderAt.addingTimeInterval(15 * 60))
        #expect(!schedule.repeats)
    }

    private func goal(_ title: String, remindIn seconds: TimeInterval) -> Goal {
        let goal = Goal(title: title, sortIndex: 0)
        goal.reminderAt = now.addingTimeInterval(seconds)
        return goal
    }
}

private extension DateComponents {
    var date: Date? {
        Calendar.current.date(from: self)
    }
}
