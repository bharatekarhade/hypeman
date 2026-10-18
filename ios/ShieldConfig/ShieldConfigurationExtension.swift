import ManagedSettings
import ManagedSettingsUI
import UIKit

final class ShieldConfigurationExtension: ShieldConfigurationDataSource {
    override func configuration(shielding application: Application) -> ShieldConfiguration {
        // TODO: "DO IT: " plus the overdue goal's title (HM-05).
        ShieldConfiguration(
            title: ShieldConfiguration.Label(text: "DO IT", color: .label),
            primaryButtonLabel: ShieldConfiguration.Label(text: "Open Hypeman", color: .black),
            primaryButtonBackgroundColor: UIColor(red: 1, green: 0.83, blue: 0, alpha: 1)
        )
    }
}
