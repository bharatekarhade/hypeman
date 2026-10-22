import HypemanKit
import OSLog
import SwiftUI

/// Picks who yells: the bundled default pack or a free pack from the store, downloaded whole before it takes over.
struct PacksView: View {
    @Binding var active: InstalledPack?
    @Environment(\.dismiss) private var dismiss
    @State private var phase = Phase.loading
    @State private var installing: UUID?
    @State private var failure: String?

    private let log = Logger(subsystem: "com.hypeman.app", category: "PacksView")

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                header
                    .padding(.bottom, 8)
                PackCard(
                    title: "Default pack",
                    description: "Hypeman’s own voice. Always on your phone.",
                    line: "GET UP. DO IT. NOW.",
                    isExplicit: false,
                    state: active == nil ? .using : .available,
                    use: useDefault
                )
                switch phase {
                case .loading:
                    ProgressView()
                        .frame(maxWidth: .infinity, minHeight: 120)
                case .failed(let message):
                    VStack(spacing: 12) {
                        Text(message)
                            .font(Theme.text(15, relativeTo: .subheadline))
                            .foregroundStyle(Theme.muted)
                            .multilineTextAlignment(.center)
                        Button("Try again") { Task { await load() } }
                            .font(Theme.text(15, weight: .bold, relativeTo: .subheadline))
                            .foregroundStyle(Theme.ink)
                            .padding(.horizontal, 16)
                            .frame(minHeight: 44)
                            .overlay(Capsule().stroke(Theme.line))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
                case .loaded(let packs):
                    ForEach(packs) { pack in
                        PackCard(
                            title: pack.title,
                            description: pack.description,
                            line: pack.clips.first { $0.role == .start }?.caption,
                            isExplicit: pack.isExplicit,
                            state: state(of: pack),
                            use: { install(pack) }
                        )
                    }
                }
            }
            .padding(EdgeInsets(top: 24, leading: 20, bottom: 24, trailing: 20))
        }
        .background(Theme.background)
        .foregroundStyle(Theme.ink)
        .task { await load() }
        .alert("Couldn’t switch packs", isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(failure ?? "")
        }
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 4) {
                Text("PACKS")
                    .font(Theme.display(46))
                    .accessibilityAddTraits(.isHeader)
                Text("Pick who yells at you.")
                    .font(Theme.text(14, relativeTo: .subheadline))
                    .foregroundStyle(Theme.muted)
            }
            Spacer()
            Button("Done") { dismiss() }
                .font(Theme.text(16, weight: .bold))
                .foregroundStyle(Theme.ink)
                .frame(minWidth: 44, minHeight: 44)
        }
    }

    private func state(of pack: CatalogPack) -> PackCard.State {
        if installing == pack.id { return .downloading }
        if active?.id == pack.id { return .using }
        return pack.isFree ? .available : .unavailable
    }

    private func load() async {
        phase = .loading
        do {
            phase = .loaded(try await HypemanAPI.shared.catalog())
        } catch {
            log.error("Could not load the catalog: \(error)")
            phase = .failed("Couldn’t reach the store. Check your connection.")
        }
    }

    private func install(_ pack: CatalogPack) {
        guard installing == nil else { return }
        installing = pack.id
        Task {
            defer { installing = nil }
            do {
                let media = try await HypemanAPI.shared.media(for: pack.id)
                active = try await PackLibrary.shared.install(pack, media: media)
            } catch {
                log.error("Could not install \(pack.slug): \(error)")
                failure = (error as? APIError)?.message ?? "The download didn’t finish. Your current pack is still on."
            }
        }
    }

    private func useDefault() {
        do {
            try PackLibrary.shared.useDefault()
            active = nil
        } catch {
            log.error("Could not remove the pack: \(error)")
            failure = "Please try again."
        }
    }

    private enum Phase {
        case loading
        case loaded([CatalogPack])
        case failed(String)
    }
}

private struct PackCard: View {
    enum State {
        case available, downloading, using, unavailable
    }

    let title: String
    let description: String
    /// The START clip's line, as a taste of the pack.
    let line: String?
    let isExplicit: Bool
    let state: State
    let use: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Text(title)
                    .font(Theme.text(20, weight: .bold, relativeTo: .title3))
                if isExplicit {
                    Text("EXPLICIT")
                        .font(Theme.text(11, weight: .bold, relativeTo: .caption2))
                        .kerning(1.2)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .overlay(RoundedRectangle(cornerRadius: 4).stroke(Theme.muted))
                        .foregroundStyle(Theme.muted)
                }
            }
            Text(description)
                .font(Theme.text(15, relativeTo: .subheadline))
                .foregroundStyle(Theme.muted)
            if let line {
                Text("“\(line)”")
                    .font(Theme.text(15, weight: .semibold, relativeTo: .subheadline))
            }
            button
        }
        .padding(EdgeInsets(top: 16, leading: 18, bottom: 16, trailing: 18))
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 18))
        .overlay {
            if state == .using {
                RoundedRectangle(cornerRadius: 18).stroke(Theme.accent, lineWidth: 2)
            }
        }
    }

    @ViewBuilder
    private var button: some View {
        switch state {
        case .available:
            Button(action: use) {
                Text("USE")
                    .font(Theme.display(20, relativeTo: .headline))
                    .kerning(1.2)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .background(Theme.accent, in: RoundedRectangle(cornerRadius: 12))
                    .foregroundStyle(Theme.onAccent)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Use \(title)")
        case .downloading:
            HStack(spacing: 10) {
                ProgressView()
                Text("DOWNLOADING")
                    .font(Theme.display(20, relativeTo: .headline))
                    .kerning(1.2)
            }
            .frame(maxWidth: .infinity, minHeight: 48)
            .foregroundStyle(Theme.muted)
        case .using:
            Label("USING", systemImage: "checkmark")
                .font(Theme.display(20, relativeTo: .headline))
                .kerning(1.2)
                .frame(maxWidth: .infinity, minHeight: 48)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.line))
        case .unavailable:
            Text("COMING SOON")
                .font(Theme.display(20, relativeTo: .headline))
                .kerning(1.2)
                .frame(maxWidth: .infinity, minHeight: 48)
                .foregroundStyle(Theme.muted)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.line))
        }
    }
}
