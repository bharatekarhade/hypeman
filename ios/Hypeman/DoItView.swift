import HypemanKit
import SwiftUI

/// The full-screen moment after DO IT: the hype figure, the goal and a running timer until DONE.
struct DoItView: View {
    let goal: Goal
    let done: () -> Void
    let close: () -> Void

    var body: some View {
        GeometryReader { proxy in
            VStack(spacing: 0) {
                stage
                    .frame(height: (proxy.size.height + proxy.safeAreaInsets.top) * 0.6)
                VStack(alignment: .leading, spacing: 6) {
                    SectionLabel("NOW")
                    Text(goal.title)
                        .font(Theme.text(22, weight: .bold, relativeTo: .title2))
                    Text(goal.startedAt ?? .now, style: .timer)
                        .font(Theme.display(112))
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.5)
                        .padding(.top, 4)
                    Spacer(minLength: 16)
                    Button(action: done) {
                        Text("DONE")
                            .font(Theme.display(28, relativeTo: .title2))
                            .kerning(1.7)
                            .frame(maxWidth: .infinity, minHeight: 64)
                            .background(Theme.accent, in: RoundedRectangle(cornerRadius: 16))
                            .foregroundStyle(Theme.onAccent)
                    }
                    .buttonStyle(.plain)
                }
                .padding(EdgeInsets(top: 22, leading: 20, bottom: 12, trailing: 20))
            }
            .ignoresSafeArea(edges: .top)
        }
        .background(Theme.background)
        .foregroundStyle(Theme.ink)
    }

    private var stage: some View {
        ZStack(alignment: .bottom) {
            Theme.stage
            HypeFigure(color: Theme.stageFigure, sparks: Theme.accent)
                .frame(maxWidth: 260, maxHeight: 338)
                .padding(.bottom, 64)
            Text("GET UP. DO IT. NOW.")
                .font(Theme.display(34))
                .foregroundStyle(Theme.captionInk)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
                .background(Theme.captionBackground)
        }
        .overlay(alignment: .top) {
            HStack {
                Text("Default pack")
                    .font(Theme.text(12, weight: .bold, relativeTo: .caption))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .background(Theme.chip, in: Capsule())
                    .foregroundStyle(Theme.chipInk)
                Spacer()
                Button(action: close) {
                    Image(systemName: "xmark")
                        .font(.system(size: 17, weight: .bold))
                        .frame(width: 44, height: 44)
                        .background(Color.black.opacity(0.6), in: Circle())
                        .foregroundStyle(Color.white)
                }
                .accessibilityLabel("Close")
            }
            .padding(.horizontal, 16)
            .padding(.top, 52)
        }
        .clipped()
    }
}

/// The payoff after DONE: the big yellow "Done." and what's next.
struct DoneView: View {
    let goal: Goal
    let next: Goal?
    let doNext: (Goal) -> Void
    let close: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 12) {
                    HypeFigure(color: Theme.accent, armRaised: false)
                        .frame(width: 40, height: 52)
                        .frame(width: 56, height: 56, alignment: .bottom)
                        .background(Theme.onAccent)
                        .clipShape(Circle())
                    Text("Hypeman says")
                        .font(Theme.text(14, weight: .bold, relativeTo: .subheadline))
                }
                Spacer(minLength: 16)
                Text("DONE.")
                    .font(Theme.display(150))
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                Text("“That’s what I’m talking about.”")
                    .font(Theme.text(20, weight: .bold, relativeTo: .title3))
                    .padding(.top, 10)
                    .padding(.bottom, 28)
                HazardStripes(stripe: 14, first: Theme.onAccent, second: Theme.accent)
                    .frame(height: 14)
            }
            .padding(EdgeInsets(top: 64, leading: 24, bottom: 28, trailing: 24))
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .background(Theme.accent)
            .foregroundStyle(Theme.onAccent)
            .ignoresSafeArea(edges: .top)
            .layoutPriority(1)

            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 12) {
                    Image(systemName: "checkmark")
                        .font(.system(size: 18, weight: .bold))
                        .accessibilityHidden(true)
                    Text([goal.title, timeSpent].compactMap { $0 }.joined(separator: " · "))
                        .font(Theme.text(17, weight: .medium))
                        .strikethrough()
                        .foregroundStyle(Theme.muted)
                }
                if let next {
                    VStack(alignment: .leading, spacing: 12) {
                        SectionLabel("NEXT UP")
                        Text(next.title)
                            .font(Theme.text(21, weight: .bold, relativeTo: .title3))
                        Button { doNext(next) } label: {
                            Text("DO IT")
                                .font(Theme.display(24, relativeTo: .title2))
                                .kerning(1.4)
                                .frame(maxWidth: .infinity, minHeight: 52)
                                .background(Theme.accent, in: RoundedRectangle(cornerRadius: 14))
                                .foregroundStyle(Theme.onAccent)
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(EdgeInsets(top: 16, leading: 18, bottom: 16, trailing: 18))
                    .background(Theme.surface, in: RoundedRectangle(cornerRadius: 18))
                }
                Spacer(minLength: 0)
                Button("Back to goals", action: close)
                    .font(Theme.text(15, weight: .bold, relativeTo: .subheadline))
                    .foregroundStyle(Theme.muted)
                    .padding(.horizontal, 16)
                    .frame(minHeight: 44)
                    .frame(maxWidth: .infinity)
            }
            .padding(EdgeInsets(top: 24, leading: 20, bottom: 8, trailing: 20))
        }
        .background(Theme.background)
        .foregroundStyle(Theme.ink)
    }

    /// "42 min" from DO IT to DONE, or nil when the goal was completed without being started.
    private var timeSpent: String? {
        guard let startedAt = goal.startedAt, let completedAt = goal.completedAt else { return nil }
        let minutes = max(1, Int((completedAt.timeIntervalSince(startedAt) / 60).rounded()))
        return minutes < 60 ? "\(minutes) min" : "\(minutes / 60) h \(minutes % 60) min"
    }
}
