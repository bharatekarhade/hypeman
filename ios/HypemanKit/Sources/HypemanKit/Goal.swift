import Foundation
import SwiftData

/// Every property has a default and nothing is unique, so the model can sync through CloudKit later (AC-04).
@Model
public final class Goal {
    public static let maxTitleLength = 80

    public var id: UUID = UUID()
    public var title: String = ""
    public var sortIndex: Int = 0
    public var reminderAt: Date?
    public var startedAt: Date?
    public var completedAt: Date?
    public var createdAt: Date = Date.now
    /// The motivator that yells for this goal (GL-08, Pro). Nil uses the default motivator. Stored as text, e.g.
    /// "pack:<uuid>", "yell:<uuid>", "mine:<uuid>" or "default".
    public var motivatorID: String?

    public init(title: String, sortIndex: Int) {
        self.title = title
        self.sortIndex = sortIndex
    }
}
