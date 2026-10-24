import HypemanKit
import OSLog
import StoreKit
import SwiftData
import SwiftUI

/// Picks who yells: the bundled default pack or one from the store, downloaded whole before it takes over. Paid packs
/// are bought one at a time, signed in or not; Pro doesn't include them.
struct PacksView: View {
    @Binding var active: InstalledPack?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    @State private var store = Store.shared
    @State private var account = Account.shared
    @State private var phase = Phase.loading
    @State private var installing: UUID?
    @State private var buying: String?
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
        .alert("Something went wrong", isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } })) {
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
        if store.owns(pack) { return .available }
        guard let productID = pack.productId, let product = store.products[productID] else { return .unavailable }
        return buying == productID ? .buying : .forSale(product.displayPrice)
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

    private func install(_ pack: CatalogPack) {
        guard installing == nil else { return }
        installing = pack.id
        Task {
            defer { installing = nil }
            do {
                let media = try await store.media(for: pack)
                active = try await PackLibrary.shared.install(pack, media: media)
                Reminders.sync(in: context)
            } catch {
                log.error("Could not install \(pack.slug): \(error)")
                failure = (error as? APIError)?.message ?? "The download didn’t finish. Your current pack is still on."
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

    private func useDefault() {
        do {
            try PackLibrary.shared.useDefault()
            active = nil
            Reminders.sync(in: context)
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
