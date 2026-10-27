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
    /// Quiet mode is on: DO IT starts the goal without a yell.
    var quiet = false
}

struct NextGoalProvider: TimelineProvider {
    func placeholder(in context: Context) -> NextGoalEntry {
        NextGoalEntry(date: .now, goal: .init(id: UUID(), title: "Leg day at the gym", startedAt: nil), remaining: 4)
    }

    func getSnapshot(in context: Context, completion: @escaping (NextGoalEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : currentEntry())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<NextGoalEntry>) -> Void) {
        // The app and the intents reload timelines on every change; the only change nobody makes is quiet mode running out.
        let quietUntil = QuietMode.until
        completion(Timeline(entries: [currentEntry()], policy: quietUntil.map { $0 < .distantFuture ? .after($0) : .never } ?? .never))
    }

    private func currentEntry() -> NextGoalEntry {
        let goals = (try? GoalStore.remaining(in: ModelContext(GoalStore.container))) ?? []
        let next = goals.first.map { NextGoalEntry.NextGoal(id: $0.id, title: $0.title, startedAt: $0.startedAt) }
        return NextGoalEntry(date: .now, goal: next, remaining: goals.count, quiet: QuietMode.isOn())
    }
}

struct NextGoalView: View {
    @Environment(\.widgetFamily) private var family
    let entry: NextGoalEntry

    var body: some View {
        if let goal = entry.goal {
            if family == .systemMedium {
                medium(goal)
            } else {
                small(goal)
            }
        } else {
            empty
        }
    }

    private func small(_ goal: NextGoalEntry.NextGoal) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if let startedAt = goal.startedAt {
                HStack(spacing: 8) {
                    Circle()
                        .fill(Theme.accent)
                        .frame(width: 10, height: 10)
                    SectionLabel("STARTED", size: 11)
                }
                Spacer(minLength: 4)
                Text(goal.title)
                    .font(Theme.text(14, weight: .bold))
                    .lineLimit(1)
                Text(startedAt, style: .timer)
                    .font(Theme.display(38))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                Spacer(minLength: 4)
                doneButton(goal, label: "DONE")
            } else {
                HStack(spacing: 8) {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(Theme.accent)
                        .frame(width: 28, height: 28)
                    SectionLabel("NEXT", size: 11)
                    Spacer(minLength: 0)
                    quietBadge
                }
                Spacer(minLength: 4)
                Text(goal.title)
                    .font(Theme.text(17, weight: .bold))
                    .lineLimit(3)
                Spacer(minLength: 4)
                doItButton(goal, height: 40)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private func medium(_ goal: NextGoalEntry.NextGoal) -> some View {
        HStack(spacing: 14) {
            HypeFigure(color: Theme.figure)
                .frame(width: 84, height: 110)
                .frame(width: 96, alignment: .bottom)
                .frame(maxHeight: .infinity, alignment: .bottom)
                .background(Theme.thumb, in: RoundedRectangle(cornerRadius: 12))
                .clipShape(RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    SectionLabel(header(for: goal), size: 11)
                    Spacer(minLength: 0)
                    quietBadge
                }
                Text(goal.title)
                    .font(Theme.text(19, weight: .bold))
                    .lineLimit(2)
                Spacer(minLength: 4)
                if let startedAt = goal.startedAt {
                    HStack(spacing: 8) {
                        Text(startedAt, style: .timer)
                            .font(Theme.display(30))
                            .monospacedDigit()
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                        doneButton(goal, label: "DONE")
                    }
                } else {
                    HStack(spacing: 8) {
                        doItButton(goal, height: 44)
                        Button(intent: CompleteGoalIntent(goalID: goal.id)) {
                            Image(systemName: "checkmark")
                                .font(.system(size: 18, weight: .bold))
                                .frame(width: 44, height: 44)
                                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.ink, lineWidth: 1.5))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Mark done")
                    }
                }
            }
        }
    }

    private var empty: some View {
        VStack(alignment: .leading, spacing: 0) {
            HazardStripes(stripe: 10)
                .frame(height: 10)
                .clipShape(RoundedRectangle(cornerRadius: 2))
            Spacer(minLength: 4)
            Text("NOTHING TO DO? SUSPICIOUS.")
                .font(Theme.display(family == .systemMedium ? 34 : 28))
                .lineLimit(3)
                .minimumScaleFactor(0.7)
            Spacer(minLength: 4)
            Text("+ Add a goal")
                .font(Theme.text(13, weight: .bold))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var quietBadge: some View {
        if entry.quiet {
            Image(systemName: "bell.slash.fill")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(Theme.muted)
                .accessibilityLabel("Quiet mode on")
        }
    }

    private func doItButton(_ goal: NextGoalEntry.NextGoal, height: CGFloat) -> some View {
        Button(intent: StartGoalIntent(goalID: goal.id)) {
            Text("DO IT")
                .font(Theme.display(20))
                .kerning(0.8)
                .frame(maxWidth: .infinity, minHeight: height)
                .background(Theme.accent, in: RoundedRectangle(cornerRadius: 10))
                .foregroundStyle(Theme.onAccent)
        }
        .buttonStyle(.plain)
    }

    private func doneButton(_ goal: NextGoalEntry.NextGoal, label: String) -> some View {
        Button(intent: CompleteGoalIntent(goalID: goal.id)) {
            Text(label)
                .font(Theme.display(20))
                .kerning(0.8)
                .frame(maxWidth: .infinity, minHeight: 40)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.ink, lineWidth: 1.5))
        }
        .buttonStyle(.plain)
    }

    private func header(for goal: NextGoalEntry.NextGoal) -> String {
        if goal.startedAt != nil { return "STARTED" }
        let more = entry.remaining - 1
        return more > 0 ? "NEXT UP · \(more) MORE TODAY" : "NEXT UP"
    }
}

struct NextGoalWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "NextGoal", provider: NextGoalProvider()) { entry in
            NextGoalView(entry: entry)
                .foregroundStyle(Theme.ink)
                .containerBackground(Theme.widget, for: .widget)
        }
        .configurationDisplayName("Next goal")
        .description("Your next goal and a DO IT button.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
