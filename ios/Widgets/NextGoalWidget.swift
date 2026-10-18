import SwiftUI
import WidgetKit

struct NextGoalEntry: TimelineEntry {
    let date: Date
    let title: String?
}

struct NextGoalProvider: TimelineProvider {
    func placeholder(in context: Context) -> NextGoalEntry {
        NextGoalEntry(date: .now, title: "Leg day at the gym")
    }

    func getSnapshot(in context: Context, completion: @escaping (NextGoalEntry) -> Void) {
        completion(placeholder(in: context))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<NextGoalEntry>) -> Void) {
        // TODO: read the next goal from the shared store (WG-01).
        completion(Timeline(entries: [NextGoalEntry(date: .now, title: nil)], policy: .never))
    }
}

struct NextGoalWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "NextGoal", provider: NextGoalProvider()) { entry in
            VStack(alignment: .leading) {
                Text("NEXT")
                    .font(.caption.bold())
                    .foregroundStyle(.secondary)
                Text(entry.title ?? "Add a goal")
                    .font(.headline)
            }
            .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Next goal")
        .description("Your next goal and a DO IT button.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
