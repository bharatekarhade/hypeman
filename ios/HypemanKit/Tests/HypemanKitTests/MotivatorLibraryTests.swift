import AVFoundation
import Foundation
import Testing
@testable import HypemanKit

/// Voices on the phone: packs from the catalog against a stub network, saved yells, the user's recordings, and which clip
/// plays. The stub answers from the URL alone, so tests can run in parallel: a video's bytes are its file name, and any
/// path containing "missing" is a 404.
struct MotivatorLibraryTests {
    let root: URL
    let suite = "MotivatorLibraryTests-\(UUID().uuidString)"
    let library: MotivatorLibrary
    let session: URLSession

    init() {
        root = FileManager.default.temporaryDirectory.appending(path: "MotivatorLibraryTests-\(UUID().uuidString)")
        library = MotivatorLibrary(root: root, defaultsSuite: suite)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubServer.self]
        session = URLSession(configuration: configuration)
    }

    @Test func atFirstThereIsOnlyTheBuiltInVoice() {
        let start = library.resolve(.start, goalMotivatorID: nil, pro: true)

        #expect(library.all.map(\.id) == [Motivator.builtInID])
        #expect(library.defaultID == Motivator.builtInID)
        #expect(start.caption == "Get up. Do it. Now.")
        #expect(start.url?.lastPathComponent == "start.m4a")
        #expect(!start.isVideo)
        #expect(start.soundName == nil)
    }

    @Test func installingAPackAddsItWithoutChangingTheDefault() async throws {
        let pack = catalogPack()

        let voice = try await library.install(pack, media: media(for: pack), session: session)

        #expect(library.all.map(\.id) == [Motivator.builtInID, voice.id])
        #expect(library.defaultID == Motivator.builtInID)
        #expect(voice.title == "Leg Day")
        #expect(voice.clips.map(\.role) == ClipRole.allCases)
        #expect(library.hasPack(pack.id))
        let startClip = try #require(voice.clip(for: .start))
        let start = try #require(library.url(of: startClip, in: voice))
        #expect(try String(contentsOf: start, encoding: .utf8) == "source-start.mp4")
        #expect(voice.clip(for: .reminder)?.fileName == "reminder.mov")
    }

    @Test func aPacksAlarmsBecomeNotificationSounds() async throws {
        let pack = catalogPack()
        let voice = try await library.install(pack, media: media(for: pack), session: session)
        library.defaultID = voice.id

        let reminder = try #require(library.resolve(.reminder, goalMotivatorID: nil, pro: false).soundName)

        #expect(reminder == "hypeman-\(pack.id.uuidString.lowercased())-reminder.caf")
        #expect(try String(contentsOf: root.appending(path: "Library/Sounds/\(reminder)"), encoding: .utf8) == "alarm-reminder.caf")
        #expect(library.resolve(.nag, goalMotivatorID: nil, pro: false).soundName != nil)
        #expect(library.resolve(.start, goalMotivatorID: nil, pro: false).soundName == nil, "only reminders and nags are notifications")
        #expect(library.resolve(.start, goalMotivatorID: nil, pro: false).isVideo)
    }

    @Test func severalPacksStayDownloaded() async throws {
        let first = catalogPack()
        let second = catalogPack(title: "Study Buddy")
        try await library.install(first, media: media(for: first), session: session)
        try await library.install(second, media: media(for: second), session: session)

        #expect(Set(library.all.map(\.title)) == ["Default pack", "Leg Day", "Study Buddy"])
        let sounds = try FileManager.default.contentsOfDirectory(atPath: root.appending(path: "Library/Sounds").path())
        #expect(sounds.count == 4)
    }

    @Test func removingTheDefaultGoesBackToTheBuiltInVoiceAndDropsItsSounds() async throws {
        let pack = catalogPack()
        let voice = try await library.install(pack, media: media(for: pack), session: session)
        library.defaultID = voice.id

        try library.remove(voice.id)

        #expect(library.defaultID == Motivator.builtInID)
        #expect(library.all.map(\.id) == [Motivator.builtInID])
        #expect(try FileManager.default.contentsOfDirectory(atPath: root.appending(path: "Library/Sounds").path()).isEmpty)
    }

    @Test func aFailedDownloadAddsNothing() async throws {
        let pack = catalogPack(title: "Broken")

        await #expect(throws: APIError.self) {
            try await library.install(pack, media: media(for: pack, missing: .nag), session: session)
        }

        #expect(library.all.map(\.id) == [Motivator.builtInID])
    }

    @Test func aPackMissingARoleIsRefused() async throws {
        let pack = catalogPack()

        await #expect(throws: APIError.self) {
            try await library.install(pack, media: Array(media(for: pack).dropLast()), session: session)
        }
        #expect(!library.hasPack(pack.id))
    }

    @Test func aGoalsOwnVoiceNeedsPro() async throws {
        let pack = catalogPack()
        let voice = try await library.install(pack, media: media(for: pack), session: session)

        #expect(library.resolve(.start, goalMotivatorID: voice.id, pro: true).motivatorID == voice.id)
        #expect(library.resolve(.start, goalMotivatorID: voice.id, pro: false).motivatorID == Motivator.builtInID)
        #expect(library.resolve(.start, goalMotivatorID: "pack:gone", pro: true).motivatorID == Motivator.builtInID)
    }

    @Test func yourOwnRecordingNeedsProAndMakesItsSounds() throws {
        let voice = try library.saveRecording(title: "Me", clips: [
            (role: .nag, caption: "Move!", audio: try recording()),
            (role: .start, caption: "Go!", audio: try recording()),
        ])
        library.defaultID = voice.id

        let nag = library.resolve(.nag, goalMotivatorID: nil, pro: true)

        #expect(voice.clips.map(\.role) == [.start, .nag])
        #expect(nag.motivatorID == voice.id)
        #expect(nag.caption == "Move!")
        let soundName = try #require(nag.soundName)
        let sound = try AVAudioFile(forReading: root.appending(path: "Library/Sounds/\(soundName)"))
        #expect(sound.fileFormat.streamDescription.pointee.mFormatID == kAudioFormatLinearPCM)
        #expect(library.resolve(.reminder, goalMotivatorID: nil, pro: true).motivatorID == Motivator.builtInID, "no REMINDER recorded")
        #expect(library.resolve(.nag, goalMotivatorID: nil, pro: false).motivatorID == Motivator.builtInID, "Pro lapsed")
    }

    @Test func shuffleNeedsProAndPicksFromVoicesWithThatClip() async throws {
        let pack = catalogPack()
        let voice = try await library.install(pack, media: media(for: pack), session: session)
        library.shuffle = true
        var offered: [String] = []

        let picked = library.resolve(.start, goalMotivatorID: nil, pro: true) { voices in
            offered = voices.map(\.id)
            return voices.last
        }

        #expect(offered == [Motivator.builtInID, voice.id])
        #expect(picked.motivatorID == voice.id)
        #expect(library.resolve(.start, goalMotivatorID: nil, pro: false) { $0.last }.motivatorID == Motivator.builtInID)
    }

    @Test func aSavedYellIsAOneClipVoice() async throws {
        let audio = try recording()
        let item = InboxItem(deliveryId: UUID(), yellId: UUID(), sender: User(id: UUID(), displayName: "Sam"), role: .nag,
                             caption: "Up!", durationMs: 1000, audioUrl: audio, videoUrl: nil, savedAt: nil, receivedAt: .now)

        let voice = try await library.saveYell(item, session: .shared)
        try await library.saveYell(item, session: .shared)
        library.defaultID = voice.id

        #expect(library.all.filter { $0.kind == .yell }.map(\.id) == [voice.id])
        #expect(voice.subtitle == "Sam · Nag")
        #expect(library.resolve(.nag, goalMotivatorID: nil, pro: false).soundName != nil)
        #expect(library.resolve(.start, goalMotivatorID: nil, pro: false).motivatorID == Motivator.builtInID, "MV-04")
    }

    @Test func theOldActivePackMovesIntoTheLibrary() throws {
        let packID = UUID()
        let legacy = root.appending(path: "ActivePack")
        try FileManager.default.createDirectory(at: legacy, withIntermediateDirectories: true)
        try Data("video".utf8).write(to: legacy.appending(path: "start.mp4"))
        try Data("""
            {"id": "\(packID.uuidString)", "title": "Coach", "clips": [{"role": "START", "caption": "Go.", "fileName": "start.mp4"}]}
            """.utf8).write(to: legacy.appending(path: "pack.json"))

        library.migrateActivePack()

        #expect(library.defaultID == MotivatorLibrary.packID(packID))
        #expect(!FileManager.default.fileExists(atPath: legacy.path()))
        let start = library.resolve(.start, goalMotivatorID: nil, pro: false)
        #expect(start.caption == "Go.")
        let url = try #require(start.url)
        #expect(try String(contentsOf: url, encoding: .utf8) == "video")
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

    /// Half a second of AAC, like YellRecorder writes.
    private func recording() throws -> URL {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let url = root.appending(path: "rec-\(UUID().uuidString).m4a")
        let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1)!
        let file = try AVAudioFile(forWriting: url, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44_100,
                                                               AVNumberOfChannelsKey: 1])
        let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 22_050)!
        buffer.frameLength = 22_050
        for frame in 0..<Int(buffer.frameLength) {
            buffer.floatChannelData![0][frame] = sin(Float(frame) * 0.06) * 0.5
        }
        try file.write(from: buffer)
        return url
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
                             videoUrl: URL(string: "https://s3.test/packs/\(pack.id)/\(name)/\(file)?X-Amz-Signature=abc")!,
                             alarmUrl: URL(string: "https://s3.test/packs/\(pack.id)/\(name)/alarm-\(name).caf?X-Amz-Signature=abc")!)
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
