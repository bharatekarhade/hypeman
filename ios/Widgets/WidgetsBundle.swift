import HypemanKit
import SwiftUI
import WidgetKit

@main
struct WidgetsBundle: WidgetBundle {
    init() {
        Theme.registerFonts()
    }

    var body: some Widget {
        NextGoalWidget()
    }
}
