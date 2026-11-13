import Foundation
import SwiftData

/// A goal deleted on this device that the server hasn't heard about yet. The goal itself is gone, so every query for
/// goals stays as it was; this is all that's left until the next sync.
@Model
public final class GoalDeletion {
    public var goalID: UUID = UUID()
    public var deletedAt: Date = Date.now

    public init(goalID: UUID, deletedAt: Date = .now) {
        self.goalID = goalID
        self.deletedAt = deletedAt
    }
}
