import CoreText
import SwiftUI

/// The Hazard direction from the JustDoIt design canvas: near-black ground, hazard yellow, Anton over Archivo.
public enum Theme {
    /// Hazard yellow, #FFD400.
    public static let accent = Color(hex: 0xFFD400)
    /// Text on the accent, and the dark stripe colour.
    public static let onAccent = Color(hex: 0x0E0E0C)

    public static let background = Color(light: 0xF2EFE8, dark: 0x0E0E0C)
    public static let surface = Color(light: 0xFFFFFF, dark: 0x1C1B18)
    public static let ink = Color(light: 0x0E0E0C, dark: 0xF5F3EE)
    public static let muted = Color(light: 0x6E6A60, dark: 0x8E8A80)
    public static let line = Color(light: 0xD9D4C8, dark: 0x2A2925)
    public static let widget = Color(light: 0xFFFFFF, dark: 0x141412)
    public static let figure = Color(light: 0xCFC9BC, dark: 0x3A3833)
    public static let thumb = Color(light: 0xECE8DF, dark: 0x1C1B18)
    public static let stage = Color(hex: 0x1C1B18)
    public static let stageFigure = Color(hex: 0x3A3833)
    /// The "GET UP. DO IT. NOW." band under the hype figure: yellow on black in dark mode, black on yellow in light.
    public static let captionBackground = Color(light: 0xFFD400, dark: 0x0E0E0C)
    public static let captionInk = Color(light: 0x0E0E0C, dark: 0xFFD400)
    public static let chip = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark ? UIColor(Color(hex: 0x0E0E0C)).withAlphaComponent(0.75) : .white
    })
    public static let chipInk = Color(light: 0x0E0E0C, dark: 0xF5F3EE)

    /// Anton, for headlines, buttons and timers. Always uppercase in the design.
    public static func display(_ size: CGFloat, relativeTo style: Font.TextStyle = .largeTitle) -> Font {
        .custom("Anton", size: size, relativeTo: style)
    }

    /// Archivo, for everything else.
    public static func text(_ size: CGFloat, weight: Font.Weight = .regular, relativeTo style: Font.TextStyle = .body) -> Font {
        .custom("Archivo", size: size, relativeTo: style).weight(weight)
    }

    /// Registers the bundled fonts with this process. The app and the widget extension each call it once at launch.
    public static func registerFonts() {
        for name in ["Anton-Regular", "Archivo-Variable"] {
            guard let url = Bundle.module.url(forResource: name, withExtension: "ttf") else { continue }
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }
}

/// The small spaced-out caps label above a goal: NEXT, NOW, NEXT UP, STARTED.
public struct SectionLabel: View {
    private let text: String
    private let size: CGFloat

    public init(_ text: String, size: CGFloat = 12) {
        self.text = text
        self.size = size
    }

    public var body: some View {
        Text(text)
            .font(Theme.text(size, weight: .bold, relativeTo: .caption))
            .kerning(size * 0.14)
            .foregroundStyle(Theme.muted)
    }
}

/// The diagonal yellow and black band.
public struct HazardStripes: View {
    private let stripe: CGFloat
    private let first: Color
    private let second: Color

    public init(stripe: CGFloat = 12, first: Color = Theme.accent, second: Color = Theme.onAccent) {
        self.stripe = stripe
        self.first = first
        self.second = second
    }

    public var body: some View {
        Canvas { context, size in
            context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(second))
            var x = -size.height
            while x < size.width {
                var band = Path()
                band.move(to: CGPoint(x: x, y: size.height))
                band.addLine(to: CGPoint(x: x + size.height, y: 0))
                band.addLine(to: CGPoint(x: x + size.height + stripe, y: 0))
                band.addLine(to: CGPoint(x: x + stripe, y: size.height))
                band.closeSubpath()
                context.fill(band, with: .color(first))
                x += stripe * 2
            }
        }
        .accessibilityHidden(true)
    }
}

/// The placeholder hype figure: a head, shoulders and a raised fist, drawn in a 200×260 box.
public struct HypeFigure: View {
    private let color: Color
    private let armRaised: Bool
    private let sparks: Color?

    public init(color: Color, armRaised: Bool = true, sparks: Color? = nil) {
        self.color = color
        self.armRaised = armRaised
        self.sparks = sparks
    }

    public var body: some View {
        Canvas { context, size in
            let scale = min(size.width / 200, size.height / 260)
            context.translateBy(x: (size.width - 200 * scale) / 2, y: size.height - 260 * scale)
            context.scaleBy(x: scale, y: scale)

            context.fill(Path(ellipseIn: CGRect(x: 66, y: 28, width: 68, height: 68)), with: .color(color))
            var body = Path()
            body.move(to: CGPoint(x: 38, y: 260))
            body.addCurve(to: CGPoint(x: 100, y: 122), control1: CGPoint(x: 38, y: 168), control2: CGPoint(x: 66, y: 122))
            body.addCurve(to: CGPoint(x: 162, y: 260), control1: CGPoint(x: 134, y: 122), control2: CGPoint(x: 162, y: 168))
            body.closeSubpath()
            context.fill(body, with: .color(color))

            if armRaised {
                var arm = Path()
                arm.move(to: CGPoint(x: 146, y: 150))
                arm.addLine(to: CGPoint(x: 178, y: 78))
                context.stroke(arm, with: .color(color), style: StrokeStyle(lineWidth: 24, lineCap: .round))
            }
            if let sparks {
                var lines = Path()
                lines.move(to: CGPoint(x: 188, y: 54))
                lines.addLine(to: CGPoint(x: 198, y: 46))
                lines.move(to: CGPoint(x: 192, y: 74))
                lines.addLine(to: CGPoint(x: 200, y: 72))
                context.stroke(lines, with: .color(sparks), style: StrokeStyle(lineWidth: 5, lineCap: .round))
            }
        }
        .aspectRatio(200 / 260, contentMode: .fit)
        .accessibilityHidden(true)
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }

    init(light: UInt32, dark: UInt32) {
        self.init(uiColor: UIColor { traits in
            UIColor(Color(hex: traits.userInterfaceStyle == .dark ? dark : light))
        })
    }
}
