import Foundation
import Testing
import UserNotifications
@testable import HypemanKit

/// Which notifications the goals call for. Building requests needs no notification center, so this runs without an app.
struct RemindersTests {
    let now = Date(timeIntervalSinceReferenceDate: 800_000_000)

    @Test func aReminderComesWithTwoNags() throws {
        let goal = goal("Leg day", remindIn: 60)

        let requests = Reminders.requests(for: [goal], pack: nil, soundName: { _ in nil }, now: now)

        #expect(requests.map(\.identifier) == ["reminder", "nag-1", "nag-2"].map { "\(goal.id.uuidString)-\($0)" })
        #expect(try requests.map { try fireDate($0) } == [60, 660, 1860].map { now.addingTimeInterval($0) })
        #expect(requests.allSatisfy { $0.content.title == "Leg day" && $0.content.threadIdentifier == goal.id.uuidString })
        #expect(requests.map(\.content.body) == ["Time to do it.", "Still not started. Get up.", "Still not started. Get up."])
        #expect(requests.allSatisfy { $0.content.sound == .default })
        #expect(requests.allSatisfy { $0.content.categoryIdentifier == Reminders.category })
        #expect(requests.allSatisfy { Reminders.goalID(of: $0.content.userInfo) == goal.id })
    }

    @Test func aPackSuppliesTheLinesAndSounds() {
        let pack = InstalledPack(id: UUID(), title: "Coach", clips: [
            .init(role: .reminder, caption: "Move it.", fileName: "reminder.mp4", soundName: "hypeman-x-reminder.caf"),
            .init(role: .nag, caption: "I'm waiting.", fileName: "nag.mp4", soundName: "hypeman-x-nag.caf"),
        ])

        let requests = Reminders.requests(for: [goal("Leg day", remindIn: 60)], pack: pack,
                                          soundName: { role in pack.clips.first { $0.role == role }?.soundName }, now: now)

        #expect(requests.map(\.content.body) == ["Move it.", "I'm waiting.", "I'm waiting."])
        #expect(requests.first?.content.sound == UNNotificationSound(named: UNNotificationSoundName("hypeman-x-reminder.caf")))
        #expect(requests.last?.content.sound == UNNotificationSound(named: UNNotificationSoundName("hypeman-x-nag.caf")))
    }

    @Test func onlyWhatIsStillAheadIsScheduled() {
        let goal = goal("Leg day", remindIn: -15 * 60)

        let requests = Reminders.requests(for: [goal], pack: nil, soundName: { _ in nil }, now: now)

        #expect(requests.map(\.identifier) == ["\(goal.id.uuidString)-nag-2"])
    }

    @Test func startedFinishedAndUnremindedGoalsGetNothing() {
        let started = goal("Started", remindIn: 60)
        started.startedAt = now
        let finished = goal("Finished", remindIn: 60)
        finished.completedAt = now
        let noReminder = Goal(title: "Whenever", sortIndex: 3)

        #expect(Reminders.requests(for: [started, finished, noReminder], pack: nil, soundName: { _ in nil }, now: now).isEmpty)
    }

    private func goal(_ title: String, remindIn seconds: TimeInterval) -> Goal {
        let goal = Goal(title: title, sortIndex: 0)
        goal.reminderAt = now.addingTimeInterval(seconds)
        return goal
    }

    private func fireDate(_ request: UNNotificationRequest) throws -> Date {
        let trigger = try #require(request.trigger as? UNCalendarNotificationTrigger)
        return try #require(Calendar.current.date(from: trigger.dateComponents))
    }
}
