import Foundation

/// The pack that yells, downloaded into the App Group container. One pack at a time: it lives in `ActivePack/` with a
/// `pack.json` beside its videos, and installing another swaps the whole folder in one move. No folder means the
/// default pack bundled with the app. Its REMINDER and NAG alarms go in `Library/Sounds/`, where iOS looks for
/// notification sounds.
public final class PackLibrary: Sendable {
    public static let shared = PackLibrary(root: AppGroup.containerURL ?? .applicationSupportDirectory)

    private let root: URL

    public init(root: URL) {
        self.root = root
    }

    private var activeFolder: URL { root.appending(path: "ActivePack", directoryHint: .isDirectory) }
    private var manifestURL: URL { activeFolder.appending(path: "pack.json") }
    private var soundsFolder: URL { root.appending(path: "Library/Sounds", directoryHint: .isDirectory) }
    /// Every sound we install starts with this, so old ones can be cleared without touching anything else.
    private static let soundPrefix = "hypeman-"
    /// Notifications only ever use these roles' sounds.
    private static let alarmRoles: Set<ClipRole> = [.reminder, .nag]

    /// The installed pack, or nil for the default pack.
    public var active: InstalledPack? {
        guard let data = try? Data(contentsOf: manifestURL) else { return nil }
        return try? JSONDecoder().decode(InstalledPack.self, from: data)
    }

    /// The installed pack's video for a role, or nil to fall back to the default pack.
    public func videoURL(for role: ClipRole) -> URL? {
        guard let clip = active?.clips.first(where: { $0.role == role }) else { return nil }
        let url = activeFolder.appending(path: clip.fileName)
        return FileManager.default.fileExists(atPath: url.path()) ? url : nil
    }

    /// The installed pack's notification sound for a role, by name, or nil for the system sound.
    public func soundName(for role: ClipRole) -> String? {
        guard let name = active?.clips.first(where: { $0.role == role })?.soundName,
              FileManager.default.fileExists(atPath: soundsFolder.appending(path: name).path()) else { return nil }
        return name
    }

    /// Downloads all four videos and the REMINDER and NAG alarms, then makes the pack the active one. If anything fails,
    /// the current pack stays.
    @discardableResult
    public func install(_ pack: CatalogPack, media: [ClipMedia], session: URLSession = .shared) async throws -> InstalledPack {
        guard Set(media.map(\.role)) == Set(ClipRole.allCases) else {
            throw APIError(code: "PACK_INCOMPLETE", message: "This pack is missing clips. Try another one.", requestId: nil)
        }
        let fileManager = FileManager.default
        let staging = root.appending(path: "Downloads/\(UUID().uuidString)", directoryHint: .isDirectory)
        try fileManager.createDirectory(at: staging, withIntermediateDirectories: true)
        defer { try? fileManager.removeItem(at: staging) }

        var clips: [InstalledPack.Clip] = []
        var alarms: [(file: URL, name: String)] = []
        for item in ClipRole.allCases.compactMap({ role in media.first { $0.role == role } }) {
            let role = item.role.rawValue.lowercased()
            let fileExtension = item.videoUrl.pathExtension.isEmpty ? "mp4" : item.videoUrl.pathExtension.lowercased()
            let fileName = "\(role).\(fileExtension)"
            try await download(item.videoUrl, to: staging.appending(path: fileName), session: session)
            var soundName: String?
            if Self.alarmRoles.contains(item.role), let alarmUrl = item.alarmUrl {
                let file = staging.appending(path: "\(role).caf")
                try await download(alarmUrl, to: file, session: session)
                // A new name per pack: iOS may cache a sound by name.
                let name = "\(Self.soundPrefix)\(pack.id.uuidString.lowercased())-\(role).caf"
                alarms.append((file, name))
                soundName = name
            }
            clips.append(InstalledPack.Clip(role: item.role, caption: item.caption, fileName: fileName, soundName: soundName))
        }
        let installed = InstalledPack(id: pack.id, title: pack.title, clips: clips)
        try JSONEncoder().encode(installed).write(to: staging.appending(path: "pack.json"))

        try removeSounds()
        try fileManager.createDirectory(at: soundsFolder, withIntermediateDirectories: true)
        for alarm in alarms {
            try fileManager.copyItem(at: alarm.file, to: soundsFolder.appending(path: alarm.name))
        }
        if fileManager.fileExists(atPath: activeFolder.path()) {
            _ = try fileManager.replaceItemAt(activeFolder, withItemAt: staging)
        } else {
            try fileManager.moveItem(at: staging, to: activeFolder)
        }
        return installed
    }

    /// Back to the default pack bundled with the app.
    public func useDefault() throws {
        try removeSounds()
        guard FileManager.default.fileExists(atPath: activeFolder.path()) else { return }
        try FileManager.default.removeItem(at: activeFolder)
    }

    private func download(_ url: URL, to file: URL, session: URLSession) async throws {
        let (downloaded, response) = try await session.download(from: url)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw APIError(code: "DOWNLOAD_FAILED", message: "Couldn’t download the pack. Please try again.", requestId: nil)
        }
        try FileManager.default.moveItem(at: downloaded, to: file)
    }

    private func removeSounds() throws {
        let fileManager = FileManager.default
        guard let names = try? fileManager.contentsOfDirectory(atPath: soundsFolder.path()) else { return }
        for name in names where name.hasPrefix(Self.soundPrefix) {
            try fileManager.removeItem(at: soundsFolder.appending(path: name))
        }
    }
}
