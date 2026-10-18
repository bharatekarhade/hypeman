import AVFoundation
import OSLog

/// Plays the default pack's clip for a role. Creator packs and recordings come later.
@MainActor
public final class YellPlayer: NSObject, AVAudioPlayerDelegate {
    public static let shared = YellPlayer()

    private var player: AVAudioPlayer?
    private let log = Logger(subsystem: "com.hypeman.app", category: "YellPlayer")

    public func play(_ role: ClipRole) {
        guard let url = Bundle.module.url(forResource: role.rawValue.lowercased(), withExtension: "m4a") else {
            log.error("No default clip for \(role.rawValue)")
            return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, options: .duckOthers)
            try session.setActive(true)
            let player = try AVAudioPlayer(contentsOf: url)
            player.delegate = self
            player.play()
            self.player = player
        } catch {
            log.error("Could not play \(role.rawValue): \(error)")
        }
    }

    public nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }
}
