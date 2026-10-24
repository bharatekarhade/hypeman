import Foundation
import Testing
@testable import HypemanKit

/// Who may use which pack. Buying goes through StoreKit and the server; this is the rule the app applies to what the
/// server says the user owns.
struct StoreTests {
    let now = Date(timeIntervalSinceReferenceDate: 800_000_000)
    let free = CatalogPack(id: UUID(), slug: "free", title: "Free", description: "", isExplicit: false, isFree: true, clips: [])
    let paid = CatalogPack(id: UUID(), slug: "coach", title: "Coach", description: "", isExplicit: false, isFree: false, clips: [],
                           productId: "com.hypeman.pack.coach")

    @Test func freePacksNeedNothing() {
        #expect(Store.owns(free, nil, now: now))
    }

    @Test func aPaidPackNeedsBuyingOrPro() {
        #expect(!Store.owns(paid, nil, now: now))
        #expect(!Store.owns(paid, Entitlements(proUntil: nil, packIds: []), now: now))
        #expect(Store.owns(paid, Entitlements(proUntil: nil, packIds: [paid.id]), now: now))
        #expect(Store.owns(paid, Entitlements(proUntil: now.addingTimeInterval(60), packIds: []), now: now))
    }

    @Test func proEndsWhenItRunsOut() {
        let lapsed = Entitlements(proUntil: now.addingTimeInterval(-60), packIds: [])

        #expect(!Store.isPro(lapsed, now: now))
        #expect(!Store.owns(paid, lapsed, now: now))
    }
}
