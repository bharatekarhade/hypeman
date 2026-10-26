import AVFoundation
import Foundation

/// A voice that yells: up to four clips, one per role (MV-01). The built-in default pack, a pack from the store, the
/// user's own recording, or a friend's yell they saved.
public struct Motivator: Codable, Identifiable, Equatable, Sendable {
    public enum Kind: String, Codable, Sendable {
        case builtIn, pack, recording, yell
    }

    public static let builtInID = "default"

    /// "default", "pack:<uuid>", "mine:<uuid>" or "yell:<uuid>": what a goal stores as its motivatorID.
    public let id: String
    public let kind: Kind
    public let title: String
    /// Who it's from, for a saved yell.
    public var subtitle: String?
    public let clips: [Clip]

    public struct Clip: Codable, Equatable, Sendable {
        public let role: ClipRole
        /// The spoken line, shown on screen while it plays and as the notification's text.
        public let caption: String
        /// Inside the motivator's folder (or the Kit's bundle, for the built-in one).
        public let fileName: String
        /// The notification sound's name in the App Group's Library/Sounds, for REMINDER and NAG clips that have one.
        public var soundName: String?

        public var isVideo: Bool {
            ["mp4", "mov", "m4v"].contains((fileName as NSString).pathExtension.lowercased())
        }
    }

    public func clip(for role: ClipRole) -> Clip? {
        clips.first { $0.role == role }
    }

    /// The user's own recordings are Pro (MV-05); when Pro lapses they stay on the phone but stop yelling (PY-06).
    public var needsPro: Bool {
        kind == .recording
    }

    static let builtIn = Motivator(id: builtInID, kind: .builtIn, title: "Default pack", clips: [
        Clip(role: .start, caption: "Get up. Do it. Now.", fileName: "start.m4a"),
        Clip(role: .reminder, caption: "Time to do it.", fileName: "reminder.m4a"),
        Clip(role: .nag, caption: "Still not started. Get up.", fileName: "nag.m4a"),
        Clip(role: .congrats, caption: "That’s what I’m talking about.", fileName: "congrats.m4a"),
    ])
}

/// The clip that plays for a role, after the goal's motivator, the default, shuffle and the fallback have had their say.
public struct ResolvedClip: Equatable, Sendable {
    public let motivatorID: String
    public let motivatorTitle: String
    public let role: ClipRole
    public let caption: String
    /// Nil only if the app bundle is missing the built-in clip.
    public let url: URL?
    public let isVideo: Bool
    public let soundName: String?
}

/// Every motivator on the phone, in the App Group container so the widget's DO IT can play them: `Motivators/<id>/`
/// holds each one's files beside a `motivator.json`. Notification sounds go in `Library/Sounds/`, where iOS looks for
/// them, named `hypeman-…` so they can be cleared without touching anything else. The user's default motivator and
/// shuffle setting live in the App Group's defaults.
public final class MotivatorLibrary: Sendable {
    public static let shared = MotivatorLibrary(root: AppGroup.containerURL ?? .applicationSupportDirectory, defaultsSuite: AppGroup.identifier)

    private let root: URL
    private let defaultsSuite: String
    private static let defaultKey = "Motivators.default"
    private static let shuffleKey = "Motivators.shuffle"
    private static let soundPrefix = "hypeman-"
    /// Notifications only ever use these roles' sounds.
    private static let alarmRoles: Set<ClipRole> = [.reminder, .nag]

    public init(root: URL, defaultsSuite: String) {
        self.root = root
        self.defaultsSuite = defaultsSuite
    }

    private var defaults: UserDefaults? { UserDefaults(suiteName: defaultsSuite) }
    private var foldersURL: URL { root.appending(path: "Motivators", directoryHint: .isDirectory) }
    private var soundsFolder: URL { root.appending(path: "Library/Sounds", directoryHint: .isDirectory) }

    // MARK: Reading

    /// The built-in pack first, then packs, the user's recordings and saved yells, each newest first.
    public var all: [Motivator] {
        let folders = (try? FileManager.default.contentsOfDirectory(at: foldersURL, includingPropertiesForKeys: [.creationDateKey])) ?? []
        let loaded = folders
            .compactMap { folder -> (Motivator, Date)? in
                guard let data = try? Data(contentsOf: folder.appending(path: "motivator.json")),
                      let motivator = try? JSONDecoder().decode(Motivator.self, from: data) else { return nil }
                let created = (try? folder.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast
                return (motivator, created)
            }
            .sorted { $0.1 > $1.1 }
            .map(\.0)
        let order: [Motivator.Kind] = [.pack, .recording, .yell]
        return [Motivator.builtIn] + order.flatMap { kind in loaded.filter { $0.kind == kind } }
    }

    public func motivator(_ id: String) -> Motivator? {
        id == Motivator.builtInID ? .builtIn : load(id)
    }

    /// The motivator goals without their own use (MV-03).
    public var defaultID: String {
        get { defaults?.string(forKey: Self.defaultKey).flatMap { motivator($0) != nil ? $0 : nil } ?? Motivator.builtInID }
        set { defaults?.set(newValue, forKey: Self.defaultKey) }
    }

    /// A random motivator on each play (MV-06, Pro).
    public var shuffle: Bool {
        get { defaults?.bool(forKey: Self.shuffleKey) ?? false }
        set { defaults?.set(newValue, forKey: Self.shuffleKey) }
    }

    /// The motivator that yells for a goal: its own (GL-08) with Pro, else the default; with shuffle, any of them. A
    /// motivator without a clip for the role falls back to the built-in one's (MV-04).
    public func resolve(_ role: ClipRole, goalMotivatorID: String?, pro: Bool,
                        pick: ([Motivator]) -> Motivator? = { $0.randomElement() }) -> ResolvedClip {
        let usable = { (motivator: Motivator) in pro || !motivator.needsPro }
        var chosen: Motivator?
        if pro && shuffle {
            chosen = pick(all.filter { usable($0) && $0.clip(for: role) != nil })
        }
        if chosen == nil, pro, let id = goalMotivatorID {
            chosen = motivator(id).flatMap { usable($0) ? $0 : nil }
        }
        if chosen == nil {
            chosen = motivator(defaultID).flatMap { usable($0) ? $0 : nil }
        }
        let motivator = chosen.flatMap { $0.clip(for: role) != nil ? $0 : nil } ?? .builtIn
        let clip = motivator.clip(for: role)!
        return ResolvedClip(motivatorID: motivator.id, motivatorTitle: motivator.title, role: role, caption: clip.caption,
                            url: url(of: clip, in: motivator), isVideo: clip.isVideo, soundName: sound(of: clip))
    }

    public func url(of clip: Motivator.Clip, in motivator: Motivator) -> URL? {
        if motivator.kind == .builtIn {
            let name = clip.fileName as NSString
            return Bundle.module.url(forResource: name.deletingPathExtension, withExtension: name.pathExtension)
        }
        let url = folder(motivator.id).appending(path: clip.fileName)
        return FileManager.default.fileExists(atPath: url.path()) ? url : nil
    }

    /// Whether a store pack is already downloaded.
    public func hasPack(_ packID: UUID) -> Bool {
        load(Self.packID(packID)) != nil
    }

    public static func packID(_ packID: UUID) -> String {
        "pack:\(packID.uuidString.lowercased())"
    }

    // MARK: Adding and removing

    /// Downloads all four videos and the REMINDER and NAG alarms into the library. If anything fails, nothing changes.
    /// Installing a pack that's already there replaces it.
    @discardableResult
    public func install(_ pack: CatalogPack, media: [ClipMedia], session: URLSession = .shared) async throws -> Motivator {
        guard Set(media.map(\.role)) == Set(ClipRole.allCases) else {
            throw APIError(code: "PACK_INCOMPLETE", message: "This pack is missing clips. Try another one.", requestId: nil)
        }
        let id = Self.packID(pack.id)
        let staging = try makeStaging()
        defer { try? FileManager.default.removeItem(at: staging) }
        var clips: [Motivator.Clip] = []
        var sounds: [(file: URL, name: String)] = []
        for item in ClipRole.allCases.compactMap({ role in media.first { $0.role == role } }) {
            let role = item.role.rawValue.lowercased()
            let fileExtension = item.videoUrl.pathExtension.isEmpty ? "mp4" : item.videoUrl.pathExtension.lowercased()
            let fileName = "\(role).\(fileExtension)"
            try await download(item.videoUrl, to: staging.appending(path: fileName), session: session)
            var soundName: String?
            if Self.alarmRoles.contains(item.role), let alarmUrl = item.alarmUrl {
                let file = staging.appending(path: "\(role).caf")
                try await download(alarmUrl, to: file, session: session)
                // A name per pack: iOS may cache a sound by name.
                soundName = "\(Self.soundPrefix)\(pack.id.uuidString.lowercased())-\(role).caf"
                sounds.append((file, soundName!))
            }
            clips.append(Motivator.Clip(role: item.role, caption: item.caption, fileName: fileName, soundName: soundName))
        }
        let motivator = Motivator(id: id, kind: .pack, title: pack.title, clips: clips)
        try commit(motivator, staging: staging, sounds: sounds)
        return motivator
    }

    /// A friend's yell, kept as a one-clip motivator (FD-03). Saving it again changes nothing.
    @discardableResult
    public func saveYell(_ item: InboxItem, session: URLSession = .shared) async throws -> Motivator {
        let id = "yell:\(item.yellId.uuidString.lowercased())"
        if let saved = load(id) {
            return saved
        }
        let staging = try makeStaging()
        defer { try? FileManager.default.removeItem(at: staging) }
        let audio = staging.appending(path: "\(item.role.rawValue.lowercased()).m4a")
        try await download(item.audioUrl, to: audio, session: session)
        let (clip, sounds) = try clipWithSound(role: item.role, caption: item.caption, audio: audio, motivatorKey: "yell-\(item.yellId.uuidString.lowercased())")
        let motivator = Motivator(id: id, kind: .yell, title: "“\(item.caption)”",
                                  subtitle: "\(item.sender?.displayName ?? "A friend") · \(item.role.title)", clips: [clip])
        try commit(motivator, staging: staging, sounds: sounds)
        return motivator
    }

    /// The user's own recording (MV-05): any of the four roles, each an audio file this takes over.
    @discardableResult
    public func saveRecording(title: String, clips recordings: [(role: ClipRole, caption: String, audio: URL)]) throws -> Motivator {
        let key = UUID().uuidString.lowercased()
        let staging = try makeStaging()
        defer { try? FileManager.default.removeItem(at: staging) }
        var clips: [Motivator.Clip] = []
        var sounds: [(file: URL, name: String)] = []
        for recording in recordings.sorted(by: { ClipRole.allCases.firstIndex(of: $0.role)! < ClipRole.allCases.firstIndex(of: $1.role)! }) {
            let audio = staging.appending(path: "\(recording.role.rawValue.lowercased()).m4a")
            try FileManager.default.moveItem(at: recording.audio, to: audio)
            let (clip, clipSounds) = try clipWithSound(role: recording.role, caption: recording.caption, audio: audio, motivatorKey: "mine-\(key)")
            clips.append(clip)
            sounds += clipSounds
        }
        let motivator = Motivator(id: "mine:\(key)", kind: .recording, title: title, clips: clips)
        try commit(motivator, staging: staging, sounds: sounds)
        return motivator
    }

    /// Deletes its files and sounds. If it was the default, the built-in pack takes over.
    public func remove(_ id: String) throws {
        guard let motivator = load(id) else { return }
        for name in motivator.clips.compactMap(\.soundName) {
            try? FileManager.default.removeItem(at: soundsFolder.appending(path: name))
        }
        try FileManager.default.removeItem(at: folder(id))
        if defaults?.string(forKey: Self.defaultKey) == id {
            defaultID = Motivator.builtInID
        }
    }

    /// Before motivators, one pack at a time lived in `ActivePack/`. It becomes a library pack and stays the default.
    public func migrateActivePack() {
        let legacy = root.appending(path: "ActivePack", directoryHint: .isDirectory)
        guard let data = try? Data(contentsOf: legacy.appending(path: "pack.json")),
              let pack = try? JSONDecoder().decode(LegacyPack.self, from: data) else { return }
        let motivator = Motivator(id: Self.packID(pack.id), kind: .pack, title: pack.title,
                                  clips: pack.clips.map { Motivator.Clip(role: $0.role, caption: $0.caption, fileName: $0.fileName, soundName: $0.soundName) })
        do {
            try FileManager.default.removeItem(at: legacy.appending(path: "pack.json"))
            try JSONEncoder().encode(motivator).write(to: legacy.appending(path: "motivator.json"))
            try FileManager.default.createDirectory(at: foldersURL, withIntermediateDirectories: true)
            try? FileManager.default.removeItem(at: folder(motivator.id))
            try FileManager.default.moveItem(at: legacy, to: folder(motivator.id))
            defaultID = motivator.id
        } catch {
            try? FileManager.default.removeItem(at: legacy)
        }
    }

    // MARK: Files

    private func folder(_ id: String) -> URL {
        foldersURL.appending(path: id.replacingOccurrences(of: ":", with: "-"), directoryHint: .isDirectory)
    }

    private func load(_ id: String) -> Motivator? {
        guard let data = try? Data(contentsOf: folder(id).appending(path: "motivator.json")) else { return nil }
        return try? JSONDecoder().decode(Motivator.self, from: data)
    }

    private func sound(of clip: Motivator.Clip) -> String? {
        guard let name = clip.soundName, FileManager.default.fileExists(atPath: soundsFolder.appending(path: name).path()) else { return nil }
        return name
    }

    private func makeStaging() throws -> URL {
        let staging = root.appending(path: "Downloads/\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        return staging
    }

    /// A REMINDER or NAG recording also becomes a notification sound, since iOS can't play AAC as one.
    private func clipWithSound(role: ClipRole, caption: String, audio: URL,
                               motivatorKey: String) throws -> (Motivator.Clip, [(file: URL, name: String)]) {
        guard Self.alarmRoles.contains(role) else {
            return (Motivator.Clip(role: role, caption: caption, fileName: audio.lastPathComponent), [])
        }
        let sound = audio.deletingPathExtension().appendingPathExtension("caf")
        try NotificationSound.make(from: audio, to: sound)
        let name = "\(Self.soundPrefix)\(motivatorKey)-\(role.rawValue.lowercased()).caf"
        return (Motivator.Clip(role: role, caption: caption, fileName: audio.lastPathComponent, soundName: name), [(sound, name)])
    }

    /// Writes motivator.json, puts the sounds in place and swaps the folder in with one move.
    private func commit(_ motivator: Motivator, staging: URL, sounds: [(file: URL, name: String)]) throws {
        let fileManager = FileManager.default
        try JSONEncoder().encode(motivator).write(to: staging.appending(path: "motivator.json"))
        try fileManager.createDirectory(at: soundsFolder, withIntermediateDirectories: true)
        for sound in sounds {
            let target = soundsFolder.appending(path: sound.name)
            try? fileManager.removeItem(at: target)
            try fileManager.moveItem(at: sound.file, to: target)
        }
        try fileManager.createDirectory(at: foldersURL, withIntermediateDirectories: true)
        let target = folder(motivator.id)
        if fileManager.fileExists(atPath: target.path()) {
            _ = try fileManager.replaceItemAt(target, withItemAt: staging)
        } else {
            try fileManager.moveItem(at: staging, to: target)
        }
    }

    private func download(_ url: URL, to file: URL, session: URLSession) async throws {
        let (downloaded, response) = try await session.download(from: url)
        if let status = (response as? HTTPURLResponse)?.statusCode, status != 200 {
            throw APIError(code: "DOWNLOAD_FAILED", message: "The download didn’t finish. Please try again.", requestId: nil)
        }
        try FileManager.default.moveItem(at: downloaded, to: file)
    }

    /// `ActivePack/pack.json` as it was written before motivators.
    private struct LegacyPack: Decodable {
        struct Clip: Decodable {
            let role: ClipRole
            let caption: String
            let fileName: String
            let soundName: String?
        }

        let id: UUID
        let title: String
        let clips: [Clip]
    }
}

/// Notification sounds must be linear PCM (or a few other uncompressed formats) in a .caf, .aiff or .wav file, and 30
/// seconds or less; every clip is at most 10.
enum NotificationSound {
    static func make(from source: URL, to target: URL) throws {
        let input = try AVAudioFile(forReading: source)
        let format = input.processingFormat
        let output = try AVAudioFile(forWriting: target, settings: [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: format.sampleRate,
            AVNumberOfChannelsKey: format.channelCount,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
        ], commonFormat: format.commonFormat, interleaved: format.isInterleaved)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 16_384) else { return }
        while input.framePosition < input.length {
            try input.read(into: buffer)
            if buffer.frameLength == 0 { break }
            try output.write(from: buffer)
        }
    }
}

public extension ClipRole {
    var title: String {
        switch self {
        case .start: "Start"
        case .reminder: "Reminder"
        case .nag: "Nag"
        case .congrats: "Congrats"
        }
    }
}
