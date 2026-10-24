import Foundation

/// A published pack as the store lists it, from `GET /v1/packs`. No media links: those are signed on demand.
public struct CatalogPack: Codable, Identifiable, Hashable, Sendable {
    public let id: UUID
    public let slug: String
    public let title: String
    public let description: String
    public let isExplicit: Bool
    public let isFree: Bool
    /// In role order: START, REMINDER, NAG, CONGRATS.
    public let clips: [CatalogClip]
    /// The App Store product a paid pack is sold as. Pro unlocks it too.
    public var productId: String? = nil
}

public struct CatalogClip: Codable, Hashable, Sendable {
    public let role: ClipRole
    /// The spoken line, shown on screen while the clip plays.
    public let caption: String
}

/// One clip's signed download links, from `POST /v1/packs/{id}/media`. They expire within the hour.
public struct ClipMedia: Codable, Hashable, Sendable {
    public let role: ClipRole
    public let caption: String
    public let videoUrl: URL
    /// The notification sound, or nil for a clip processed before the server made them.
    public var alarmUrl: URL?
}

/// The pack on this phone that yells, as saved next to its videos.
public struct InstalledPack: Codable, Equatable, Sendable {
    public let id: UUID
    public let title: String
    public let clips: [Clip]

    public struct Clip: Codable, Equatable, Sendable {
        public let role: ClipRole
        public let caption: String
        /// The video's name inside the pack's folder.
        public let fileName: String
        /// The notification sound's name in the App Group's Library/Sounds, for REMINDER and NAG clips that have one.
        public var soundName: String?
    }

    public func caption(for role: ClipRole) -> String? {
        clips.first { $0.role == role }?.caption
    }
}
