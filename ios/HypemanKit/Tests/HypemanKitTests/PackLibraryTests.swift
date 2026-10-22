import Foundation
import Testing
@testable import HypemanKit

/// Packs from the catalog to the phone, against a stub network. The stub answers from the URL alone, so tests can run in
/// parallel: a video's bytes are its file name, and any path containing "missing" is a 404.
struct PackLibraryTests {
    let root: URL
    let library: PackLibrary
    let session: URLSession

    init() {
        root = FileManager.default.temporaryDirectory.appending(path: "PackLibraryTests-\(UUID().uuidString)")
        library = PackLibrary(root: root)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubServer.self]
        session = URLSession(configuration: configuration)
    }

    @Test func noPackMeansTheDefaultPack() {
        #expect(library.active == nil)
        #expect(library.videoURL(for: .start) == nil)
    }

    @Test func installingSavesAllFourVideosAndMakesThePackActive() async throws {
        let pack = catalogPack()

        let installed = try await library.install(pack, media: media(for: pack), session: session)

        #expect(library.active == installed)
        #expect(installed.title == "Leg Day")
        #expect(installed.clips.map(\.role) == ClipRole.allCases)
        #expect(installed.caption(for: .congrats) == "Line for CONGRATS")
        let start = try #require(library.videoURL(for: .start))
        #expect(start.lastPathComponent == "start.mp4")
        #expect(try String(contentsOf: start, encoding: .utf8) == "source-start.mp4")
        #expect(library.videoURL(for: .reminder)?.lastPathComponent == "reminder.mov")
    }

    @Test func installingAnotherPackReplacesTheFirst() async throws {
        let first = catalogPack()
        try await library.install(first, media: media(for: first), session: session)
        let second = catalogPack(title: "Study Buddy")

        try await library.install(second, media: media(for: second), session: session)

        #expect(library.active?.id == second.id)
        let start = try #require(library.videoURL(for: .start))
        #expect(try String(contentsOf: start, encoding: .utf8) == "source-start.mp4")
    }

    @Test func aFailedDownloadKeepsTheCurrentPack() async throws {
        let first = catalogPack()
        try await library.install(first, media: media(for: first), session: session)
        let second = catalogPack(title: "Broken")

        await #expect(throws: APIError.self) {
            try await library.install(second, media: media(for: second, missing: .nag), session: session)
        }

        #expect(library.active?.id == first.id)
        #expect(library.videoURL(for: .nag) != nil)
    }

    @Test func aPackMissingARoleIsRefused() async throws {
        let pack = catalogPack()

        await #expect(throws: APIError.self) {
            try await library.install(pack, media: Array(media(for: pack).dropLast()), session: session)
        }
        #expect(library.active == nil)
    }

    @Test func goingBackToTheDefaultRemovesThePack() async throws {
        let pack = catalogPack()
        try await library.install(pack, media: media(for: pack), session: session)

        try library.useDefault()

        #expect(library.active == nil)
        #expect(library.videoURL(for: .start) == nil)
    }

    @Test func theCatalogDecodesTheServersJSON() async throws {
        let api = HypemanAPI(baseURL: URL(string: "https://api.test")!, session: session)

        let packs = try await api.catalog()

        #expect(packs.map(\.title) == ["Leg Day"])
        #expect(packs.first?.isExplicit == true)
        #expect(packs.first?.clips.map(\.role) == ClipRole.allCases)
    }

    @Test func serverErrorsCarryTheirCodeAndMessage() async throws {
        let api = HypemanAPI(baseURL: URL(string: "https://api.test")!, session: session)

        await #expect(throws: APIError(code: "PACK_NOT_PURCHASED", message: "Buy this pack to download it.", requestId: "req-1")) {
            try await api.media(for: UUID())
        }
    }

    private func catalogPack(title: String = "Leg Day") -> CatalogPack {
        CatalogPack(id: UUID(), slug: title.lowercased(), title: title, description: "Yells", isExplicit: false, isFree: true,
                    clips: ClipRole.allCases.map { CatalogClip(role: $0, caption: "Line for \($0.rawValue)") })
    }

    /// REMINDER is a .mov, as an upload from the Photos library would be.
    private func media(for pack: CatalogPack, missing: ClipRole? = nil) -> [ClipMedia] {
        ClipRole.allCases.map { role in
            let name = role.rawValue.lowercased()
            let file = role == missing ? "missing.mp4" : "source-\(name).\(role == .reminder ? "mov" : "mp4")"
            return ClipMedia(role: role, caption: "Line for \(role.rawValue)",
                             videoUrl: URL(string: "https://s3.test/packs/\(pack.id)/\(name)/\(file)?X-Amz-Signature=abc")!)
        }
    }
}

private final class StubServer: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        let url = request.url!
        let (status, body): (Int, String) = switch url.path() {
        case let path where path.contains("missing"):
            (404, "")
        case let path where path.hasSuffix("/media"):
            (402, #"{"code": "PACK_NOT_PURCHASED", "message": "Buy this pack to download it.", "requestId": "req-1"}"#)
        case "/v1/packs":
            (200, #"""
            [{"id": "0199f2a4-6c1e-7d3a-9b2f-3c4d5e6f7a8b", "slug": "leg-day", "title": "Leg Day", "description": "Yells",
              "isExplicit": true, "isFree": true, "clips": [
                {"role": "START", "caption": "Up."}, {"role": "REMINDER", "caption": "Now."},
                {"role": "NAG", "caption": "Still?"}, {"role": "CONGRATS", "caption": "Yes!"}]}]
            """#)
        default:
            (200, url.lastPathComponent)
        }
        let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
}
