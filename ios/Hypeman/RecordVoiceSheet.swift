import HypemanKit
import OSLog
import SwiftUI

/// The user's own voice (MV-05, Pro): a recording for any of the four moments, each up to 10 seconds with its line. Moments
/// left empty fall back to the default pack (MV-04). Saving makes it the default voice.
struct RecordVoiceSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var title = "My voice"
    @State private var recorders: [ClipRole: YellRecorder] = Dictionary(uniqueKeysWithValues: ClipRole.allCases.map { ($0, YellRecorder()) })
    @State private var captions: [ClipRole: String] = [:]
    @State private var failure: String?

    private let log = Logger(subsystem: "com.hypeman.app", category: "RecordVoiceSheet")

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .firstTextBaseline) {
                    Text("YOUR VOICE")
                        .font(Theme.display(36))
                        .accessibilityAddTraits(.isHeader)
                    Spacer()
                    Button("Cancel") { dismiss() }
                        .font(Theme.text(16, weight: .bold))
                        .foregroundStyle(Theme.ink)
                        .frame(minHeight: 44)
                }
                VStack(alignment: .leading, spacing: 8) {
                    SectionLabel("NAME")
                    TextField("My voice", text: $title)
                        .font(Theme.text(17, weight: .semibold))
                        .padding(14)
                        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
                }
                Text("Record any of the four. The ones you skip use the default pack.")
                    .font(Theme.text(14, relativeTo: .footnote))
                    .foregroundStyle(Theme.muted)
                ForEach(ClipRole.allCases, id: \.self) { role in
                    MomentRecorder(role: role, recorder: recorders[role]!, caption: Binding(
                        get: { captions[role] ?? "" },
                        set: { captions[role] = String($0.prefix(120)) }
                    )) { message in
                        failure = message
                    }
                }
                Button(action: save) {
                    Text("SAVE")
                        .font(Theme.display(22, relativeTo: .headline))
                        .kerning(1.3)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(Theme.accent, in: RoundedRectangle(cornerRadius: 14))
                        .foregroundStyle(Theme.onAccent)
                }
                .buttonStyle(.plain)
                .disabled(ready.isEmpty)
                .opacity(ready.isEmpty ? 0.5 : 1)
            }
            .padding(EdgeInsets(top: 24, leading: 20, bottom: 24, trailing: 20))
        }
        .background(Theme.background)
        .foregroundStyle(Theme.ink)
        .presentationDetents([.large])
        .onDisappear {
            recorders.values.forEach { $0.discard() }
        }
        .alert("Something went wrong", isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(failure ?? "")
        }
    }

    /// The moments with a recording and a line.
    private var ready: [(role: ClipRole, caption: String, audio: URL)] {
        ClipRole.allCases.compactMap { role in
            let caption = (captions[role] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard case .recorded(let url, _) = recorders[role]?.state, !caption.isEmpty else { return nil }
            return (role, caption, url)
        }
    }

    private func save() {
        let name = title.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            let library = MotivatorLibrary.shared
            let voice = try library.saveRecording(title: name.isEmpty ? "My voice" : String(name.prefix(40)), clips: ready)
            library.defaultID = voice.id
            dismiss()
        } catch {
            log.error("Could not save the recording: \(error)")
            failure = "Couldn’t save your voice. Please try again."
        }
    }
}

/// Record, play back or redo one moment's clip, with its line.
private struct MomentRecorder: View {
    let role: ClipRole
    let recorder: YellRecorder
    @Binding var caption: String
    let fail: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(role.title.uppercased())
                .font(Theme.display(20, relativeTo: .headline))
                .kerning(1.2)
            Text(role.hint)
                .font(Theme.text(13, relativeTo: .footnote))
                .foregroundStyle(Theme.muted)
            TextField("The line, word for word", text: $caption)
                .font(Theme.text(16, weight: .semibold))
                .padding(12)
                .background(Theme.background, in: RoundedRectangle(cornerRadius: 10))
            HStack(spacing: 10) {
                switch recorder.state {
                case .idle:
                    Button {
                        Task {
                            do {
                                try await recorder.start()
                            } catch {
                                fail((error as? LocalizedError)?.errorDescription ?? "Couldn’t start recording.")
                            }
                        }
                    } label: {
                        Label("Record", systemImage: "mic.fill")
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.ink))
                    }
                case .recording(let started):
                    Button { recorder.stop() } label: {
                        TimelineView(.periodic(from: started, by: 0.1)) { context in
                            let elapsed = min(context.date.timeIntervalSince(started), YellRecorder.maxDuration)
                            Label(String(format: "Stop · %.0f / 10 s", elapsed.rounded(.down)), systemImage: "stop.fill")
                                .monospacedDigit()
                        }
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(Color.red, in: RoundedRectangle(cornerRadius: 10))
                        .foregroundStyle(.white)
                    }
                case .recorded(let url, let duration):
                    Button { ClipPreview.shared.play(url) } label: {
                        Label(String(format: "Play · %.1f s", duration), systemImage: "play.fill")
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(Theme.background, in: RoundedRectangle(cornerRadius: 10))
                    }
                    Button { recorder.discard() } label: {
                        Label("Redo", systemImage: "arrow.counterclockwise")
                            .frame(minWidth: 96, minHeight: 44)
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.line))
                    }
                }
            }
            .font(Theme.text(15, weight: .bold, relativeTo: .subheadline))
            .buttonStyle(.plain)
        }
        .padding(16)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 18))
    }
}
