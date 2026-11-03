import AVFoundation
import HypemanKit
import SwiftUI

/// The microphone, recording AAC into a temporary .m4a, up to 10 seconds: one clip of the user's own voice.
@MainActor
@Observable
final class YellRecorder {
    enum State: Equatable {
        case idle
        case recording(started: Date)
        case recorded(URL, duration: TimeInterval)
    }

    static let maxDuration: TimeInterval = 10
    /// Shorter is a slip of the finger, not a yell.
    private static let minDuration: TimeInterval = 0.5

    private(set) var state = State.idle
    @ObservationIgnored private var recorder: AVAudioRecorder?
    @ObservationIgnored private var limit: Task<Void, Never>?

    func start() async throws {
        guard await AVAudioApplication.requestRecordPermission() else {
            throw RecorderError("Hypeman needs the microphone to record. Allow it in Settings → Hypeman.")
        }
        discard()
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
        try session.setActive(true)
        let url = FileManager.default.temporaryDirectory.appending(path: "yell-\(UUID().uuidString).m4a")
        let recorder = try AVAudioRecorder(url: url, settings: [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 44_100,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
        ])
        guard recorder.record(forDuration: Self.maxDuration) else {
            throw RecorderError("Couldn’t start recording. Try again.")
        }
        self.recorder = recorder
        state = .recording(started: .now)
        limit = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.maxDuration))
            guard !Task.isCancelled else { return }
            self?.stop()
        }
    }

    func stop() {
        guard let recorder, case .recording = state else { return }
        let duration = recorder.currentTime
        recorder.stop()
        limit?.cancel()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        if duration < Self.minDuration {
            discard()
        } else {
            state = .recorded(recorder.url, duration: min(duration, Self.maxDuration))
        }
    }

    func discard() {
        limit?.cancel()
        recorder?.stop()
        if let url = recorder?.url {
            try? FileManager.default.removeItem(at: url)
        }
        recorder = nil
        state = .idle
    }
}

private struct RecorderError: LocalizedError {
    let errorDescription: String?

    init(_ message: String) {
        errorDescription = message
    }
}

/// Plays a recording or a store pack's clip, local or streamed.
@MainActor
final class ClipPreview {
    static let shared = ClipPreview()

    private let player = AVPlayer()

    func play(_ url: URL) {
        try? AVAudioSession.sharedInstance().setCategory(.playback, options: .duckOthers)
        try? AVAudioSession.sharedInstance().setActive(true)
        player.replaceCurrentItem(with: AVPlayerItem(url: url))
        player.play()
    }
}

extension ClipRole {
    /// From the clip format: when it plays and how it should sound.
    var hint: String {
        switch self {
        case .start: "Plays when you hit DO IT. Punchy: “Get up. Do it. Now.”"
        case .reminder: "Plays at the goal’s reminder time. Firm: “It’s time. You said you’d do this.”"
        case .nag: "Plays when you ignore a reminder. Angrier: “You snoozed again? Really?”"
        case .congrats: "Plays when you finish. Celebrate."
        }
    }
}
