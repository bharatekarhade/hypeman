import HypemanKit
import SwiftUI

@main
struct HypemanApp: App {
    var body: some Scene {
        WindowGroup {
            GoalsView()
        }
        .modelContainer(GoalStore.container)
    }
}
