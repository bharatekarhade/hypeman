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
    /// The App Store product a paid pack is sold as; nil for a free pack.
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
