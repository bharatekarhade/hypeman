import Foundation
import Observation
import OSLog
import StoreKit

/// Hypeman Pro and single packs through StoreKit 2. Every purchase carries the signed-in user's id as its
/// appAccountToken, which is how the server ties it, and Apple's later notifications about it, to the account. The server
/// decides what someone owns; a transaction is finished only once the server has it, so one that fails to report comes
/// back through `Transaction.updates` on the next launch.
@MainActor
@Observable
public final class Store {
    public static let shared = Store(account: .shared, api: .shared)

    /// The Pro subscriptions, cheapest period first. Kept in step with hypeman.app-store.pro-product-ids on the server.
    public static let proProductIDs = ["com.hypeman.pro.monthly", "com.hypeman.pro.yearly"]

    /// Server codes that will never succeed on a retry, so the transaction is finished anyway.
    private static let permanentFailures: Set<String> = ["PURCHASE_ACCOUNT_MISMATCH", "UNKNOWN_PRODUCT", "INVALID_TRANSACTION"]

    public private(set) var products: [String: Product] = [:]
    /// Nil until loaded, and whenever nobody is signed in.
    public private(set) var entitlements: Entitlements?

    private let account: Account
    private let api: HypemanAPI
    @ObservationIgnored private var updates: Task<Void, Never>?
    private let log = Logger(subsystem: "com.hypeman.app", category: "Store")

    public init(account: Account, api: HypemanAPI) {
        self.account = account
        self.api = api
    }

    public var proProducts: [Product] {
        Self.proProductIDs.compactMap { products[$0] }
    }

    public var isPro: Bool {
        Self.isPro(entitlements, now: .now)
    }

    public func owns(_ pack: CatalogPack) -> Bool {
        Self.owns(pack, entitlements, now: .now)
    }

    nonisolated static func isPro(_ entitlements: Entitlements?, now: Date) -> Bool {
        entitlements?.proUntil.map { $0 > now } ?? false
    }

    nonisolated static func owns(_ pack: CatalogPack, _ entitlements: Entitlements?, now: Date) -> Bool {
        pack.isFree || isPro(entitlements, now: now) || entitlements?.packIds.contains(pack.id) == true
    }

    /// Prices for Pro and for the catalog's paid packs, in the user's storefront.
    public func loadProducts(for packs: [CatalogPack]) async {
        let ids = Set(Self.proProductIDs + packs.compactMap(\.productId))
        do {
            for product in try await Product.products(for: ids) {
                products[product.id] = product
            }
        } catch {
            log.error("Could not load products: \(error)")
        }
    }

    public func refreshEntitlements() async {
        guard account.isSignedIn else {
            entitlements = nil
            return
        }
        do {
            entitlements = try await account.authorized { [api] token in try await api.entitlements(accessToken: token) }
        } catch {
            log.error("Could not load entitlements: \(error)")
        }
    }

    /// Signed links to a pack's videos, with the user's sign-in when the pack is paid.
    public func media(for pack: CatalogPack) async throws -> [ClipMedia] {
        if pack.isFree {
            return try await api.media(for: pack.id)
        }
        return try await account.authorized { [api] token in try await api.media(for: pack.id, accessToken: token) }
    }

    /// Needs a signed-in user. False when the user cancelled or the purchase waits for approval (Ask to Buy); an
    /// approved one arrives later through `Transaction.updates`.
    public func buy(_ product: Product) async throws -> Bool {
        guard let userID = account.session?.user.id else { throw APIError.signedOut }
        switch try await product.purchase(options: [.appAccountToken(userID)]) {
        case .success(let verification):
            try await report(verification)
            return true
        case .pending, .userCancelled:
            return false
        @unknown default:
            return false
        }
    }

    /// Asks the App Store for the user's purchases (it may ask for their Apple ID password) and reports each one.
    public func restore() async throws {
        try await AppStore.sync()
        try await reportCurrentEntitlements()
    }

    /// Reports what this Apple ID owns without prompting, e.g. right after signing in on a phone that already bought.
    public func reportCurrentEntitlements() async throws {
        var failure: Error?
        for await verification in Transaction.currentEntitlements {
            do {
                try await report(verification)
            } catch {
                failure = error
            }
        }
        await refreshEntitlements()
        if let failure {
            throw failure
        }
    }

    /// Renewals, Ask to Buy approvals and purchases made on other devices. Call once at launch.
    public func startListening() {
        guard updates == nil else { return }
        updates = Task { [weak self] in
            for await verification in Transaction.updates {
                do {
                    try await self?.report(verification)
                } catch {
                    self?.log.error("Could not report a transaction update: \(error)")
                }
            }
        }
    }

    private func report(_ verification: VerificationResult<Transaction>) async throws {
        guard case .verified(let transaction) = verification else {
            throw APIError(code: "UNVERIFIED_PURCHASE", message: "The App Store couldn’t confirm that purchase. Please try again.", requestId: nil)
        }
        // Left unfinished while signed out, so it's reported after the next sign-in.
        guard account.isSignedIn else { return }
        do {
            entitlements = try await account.authorized { [api] token in
                try await api.recordPurchase(signedTransaction: verification.jwsRepresentation, accessToken: token)
            }
            await transaction.finish()
        } catch let error as APIError where Self.permanentFailures.contains(error.code) {
            await transaction.finish()
            throw error
        }
    }
}
