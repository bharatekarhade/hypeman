import Foundation
import Testing
import UserNotifications
@testable import HypemanKit

/// Which notifications the goals call for. Building requests needs no notification center, so this runs without an app.
struct RemindersTests {
    let now = Date(timeIntervalSinceReferenceDate: 800_000_000)

    @Test func aReminderComesWithTwoNags() throws {
        let goal = goal("Leg day", remindIn: 60)

        let requests = Reminders.requests(for: [goal], clip: builtIn, now: now)

        #expect(requests.map(\.identifier) == ["reminder", "nag-1", "nag-2"].map { "\(goal.id.uuidString)-\($0)" })
        #expect(try requests.map { try fireDate($0) } == [60, 660, 1860].map { now.addingTimeInterval($0) })
        #expect(requests.allSatisfy { $0.content.title == "Leg day" && $0.content.threadIdentifier == goal.id.uuidString })
        #expect(requests.map(\.content.body) == ["Time to do it.", "Still not started. Get up.", "Still not started. Get up."])
        #expect(requests.allSatisfy { $0.content.sound == .default })
        #expect(requests.allSatisfy { $0.content.categoryIdentifier == Reminders.category })
        #expect(requests.allSatisfy { Reminders.goalID(of: $0.content.userInfo) == goal.id })
    }

    @Test func eachGoalsVoiceSuppliesItsLinesAndSounds() {
        let coached = goal("Leg day", remindIn: 60)
        coached.motivatorID = "pack:coach"
        let plain = goal("Read", remindIn: 120)

        let requests = Reminders.requests(for: [coached, plain], clip: { goal, role in
            guard goal.motivatorID == "pack:coach" else { return builtIn(goal, role) }
            return ResolvedClip(motivatorID: "pack:coach", motivatorTitle: "Coach", role: role, caption: role == .reminder ? "Move it." : "I'm waiting.",
                                url: nil, isVideo: true, soundName: "hypeman-x-\(role.rawValue.lowercased()).caf")
        }, now: now)

        #expect(requests.prefix(3).map(\.content.body) == ["Move it.", "I'm waiting.", "I'm waiting."])
        #expect(requests.first?.content.sound == UNNotificationSound(named: UNNotificationSoundName("hypeman-x-reminder.caf")))
        #expect(requests[2].content.sound == UNNotificationSound(named: UNNotificationSoundName("hypeman-x-nag.caf")))
        #expect(requests[3].content.body == "Time to do it.")
        #expect(requests[3].content.sound == .default)
    }

    @Test func onlyWhatIsStillAheadIsScheduled() {
        let goal = goal("Leg day", remindIn: -15 * 60)

        let requests = Reminders.requests(for: [goal], clip: builtIn, now: now)

        #expect(requests.map(\.identifier) == ["\(goal.id.uuidString)-nag-2"])
    }

    @Test func startedFinishedAndUnremindedGoalsGetNothing() {
        let started = goal("Started", remindIn: 60)
        started.startedAt = now
        let finished = goal("Finished", remindIn: 60)
        finished.completedAt = now
        let noReminder = Goal(title: "Whenever", sortIndex: 3)

        #expect(Reminders.requests(for: [started, finished, noReminder], clip: builtIn, now: now).isEmpty)
    }

    /// The built-in voice, from a library with nothing in it.
    private func builtIn(_ goal: Goal, _ role: ClipRole) -> ResolvedClip {
        MotivatorLibrary(root: FileManager.default.temporaryDirectory.appending(path: "empty-\(UUID().uuidString)"),
                         defaultsSuite: "RemindersTests-\(UUID().uuidString)").resolve(role, goalMotivatorID: nil, pro: false)
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
