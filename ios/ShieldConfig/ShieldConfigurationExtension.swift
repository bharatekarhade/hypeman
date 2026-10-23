import HypemanKit
import ManagedSettings
import ManagedSettingsUI
import UIKit

/// What a locked app shows: the overdue goal and a DO IT button (HM-05).
final class ShieldConfigurationExtension: ShieldConfigurationDataSource {
    override func configuration(shielding application: Application) -> ShieldConfiguration {
        shield()
    }

    override func configuration(shielding application: Application, in category: ActivityCategory) -> ShieldConfiguration {
        shield()
    }

    override func configuration(shielding webDomain: WebDomain) -> ShieldConfiguration {
        shield()
    }

    override func configuration(shielding webDomain: WebDomain, in category: ActivityCategory) -> ShieldConfiguration {
        shield()
    }

    private func shield() -> ShieldConfiguration {
        let goal = AppLock.overdueGoal()
        return ShieldConfiguration(
            backgroundBlurStyle: .systemUltraThinMaterialDark,
            backgroundColor: UIColor(red: 0.11, green: 0.106, blue: 0.094, alpha: 1),
            icon: UIImage(systemName: "megaphone.fill")?.withTintColor(.accent, renderingMode: .alwaysOriginal),
            title: ShieldConfiguration.Label(text: goal.map { "DO IT: \($0.title)" } ?? "DO IT", color: .white),
            subtitle: ShieldConfiguration.Label(text: "Start your goal and your apps come back.", color: .lightGray),
            primaryButtonLabel: ShieldConfiguration.Label(text: "DO IT", color: .black),
            primaryButtonBackgroundColor: .accent
        )
    }
}

private extension UIColor {
    /// Theme.accent.
    static let accent = UIColor(red: 1, green: 0.83, blue: 0, alpha: 1)
}
