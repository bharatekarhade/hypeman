/// The moment in a goal's life a clip plays for. Raw values match the backend's `role` column.
public enum ClipRole: String, CaseIterable, Codable, Sendable {
    case start = "START"
    case reminder = "REMINDER"
    case nag = "NAG"
    case congrats = "CONGRATS"

    public static let maxDuration: Duration = .seconds(10)
}
