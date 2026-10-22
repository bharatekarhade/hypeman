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

    /// Fresh signed links to a free pack's videos. They expire within the hour, so download straight away.
    public func media(for packID: UUID) async throws -> [ClipMedia] {
        let response: MediaResponse = try await send("POST", "v1/packs/\(packID.uuidString.lowercased())/media")
        return response.clips
    }

    private func send<T: Decodable>(_ method: String, _ path: String) async throws -> T {
        var request = URLRequest(url: baseURL.appending(path: path))
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw (try? JSONDecoder().decode(APIError.self, from: data))
                ?? APIError(code: "HTTP_\(status)", message: "Something went wrong on our side. Please try again.", requestId: nil)
        }
        return try JSONDecoder().decode(T.self, from: data)
    }

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

public struct APIError: Error, Decodable, Equatable, LocalizedError, Sendable {
    public let code: String
    public let message: String
    /// Quote it in bug reports: it's on the server's log lines for this request.
    public let requestId: String?

    public var errorDescription: String? { message }
}
