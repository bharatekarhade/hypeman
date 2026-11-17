import Foundation
import SwiftData
import Testing
@testable import HypemanKit

/// Syncing goals against the stub server, which keeps whatever it's pushed and hands it all back on every pull.
@MainActor
struct GoalSyncTests {
    let server = FakeAuthServer()
    let container: ModelContainer
    let defaults: UserDefaults
    let account: Account
    let sync: GoalSync
    var context: ModelContext { container.mainContext }

    init() throws {
        container = try ModelContainer(for: GoalStore.schema, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        defaults = try #require(UserDefaults(suiteName: "GoalSyncTests-\(UUID().uuidString)"))
        let api = HypemanAPI(baseURL: server.baseURL, session: server.session)
        // The stub's tokens expire 15 minutes after 1_800_000_000.
        account = Account(api: api, store: MemorySessionStore(), now: { Date(timeIntervalSince1970: 1_800_000_000) })
        sync = GoalSync(account: account, api: api, container: container, defaults: defaults)
    }

    @Test func signedOutNothingIsSent() async throws {
        try GoalStore.add(title: "Leg day", in: context)

        await sync.sync()

        #expect(sync.status == .idle)
        #expect(server.requests.isEmpty)
    }

    @Test func theFirstSyncSendsTheGoalsHereAndBringsTheAccountsDown() async throws {
        try await signIn()
        try GoalStore.add(title: "Leg day", in: context)
        let fromPhone = try #require(try GoalStore.remaining(in: context).first)
        let fromIPad = UUID()
        server.addGoal(remote(fromIPad, "Call mom"))

        await sync.sync()

        #expect(server.goals[fromPhone.id]?.title == "Leg day")
        #expect(Set(try GoalStore.remaining(in: context).map(\.title)) == ["Leg day", "Call mom"])
        #expect(!fromPhone.needsUpload)
        guard case .synced = sync.status else {
            Issue.record("expected synced, got \(sync.status)")
            return
        }
    }

    @Test func aDeletionGoesUpOnceAndIsForgotten() async throws {
        try await signIn()
        try GoalStore.add(title: "Leg day", in: context)
        let goal = try #require(try GoalStore.remaining(in: context).first)
        let id = goal.id
        await sync.sync()

        try GoalStore.delete(goal, in: context)
        await sync.sync()

        #expect(server.goals[id]?.deleted == true)
        #expect(try context.fetch(FetchDescriptor<GoalDeletion>()).isEmpty)
    }

    @Test func theSecondPullAsksOnlyForWhatChangedSinceTheFirst() async throws {
        try await signIn()
        await sync.sync()

        await sync.sync()

        let pulls = server.requests.filter { $0.path == "v1/goals" && $0.method == "GET" }
        #expect(pulls.count == 2)
        #expect(pulls[0].query == nil)
        #expect(pulls[1].query?.hasPrefix("since=2027-01-15T08:00:00") == true)
    }

    @Test func aGoalAddedWhileAPushIsUnderWayStillGoesUp() async throws {
        try await signIn()
        try GoalStore.add(title: "Leg day", in: context)
        server.hold("v1/goals")
        let first = Task { await sync.sync() }
        await server.arrived("v1/goals")

        try GoalStore.add(title: "Call mom", in: context)
        let second = Task { await sync.sync() }
        server.release("v1/goals")
        await first.value
        await second.value

        #expect(Set(server.goals.values.compactMap(\.title)) == ["Leg day", "Call mom"])
        #expect(try GoalStore.remaining(in: context).allSatisfy { !$0.needsUpload })
    }

    @Test func aFailedSyncKeepsTheChangesForNextTime() async throws {
        try await signIn()
        try GoalStore.add(title: "Leg day", in: context)
        server.failEverything()

        await sync.sync()

        #expect(try GoalStore.remaining(in: context).first?.needsUpload == true)
        guard case .failed = sync.status else {
            Issue.record("expected failed, got \(sync.status)")
            return
        }
    }

    @Test func signingInAsSomeoneElseSendsEveryGoalAgain() async throws {
        try await signIn()
        try GoalStore.add(title: "Leg day", in: context)
        let goal = try #require(try GoalStore.remaining(in: context).first)
        goal.needsUpload = false
        try context.save()
        defaults.set(UUID().uuidString, forKey: "GoalSync.userID")

        await sync.sync()

        #expect(server.goals[goal.id]?.title == "Leg day")
    }

    private func signIn() async throws {
        try await account.signInWithApple(identityToken: "apple-token", nonce: "raw-nonce", appleUserID: "apple-user")
    }

    private func remote(_ id: UUID, _ title: String) -> RemoteGoal {
        RemoteGoal(id: id, title: title, sortIndex: 0, reminderAt: nil, startedAt: nil, completedAt: nil, motivatorId: nil,
                   changedAt: Date(timeIntervalSince1970: 1_799_999_000), deleted: false)
    }
}
