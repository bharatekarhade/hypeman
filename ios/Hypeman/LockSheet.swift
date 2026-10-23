import FamilyControls
import HypemanKit
import SwiftData
import SwiftUI

/// Picks the apps that lock while a goal is overdue (HM-04). Asks for Screen Time permission first.
struct LockSheet: View {
    @Environment(\.modelContext) private var context
    @State private var authorized = AppLock.isAuthorized
    @State private var selection = AppLock.selection
    @State private var showPicker = false
    @State private var authorizationFailed = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("LOCK MY APPS")
                    .font(Theme.display(36))
                    .accessibilityAddTraits(.isHeader)
                Text("When a reminder goes off and the goal still isn’t started, these apps stay locked until you hit DO IT.")
                    .font(Theme.text(16, relativeTo: .subheadline))
                    .foregroundStyle(Theme.muted)
            }
            if authorized {
                picked
                primaryButton(selection.isEmpty ? "PICK APPS" : "CHANGE APPS") { showPicker = true }
                if !selection.isEmpty {
                    Button("Turn off") { selection = FamilyActivitySelection() }
                        .font(Theme.text(15, weight: .bold, relativeTo: .subheadline))
                        .foregroundStyle(Theme.muted)
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
            } else {
                primaryButton("ALLOW SCREEN TIME", action: authorize)
                Text("Hypeman uses Screen Time to lock apps. It never sees which apps you use.")
                    .font(Theme.text(14, relativeTo: .footnote))
                    .foregroundStyle(Theme.muted)
            }
        }
        .padding(EdgeInsets(top: 28, leading: 20, bottom: 12, trailing: 20))
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Theme.background)
        .foregroundStyle(Theme.ink)
        .presentationDetents([.medium, .large])
        .familyActivityPicker(isPresented: $showPicker, selection: $selection)
        .onChange(of: selection) { _, selection in
            AppLock.selection = selection
            AppLock.sync(in: context)
        }
        .alert("Screen Time isn’t available", isPresented: $authorizationFailed) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Allow Hypeman in Screen Time to lock apps. If Screen Time is restricted on this iPhone, it can’t be used.")
        }
    }

    @ViewBuilder
    private var picked: some View {
        if selection.isEmpty {
            Text("No apps picked, so nothing locks.")
                .font(Theme.text(15, weight: .medium, relativeTo: .subheadline))
        } else {
            HStack(spacing: 10) {
                ForEach(Array(selection.applicationTokens.prefix(6)), id: \.self) { token in
                    Label(token)
                        .labelStyle(.iconOnly)
                        .frame(width: 36, height: 36)
                }
                ForEach(Array(selection.categoryTokens.prefix(3)), id: \.self) { token in
                    Label(token)
                        .labelStyle(.iconOnly)
                        .frame(width: 36, height: 36)
                }
                Spacer(minLength: 0)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(summary)
            Text(summary)
                .font(Theme.text(15, weight: .medium, relativeTo: .subheadline))
        }
    }

    private var summary: String {
        var parts: [String] = []
        let apps = selection.applicationTokens.count
        let categories = selection.categoryTokens.count
        let sites = selection.webDomainTokens.count
        if apps > 0 { parts.append("\(apps) \(apps == 1 ? "app" : "apps")") }
        if categories > 0 { parts.append("\(categories) \(categories == 1 ? "category" : "categories")") }
        if sites > 0 { parts.append("\(sites) \(sites == 1 ? "site" : "sites")") }
        return parts.joined(separator: " · ") + " lock when a goal is overdue."
    }

    private func primaryButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(Theme.display(22, relativeTo: .headline))
                .kerning(1.3)
                .frame(maxWidth: .infinity, minHeight: 52)
                .background(Theme.accent, in: RoundedRectangle(cornerRadius: 14))
                .foregroundStyle(Theme.onAccent)
        }
        .buttonStyle(.plain)
    }

    private func authorize() {
        Task {
            do {
                try await AppLock.requestAuthorization()
                authorized = true
                showPicker = true
            } catch {
                authorizationFailed = true
            }
        }
    }
}
