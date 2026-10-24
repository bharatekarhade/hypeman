import Foundation
import Testing
@testable import HypemanKit

/// Who may use which pack, and when Pro ends. Buying goes through StoreKit and the server; these are the rules the app
/// applies to what each of them says.
struct StoreTests {
    let now = Date(timeIntervalSinceReferenceDate: 800_000_000)
    let free = CatalogPack(id: UUID(), slug: "free", title: "Free", description: "", isExplicit: false, isFree: true, clips: [])
    let paid = CatalogPack(id: UUID(), slug: "coach", title: "Coach", description: "", isExplicit: false, isFree: false, clips: [],
                           productId: "com.hypeman.pack.coach")

    @Test func freePacksNeedNothing() {
        #expect(Store.owns(free, nil, LocalEntitlements()))
    }

    @Test func aPaidPackNeedsBuying() {
        #expect(!Store.owns(paid, nil, LocalEntitlements()))
        #expect(!Store.owns(paid, Entitlements(proUntil: nil, packIds: []), LocalEntitlements()))
        #expect(Store.owns(paid, Entitlements(proUntil: nil, packIds: [paid.id]), LocalEntitlements()))
    }

    @Test func aPackBoughtSignedOutIsOwnedThroughStoreKit() {
        #expect(Store.owns(paid, nil, LocalEntitlements(packProductIDs: ["com.hypeman.pack.coach"])))
        #expect(!Store.owns(paid, nil, LocalEntitlements(packProductIDs: ["com.hypeman.pack.other"])))
    }

    @Test func proDoesNotIncludePacks() {
        let pro = now.addingTimeInterval(60)

        #expect(!Store.owns(paid, Entitlements(proUntil: pro, packIds: []), LocalEntitlements(proUntil: pro)))
    }

    @Test func proLastsUntilTheLaterOfStoreKitAndTheServer() {
        let sooner = now.addingTimeInterval(60)
        let later = now.addingTimeInterval(3600)

        #expect(Store.proUntil(nil, LocalEntitlements()) == nil)
        #expect(Store.proUntil(Entitlements(proUntil: sooner, packIds: []), LocalEntitlements(proUntil: later)) == later)
        #expect(Store.proUntil(Entitlements(proUntil: later, packIds: []), LocalEntitlements(proUntil: sooner)) == later)
        #expect(Store.proUntil(nil, LocalEntitlements(proUntil: sooner)) == sooner)
    }

    @Test func proAccessLapsesOnItsOwn() {
        let saved = ProAccess.until
        defer { ProAccess.until = saved }

        ProAccess.until = now.addingTimeInterval(60)

        #expect(ProAccess.isActive(now: now))
        #expect(!ProAccess.isActive(now: now.addingTimeInterval(61)))
    }
}
