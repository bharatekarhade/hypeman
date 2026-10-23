import ActivityKit
import HypemanKit
import SwiftUI
import WidgetKit

/// The started goal on the Lock Screen and in the Dynamic Island, counting up until it's done (DI-05).
struct GoalActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: GoalActivityAttributes.self) { context in
            LockScreenView(goalID: context.attributes.goalID, state: context.state)
                .activityBackgroundTint(Theme.stage)
                .activitySystemActionForegroundColor(Theme.accent)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label {
                        Text("STARTED")
                            .font(Theme.text(12, weight: .bold))
                            .kerning(1.2)
                    } icon: {
                        Circle()
                            .fill(Theme.accent)
                            .frame(width: 8, height: 8)
                    }
                    .foregroundStyle(Theme.accent)
                    .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    timer(context.state.startedAt)
                        .font(Theme.display(24))
                        .foregroundStyle(.white)
                        .frame(maxWidth: 90, alignment: .trailing)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack(spacing: 12) {
                        Text(context.state.title)
                            .font(Theme.text(16, weight: .bold))
                            .foregroundStyle(.white)
                            .lineLimit(2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        DoneButton(goalID: context.attributes.goalID, height: 40)
                            .frame(width: 96)
                    }
                    .padding(.horizontal, 4)
                }
            } compactLeading: {
                Image(systemName: "megaphone.fill")
                    .foregroundStyle(Theme.accent)
            } compactTrailing: {
                timer(context.state.startedAt)
                    .font(Theme.text(14, weight: .bold))
                    .foregroundStyle(Theme.accent)
                    .frame(maxWidth: 52)
            } minimal: {
                Image(systemName: "megaphone.fill")
                    .foregroundStyle(Theme.accent)
            }
            .keylineTint(Theme.accent)
        }
    }
}

private struct LockScreenView: View {
    let goalID: UUID
    let state: GoalActivityAttributes.ContentState

    var body: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Circle()
                        .fill(Theme.accent)
                        .frame(width: 10, height: 10)
                    Text("STARTED")
                        .font(Theme.text(12, weight: .bold))
                        .kerning(1.2)
                        .foregroundStyle(Theme.accent)
                }
                Text(state.title)
                    .font(Theme.text(17, weight: .bold))
                    .lineLimit(2)
                timer(state.startedAt)
                    .font(Theme.display(34))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            DoneButton(goalID: goalID, height: 56)
                .frame(width: 100)
        }
        .foregroundStyle(.white)
        .padding(16)
    }
}

private struct DoneButton: View {
    let goalID: UUID
    let height: CGFloat

    var body: some View {
        Button(intent: CompleteGoalIntent(goalID: goalID)) {
            Text("DONE")
                .font(Theme.display(22))
                .kerning(0.8)
                .frame(maxWidth: .infinity, minHeight: height)
                .background(Theme.accent, in: RoundedRectangle(cornerRadius: 12))
                .foregroundStyle(Theme.onAccent)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Mark done")
    }
}

/// Counts up from the start, like the DO IT screen.
private func timer(_ startedAt: Date) -> some View {
    Text(startedAt, style: .timer)
        .monospacedDigit()
        .lineLimit(1)
        .multilineTextAlignment(.trailing)
}

#Preview("Lock Screen", as: .content, using: GoalActivityAttributes(goalID: UUID())) {
    GoalActivityWidget()
} contentStates: {
    GoalActivityAttributes.ContentState(title: "Leg day at the gym", startedAt: .now.addingTimeInterval(-754))
}
