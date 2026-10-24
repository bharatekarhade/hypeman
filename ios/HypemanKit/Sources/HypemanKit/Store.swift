import Foundation
import Observation
import OSLog
import StoreKit

/// Hypeman Pro and single packs through StoreKit 2. Pro is for app features (hard mode, the Live Activity); packs are
/// always bought one at a time, so every sale is its creator's. Neither needs an account: what this Apple ID owns comes
/// from StoreKit on the phone, and a pack bought signed out downloads with its signed transaction. When someone is signed
/// in, purchases carry their user id as the appAccountToken and are reported to the server, so they follow the account;
/// such a transaction is finished only once the server has it, so one that fails to report comes back through
/// `Transaction.updates` on the next launch.
@MainActor
@Observable
public final class Store {
    public static let shared = Store(account: .shared, api: .shared)

    /// The Pro subscriptions, cheapest period first. Kept in step with hypeman.app-store.pro-product-ids on the server.
    public static let proProductIDs = ["com.hypeman.pro.monthly", "com.hypeman.pro.yearly"]

    /// Server codes that will never succeed on a retry, so the transaction is finished anyway.
    private static let permanentFailures: Set<String> = ["PURCHASE_ACCOUNT_MISMATCH", "UNKNOWN_PRODUCT", "INVALID_TRANSACTION"]

    public private(set) var products: [String: Product] = [:]
    /// What the server says the signed-in user owns. Nil until loaded, and whenever nobody is signed in.
    public private(set) var entitlements: Entitlements?
    /// What this Apple ID owns according to StoreKit, signed in or not.
    public private(set) var local = LocalEntitlements()

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

    /// When the current Pro period ends, from StoreKit or the server, whichever is later.
    public var proUntil: Date? {
        Self.proUntil(entitlements, local)
    }

    public var isPro: Bool {
        proUntil.map { $0 > .now } ?? false
    }

    public func owns(_ pack: CatalogPack) -> Bool {
        Self.owns(pack, entitlements, local)
    }

    nonisolated static func proUntil(_ entitlements: Entitlements?, _ local: LocalEntitlements) -> Date? {
        [entitlements?.proUntil, local.proUntil].compactMap { $0 }.max()
    }

    nonisolated static func owns(_ pack: CatalogPack, _ entitlements: Entitlements?, _ local: LocalEntitlements) -> Bool {
        pack.isFree || entitlements?.packIds.contains(pack.id) == true
            || pack.productId.map(local.packProductIDs.contains) == true
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

    /// Loads Pro prices only, for the paywall.
    public func loadProProducts() async {
        await loadProducts(for: [])
    }

    public func refreshEntitlements() async {
        await refreshLocal()
        guard account.isSignedIn else {
            entitlements = nil
            publishPro()
            return
        }
        do {
            entitlements = try await account.authorized { [api] token in try await api.entitlements(accessToken: token) }
        } catch {
            log.error("Could not load entitlements: \(error)")
        }
        publishPro()
    }

    /// Signed links to a pack's videos. A paid pack proves its purchase with StoreKit's transaction when this phone has
    /// one, and otherwise with the user's sign-in.
    public func media(for pack: CatalogPack) async throws -> [ClipMedia] {
        if pack.isFree {
            return try await api.media(for: pack.id)
        }
        if let productID = pack.productId, let transaction = await Self.current(productID) {
            return try await api.media(for: pack.id, signedTransaction: transaction.jwsRepresentation)
        }
        return try await account.authorized { [api] token in try await api.media(for: pack.id, accessToken: token) }
    }

    /// Works signed out. False when the user cancelled or the purchase waits for approval (Ask to Buy); an approved one
    /// arrives later through `Transaction.updates`.
    public func buy(_ product: Product) async throws -> Bool {
        var options: Set<Product.PurchaseOption> = []
        if let userID = account.session?.user.id {
            options.insert(.appAccountToken(userID))
        }
        switch try await product.purchase(options: options) {
        case .success(let verification):
            guard case .verified = verification else { throw Self.unverified }
            do {
                try await report(verification)
            } catch {
                // Bought all the same: StoreKit unlocks it on this phone, and the server hears on a later launch.
                log.error("Bought \(product.id) but couldn't report it yet: \(error)")
            }
            return true
        case .pending, .userCancelled:
            return false
        @unknown default:
            return false
        }
    }

    /// Asks the App Store for the user's purchases (it may ask for their Apple ID password), then reports each one when
    /// someone is signed in.
    public func restore() async throws {
        try await AppStore.sync()
        if account.isSignedIn {
            try await reportCurrentEntitlements()
        } else {
            await refreshEntitlements()
        }
    }

    /// Reports what this Apple ID owns without prompting, e.g. right after signing in on a phone that already bought,
    /// including purchases made signed out.
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

    /// Renewals, refunds, Ask to Buy approvals and purchases made on other devices. Call once at launch.
    public func startListening() {
        guard updates == nil else { return }
        updates = Task { [weak self] in
            await self?.refreshEntitlements()
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
        guard case .verified(let transaction) = verification else { throw Self.unverified }
        guard account.isSignedIn else {
            // StoreKit keeps it as an entitlement, so it's reported after the next sign-in all the same.
            await transaction.finish()
            await refreshLocal()
            return
        }
        do {
            entitlements = try await account.authorized { [api] token in
                try await api.recordPurchase(signedTransaction: verification.jwsRepresentation, accessToken: token)
            }
            await transaction.finish()
        } catch {
            if let error = error as? APIError, Self.permanentFailures.contains(error.code) {
                await transaction.finish()
            }
            await refreshLocal()
            throw error
        }
        await refreshLocal()
    }

    private static let unverified = APIError(code: "UNVERIFIED_PURCHASE", message: "The App Store couldn’t confirm that purchase. Please try again.",
                                             requestId: nil)

    private func refreshLocal() async {
        var local = LocalEntitlements()
        for await verification in Transaction.currentEntitlements {
            guard case .verified(let transaction) = verification, transaction.revocationDate == nil else { continue }
            if Self.proProductIDs.contains(transaction.productID) {
                local.proUntil = [local.proUntil, transaction.expirationDate].compactMap { $0 }.max()
            } else {
                local.packProductIDs.insert(transaction.productID)
            }
        }
        self.local = local
        publishPro()
    }

    /// Hands the Pro end date to the parts of the app that can't ask the Store: the Kit's syncs and the extensions.
    private func publishPro() {
        ProAccess.until = proUntil
    }

    private static func current(_ productID: String) async -> VerificationResult<Transaction>? {
        for await verification in Transaction.currentEntitlements {
            if case .verified(let transaction) = verification, transaction.productID == productID, transaction.revocationDate == nil {
                return verification
            }
        }
        return nil
    }
}

/// What StoreKit says this Apple ID owns, unrevoked.
public struct LocalEntitlements: Equatable, Sendable {
    public var proUntil: Date?
    public var packProductIDs: Set<String> = []

    public init(proUntil: Date? = nil, packProductIDs: Set<String> = []) {
        self.proUntil = proUntil
        self.packProductIDs = packProductIDs
    }
}
