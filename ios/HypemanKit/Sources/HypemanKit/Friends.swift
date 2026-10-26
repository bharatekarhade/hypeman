import Foundation
import Observation
import OSLog

/// Friends and their yells (FD-01 to FD-05), for the signed-in user. Everything here needs an account; the server is the
/// source of truth, and this keeps the latest copy for the screens.
@MainActor
@Observable
public final class Friends {
    public static let shared = Friends(account: .shared, api: .shared, library: .shared)

    public private(set) var me: Me?
    public private(set) var friends: [User] = []
    public private(set) var inbox: [InboxItem] = []
    public private(set) var hasMoreInbox = false

    private let account: Account
    private let api: HypemanAPI
    private let library: MotivatorLibrary
    @ObservationIgnored private var inboxCursor: String?
    private let log = Logger(subsystem: "com.hypeman.app", category: "Friends")

    public init(account: Account, api: HypemanAPI, library: MotivatorLibrary) {
        self.account = account
        self.api = api
        self.library = library
    }

    /// Free yells left this month; nil for Pro, or before the first refresh.
    public var sendsLeft: Int? {
        me?.sendsLeftThisMonth
    }

    /// The user's own profile, friends and the first page of their inbox.
    public func refresh() async throws {
        guard account.isSignedIn else {
            clear()
            return
        }
        async let me = account.me()
        async let friends = account.authorized { [api] token in try await api.friends(accessToken: token) }
        async let page = account.authorized { [api] token in try await api.inbox(accessToken: token) }
        let (loadedMe, loadedFriends, firstPage) = try await (me, friends, page)
        self.me = loadedMe
        self.friends = loadedFriends
        inbox = firstPage.deliveries
        inboxCursor = firstPage.nextCursor
        hasMoreInbox = firstPage.nextCursor != nil
    }

    public func loadMoreInbox() async throws {
        guard let cursor = inboxCursor else { return }
        let page = try await account.authorized { [api] token in try await api.inbox(cursor: cursor, accessToken: token) }
        let known = Set(inbox.map(\.id))
        inbox += page.deliveries.filter { !known.contains($0.id) }
        inboxCursor = page.nextCursor
        hasMoreInbox = page.nextCursor != nil
    }

    public func setDisplayName(_ name: String) async throws {
        _ = try await account.authorized { [api] token in try await api.updateMe(displayName: name, accessToken: token) }
        me = try await account.me()
    }

    public func createInvite() async throws -> Invite {
        try await account.authorized { [api] token in try await api.createInvite(accessToken: token) }
    }

    /// Takes a bare code or a whole invite link.
    public func accept(_ codeOrLink: String) async throws -> User {
        let code = Self.inviteCode(in: codeOrLink)
        let friend = try await account.authorized { [api] token in try await api.acceptInvite(code: code, accessToken: token) }
        if !friends.contains(friend) {
            friends.append(friend)
        }
        return friend
    }

    public func remove(_ friend: User) async throws {
        try await account.authorized { [api] token in try await api.removeFriend(friend.id, accessToken: token) }
        friends.removeAll { $0.id == friend.id }
    }

    /// Their yells leave the inbox too.
    public func block(_ userID: UUID) async throws {
        try await account.authorized { [api] token in try await api.block(userID, accessToken: token) }
        friends.removeAll { $0.id == userID }
        inbox.removeAll { $0.sender?.id == userID }
    }

    /// Uploads the recording, then sends it to the friends picked.
    public func send(recording: URL, role: ClipRole, caption: String, to recipients: [UUID]) async throws {
        let size = try recording.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        let yell = try await account.authorized { [api] token in
            try await api.createYell(role: role, caption: caption, audioSize: size, accessToken: token)
        }
        try await api.upload(recording, to: yell.audioUpload)
        let result = try await account.authorized { [api] token in
            try await api.sendYell(yell.yellId, to: recipients, accessToken: token)
        }
        me?.sendsLeftThisMonth = result.sendsLeftThisMonth
    }

    /// Keeps the yell: marked on the server, and downloaded into the motivator library, so it plays offline and can yell
    /// for goals.
    public func save(_ item: InboxItem) async throws {
        try await account.authorized { [api] token in try await api.saveDelivery(item.deliveryId, accessToken: token) }
        try await library.saveYell(item)
    }

    public func hide(_ item: InboxItem) async throws {
        try await account.authorized { [api] token in try await api.hideDelivery(item.deliveryId, accessToken: token) }
        inbox.removeAll { $0.id == item.id }
    }

    /// It leaves the inbox at once, and moderators review it.
    public func report(_ item: InboxItem, reason: ReportReason) async throws {
        try await account.authorized { [api] token in try await api.report(item.yellId, reason: reason, accessToken: token) }
        inbox.removeAll { $0.id == item.id }
    }

    /// From application(_:didRegisterForRemoteNotificationsWithDeviceToken:). Debug builds get pushes from APNs' sandbox.
    public func registerPushToken(_ token: Data) async {
        guard account.isSignedIn else { return }
        let hex = token.map { String(format: "%02x", $0) }.joined()
        #if DEBUG
        let sandbox = true
        #else
        let sandbox = false
        #endif
        do {
            try await account.authorized { [api] accessToken in
                try await api.registerDevice(apnsToken: hex, sandbox: sandbox, accessToken: accessToken)
            }
        } catch {
            log.error("Could not register for pushes: \(error)")
        }
    }

    /// After signing out, nothing of the last account stays on screen.
    public func clear() {
        me = nil
        friends = []
        inbox = []
        inboxCursor = nil
        hasMoreInbox = false
    }

    /// The code from "https://hypeman.app/invite/ABCD2345", or the text itself, tidied.
    nonisolated static func inviteCode(in text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let last = URL(string: trimmed).flatMap { $0.scheme == nil ? nil : $0.lastPathComponent } ?? trimmed
        return last.uppercased().filter { $0.isLetter || $0.isNumber }
    }
}
