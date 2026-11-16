import Foundation
import Observation
import OSLog
import SwiftData

/// Keeps the goals on this device and the signed-in account's goals the same: pushes what changed here, then pulls
/// what changed on the user's other devices. Signed out, goals stay on this device; when someone signs in, the goals
/// here join their account.
@MainActor
@Observable
public final class GoalSync {
    public static let shared = GoalSync(account: .shared, api: .shared, container: GoalStore.container)

    public enum Status: Equatable, Sendable {
        /// Signed out, or not synced since launch.
        case idle
        case syncing
        case synced(Date)
        /// The message says why, for showing as is.
        case failed(String)
    }

    public private(set) var status: Status = .idle

    private let account: Account
    private let api: HypemanAPI
    private let container: ModelContainer
    private let defaults: UserDefaults
    @ObservationIgnored private var running: Task<Void, Never>?
    @ObservationIgnored private var runAgain = false
    @ObservationIgnored private var scheduled: Task<Void, Never>?
    @ObservationIgnored private var editObserver: (any NSObjectProtocol)?
    private let log = Logger(subsystem: "com.hypeman.app", category: "GoalSync")

    public init(account: Account, api: HypemanAPI, container: ModelContainer, defaults: UserDefaults = .standard) {
        self.account = account
        self.api = api
        self.container = container
        self.defaults = defaults
    }

    /// Syncs now. Called while a sync is under way, it waits for that one and then syncs again, so an edit made during
    /// it isn't left behind.
    public func sync() async {
        if let running {
            runAgain = true
            await running.value
            return
        }
        let task = Task {
            repeat {
                runAgain = false
                await syncOnce()
            } while runAgain
            running = nil
        }
        running = task
        await task.value
    }

    /// Syncs a couple of seconds after the last call, so a burst of edits (a reorder, say) goes up in one push.
    public func scheduleSync() {
        scheduled?.cancel()
        scheduled = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            await self?.sync()
        }
    }

    /// Schedules a sync after every edit made in this process. Call once, when the app launches.
    public func syncAfterEdits() {
        guard editObserver == nil else { return }
        editObserver = NotificationCenter.default.addObserver(forName: GoalStore.edited, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.scheduleSync()
            }
        }
    }

    private func syncOnce() async {
        guard let userID = account.session?.user.id else {
            status = .idle
            return
        }
        status = .syncing
        let context = container.mainContext
        do {
            try join(userID, in: context)
            try await push(for: userID, in: context)
            try await pull(for: userID, in: context)
            status = .synced(.now)
        } catch let error as APIError where error == .signedOut {
            status = .idle
        } catch {
            log.error("Goal sync failed: \(error)")
            status = .failed(error.localizedDescription)
        }
    }

    /// The first sync for an account: every goal here goes up to it, and the cursor starts over, so all its goals come
    /// down.
    private func join(_ userID: UUID, in context: ModelContext) throws {
        guard defaults.string(forKey: Keys.userID) != userID.uuidString else { return }
        for goal in try context.fetch(FetchDescriptor<Goal>()) {
            goal.needsUpload = true
        }
        try context.save()
        defaults.set(userID.uuidString, forKey: Keys.userID)
        defaults.removeObject(forKey: Keys.cursor)
    }

    private func push(for userID: UUID, in context: ModelContext) async throws {
        let changes = try context.fetch(Self.needingUpload).map(\.remote) + context.fetch(FetchDescriptor<GoalDeletion>()).map(\.remote)
        for start in stride(from: 0, to: changes.count, by: HypemanAPI.maxGoalsPerPush) {
            let batch = Array(changes[start..<min(start + HypemanAPI.maxGoalsPerPush, changes.count)])
            try await account.authorized { [api] token in try await api.pushGoals(batch, accessToken: token) }
        }
        try checkStillSignedIn(as: userID)
        // Fetched again: a goal edited or deleted while the push was under way has changed since, and still has to go up.
        let sent = Dictionary(changes.map { ($0.id, $0.changedAt) }, uniquingKeysWith: max)
        for goal in try context.fetch(Self.needingUpload) where sent[goal.id] == goal.changedAt {
            goal.needsUpload = false
        }
        for deletion in try context.fetch(FetchDescriptor<GoalDeletion>()) where sent[deletion.goalID] == deletion.deletedAt {
            context.delete(deletion)
        }
        try context.save()
    }

    private func pull(for userID: UUID, in context: ModelContext) async throws {
        let since = defaults.object(forKey: Keys.cursor) as? Date
        let page = try await account.authorized { [api] token in try await api.goals(since: since, accessToken: token) }
        try checkStillSignedIn(as: userID)
        try GoalMerge.apply(page.goals, in: context)
        defaults.set(page.cursor, forKey: Keys.cursor)
    }

    /// Someone who signed out, or in as someone else, while a call was under way doesn't get this account's goals.
    private func checkStillSignedIn(as userID: UUID) throws {
        guard account.session?.user.id == userID else { throw APIError.signedOut }
    }

    private static let needingUpload = FetchDescriptor<Goal>(predicate: #Predicate { $0.needsUpload == true })

    private enum Keys {
        static let userID = "GoalSync.userID"
        static let cursor = "GoalSync.cursor"
    }
}

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
