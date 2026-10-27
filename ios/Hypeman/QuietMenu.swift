import HypemanKit
import SwiftUI

/// Quiet mode (WG-06) under the day's count: whether reminders and the widget make sound, and a menu to change it.
struct QuietMenu: View {
    /// When quiet mode ends; nil when it's off.
    let until: Date?
    let set: (Date?) -> Void

    var body: some View {
        // Redrawn every minute, so the label turns back to "Sound on" when quiet mode runs out.
        TimelineView(.everyMinute) { context in
            let quiet = until.map { context.date < $0 } ?? false
            Menu {
                if quiet {
                    Button("Turn sound back on", systemImage: "bell.fill") { set(nil) }
                }
                Button("Quiet for 1 hour", systemImage: "clock") { set(.now.addingTimeInterval(60 * 60)) }
                Button("Quiet until \(Self.describe(QuietMode.nextMorning()))", systemImage: "moon.fill") {
                    set(QuietMode.nextMorning())
                }
                Button("Quiet until I turn it off", systemImage: "bell.slash.fill") { set(.distantFuture) }
            } label: {
                Label(quiet ? label(until!) : "Sound on", systemImage: quiet ? "bell.slash.fill" : "bell.fill")
                    .font(Theme.text(13, weight: .bold, relativeTo: .caption))
                    .foregroundStyle(quiet ? Theme.ink : Theme.muted)
                    .frame(minHeight: 32)
                    .contentShape(Rectangle())
            }
            .accessibilityHint("Reminders and the widget’s DO IT skip sound while quiet mode is on.")
        }
    }

    private func label(_ until: Date) -> String {
        until == .distantFuture ? "Quiet" : "Quiet until \(Self.describe(until))"
    }

    /// "9:41 AM", "tomorrow, 8:00 AM" or a date further out.
    static func describe(_ date: Date) -> String {
        let time = date.formatted(date: .omitted, time: .shortened)
        if Calendar.current.isDateInToday(date) {
            return time
        }
        if Calendar.current.isDateInTomorrow(date) {
            return "tomorrow, \(time)"
        }
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}
