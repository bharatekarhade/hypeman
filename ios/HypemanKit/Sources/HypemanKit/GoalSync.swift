import Foundation
import SwiftData

/// How goals from the server and goals on this device come together.
enum GoalMerge {
    /// Applies goals pulled from the server. A goal changed here after the server's copy keeps the change made here,
    /// which goes up on the next push. A deletion wins either way, as it does on the server.
    static func apply(_ remoteGoals: [RemoteGoal], in context: ModelContext) throws {
        guard !remoteGoals.isEmpty else { return }
        let goals = Dictionary(try context.fetch(FetchDescriptor<Goal>()).map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let deletions = Dictionary(try context.fetch(FetchDescriptor<GoalDeletion>()).map { ($0.goalID, $0) },
                                   uniquingKeysWith: { first, _ in first })
        for remote in remoteGoals {
            if remote.deleted {
                if let goal = goals[remote.id] {
                    context.delete(goal)
                }
                if let deletion = deletions[remote.id] {
                    context.delete(deletion)
                }
                continue
            }
            guard deletions[remote.id] == nil, let title = remote.title, let sortIndex = remote.sortIndex else { continue }
            if let goal = goals[remote.id] {
                guard !goal.needsUpload || goal.changedAt <= remote.changedAt else { continue }
                goal.update(from: remote, title: title, sortIndex: sortIndex)
            } else {
                let goal = Goal(title: title, sortIndex: sortIndex)
                goal.id = remote.id
                goal.update(from: remote, title: title, sortIndex: sortIndex)
                context.insert(goal)
            }
        }
        try context.save()
        GoalStore.didChange(in: context)
    }
}

extension Goal {
    /// The goal as the server should have it.
    var remote: RemoteGoal {
        RemoteGoal(id: id, title: title, sortIndex: sortIndex, reminderAt: reminderAt, startedAt: startedAt, completedAt: completedAt,
                   motivatorId: motivatorID, changedAt: changedAt, deleted: false)
    }

    fileprivate func update(from remote: RemoteGoal, title: String, sortIndex: Int) {
        self.title = title
        self.sortIndex = sortIndex
        reminderAt = remote.reminderAt
        startedAt = remote.startedAt
        completedAt = remote.completedAt
        motivatorID = remote.motivatorId
        changedAt = remote.changedAt
        needsUpload = false
    }
}

extension GoalDeletion {
    var remote: RemoteGoal {
        RemoteGoal(id: goalID, title: nil, sortIndex: nil, reminderAt: nil, startedAt: nil, completedAt: nil, motivatorId: nil,
                   changedAt: deletedAt, deleted: true)
    }
}
