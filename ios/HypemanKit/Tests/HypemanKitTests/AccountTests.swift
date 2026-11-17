import Foundation
import os
import Testing
@testable import HypemanKit

/// Signing in, refreshing and signing out against a stub server. Each test has its own host, so they can run in parallel.
@MainActor
struct AccountTests {
    let server = FakeAuthServer()
    let store = MemorySessionStore()
    let clock = TestClock()
    let account: Account

    init() {
        account = Account(api: HypemanAPI(baseURL: server.baseURL, session: server.session), store: store, now: { [clock] in clock.now })
    }

    @Test func signingInKeepsTheSession() async throws {
        try await account.signInWithApple(identityToken: "apple-token", nonce: "raw-nonce", appleUserID: "apple-user")

        #expect(account.isSignedIn)
        #expect(account.session?.accessToken == "access-1")
        #expect(account.session?.appleUserID == "apple-user")
        #expect(store.load() == account.session)
        #expect(server.requests.first?.body == ["identityToken": "apple-token", "nonce": "raw-nonce", "deviceId": store.deviceID.uuidString])
    }

    @Test func aFreshTokenIsUsedAsIs() async throws {
        try await signIn()

        _ = try await account.me()

        #expect(server.paths == ["v1/auth/apple", "v1/me"])
        #expect(server.requests.last?.authorization == "Bearer access-1")
    }

    @Test func anExpiringTokenIsRefreshedFirst() async throws {
        try await signIn()
        clock.now = clock.now.addingTimeInterval(15 * 60)

        _ = try await account.me()

        #expect(server.paths == ["v1/auth/apple", "v1/auth/refresh", "v1/me"])
        #expect(server.requests[1].body == ["refreshToken": "refresh-1"])
        #expect(server.requests.last?.authorization == "Bearer access-2")
        #expect(store.load()?.refreshToken == "refresh-2")
    }

    @Test func aRejectedTokenIsRefreshedAndTheCallRetriedOnce() async throws {
        try await signIn()
        server.rejectAccessToken("access-1")

        _ = try await account.me()

        #expect(server.paths == ["v1/auth/apple", "v1/me", "v1/auth/refresh", "v1/me"])
    }

    @Test func callsThatNeedARefreshAtTheSameTimeShareIt() async throws {
        try await signIn()
        clock.now = clock.now.addingTimeInterval(15 * 60)

        async let first = account.me()
        async let second = account.me()
        _ = try await (first, second)

        #expect(server.paths.filter { $0 == "v1/auth/refresh" }.count == 1, "a second refresh with the same token signs the device out")
    }

    @Test func anEndedSessionSignsOut() async throws {
        try await signIn()
        clock.now = clock.now.addingTimeInterval(15 * 60)
        server.failRefresh(code: "REFRESH_TOKEN_REUSED")

        await #expect(throws: APIError.self) { try await account.me() }

        #expect(!account.isSignedIn)
        #expect(store.load() == nil)
    }

    @Test func aNetworkFailureKeepsTheSession() async throws {
        try await signIn()
        clock.now = clock.now.addingTimeInterval(15 * 60)
        server.failRefresh(code: "HTTP_503")

        await #expect(throws: APIError.self) { try await account.me() }

        #expect(account.isSignedIn)
    }

    @Test func aRefreshThatFinishesAfterSigningOutIsDropped() async throws {
        try await signIn()
        clock.now = clock.now.addingTimeInterval(15 * 60)
        server.holdRefreshes()
        let call = Task { try await account.me() }
        await server.refreshArrived()

        account.endSession()
        server.releaseRefreshes()

        await #expect(throws: APIError.signedOut) { try await call.value }
        #expect(!account.isSignedIn)
        #expect(store.load() == nil)
    }

    @Test func aDeletedAccountSignsOutFromAnyCall() async throws {
        try await signIn()
        server.failMe(code: "ACCOUNT_DELETED")

        await #expect(throws: APIError.self) { try await account.me() }

        #expect(!account.isSignedIn)
        #expect(store.load() == nil)
    }

    @Test func signingOutRevokesTheRefreshToken() async throws {
        try await signIn()

        await account.signOut()

        #expect(!account.isSignedIn)
        #expect(store.load() == nil)
        #expect(server.requests.last?.path == "v1/auth/logout")
        #expect(server.requests.last?.body == ["refreshToken": "refresh-1"])
    }

    @Test func signingOutWorksOffline() async throws {
        try await signIn()
        server.failEverything()

        await account.signOut()

        #expect(!account.isSignedIn)
    }

    @Test func deletingSendsTheAppleCodeAndSignsOut() async throws {
        try await signIn()

        try await account.deleteAccount(appleAuthorizationCode: "apple-code")

        #expect(server.requests.last?.method == "DELETE")
        #expect(server.requests.last?.body == ["appleAuthorizationCode": "apple-code"])
        #expect(!account.isSignedIn)
    }

    @Test func callsNeedASession() async {
        await #expect(throws: APIError.signedOut) { try await account.me() }
    }

    private func signIn() async throws {
        try await account.signInWithApple(identityToken: "apple-token", nonce: "raw-nonce", appleUserID: "apple-user")
    }
}

final class TestClock: @unchecked Sendable {
    private let lock = OSAllocatedUnfairLock(initialState: Date(timeIntervalSince1970: 1_800_000_000))

    var now: Date {
        get { lock.withLock { $0 } }
        set { lock.withLock { $0 = newValue } }
    }
}

final class MemorySessionStore: SessionStore, @unchecked Sendable {
    private let lock = OSAllocatedUnfairLock<Session?>(initialState: nil)
    let deviceID = UUID()

    func load() -> Session? { lock.withLock { $0 } }
    func save(_ session: Session?) { lock.withLock { $0 = session } }
}

/// Issues access-N/refresh-N pairs that expire 15 minutes after the test clock's start, and records what it was sent.
final class FakeAuthServer: @unchecked Sendable {
    struct Request {
        let method: String
        let path: String
        let query: String?
        let authorization: String?
        let body: [String: String]?
    }

    private struct State {
        var requests: [Request] = []
        var issued = 0
        var rejected: Set<String> = []
        var refreshFailure: String?
        var meFailure: String?
        var down = false
        /// Paths whose requests wait until released, and those with a request waiting now.
        var held: Set<String> = []
        var waiting: Set<String> = []
        var goals: [UUID: RemoteGoal] = [:]
    }

    let baseURL: URL
    let session: URLSession
    private let state = OSAllocatedUnfairLock(initialState: State())
    private let gate = DispatchSemaphore(value: 0)

    init() {
        let host = "\(UUID().uuidString.lowercased()).test"
        baseURL = URL(string: "https://\(host)")!
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [FakeAuthProtocol.self]
        session = URLSession(configuration: configuration)
        FakeAuthProtocol.servers.withLock { $0[host] = self }
    }

    var requests: [Request] { state.withLock { $0.requests } }
    var paths: [String] { requests.map(\.path) }

    func rejectAccessToken(_ token: String) { state.withLock { _ = $0.rejected.insert(token) } }
    func failRefresh(code: String) { state.withLock { $0.refreshFailure = code } }
    func failMe(code: String) { state.withLock { $0.meFailure = code } }
    func failEverything() { state.withLock { $0.down = true } }

    /// What the account's goals are on the server.
    var goals: [UUID: RemoteGoal] { state.withLock { $0.goals } }

    /// A goal pushed from another device.
    func addGoal(_ goal: RemoteGoal) { state.withLock { $0.goals[goal.id] = goal } }

    /// Requests to the path, such as "v1/goals", wait on the URL loading thread until released. One at a time.
    func hold(_ path: String) { state.withLock { _ = $0.held.insert(path) } }

    func release(_ path: String) {
        state.withLock { _ = $0.held.remove(path) }
        gate.signal()
    }

    /// Returns once a request to the held path is waiting.
    func arrived(_ path: String) async {
        while !state.withLock({ $0.waiting.contains(path) }) {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }

    func holdRefreshes() { hold("v1/auth/refresh") }
    func releaseRefreshes() { release("v1/auth/refresh") }
    func refreshArrived() async { await arrived("v1/auth/refresh") }

    fileprivate func waitIfHeld(_ request: URLRequest) {
        let path = String(request.url!.path().dropFirst())
        let held = state.withLock { state in
            guard state.held.contains(path) else { return false }
            state.waiting.insert(path)
            return true
        }
        if held {
            gate.wait()
            state.withLock { _ = $0.waiting.remove(path) }
        }
    }

    fileprivate func respond(to request: URLRequest) -> (Int, Data) {
        let data = request.httpBodyStream.map(Self.read)
        let body = data.flatMap { try? JSONDecoder().decode([String: String].self, from: $0) }
        let path = String(request.url!.path().dropFirst())
        let authorization = request.value(forHTTPHeaderField: "Authorization")
        return state.withLock { state in
            state.requests.append(Request(method: request.httpMethod ?? "GET", path: path, query: request.url!.query(),
                                          authorization: authorization, body: body))
            if state.down {
                return Self.error(503, "HTTP_503")
            }
            if let token = authorization?.replacingOccurrences(of: "Bearer ", with: ""), state.rejected.contains(token) {
                return Self.error(401, "UNAUTHORIZED")
            }
            switch path {
            case "v1/auth/apple":
                state.issued += 1
                return (200, Self.json(Self.pair(state.issued, user: true)))
            case "v1/auth/refresh":
                if let code = state.refreshFailure {
                    return Self.error(code == "HTTP_503" ? 503 : 401, code)
                }
                state.issued += 1
                return (200, Self.json(Self.pair(state.issued, user: false)))
            case "v1/me":
                if let code = state.meFailure {
                    return Self.error(401, code)
                }
                if request.httpMethod == "DELETE" {
                    return (202, Data())
                }
                return (200, Self.json(["user": ["id": "0192d1a4-0000-7000-8000-000000000001"], "identities": [["provider": "APPLE", "email": "me@example.com"]]]))
            case "v1/auth/logout":
                return (204, Data())
            case "v1/goals" where request.httpMethod == "PUT":
                let pushed = data.flatMap { try? Self.goalDecoder.decode([String: [RemoteGoal]].self, from: $0) }?["goals"] ?? []
                for goal in pushed {
                    state.goals[goal.id] = goal
                }
                return (204, Data())
            case "v1/goals":
                let page = try! Self.goalEncoder.encode(Page(goals: Array(state.goals.values), cursor: Date(timeIntervalSince1970: 1_800_000_000)))
                return (200, page)
            default:
                return Self.error(404, "NOT_FOUND")
            }
        }
    }

    /// Every goal it has, whatever the cursor.
    private struct Page: Encodable {
        let goals: [RemoteGoal]
        let cursor: Date
    }

    private static let goalDecoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let text = try decoder.singleValueContainer().decode(String.self)
            return try Date(text, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true))
        }
        return decoder
    }()

    private static let goalEncoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }()

    private static func pair(_ n: Int, user: Bool) -> [String: Any] {
        var pair: [String: Any] = ["accessToken": "access-\(n)", "accessTokenExpiresAt": "2027-01-15T08:15:00.123456Z", "refreshToken": "refresh-\(n)"]
        // 1_800_000_000 is 2027-01-15T08:00:00Z, so tokens expire 15 minutes after the test clock starts.
        if user {
            pair["user"] = ["id": "0192d1a4-0000-7000-8000-000000000001", "displayName": NSNull()]
        }
        return pair
    }

    private static func error(_ status: Int, _ code: String) -> (Int, Data) {
        (status, json(["code": code, "message": "Stub \(code)", "requestId": "req-1"]))
    }

    private static func json(_ object: Any) -> Data {
        try! JSONSerialization.data(withJSONObject: object)
    }

    private static func read(_ stream: InputStream) -> Data {
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            data.append(buffer, count: count)
        }
        return data
    }
}

final class FakeAuthProtocol: URLProtocol, @unchecked Sendable {
    static let servers = OSAllocatedUnfairLock<[String: FakeAuthServer]>(initialState: [:])

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let host = request.url?.host(), let server = Self.servers.withLock({ $0[host] }) else {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotFindHost))
            return
        }
        server.waitIfHeld(request)
        let (status, data) = server.respond(to: request)
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!,
                            cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
