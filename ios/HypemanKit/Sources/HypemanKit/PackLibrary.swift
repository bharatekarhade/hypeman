import Foundation

/// The pack that yells, downloaded into the App Group container. One pack at a time: it lives in `ActivePack/` with a
/// `pack.json` beside its videos, and installing another swaps the whole folder in one move. No folder means the
/// default pack bundled with the app.
public final class PackLibrary: Sendable {
    public static let shared = PackLibrary(root: AppGroup.containerURL ?? .applicationSupportDirectory)

    private let root: URL

    public init(root: URL) {
        self.root = root
    }

    private var activeFolder: URL { root.appending(path: "ActivePack", directoryHint: .isDirectory) }
    private var manifestURL: URL { activeFolder.appending(path: "pack.json") }

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

    /// Downloads all four videos, then makes the pack the active one. If anything fails, the current pack stays.
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
        for item in ClipRole.allCases.compactMap({ role in media.first { $0.role == role } }) {
            let (downloaded, response) = try await session.download(from: item.videoUrl)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else {
                throw APIError(code: "DOWNLOAD_FAILED", message: "Couldn’t download the pack. Please try again.", requestId: nil)
            }
            let fileExtension = item.videoUrl.pathExtension.isEmpty ? "mp4" : item.videoUrl.pathExtension.lowercased()
            let fileName = "\(item.role.rawValue.lowercased()).\(fileExtension)"
            try fileManager.moveItem(at: downloaded, to: staging.appending(path: fileName))
            clips.append(InstalledPack.Clip(role: item.role, caption: item.caption, fileName: fileName))
        }
        let installed = InstalledPack(id: pack.id, title: pack.title, clips: clips)
        try JSONEncoder().encode(installed).write(to: staging.appending(path: "pack.json"))

        if fileManager.fileExists(atPath: activeFolder.path()) {
            _ = try fileManager.replaceItemAt(activeFolder, withItemAt: staging)
        } else {
            try fileManager.moveItem(at: staging, to: activeFolder)
        }
        return installed
    }

    /// Back to the default pack bundled with the app.
    public func useDefault() throws {
        guard FileManager.default.fileExists(atPath: activeFolder.path()) else { return }
        try FileManager.default.removeItem(at: activeFolder)
    }
}
