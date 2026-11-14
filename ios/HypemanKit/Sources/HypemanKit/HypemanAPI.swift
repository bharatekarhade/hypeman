import Foundation

/// The backend's REST API. Every failure comes back as `{code, message, requestId}`, thrown as an `APIError`.
public struct HypemanAPI: Sendable {
    /// Talks to the server named by `HypemanAPIBaseURL` in the app's Info.plist.
    public static let shared = HypemanAPI(baseURL: configuredBaseURL)

    public let baseURL: URL
    private let session: URLSession

    public init(baseURL: URL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    /// Published packs. Public: the store shows them before anyone signs in.
    public func catalog() async throws -> [CatalogPack] {
        try await send("GET", "v1/packs")
    }

    /// Fresh signed links to a pack's videos. They expire within the hour, so download straight away. Every pack needs
    /// sign-in; a paid one also needs the user's purchase, which the pack's StoreKit transaction (its jwsRepresentation)
    /// proves before the server has recorded it.
    public func media(for packID: UUID, accessToken: String, signedTransaction: String? = nil) async throws -> [ClipMedia] {
        let response: MediaResponse = try await send("POST", "v1/packs/\(packID.uuidString.lowercased())/media",
                                                     body: signedTransaction.map { ["signedTransaction": $0] }, token: accessToken)
        return response.clips
    }

    /// What the user has paid for.
    public func entitlements(accessToken: String) async throws -> Entitlements {
        try await send("GET", "v1/purchases", token: accessToken)
    }

    /// Reports a StoreKit purchase or restore; the server checks Apple's signature. Returns what the user owns now.
    public func recordPurchase(signedTransaction: String, accessToken: String) async throws -> Entitlements {
        try await send("POST", "v1/purchases", body: ["signedTransaction": signedTransaction], token: accessToken)
    }

    /// The most goals one push may carry.
    public static let maxGoalsPerPush = 500

    /// The goals changed after `since`, deletions included, or with no `since`, every goal the account has. Pass the
    /// page's cursor as `since` next time.
    public func goals(since: Date?, accessToken: String) async throws -> GoalPage {
        let query = since.map { [URLQueryItem(name: "since", value: Self.instant($0))] } ?? []
        return try await send("GET", "v1/goals", query: query, token: accessToken)
    }

    /// Up to `maxGoalsPerPush` changes. The server keeps whichever change to a goal is newest, so sending one twice is fine.
    public func pushGoals(_ goals: [RemoteGoal], accessToken: String) async throws {
        let _: Empty = try await send("PUT", "v1/goals", body: ["goals": goals], token: accessToken)
    }

    /// Signs in with the identity token Apple returned, which carries the SHA-256 of `nonce`. A device has one account
    /// at a time: whoever was signed in on it before is signed out there.
    public func signInWithApple(identityToken: String, nonce: String, deviceID: UUID) async throws -> SignInResponse {
        try await send("POST", "v1/auth/apple", body: ["identityToken": identityToken, "nonce": nonce, "deviceId": deviceID.uuidString])
    }

    /// Swaps a refresh token for a new pair. Each refresh token works once; sending one twice signs the device out.
    public func refresh(_ refreshToken: String) async throws -> TokenPair {
        try await send("POST", "v1/auth/refresh", body: ["refreshToken": refreshToken])
    }

    public func logout(refreshToken: String, accessToken: String) async throws {
        let _: Empty = try await send("POST", "v1/auth/logout", body: ["refreshToken": refreshToken], token: accessToken)
    }

    public func me(accessToken: String) async throws -> Me {
        try await send("GET", "v1/me", token: accessToken)
    }

    /// An Apple account needs a fresh authorization code from Sign in with Apple, so the server can revoke the sign-in.
    public func deleteAccount(appleAuthorizationCode: String?, accessToken: String) async throws {
        let body = appleAuthorizationCode.map { ["appleAuthorizationCode": $0] }
        let _: Empty = try await send("DELETE", "v1/me", body: body, token: accessToken)
    }

    private func send<T: Decodable>(_ method: String, _ path: String, query: [URLQueryItem] = [], body: (any Encodable)? = nil,
                                    token: String? = nil) async throws -> T {
        var url = baseURL.appending(path: path)
        if !query.isEmpty {
            url.append(queryItems: query)
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try Self.encoder.encode(body)
        }
        if let token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw (try? JSONDecoder().decode(APIError.self, from: data))
                ?? APIError(code: "HTTP_\(status)", message: "Something went wrong on our side. Please try again.", requestId: nil)
        }
        if T.self == Empty.self {
            return Empty() as! T
        }
        return try Self.decoder.decode(T.self, from: data)
    }

    /// Instants come as ISO 8601, with or without fractions of a second.
    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let text = try container.decode(String.self)
            let whole = text.replacingOccurrences(of: #"\.\d+"#, with: "", options: .regularExpression)
            guard let date = try? Date(whole, strategy: .iso8601) else {
                throw DecodingError.dataCorruptedError(in: container, debugDescription: "Not an ISO 8601 instant: \(text)")
            }
            return date
        }
        return decoder
    }()

    /// Instants go as ISO 8601 in UTC, to the millisecond, as the server expects.
    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(instant(date))
        }
        return encoder
    }()

    private static func instant(_ date: Date) -> String {
        date.formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true))
    }

    /// For 202 and 204 responses.
    private struct Empty: Decodable {}

    private static var configuredBaseURL: URL {
        if let value = Bundle.main.object(forInfoDictionaryKey: "HypemanAPIBaseURL") as? String, let url = URL(string: value) {
            return url
        }
        return URL(string: "http://localhost:8080")!
    }

    private struct MediaResponse: Decodable {
        let clips: [ClipMedia]
    }
}

public struct SignInResponse: Decodable, Sendable {
    public let accessToken: String
    public let accessTokenExpiresAt: Date
    public let refreshToken: String
    public let user: User
}

public struct TokenPair: Decodable, Sendable {
    public let accessToken: String
    public let accessTokenExpiresAt: Date
    public let refreshToken: String
}

public struct User: Codable, Equatable, Sendable {
    public let id: UUID
    public let displayName: String?
}

public struct Me: Decodable, Equatable, Sendable {
    public struct Identity: Decodable, Equatable, Sendable {
        public let provider: String
        public let email: String?
    }

    public let user: User
    public let identities: [Identity]
}

public struct Entitlements: Decodable, Equatable, Sendable {
    /// Packs the user bought.
    public let packIds: [UUID]

    public init(packIds: [UUID]) {
        self.packIds = packIds
    }
}

/// A goal as the server has it, or its deletion. The id is the one this app gave the goal.
public struct RemoteGoal: Codable, Equatable, Sendable {
    public var id: UUID
    /// Nil for a deletion.
    public var title: String?
    /// Nil for a deletion.
    public var sortIndex: Int?
    public var reminderAt: Date?
    public var startedAt: Date?
    public var completedAt: Date?
    public var motivatorId: String?
    /// When the change was made, on whichever device made it.
    public var changedAt: Date
    public var deleted: Bool

    public init(id: UUID, title: String?, sortIndex: Int?, reminderAt: Date?, startedAt: Date?, completedAt: Date?,
                motivatorId: String?, changedAt: Date, deleted: Bool) {
        self.id = id
        self.title = title
        self.sortIndex = sortIndex
        self.reminderAt = reminderAt
        self.startedAt = startedAt
        self.completedAt = completedAt
        self.motivatorId = motivatorId
        self.changedAt = changedAt
        self.deleted = deleted
    }
}

public struct GoalPage: Decodable, Sendable {
    public let goals: [RemoteGoal]
    /// Pass as `since` on the next pull.
    public let cursor: Date
}

public struct APIError: Error, Decodable, Equatable, LocalizedError, Sendable {
    public let code: String
    public let message: String
    /// Quote it in bug reports: it's on the server's log lines for this request.
    public let requestId: String?

    public init(code: String, message: String, requestId: String?) {
        self.code = code
        self.message = message
        self.requestId = requestId
    }

    public var errorDescription: String? { message }
}
