import AVFoundation
import HypemanKit
import OSLog
import SwiftUI

/// Records a yell for one of the four roles and sends it to friends (FD-02). Audio only, up to 10 seconds, with its line
/// typed out as the caption.
struct RecordYellSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var friends = Friends.shared
    @State private var recorder = YellRecorder()
    @State private var role = ClipRole.start
    @State private var caption = ""
    @State private var recipients: Set<UUID> = []
    @State private var sending = false
    @State private var showPaywall = false
    @State private var failure: String?

    private let log = Logger(subsystem: "com.hypeman.app", category: "RecordYellSheet")

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .firstTextBaseline) {
                    Text("RECORD A YELL")
                        .font(Theme.display(36))
                        .accessibilityAddTraits(.isHeader)
                    Spacer()
                    Button("Cancel") { dismiss() }
                        .font(Theme.text(16, weight: .bold))
                        .foregroundStyle(Theme.ink)
                        .frame(minHeight: 44)
                }
                VStack(alignment: .leading, spacing: 8) {
                    SectionLabel("WHEN IT PLAYS")
                    Picker("When it plays", selection: $role) {
                        ForEach(ClipRole.allCases, id: \.self) { role in
                            Text(role.title).tag(role)
                        }
                    }
                    .pickerStyle(.segmented)
                    Text(role.hint)
                        .font(Theme.text(14, relativeTo: .footnote))
                        .foregroundStyle(Theme.muted)
                }
                VStack(alignment: .leading, spacing: 8) {
                    SectionLabel("WHAT YOU’RE YELLING")
                    TextField("Word for word, for the caption", text: $caption, axis: .vertical)
                        .font(Theme.text(17, weight: .semibold))
                        .lineLimit(1...3)
                        .padding(14)
                        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
                        .onChange(of: caption) { _, text in
                            if text.count > 120 { caption = String(text.prefix(120)) }
                        }
                }
                recordControl
                VStack(alignment: .leading, spacing: 8) {
                    SectionLabel("SEND TO")
                    ForEach(friends.friends, id: \.id) { friend in
                        Button { toggle(friend.id) } label: {
                            HStack {
                                Text(friend.displayName ?? "Friend")
                                    .font(Theme.text(17, weight: .bold))
                                Spacer()
                                Image(systemName: recipients.contains(friend.id) ? "checkmark.circle.fill" : "circle")
                                    .font(.system(size: 22))
                                    .foregroundStyle(recipients.contains(friend.id) ? Theme.accent : Theme.muted)
                            }
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(recipients.contains(friend.id) ? .isSelected : [])
                    }
                }
                Button(action: send) {
                    Group {
                        if sending {
                            ProgressView()
                        } else {
                            Text("SEND")
                                .font(Theme.display(22, relativeTo: .headline))
                                .kerning(1.3)
                        }
                    }
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .background(Theme.accent, in: RoundedRectangle(cornerRadius: 14))
                    .foregroundStyle(Theme.onAccent)
                }
                .buttonStyle(.plain)
                .disabled(!canSend)
                .opacity(canSend ? 1 : 0.5)
                if let left = friends.sendsLeft {
                    Text("\(left) free \(left == 1 ? "yell" : "yells") left this month. Pro sends as many as you like.")
                        .font(Theme.text(13, relativeTo: .footnote))
                        .foregroundStyle(Theme.muted)
                }
            }
            .padding(EdgeInsets(top: 24, leading: 20, bottom: 24, trailing: 20))
        }
        .background(Theme.background)
        .foregroundStyle(Theme.ink)
        .presentationDetents([.large])
        .interactiveDismissDisabled(sending)
        .onDisappear { recorder.discard() }
        .sheet(isPresented: $showPaywall) { PaywallSheet(headline: "Yell at friends as much as you like.") }
        .alert("Something went wrong", isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(failure ?? "")
        }
    }

    @ViewBuilder
    private var recordControl: some View {
        VStack(spacing: 12) {
            switch recorder.state {
            case .idle:
                Button { Task { await startRecording() } } label: {
                    Label("RECORD", systemImage: "mic.fill")
                        .font(Theme.display(22, relativeTo: .headline))
                        .kerning(1.3)
                        .frame(maxWidth: .infinity, minHeight: 64)
                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.ink, lineWidth: 1.5))
                }
                .buttonStyle(.plain)
            case .recording(let started):
                Button { recorder.stop() } label: {
                    TimelineView(.periodic(from: started, by: 0.1)) { context in
                        let elapsed = min(context.date.timeIntervalSince(started), YellRecorder.maxDuration)
                        Label(String(format: "STOP · %.0f / 10 s", elapsed.rounded(.down)), systemImage: "stop.fill")
                            .font(Theme.display(22, relativeTo: .headline))
                            .kerning(1.3)
                            .monospacedDigit()
                    }
                    .frame(maxWidth: .infinity, minHeight: 64)
                    .background(Color.red, in: RoundedRectangle(cornerRadius: 14))
                    .foregroundStyle(.white)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Stop recording")
            case .recorded(let url, let duration):
                HStack(spacing: 12) {
                    Button { ClipPreview.shared.play(url) } label: {
                        Label(String(format: "PLAY · %.1f s", duration), systemImage: "play.fill")
                            .font(Theme.display(20, relativeTo: .headline))
                            .frame(maxWidth: .infinity, minHeight: 56)
                            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14))
                    }
                    .buttonStyle(.plain)
                    Button { recorder.discard() } label: {
                        Label("REDO", systemImage: "arrow.counterclockwise")
                            .font(Theme.display(20, relativeTo: .headline))
                            .frame(minWidth: 110, minHeight: 56)
                            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.line))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var canSend: Bool {
        if case .recorded = recorder.state {
            return !sending && !recipients.isEmpty && !caption.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        return false
    }

    private func toggle(_ id: UUID) {
        if recipients.contains(id) {
            recipients.remove(id)
        } else {
            recipients.insert(id)
        }
    }

    private func startRecording() async {
        do {
            try await recorder.start()
        } catch {
            failure = (error as? LocalizedError)?.errorDescription ?? "Couldn’t start recording."
        }
    }

    private func send() {
        guard case .recorded(let url, _) = recorder.state else { return }
        sending = true
        Task {
            defer { sending = false }
            do {
                try await friends.send(recording: url, role: role, caption: caption.trimmingCharacters(in: .whitespacesAndNewlines),
                                       to: Array(recipients))
                dismiss()
            } catch let error as APIError where error.code == "SEND_CAP_REACHED" {
                showPaywall = true
            } catch {
                log.error("Could not send a yell: \(error)")
                failure = (error as? APIError)?.message ?? "The yell didn’t send. Check your connection and try again."
            }
        }
    }
}

/// The microphone, recording AAC into a temporary .m4a: what the server takes as a yell's audio.
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

/// Plays a recording or a friend's yell, local or streamed.
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
    var title: String {
        switch self {
        case .start: "Start"
        case .reminder: "Reminder"
        case .nag: "Nag"
        case .congrats: "Congrats"
        }
    }

    /// From the clip format: when it plays and how it should sound.
    var hint: String {
        switch self {
        case .start: "Plays when they hit DO IT. Punchy: “Get up. Do it. Now.”"
        case .reminder: "Plays at the goal’s reminder time. Firm: “It’s time. You said you’d do this.”"
        case .nag: "Plays when they ignore a reminder. Angrier: “You snoozed again? Really?”"
        case .congrats: "Plays when they finish. Celebrate."
        }
    }
}
