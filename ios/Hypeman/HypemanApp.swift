import HypemanKit
import SwiftUI

@main
struct HypemanApp: App {
    init() {
        Theme.registerFonts()
    }

    var body: some Scene {
        WindowGroup {
            GoalsView()
        }
        .modelContainer(GoalStore.container)
    }
}
