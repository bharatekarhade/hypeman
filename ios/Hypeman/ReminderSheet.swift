import HypemanKit
import SwiftData
import SwiftUI

/// Sets or clears a goal's reminder: the REMINDER yell at that time, then the NAG ones until the goal is started.
struct ReminderSheet: View {
    let goal: Goal
    let save: (Date?) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    @State private var date: Date
    @State private var notificationsOff = false

    init(goal: Goal, save: @escaping (Date?) -> Void) {
        self.goal = goal
        self.save = save
        _date = State(initialValue: goal.reminderAt.flatMap { $0 > .now ? $0 : nil } ?? Self.nextQuarterHour())
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                Text("REMIND ME")
                    .font(Theme.display(36))
                    .accessibilityAddTraits(.isHeader)
                Text(goal.title)
                    .font(Theme.text(16, weight: .medium))
                    .foregroundStyle(Theme.muted)
                    .lineLimit(2)
            }
            DatePicker("Reminder", selection: $date, in: Date.now..., displayedComponents: [.date, .hourAndMinute])
                .datePickerStyle(.wheel)
                .labelsHidden()
                .frame(maxWidth: .infinity)
            Text(nagsNote)
                .font(Theme.text(14, relativeTo: .subheadline))
                .foregroundStyle(Theme.muted)
            Button(action: setReminder) {
                Text("SET REMINDER")
                    .font(Theme.display(22, relativeTo: .headline))
                    .kerning(1.3)
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .background(Theme.accent, in: RoundedRectangle(cornerRadius: 14))
                    .foregroundStyle(Theme.onAccent)
            }
            .buttonStyle(.plain)
            if goal.reminderAt != nil {
                Button("Remove reminder") {
                    save(nil)
                    dismiss()
                }
                .font(Theme.text(15, weight: .bold, relativeTo: .subheadline))
                .foregroundStyle(Theme.muted)
                .frame(maxWidth: .infinity, minHeight: 44)
            }
        }
        .padding(EdgeInsets(top: 28, leading: 20, bottom: 12, trailing: 20))
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Theme.background)
        .foregroundStyle(Theme.ink)
        .presentationDetents([.medium, .large])
        .alert("Notifications are off", isPresented: $notificationsOff) {
            Button("Open Settings") {
                if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            Button("Not now", role: .cancel) {}
        } message: {
            Text("The reminder is saved, but Hypeman can’t yell at you until you allow notifications.")
        }
    }

    private var nagsNote: String {
        let minutes = Reminders.nagDelays.map { "\(Int($0 / 60))" }.joined(separator: " and ")
        return "Not started by then? You’ll get nagged \(minutes) minutes later."
    }

    private func setReminder() {
        save(date)
        Task {
            if await Reminders.requestPermission() {
                // Scheduled again now that iOS will deliver them, in case this was the first permission prompt.
                Reminders.sync(in: context)
                dismiss()
            } else {
                notificationsOff = true
            }
        }
    }

    /// The next :00, :15, :30 or :45 at least five minutes away.
    private static func nextQuarterHour() -> Date {
        let soonest = Date.now.addingTimeInterval(5 * 60)
        let quarter: TimeInterval = 15 * 60
        return Date(timeIntervalSinceReferenceDate: (soonest.timeIntervalSinceReferenceDate / quarter).rounded(.up) * quarter)
    }
}
