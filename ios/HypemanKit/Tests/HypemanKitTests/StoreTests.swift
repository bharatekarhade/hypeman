import Foundation
import Testing
@testable import HypemanKit

/// Who may use which pack. Buying goes through StoreKit and the server; these are the rules the app applies to what each
/// of them says.
struct StoreTests {
    let free = CatalogPack(id: UUID(), slug: "free", title: "Free", description: "", isExplicit: false, isFree: true, clips: [])
    let paid = CatalogPack(id: UUID(), slug: "coach", title: "Coach", description: "", isExplicit: false, isFree: false, clips: [],
                           productId: "com.hypeman.pack.coach")

    @Test func freePacksNeedNothing() {
        #expect(Store.owns(free, nil, LocalEntitlements()))
    }

    @Test func aPaidPackNeedsBuying() {
        #expect(!Store.owns(paid, nil, LocalEntitlements()))
        #expect(!Store.owns(paid, Entitlements(packIds: []), LocalEntitlements()))
        #expect(Store.owns(paid, Entitlements(packIds: [paid.id]), LocalEntitlements()))
    }

    @Test func aPackBoughtSignedOutIsOwnedThroughStoreKit() {
        #expect(Store.owns(paid, nil, LocalEntitlements(packProductIDs: ["com.hypeman.pack.coach"])))
        #expect(!Store.owns(paid, nil, LocalEntitlements(packProductIDs: ["com.hypeman.pack.other"])))
    }
}
