import HypemanKit
import OSLog
import StoreKit
import SwiftData
import SwiftUI

/// Who yells (MV-01 to MV-06): the user's library of voices, the default one goals use, shuffle, recording their own,
/// and the pack store. Store packs download into the library; paid ones are bought one at a time, signed in or not.
struct VoicesView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var store = Store.shared
    @State private var account = Account.shared
    @State private var voices = MotivatorLibrary.shared.all
    @State private var defaultID = MotivatorLibrary.shared.defaultID
    @State private var phase = Phase.loading
    @State private var installing: UUID?
    @State private var buying: String?
    @State private var showRecorder = false
    @State private var paywall: String?
    @State private var confirmRemove: Motivator?
    @State private var failure: String?

    private let library = MotivatorLibrary.shared
    private let log = Logger(subsystem: "com.hypeman.app", category: "VoicesView")

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                header
                    .padding(.bottom, 8)
                SectionLabel("YOUR VOICES")
                ForEach(voices) { voice in
                    VoiceRow(voice: voice, isSelected: voice.id == defaultID, isLocked: voice.needsPro && !store.isPro) {
                        use(voice)
                    } remove: {
                        confirmRemove = voice
                    }
                }
                Toggle(isOn: Binding(get: { library.shuffle && store.isPro }, set: setShuffle)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Shuffle")
                            .font(Theme.text(17, weight: .bold))
                        Text("A random voice from your library on every yell. Pro.")
                            .font(Theme.text(13, relativeTo: .footnote))
                            .foregroundStyle(Theme.muted)
                    }
                }
                .tint(Theme.accent)
                .padding(16)
                .background(Theme.surface, in: RoundedRectangle(cornerRadius: 18))
                Button {
                    if store.isPro {
                        showRecorder = true
                    } else {
                        paywall = "Yell at yourself in your own voice."
                    }
                } label: {
                    Label("RECORD YOUR OWN", systemImage: "mic.fill")
                        .font(Theme.display(20, relativeTo: .headline))
                        .kerning(1.2)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.ink, lineWidth: 1.5))
                }
                .buttonStyle(.plain)
                SectionLabel("STORE")
                    .padding(.top, 16)
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
                            use: { install(pack) },
                            buy: { pack.productId.flatMap { store.products[$0] }.map(buy) }
                        )
                    }
                }
                Button("Restore purchases", action: restore)
                    .font(Theme.text(15, weight: .bold, relativeTo: .subheadline))
                    .foregroundStyle(Theme.muted)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .disabled(buying != nil)
            }
            .padding(EdgeInsets(top: 24, leading: 20, bottom: 24, trailing: 20))
        }
        .background(Theme.background)
        .foregroundStyle(Theme.ink)
        .task { await load() }
        .task(id: account.isSignedIn) { await store.refreshEntitlements() }
        .sheet(isPresented: $showRecorder, onDismiss: reload) { RecordVoiceSheet() }
        .sheet(isPresented: Binding(get: { paywall != nil }, set: { if !$0 { paywall = nil } })) {
            PaywallSheet(headline: paywall ?? "")
        }
        .confirmationDialog("Remove \(confirmRemove?.title ?? "this voice")?",
                            isPresented: Binding(get: { confirmRemove != nil }, set: { if !$0 { confirmRemove = nil } }),
                            titleVisibility: .visible, presenting: confirmRemove) { voice in
            Button("Remove", role: .destructive) { remove(voice) }
        } message: { voice in
            Text(voice.kind == .pack ? "You can download it again from the store." : "This can’t be undone.")
        }
        .alert("Something went wrong", isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(failure ?? "")
        }
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 4) {
                Text("VOICES")
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
        if defaultID == MotivatorLibrary.packID(pack.id) { return .using }
        if store.owns(pack) { return .available }
        guard let productID = pack.productId, let product = store.products[productID] else { return .unavailable }
        return buying == productID ? .buying : .forSale(product.displayPrice)
    }

    private func reload() {
        voices = library.all
        defaultID = library.defaultID
    }

    private func use(_ voice: Motivator) {
        if voice.needsPro && !store.isPro {
            paywall = "Yell at yourself in your own voice."
            return
        }
        library.defaultID = voice.id
        reload()
    }

    private func setShuffle(_ on: Bool) {
        guard store.isPro else {
            paywall = "A different voice every time."
            return
        }
        library.shuffle = on
        reload()
    }

    private func remove(_ voice: Motivator) {
        do {
            try library.remove(voice.id)
        } catch {
            log.error("Could not remove \(voice.id): \(error)")
            failure = "Please try again."
        }
        reload()
    }

    private func load() async {
        phase = .loading
        do {
            let packs = try await HypemanAPI.shared.catalog()
            phase = .loaded(packs)
            await store.loadProducts(for: packs)
        } catch {
            log.error("Could not load the catalog: \(error)")
            phase = .failed("Couldn’t reach the store. Check your connection.")
        }
    }

    /// Downloads the pack if it isn't on the phone yet, then makes it the default voice.
    private func install(_ pack: CatalogPack) {
        guard installing == nil else { return }
        if library.hasPack(pack.id) {
            library.defaultID = MotivatorLibrary.packID(pack.id)
            reload()
            return
        }
        installing = pack.id
        Task {
            defer { installing = nil }
            do {
                let media = try await store.media(for: pack)
                let voice = try await library.install(pack, media: media)
                library.defaultID = voice.id
                reload()
            } catch {
                log.error("Could not install \(pack.slug): \(error)")
                failure = (error as? APIError)?.message ?? "The download didn’t finish. Your current voice is still on."
            }
        }
    }

    private func buy(_ product: Product) {
        guard buying == nil else { return }
        buying = product.id
        Task {
            defer { buying = nil }
            do {
                _ = try await store.buy(product)
            } catch {
                log.error("Could not buy \(product.id): \(error)")
                failure = (error as? APIError)?.message ?? "The purchase didn’t go through. You weren’t charged twice; try again."
            }
        }
    }

    private func restore() {
        buying = "restore"
        Task {
            defer { buying = nil }
            do {
                try await store.restore()
            } catch StoreKitError.userCancelled {
                // Cancelled the App Store sign-in.
            } catch {
                log.error("Could not restore purchases: \(error)")
                failure = (error as? APIError)?.message ?? "Couldn’t restore your purchases. Please try again."
            }
        }
    }

    private enum Phase {
        case loading
        case loaded([CatalogPack])
        case failed(String)
    }
}

/// One voice in the library: a preview button, what it is, and whether it's the one in use.
struct VoiceRow: View {
    let voice: Motivator
    let isSelected: Bool
    let isLocked: Bool
    let select: () -> Void
    var remove: (() -> Void)?

    var body: some View {
        HStack(spacing: 12) {
            Button(action: preview) {
                Image(systemName: "play.fill")
                    .font(.system(size: 14, weight: .bold))
                    .frame(width: 40, height: 40)
                    .background(Theme.background, in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Play \(voice.title)")
            Button(action: select) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(voice.title)
                            .font(Theme.text(17, weight: .bold))
                            .lineLimit(2)
                        Text(detail)
                            .font(Theme.text(13, relativeTo: .footnote))
                            .foregroundStyle(Theme.muted)
                    }
                    Spacer(minLength: 8)
                    if isLocked {
                        Text("PRO")
                            .font(Theme.text(11, weight: .bold, relativeTo: .caption2))
                            .kerning(1.2)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .overlay(RoundedRectangle(cornerRadius: 4).stroke(Theme.muted))
                            .foregroundStyle(Theme.muted)
                    } else if isSelected {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.system(size: 22))
                            .foregroundStyle(Theme.accent)
                    }
                }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(isSelected ? .isSelected : [])
            if let remove, voice.kind != .builtIn {
                Menu {
                    Button("Remove", systemImage: "trash", role: .destructive, action: remove)
                } label: {
                    Image(systemName: "ellipsis")
                        .frame(width: 32, height: 44)
                }
                .accessibilityLabel("Options for \(voice.title)")
            }
        }
        .padding(EdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12))
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 18))
        .overlay {
            if isSelected {
                RoundedRectangle(cornerRadius: 18).stroke(Theme.accent, lineWidth: 2)
            }
        }
    }

    private var detail: String {
        switch voice.kind {
        case .builtIn: "Hypeman’s own voice. Always on your phone."
        case .pack: "Pack · all four moments"
        case .recording: "Your recording · " + voice.clips.map(\.role.title).joined(separator: ", ")
        case .yell: voice.subtitle ?? "Saved yell"
        }
    }

    /// The START clip, or whatever it has.
    private func preview() {
        guard let clip = voice.clip(for: .start) ?? voice.clips.first,
              let url = MotivatorLibrary.shared.url(of: clip, in: voice) else { return }
        ClipPreview.shared.play(url)
    }
}

/// The voice for one goal (GL-08, Pro). Picking the default makes the goal follow it.
struct VoicePickerSheet: View {
    let goal: Goal
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    @State private var voices = MotivatorLibrary.shared.all
    @State private var failure = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("VOICE")
                        .font(Theme.display(36))
                        .accessibilityAddTraits(.isHeader)
                    Text("Who yells for “\(goal.title)”.")
                        .font(Theme.text(15, relativeTo: .subheadline))
                        .foregroundStyle(Theme.muted)
                }
                .padding(.bottom, 8)
                Button { choose(nil) } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("My default voice")
                                .font(Theme.text(17, weight: .bold))
                            Text(defaultTitle)
                                .font(Theme.text(13, relativeTo: .footnote))
                                .foregroundStyle(Theme.muted)
                        }
                        Spacer()
                        if goal.motivatorID == nil {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.system(size: 22))
                                .foregroundStyle(Theme.accent)
                        }
                    }
                    .padding(16)
                    .frame(minHeight: 44)
                    .background(Theme.surface, in: RoundedRectangle(cornerRadius: 18))
                }
                .buttonStyle(.plain)
                ForEach(voices) { voice in
                    VoiceRow(voice: voice, isSelected: goal.motivatorID == voice.id, isLocked: false) { choose(voice.id) }
                }
            }
            .padding(EdgeInsets(top: 28, leading: 20, bottom: 24, trailing: 20))
        }
        .background(Theme.background)
        .foregroundStyle(Theme.ink)
        .presentationDetents([.medium, .large])
        .alert("Couldn’t save the voice", isPresented: $failure) {
            Button("OK", role: .cancel) {}
        }
    }

    private var defaultTitle: String {
        let library = MotivatorLibrary.shared
        return library.motivator(library.defaultID)?.title ?? "Default pack"
    }

    private func choose(_ id: String?) {
        do {
            try GoalStore.setMotivator(goal.id, to: id, in: context)
            dismiss()
        } catch {
            failure = true
        }
    }
}

private struct PackCard: View {
    enum State: Equatable {
        case available, downloading, using, unavailable, buying
        /// Not owned; the price in the user's storefront.
        case forSale(String)
    }

    let title: String
    let description: String
    /// The START clip's line, as a taste of the pack.
    let line: String?
    let isExplicit: Bool
    let state: State
    let use: () -> Void
    var buy: () -> Void = {}

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
        case .forSale(let price):
            Button(action: buy) {
                Text("BUY · \(price)")
                    .font(Theme.display(20, relativeTo: .headline))
                    .kerning(1.2)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.ink, lineWidth: 1.5))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Buy \(title) for \(price)")
        case .buying:
            ProgressView()
                .frame(maxWidth: .infinity, minHeight: 48)
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
