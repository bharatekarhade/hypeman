import AuthenticationServices
import Foundation
import Observation
import OSLog
import Security

/// Who is signed in, and their tokens. Goals never need an account; it's for things the server keeps per person.
public struct Session: Codable, Equatable, Sendable {
    public var accessToken: String
    public var accessTokenExpiresAt: Date
    public var refreshToken: String
    public var user: User
    /// Apple's id for this user, to check the sign-in hasn't been revoked in Settings.
    public var appleUserID: String?
}

/// Keeps the session between launches.
public protocol SessionStore: Sendable {
    func load() -> Session?
    func save(_ session: Session?)
    /// Stable for this install, so the server can sign this device out without touching the user's others.
    var deviceID: UUID { get }
}

/// The signed-in account. The server's access tokens last 15 minutes and each refresh token works once, so every call
/// goes through `authorized`, which refreshes when needed and never sends the same refresh token twice.
@MainActor
@Observable
public final class Account {
    public static let shared = Account(api: .shared, store: KeychainSessionStore())

    public private(set) var session: Session?
    public var isSignedIn: Bool { session != nil }

    private let api: HypemanAPI
    private let store: SessionStore
    private let now: @Sendable () -> Date
    @ObservationIgnored private var refreshing: Task<Session, Error>?
    /// Bumped on every sign-in and sign-out, so work started for an earlier session can't touch the current one.
    @ObservationIgnored private var generation = 0
    private let log = Logger(subsystem: "com.hypeman.app", category: "Account")

    public init(api: HypemanAPI, store: SessionStore, now: @escaping @Sendable () -> Date = { .now }) {
        self.api = api
        self.store = store
        self.now = now
        session = store.load()
    }

    public func signInWithApple(identityToken: String, nonce: String, appleUserID: String) async throws {
        let response = try await api.signInWithApple(identityToken: identityToken, nonce: nonce, deviceID: store.deviceID)
        replace(with: Session(accessToken: response.accessToken, accessTokenExpiresAt: response.accessTokenExpiresAt,
                              refreshToken: response.refreshToken, user: response.user, appleUserID: appleUserID))
    }

    /// Signs out here even when the server can't be reached; the refresh token then just expires unused.
    public func signOut() async {
        guard session != nil else { return }
        do {
            try await authorized { [self] token in
                guard let refreshToken = session?.refreshToken else { return }
                try await api.logout(refreshToken: refreshToken, accessToken: token)
            }
        } catch {
            log.info("Signed out on this device only: \(error)")
        }
        replace(with: nil)
    }

    public func me() async throws -> Me {
        try await authorized { [api] token in try await api.me(accessToken: token) }
    }

    /// Signed out once the server has accepted; it removes the rest within 30 days.
    public func deleteAccount(appleAuthorizationCode: String?) async throws {
        try await authorized { [api] token in
            try await api.deleteAccount(appleAuthorizationCode: appleAuthorizationCode, accessToken: token)
        }
        replace(with: nil)
    }

    /// Signs out if the user stopped using Sign in with Apple for Hypeman in Settings. Call when the app comes to the front.
    public func checkAppleCredential() async {
        guard let appleUserID = session?.appleUserID,
              let state = try? await ASAuthorizationAppleIDProvider().credentialState(forUserID: appleUserID) else { return }
        if state == .revoked || state == .notFound {
            log.info("The Apple sign-in was revoked; signing out")
            endSession()
        }
    }

    /// Signs out on this device without telling the server.
    func endSession() {
        replace(with: nil)
    }

    /// Runs `call` with a current access token. A token about to expire is refreshed first, and a call the server turns
    /// away as unauthorized is retried once with a fresh one. An error that ends the session, from the call or the
    /// refresh, signs out.
    func authorized<T>(_ call: (String) async throws -> T) async throws -> T {
        guard var session else { throw APIError.signedOut }
        let generation = generation
        do {
            if session.accessTokenExpiresAt.timeIntervalSince(now()) < 60 {
                session = try await refreshed(from: session)
            }
            do {
                return try await call(session.accessToken)
            } catch let error as APIError where error.code == "UNAUTHORIZED" {
                return try await call(try await refreshed(from: session).accessToken)
            }
        } catch let error as APIError where error.endsSession {
            if self.generation == generation {
                replace(with: nil)
            }
            throw error
        }
    }

    /// One refresh at a time: callers that find their session stale share the refresh already under way, or use the
    /// session it produced. A refresh that finishes after a sign-out or a new sign-in is dropped.
    private func refreshed(from stale: Session) async throws -> Session {
        if let current = session, current.refreshToken != stale.refreshToken {
            return current
        }
        let generation = generation
        let task = refreshing ?? Task { [api] in
            let pair = try await api.refresh(stale.refreshToken)
            var fresh = stale
            fresh.accessToken = pair.accessToken
            fresh.accessTokenExpiresAt = pair.accessTokenExpiresAt
            fresh.refreshToken = pair.refreshToken
            return fresh
        }
        refreshing = task
        defer {
            if refreshing == task {
                refreshing = nil
            }
        }
        let fresh = try await task.value
        guard self.generation == generation, let current = session else {
            guard let session else { throw APIError.signedOut }
            return session
        }
        // The first caller back saves it; the rest find it saved.
        if current.refreshToken == stale.refreshToken {
            session = fresh
            store.save(fresh)
        }
        return session ?? fresh
    }

    /// A sign-in or sign-out: a different session, so anything still running for the old one is cut loose.
    private func replace(with session: Session?) {
        generation += 1
        refreshing = nil
        self.session = session
        store.save(session)
    }
}

public extension APIError {
    static let signedOut = APIError(code: "SIGNED_OUT", message: "Sign in to do this.", requestId: nil)

    /// The server ended the session: the user has to sign in again, or, banned, can't.
    var endsSession: Bool {
        ["INVALID_REFRESH_TOKEN", "REFRESH_TOKEN_REUSED", "DEVICE_SIGNED_OUT", "ACCOUNT_DELETED", "ACCOUNT_BANNED"].contains(code)
    }
}

/// The session in the Keychain, readable once the phone has been unlocked after a restart and never restored to
/// another device.
public struct KeychainSessionStore: SessionStore {
    private static let service = "com.hypeman.app.account"
    private let log = Logger(subsystem: "com.hypeman.app", category: "KeychainSessionStore")

    public init() {}

    public func load() -> Session? {
        read("session").flatMap { try? JSONDecoder().decode(Session.self, from: $0) }
    }

    public func save(_ session: Session?) {
        if let session, let data = try? JSONEncoder().encode(session) {
            write(data, "session")
        } else {
            delete("session")
        }
    }

    public var deviceID: UUID {
        if let data = read("deviceID"), let id = UUID(uuidString: String(decoding: data, as: UTF8.self)) {
            return id
        }
        let id = UUID()
        write(Data(id.uuidString.utf8), "deviceID")
        return id
    }

    private func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: Self.service, kSecAttrAccount as String: account]
    }

    private func read(_ account: String) -> Data? {
        var query = query(account)
        query[kSecReturnData as String] = true
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    private func write(_ data: Data, _ account: String) {
        delete(account)
        var item = query(account)
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(item as CFDictionary, nil)
        if status != errSecSuccess {
            log.error("Could not save \(account) to the Keychain: \(status)")
        }
    }

    private func delete(_ account: String) {
        SecItemDelete(query(account) as CFDictionary)
    }
}
