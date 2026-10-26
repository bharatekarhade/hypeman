import HypemanKit
import OSLog
import StoreKit
import SwiftUI

/// Hypeman Pro (PY-01, PY-02): what it unlocks, both plans with the yearly trial, and the disclosures App Review asks
/// for. Opens from the features it gates (PY-03), and works signed out.
struct PaywallSheet: View {
    /// The headline, naming the feature that opened it.
    var headline = "Make it impossible to ignore."
    @Environment(\.dismiss) private var dismiss
    @State private var store = Store.shared
    @State private var buying: String?
    @State private var trialEligible = false
    @State private var manageSubscription = false
    @State private var failure: String?

    private let log = Logger(subsystem: "com.hypeman.app", category: "PaywallSheet")

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HazardStripes()
                    .frame(height: 12)
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 8) {
                        SectionLabel("HYPEMAN PRO")
                        Text(headline)
                            .font(Theme.display(36))
                            .accessibilityAddTraits(.isHeader)
                    }
                    VStack(alignment: .leading, spacing: 14) {
                        Feature(icon: "lock.fill", title: "Hard mode",
                                detail: "The apps you pick stay locked while a goal is overdue, until you hit DO IT.")
                        Feature(icon: "timer", title: "Live Activity",
                                detail: "A running timer and DONE on your Lock Screen and in the Dynamic Island.")
                        Feature(icon: "waveform", title: "Your voices",
                                detail: "Record your own yells, give each goal its own voice, or shuffle them.")
                        Feature(icon: "megaphone.fill", title: "Unlimited yells",
                                detail: "Send friends as many yells as you like. Without Pro it’s 5 a month.")
                    }
                    if let proUntil = store.proUntil, store.isPro {
                        subscribed(until: proUntil)
                    } else {
                        plans
                    }
                    links
                }
                .padding(EdgeInsets(top: 24, leading: 20, bottom: 24, trailing: 20))
            }
        }
        .background(Theme.background)
        .foregroundStyle(Theme.ink)
        .presentationDetents([.large])
        .task {
            await store.loadProProducts()
            trialEligible = await yearly?.subscription?.isEligibleForIntroOffer ?? false
        }
        .manageSubscriptionsSheet(isPresented: $manageSubscription)
        .alert("Something went wrong", isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(failure ?? "")
        }
    }

    private var yearly: Product? {
        store.proProducts.first { $0.subscription?.subscriptionPeriod.unit == .year }
    }

    /// The yearly plan first: it's the one with the trial.
    private var plans: some View {
        VStack(alignment: .leading, spacing: 12) {
            if store.proProducts.isEmpty {
                Text("Not available right now.")
                    .font(Theme.text(15, relativeTo: .subheadline))
                    .foregroundStyle(Theme.muted)
            }
            ForEach(store.proProducts.reversed(), id: \.id) { product in
                Button { buy(product) } label: {
                    VStack(spacing: 2) {
                        if buying == product.id {
                            ProgressView()
                        } else {
                            Text("\(product.displayPrice) / \(Plan.unit(of: product).uppercased())")
                                .font(Theme.display(20, relativeTo: .headline))
                                .kerning(1.2)
                            if let trial = trial(of: product) {
                                Text("\(trial) free, then \(product.displayPrice) a \(Plan.unit(of: product))")
                                    .font(Theme.text(13, weight: .semibold, relativeTo: .footnote))
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .background(Theme.accent, in: RoundedRectangle(cornerRadius: 12))
                    .foregroundStyle(Theme.onAccent)
                }
                .buttonStyle(.plain)
                .disabled(buying != nil)
            }
            Text(disclosure)
                .font(Theme.text(12, relativeTo: .caption))
                .foregroundStyle(Theme.muted)
        }
    }

    private func subscribed(until proUntil: Date) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("You’re Pro until \(proUntil.formatted(date: .abbreviated, time: .omitted)).")
                .font(Theme.text(17, weight: .bold))
            Button("Manage subscription") { manageSubscription = true }
                .font(Theme.text(15, weight: .bold, relativeTo: .subheadline))
                .foregroundStyle(Theme.ink)
                .frame(minHeight: 44)
        }
    }

    private var links: some View {
        HStack(spacing: 16) {
            Link("Terms of Use", destination: Legal.terms)
            Link("Privacy Policy", destination: Legal.privacy)
            Spacer(minLength: 0)
            Button("Restore", action: restore)
                .disabled(buying != nil)
        }
        .font(Theme.text(13, weight: .bold, relativeTo: .footnote))
        .foregroundStyle(Theme.muted)
        .frame(minHeight: 44)
    }

    private var disclosure: String {
        var text = "Payment is charged to your Apple ID when you confirm. The subscription renews automatically unless you cancel at least 24 hours before the period ends. Manage or cancel it in Settings."
        if trialEligible, let yearly, trial(of: yearly) != nil {
            text += " If you cancel during the free trial, you won’t be charged."
        }
        return text
    }

    /// "1 week" for a free trial this user can still get.
    private func trial(of product: Product) -> String? {
        guard trialEligible, let offer = product.subscription?.introductoryOffer, offer.paymentMode == .freeTrial else { return nil }
        return Plan.length(offer.period)
    }

    private func buy(_ product: Product) {
        guard buying == nil else { return }
        buying = product.id
        Task {
            defer { buying = nil }
            do {
                if try await store.buy(product) {
                    dismiss()
                }
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
                if !store.isPro {
                    failure = "There’s no Pro subscription on this Apple ID."
                }
            } catch StoreKitError.userCancelled {
                // Cancelled the App Store sign-in.
            } catch {
                log.error("Could not restore purchases: \(error)")
                failure = (error as? APIError)?.message ?? "Couldn’t restore your purchases. Please try again."
            }
        }
    }
}

/// The links App Review requires beside a subscription. The terms are Apple's standard EULA until Hypeman has its own;
/// the privacy policy address is a placeholder to replace before submitting.
enum Legal {
    static let terms = URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")!
    static let privacy = URL(string: "https://hypeman.app/privacy")!
}

private enum Plan {
    static func unit(of product: Product) -> String {
        switch product.subscription?.subscriptionPeriod.unit {
        case .year: "year"
        case .month: "month"
        case .week: "week"
        case .day: "day"
        default: "period"
        }
    }

    static func length(_ period: Product.SubscriptionPeriod) -> String {
        let unit = switch period.unit {
        case .day: "day"
        case .week: "week"
        case .month: "month"
        case .year: "year"
        @unknown default: "period"
        }
        return "\(period.value) \(unit)\(period.value == 1 ? "" : "s")"
    }
}

private struct Feature: View {
    let icon: String
    let title: String
    let detail: String

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .bold))
                .frame(width: 36, height: 36)
                .background(Theme.accent, in: Circle())
                .foregroundStyle(Theme.onAccent)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(Theme.text(17, weight: .bold))
                Text(detail)
                    .font(Theme.text(15, relativeTo: .subheadline))
                    .foregroundStyle(Theme.muted)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
