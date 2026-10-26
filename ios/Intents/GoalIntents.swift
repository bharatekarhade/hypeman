import AppIntents
import Foundation
import HypemanKit

/// DO IT on the widget. Audio intents run in the app's process, which can play the yell without opening the app.
struct StartGoalIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "DO IT"
    static let isDiscoverable = false

    @Parameter(title: "Goal")
    var goalID: String

    init() {}

    init(goalID: UUID) {
        self.goalID = goalID.uuidString
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        let context = GoalStore.container.mainContext
        guard let id = UUID(uuidString: goalID), let goal = try GoalStore.remaining(in: context).first(where: { $0.id == id }) else {
            return .result()
        }
        try GoalStore.start(id, in: context)
        YellPlayer.shared.play(.start, motivatorID: goal.motivatorID)
        // Starting cancels the nags and starts the Live Activity; let that land before the app can be suspended.
        await GoalStore.synced()
        return .result()
    }
}

/// Done on the widget and on the started goal's Live Activity, which completing ends. Live Activity intents run in
/// the app's process, so an open goal list updates at once.
struct CompleteGoalIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Done"
    static let isDiscoverable = false

    @Parameter(title: "Goal")
    var goalID: String

    init() {}

    init(goalID: UUID) {
        self.goalID = goalID.uuidString
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        if let id = UUID(uuidString: goalID) {
            try GoalStore.complete(id, in: GoalStore.container.mainContext)
        }
        await GoalStore.synced()
        return .result()
    }
}
