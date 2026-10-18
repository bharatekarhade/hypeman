import AppIntents
import HypemanKit
import SwiftData
import SwiftUI
import WidgetKit

struct NextGoalEntry: TimelineEntry {
    struct NextGoal {
        let id: UUID
        let title: String
        let startedAt: Date?
    }

    let date: Date
    let goal: NextGoal?
    let remaining: Int
}

struct NextGoalProvider: TimelineProvider {
    func placeholder(in context: Context) -> NextGoalEntry {
        NextGoalEntry(date: .now, goal: .init(id: UUID(), title: "Leg day at the gym", startedAt: nil), remaining: 4)
    }

    func getSnapshot(in context: Context, completion: @escaping (NextGoalEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : currentEntry())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<NextGoalEntry>) -> Void) {
        // The app and the intents reload timelines on every change, so no refresh schedule is needed.
        completion(Timeline(entries: [currentEntry()], policy: .never))
    }

    private func currentEntry() -> NextGoalEntry {
        let goals = (try? GoalStore.remaining(in: ModelContext(GoalStore.container))) ?? []
        let next = goals.first.map { NextGoalEntry.NextGoal(id: $0.id, title: $0.title, startedAt: $0.startedAt) }
        return NextGoalEntry(date: .now, goal: next, remaining: goals.count)
    }
}

struct NextGoalView: View {
    @Environment(\.widgetFamily) private var family
    let entry: NextGoalEntry

    var body: some View {
        if let goal = entry.goal {
            VStack(alignment: .leading, spacing: 6) {
                Text(header(for: goal))
                    .font(.caption2.bold())
                    .foregroundStyle(.secondary)
                Text(goal.title)
                    .font(.headline)
                    .lineLimit(2)
                if let startedAt = goal.startedAt {
                    Text(startedAt, style: .timer)
                        .font(.title2.bold().monospacedDigit())
                }
                Spacer(minLength: 0)
                HStack {
                    Button(intent: StartGoalIntent(goalID: goal.id)) {
                        Text("DO IT")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                    }
                    .tint(Theme.accent)
                    .foregroundStyle(.black)
                    if family != .systemSmall || goal.startedAt != nil {
                        Button(intent: CompleteGoalIntent(goalID: goal.id)) {
                            Image(systemName: "checkmark")
                                .font(.headline)
                        }
                        .accessibilityLabel("Mark done")
                    }
                }
            }
        } else {
            VStack(alignment: .leading) {
                Text("Nothing to do? Suspicious.")
                    .font(.headline)
                Spacer(minLength: 0)
                Text("+ Add a goal")
                    .font(.caption.bold())
            }
        }
    }

    private func header(for goal: NextGoalEntry.NextGoal) -> String {
        if goal.startedAt != nil { return "STARTED" }
        let more = entry.remaining - 1
        return family == .systemMedium && more > 0 ? "NEXT UP · \(more) MORE" : "NEXT"
    }
}

struct NextGoalWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "NextGoal", provider: NextGoalProvider()) { entry in
            NextGoalView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Next goal")
        .description("Your next goal and a DO IT button.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
