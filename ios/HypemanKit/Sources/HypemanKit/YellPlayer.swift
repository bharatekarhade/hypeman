import AVFoundation
import Observation
import OSLog

/// Plays the clip for a role from the goal's motivator (see MotivatorLibrary.resolve). With no screen showing it, a video
/// just plays its sound, so the widget's DO IT yells too.
@MainActor
@Observable
public final class YellPlayer {
    public static let shared = YellPlayer()

    /// The DO IT screen shows this player's picture while a pack video plays.
    @ObservationIgnored public let player = AVPlayer()
    /// What's playing, or played last, so the screens show its caption and whose voice it is. With shuffle on, only
    /// this knows which motivator was picked.
    public private(set) var nowPlaying: ResolvedClip?

    @ObservationIgnored private var endObserver: (any NSObjectProtocol)?
    @ObservationIgnored private let log = Logger(subsystem: "com.hypeman.app", category: "YellPlayer")

    /// @param motivatorID the goal's own motivator, if it has one
    public func play(_ role: ClipRole, motivatorID: String? = nil) {
        let clip = MotivatorLibrary.shared.resolve(role, goalMotivatorID: motivatorID, pro: ProAccess.isActive())
        nowPlaying = clip
        guard let url = clip.url else {
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
