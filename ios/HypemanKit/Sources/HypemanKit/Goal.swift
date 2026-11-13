import Foundation
import SwiftData

/// Every property has a default and nothing is unique, so new properties migrate on their own. Goals sync to the
/// signed-in account through GoalSync.
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
    /// The motivator that yells for this goal (GL-08). Nil uses the default motivator. Stored as text, e.g.
    /// "pack:<uuid>", "mine:<uuid>" or "default".
    public var motivatorID: String?
    /// When the goal last changed on any device. The newest change wins when devices disagree.
    public var changedAt: Date = Date.now
    /// Changed here since the server last heard about it. Goals saved before sync existed start out true, so they upload.
    public var needsUpload: Bool = true

    public init(title: String, sortIndex: Int) {
        self.title = title
        self.sortIndex = sortIndex
    }
}
