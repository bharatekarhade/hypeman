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

    /// Fresh signed links to a pack's videos. They expire within the hour, so download straight away. A paid pack needs
    /// the access token of someone who bought it, or the pack's StoreKit transaction (its jwsRepresentation), which is
    /// how a pack bought signed out downloads.
    public func media(for packID: UUID, accessToken: String? = nil, signedTransaction: String? = nil) async throws -> [ClipMedia] {
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

    // MARK: Friends and yells

    /// The name friends see.
    public func updateMe(displayName: String, accessToken: String) async throws -> User {
        try await send("PATCH", "v1/me", body: ["displayName": displayName], token: accessToken)
    }

    /// A one-use code, and a link carrying it, to send a friend.
    public func createInvite(accessToken: String) async throws -> Invite {
        try await send("POST", "v1/invites", token: accessToken)
    }

    /// Returns the new friend.
    public func acceptInvite(code: String, accessToken: String) async throws -> User {
        let response: FriendResponse = try await send("POST", "v1/invites/\(code)/accept", token: accessToken)
        return response.friend
    }

    public func friends(accessToken: String) async throws -> [User] {
        let response: FriendsResponse = try await send("GET", "v1/friends", token: accessToken)
        return response.friends
    }

    public func removeFriend(_ userID: UUID, accessToken: String) async throws {
        let _: Empty = try await send("DELETE", "v1/friends/\(userID.uuidString.lowercased())", token: accessToken)
    }

    /// Ends the friendship and hides their yells.
    public func block(_ userID: UUID, accessToken: String) async throws {
        let _: Empty = try await send("POST", "v1/blocks", body: ["userId": userID.uuidString.lowercased()], token: accessToken)
    }

    /// Creates an audio-only yell and the link to PUT its recording to, which must be exactly `audioSize` bytes.
    public func createYell(role: ClipRole, caption: String, audioSize: Int, accessToken: String) async throws -> YellUpload {
        try await send("POST", "v1/yells/upload-urls", body: YellRequest(role: role, caption: caption, audioSize: audioSize), token: accessToken)
    }

    /// Uploads a file straight to S3 with a presigned link.
    public func upload(_ file: URL, to upload: PresignedUpload) async throws {
        var request = URLRequest(url: upload.url)
        request.httpMethod = "PUT"
        for (header, value) in upload.headers {
            request.setValue(value, forHTTPHeaderField: header)
        }
        let (_, response) = try await session.upload(for: request, fromFile: file)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw APIError(code: "UPLOAD_FAILED", message: "The recording didn’t upload. Check your connection and try again.", requestId: nil)
        }
    }

    /// Safe to repeat. Fails with SEND_CAP_REACHED past the free monthly cap.
    public func sendYell(_ yellID: UUID, to recipients: [UUID], accessToken: String) async throws -> SendResult {
        try await send("POST", "v1/yells/\(yellID.uuidString.lowercased())/send", body: ["recipientIds": recipients.map { $0.uuidString.lowercased() }],
                       token: accessToken)
    }

    /// Newest first. Pass the previous page's `nextCursor` for the next one.
    public func inbox(cursor: String? = nil, accessToken: String) async throws -> InboxPage {
        try await send("GET", "v1/inbox", query: cursor.map { [URLQueryItem(name: "cursor", value: $0)] } ?? [], token: accessToken)
    }

    public func saveDelivery(_ deliveryID: UUID, accessToken: String) async throws {
        let _: Empty = try await send("POST", "v1/inbox/\(deliveryID.uuidString.lowercased())/save", token: accessToken)
    }

    public func hideDelivery(_ deliveryID: UUID, accessToken: String) async throws {
        let _: Empty = try await send("POST", "v1/inbox/\(deliveryID.uuidString.lowercased())/hide", token: accessToken)
    }

    public func report(_ yellID: UUID, reason: ReportReason, accessToken: String) async throws {
        let _: Empty = try await send("POST", "v1/reports", body: ["yellId": yellID.uuidString.lowercased(), "reason": reason.rawValue],
                                      token: accessToken)
    }

    /// The push token for the device this access token was issued to.
    public func registerDevice(apnsToken: String, sandbox: Bool, accessToken: String) async throws {
        let _: Empty = try await send("POST", "v1/devices", body: ["apnsToken": apnsToken, "environment": sandbox ? "SANDBOX" : "PRODUCTION"],
                                      token: accessToken)
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
            request.httpBody = try JSONEncoder().encode(body)
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

    private struct FriendResponse: Decodable {
        let friend: User
    }

    private struct FriendsResponse: Decodable {
        let friends: [User]
    }

    private struct YellRequest: Encodable {
        let role: ClipRole
        let caption: String
        let audioSize: Int
    }
}

public struct Invite: Decodable, Equatable, Sendable {
    public let code: String
    public let link: URL
    public let expiresAt: Date
}

/// A link to PUT one file to S3, with headers to send exactly as given.
public struct PresignedUpload: Decodable, Equatable, Sendable {
    public let url: URL
    public let headers: [String: String]
    public let expiresAt: Date
}

public struct YellUpload: Decodable, Equatable, Sendable {
    public let yellId: UUID
    public let audioUpload: PresignedUpload
}

public struct SendResult: Decodable, Equatable, Sendable {
    public let recipients: Int
    /// Nil for Pro, which has no cap.
    public let sendsLeftThisMonth: Int?
}

/// A yell a friend sent.
public struct InboxItem: Decodable, Equatable, Identifiable, Sendable {
    public let deliveryId: UUID
    public let yellId: UUID
    /// Nil if the sender's account was deleted since.
    public let sender: User?
    public let role: ClipRole
    public let caption: String
    /// Nil until the server has checked the recording.
    public let durationMs: Int?
    public let audioUrl: URL
    public let videoUrl: URL?
    public let savedAt: Date?
    public let receivedAt: Date

    public var id: UUID { deliveryId }

    public init(deliveryId: UUID, yellId: UUID, sender: User?, role: ClipRole, caption: String, durationMs: Int?, audioUrl: URL,
                videoUrl: URL?, savedAt: Date?, receivedAt: Date) {
        self.deliveryId = deliveryId
        self.yellId = yellId
        self.sender = sender
        self.role = role
        self.caption = caption
        self.durationMs = durationMs
        self.audioUrl = audioUrl
        self.videoUrl = videoUrl
        self.savedAt = savedAt
        self.receivedAt = receivedAt
    }
}

public struct InboxPage: Decodable, Equatable, Sendable {
    public let deliveries: [InboxItem]
    /// Nil on the last page.
    public let nextCursor: String?
}

public enum ReportReason: String, CaseIterable, Codable, Sendable {
    case spam = "SPAM"
    case harassment = "HARASSMENT"
    case hate = "HATE"
    case sexual = "SEXUAL"
    case other = "OTHER"
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
    public let proUntil: Date?
    /// Free friend yells left this month; nil for Pro, which has no cap.
    public var sendsLeftThisMonth: Int?
}

public struct Entitlements: Decodable, Equatable, Sendable {
    /// When Pro runs out; nil if the user never had it.
    public let proUntil: Date?
    /// Packs bought on their own.
    public let packIds: [UUID]

    public init(proUntil: Date?, packIds: [UUID]) {
        self.proUntil = proUntil
        self.packIds = packIds
    }
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
