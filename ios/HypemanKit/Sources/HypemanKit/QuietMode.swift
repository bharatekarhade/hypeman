import Foundation
import SwiftData
import WidgetKit

/// Quiet mode (WG-06): the widget's DO IT and the reminders and nags skip audio, for a while or until it's turned off.
/// Kept in the App Group so the widget's intent sees it too. Notifications are scheduled silent or not by whether quiet
/// mode covers the moment each one fires, so it ends by itself without the app running.
public enum QuietMode {
    private static let untilKey = "QuietMode.until"
    /// When "until morning" ends.
    public static let morningHour = 8

    /// When quiet mode ends; `Date.distantFuture` until it's turned off; nil when it's off.
    public static var until: Date? {
        guard let until = AppGroup.defaults?.object(forKey: untilKey) as? Date, until > .now else { return nil }
        return until
    }

    public static func isOn(at date: Date = .now) -> Bool {
        until.map { date < $0 } ?? false
    }

    /// Turns it on until that moment, or off with nil, then reschedules the reminders and redraws the widgets.
    public static func set(_ until: Date?, in context: ModelContext) {
        AppGroup.defaults?.set(until, forKey: untilKey)
        Reminders.sync(in: context)
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// The next 8 AM after `now`: later today before 8, tomorrow from 8 on.
    public static func nextMorning(after now: Date = .now, calendar: Calendar = .current) -> Date {
        calendar.nextDate(after: now, matching: DateComponents(hour: morningHour, minute: 0, second: 0),
                          matchingPolicy: .nextTime) ?? now.addingTimeInterval(12 * 60 * 60)
    }
}
