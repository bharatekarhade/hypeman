import Foundation
import os
import Testing
@testable import HypemanKit

/// The friends and yells calls against the shapes the server sends and expects, and the saved-yell library.
struct FriendsTests {
    let server = YellStubServer()

    var api: HypemanAPI { HypemanAPI(baseURL: server.baseURL, session: server.session) }

    @Test func anInboxPageDecodes() async throws {
        server.respond("v1/inbox", with: """
            {"deliveries": [{"deliveryId": "0192d1a4-0000-7000-8000-0000000000d1", "yellId": "0192d1a4-0000-7000-8000-0000000000e1",
              "sender": {"id": "0192d1a4-0000-7000-8000-000000000001", "displayName": "Coach"}, "role": "NAG",
              "caption": "You snoozed again?", "durationMs": null, "audioUrl": "https://s3.test/yells/e1/audio.m4a?signed",
              "videoUrl": null, "savedAt": null, "receivedAt": "2026-10-24T09:30:00.123456Z"},
             {"deliveryId": "0192d1a4-0000-7000-8000-0000000000d2", "yellId": "0192d1a4-0000-7000-8000-0000000000e2",
              "sender": null, "role": "START", "caption": "Up!", "durationMs": 4200, "audioUrl": "https://s3.test/a.m4a",
              "videoUrl": null, "savedAt": "2026-10-24T09:31:00Z", "receivedAt": "2026-10-24T09:00:00Z"}],
             "nextCursor": "abc"}
            """)

        let page = try await api.inbox(cursor: "prev", accessToken: "token")

        #expect(page.deliveries.count == 2)
        #expect(page.deliveries[0].role == .nag)
        #expect(page.deliveries[0].sender?.displayName == "Coach")
        #expect(page.deliveries[1].sender == nil)
        #expect(page.deliveries[1].durationMs == 4200)
        #expect(page.nextCursor == "abc")
        #expect(server.requests.last?.query == "cursor=prev")
    }

    @Test func creatingAndSendingAYellSendWhatTheServerExpects() async throws {
        let yellID = UUID()
        server.respond("v1/yells/upload-urls", with: """
            {"yellId": "\(yellID.uuidString.lowercased())", "audioUpload": {"url": "https://s3.test/put", "headers": {"Content-Type": "audio/mp4"},
             "expiresAt": "2026-10-24T09:45:00Z"}, "videoUpload": null}
            """)
        server.respond("v1/yells/\(yellID.uuidString.lowercased())/send", with: #"{"recipients": 1, "sendsLeftThisMonth": null}"#)
        let friend = UUID()

        let upload = try await api.createYell(role: .start, caption: "Get up.", audioSize: 40_000, accessToken: "token")
        let result = try await api.sendYell(upload.yellId, to: [friend], accessToken: "token")

        #expect(upload.audioUpload.headers == ["Content-Type": "audio/mp4"])
        #expect(server.json(at: 0) as? [String: AnyHashable] == ["role": "START", "caption": "Get up.", "audioSize": 40_000])
        #expect(server.json(at: 1) as? [String: [String]] == ["recipientIds": [friend.uuidString.lowercased()]])
        #expect(result.sendsLeftThisMonth == nil)
    }

    @Test func meCarriesTheSendsLeft() async throws {
        server.respond("v1/me", with: """
            {"user": {"id": "0192d1a4-0000-7000-8000-000000000001", "displayName": null}, "identities": [], "proUntil": null,
             "sendsLeftThisMonth": 3}
            """)

        #expect(try await api.me(accessToken: "token").sendsLeftThisMonth == 3)
    }

    @Test func anInviteCodeComesFromALinkOrTyping() {
        #expect(Friends.inviteCode(in: "https://hypeman.app/invite/ABCD2345") == "ABCD2345")
        #expect(Friends.inviteCode(in: " abcd-2345\n") == "ABCD2345")
    }
}

/// Answers each path with canned JSON and records what it was sent. Each instance has its own host.
final class YellStubServer: @unchecked Sendable {
    struct Request {
        let method: String
        let path: String
        let query: String?
        let body: Data?
    }

    let baseURL: URL
    let session: URLSession
    private let state = OSAllocatedUnfairLock(initialState: (requests: [Request](), responses: [String: String]()))

    init() {
        let host = "\(UUID().uuidString.lowercased()).stub"
        baseURL = URL(string: "https://\(host)")!
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [YellStubProtocol.self]
        session = URLSession(configuration: configuration)
        YellStubProtocol.servers.withLock { $0[host] = self }
    }

    var requests: [Request] { state.withLock { $0.requests } }

    func respond(_ path: String, with json: String) {
        state.withLock { $0.responses[path] = json }
    }

    func json(at index: Int) -> Any? {
        requests[index].body.flatMap { try? JSONSerialization.jsonObject(with: $0) }
    }

    fileprivate func handle(_ request: URLRequest) -> (Int, Data) {
        let body = request.httpBody ?? request.httpBodyStream.map(Self.read)
        let path = String(request.url!.path().dropFirst())
        return state.withLock { state in
            state.requests.append(Request(method: request.httpMethod ?? "GET", path: path, query: request.url?.query(), body: body))
            guard let json = state.responses[path] else {
                return (404, Data(#"{"code": "NOT_FOUND", "message": "Stub has no \#(path)", "requestId": null}"#.utf8))
            }
            return (200, Data(json.utf8))
        }
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

final class YellStubProtocol: URLProtocol, @unchecked Sendable {
    static let servers = OSAllocatedUnfairLock<[String: YellStubServer]>(initialState: [:])

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let host = request.url?.host(), let server = Self.servers.withLock({ $0[host] }) else {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotFindHost))
            return
        }
        let (status, data) = server.handle(request)
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!,
                            cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
