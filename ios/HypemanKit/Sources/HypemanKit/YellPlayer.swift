import AVFoundation
import OSLog

/// Plays the clip for a role: the installed pack's video, or the default pack's audio when there's no pack or it lacks
/// that role. With no screen showing it, a video just plays its sound, so the widget's DO IT yells too.
@MainActor
public final class YellPlayer {
    public static let shared = YellPlayer()

    /// The DO IT screen shows this player's picture while a pack video plays.
    public let player = AVPlayer()

    private var endObserver: (any NSObjectProtocol)?
    private let log = Logger(subsystem: "com.hypeman.app", category: "YellPlayer")

    public func play(_ role: ClipRole) {
        guard let url = PackLibrary.shared.videoURL(for: role)
            ?? Bundle.module.url(forResource: role.rawValue.lowercased(), withExtension: "m4a") else {
            log.error("No clip for \(role.rawValue)")
            return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, options: .duckOthers)
            try session.setActive(true)
        } catch {
            log.error("Could not start audio for \(role.rawValue): \(error)")
            return
        }
        let item = AVPlayerItem(url: url)
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
        }
        endObserver = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: .main) { _ in
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
        player.replaceCurrentItem(with: item)
        player.play()
    }
}
