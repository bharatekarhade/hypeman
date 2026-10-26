import HypemanKit
import OSLog
import SwiftUI
import UserNotifications

/// Friends and the yells they send (FD-01 to FD-05): invite by link or code, an inbox to play, save, hide or report
/// yells, and recording your own. Needs an account, so it starts with signing in (AC-01).
struct FriendsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var account = Account.shared
    @State private var friends = Friends.shared
    @State private var saved = FriendsView.savedYells
    @State private var loading = false
    @State private var invite: Invite?
    @State private var code = ""
    @State private var name = ""
    @State private var busy = false
    @State private var showAccount = false
    @State private var showRecorder = false
    @State private var reporting: InboxItem?
    @State private var confirmBlock: User?
    @State private var failure: String?

    private let log = Logger(subsystem: "com.hypeman.app", category: "FriendsView")

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                header
                if account.isSignedIn {
                    signedIn
                } else {
                    signedOut
                }
            }
            .padding(EdgeInsets(top: 24, leading: 20, bottom: 24, trailing: 20))
        }
        .background(Theme.background)
        .foregroundStyle(Theme.ink)
        .refreshable { await refresh() }
        .task(id: account.isSignedIn) {
            await refresh()
            if account.isSignedIn {
                await PushRegistration.enable()
            }
        }
        .disabled(busy)
        .sheet(isPresented: $showAccount) { AccountSheet() }
        .sheet(isPresented: $showRecorder) { RecordYellSheet() }
        .confirmationDialog("Report this yell?", isPresented: Binding(get: { reporting != nil }, set: { if !$0 { reporting = nil } }),
                            titleVisibility: .visible, presenting: reporting) { item in
            ForEach(ReportReason.allCases, id: \.self) { reason in
                Button(reason.title) { run { try await friends.report(item, reason: reason) } }
            }
        } message: { _ in
            Text("It leaves your inbox now, and we’ll review it.")
        }
        .confirmationDialog("Block \(confirmBlock?.displayName ?? "this friend")?",
                            isPresented: Binding(get: { confirmBlock != nil }, set: { if !$0 { confirmBlock = nil } }),
                            titleVisibility: .visible, presenting: confirmBlock) { friend in
            Button("Block", role: .destructive) { run { try await friends.block(friend.id) } }
        } message: { _ in
            Text("They’re removed as a friend, their yells disappear, and they can’t add you again.")
        }
        .alert("Something went wrong", isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(failure ?? "")
        }
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 4) {
                Text("FRIENDS")
                    .font(Theme.display(46))
                    .accessibilityAddTraits(.isHeader)
                Text("Yell at each other to get it done.")
                    .font(Theme.text(14, relativeTo: .subheadline))
                    .foregroundStyle(Theme.muted)
            }
            Spacer()
            Button("Done") { dismiss() }
                .font(Theme.text(16, weight: .bold))
                .foregroundStyle(Theme.ink)
                .frame(minWidth: 44, minHeight: 44)
        }
    }

    private var signedOut: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Sign in to add friends and trade yells. Your goals stay on this iPhone either way.")
                .font(Theme.text(16, relativeTo: .subheadline))
                .foregroundStyle(Theme.muted)
            primaryButton("SIGN IN") { showAccount = true }
        }
    }

    @ViewBuilder
    private var signedIn: some View {
        if friends.me != nil, friends.me?.user.displayName == nil {
            nameCard
        }
        primaryButton("RECORD A YELL", systemImage: "mic.fill") { showRecorder = true }
            .disabled(friends.friends.isEmpty)
            .opacity(friends.friends.isEmpty ? 0.5 : 1)
        if loading && friends.inbox.isEmpty && friends.friends.isEmpty {
            ProgressView()
                .frame(maxWidth: .infinity, minHeight: 80)
        }
        inboxSection
        if !saved.isEmpty {
            savedSection
        }
        friendsSection
        inviteSection
    }

    private var nameCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("What should friends call you?")
                .font(Theme.text(17, weight: .bold))
            HStack(spacing: 10) {
                TextField("Your name", text: $name)
                    .font(Theme.text(16))
                    .textContentType(.givenName)
                    .padding(.horizontal, 14)
                    .frame(minHeight: 48)
                    .background(Theme.background, in: RoundedRectangle(cornerRadius: 12))
                    .onChange(of: name) { _, text in
                        if text.count > 40 { name = String(text.prefix(40)) }
                    }
                Button("Save") {
                    let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                    run { try await friends.setDisplayName(trimmed) }
                }
                .font(Theme.text(16, weight: .bold))
                .frame(minWidth: 64, minHeight: 48)
                .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(16)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 16))
    }

    private var inboxSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionLabel("INBOX")
            if friends.inbox.isEmpty {
                Text(friends.friends.isEmpty ? "Add a friend and their yells land here." : "No yells yet.")
                    .font(Theme.text(15, relativeTo: .subheadline))
                    .foregroundStyle(Theme.muted)
            }
            ForEach(friends.inbox) { item in
                InboxRow(item: item, isSaved: saved.contains { $0.id == "yell:\(item.yellId.uuidString.lowercased())" }) {
                    ClipPreview.shared.play(item.audioUrl)
                } save: {
                    run {
                        try await friends.save(item)
                        saved = Self.savedYells
                    }
                } hide: {
                    run { try await friends.hide(item) }
                } report: {
                    reporting = item
                } block: {
                    confirmBlock = item.sender
                }
            }
            if friends.hasMoreInbox {
                Button("Show older") { run { try await friends.loadMoreInbox() } }
                    .font(Theme.text(15, weight: .bold, relativeTo: .subheadline))
                    .foregroundStyle(Theme.muted)
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
        }
    }

    /// Saved yells are voices: they can yell for goals from the Voices screen.
    private var savedSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionLabel("SAVED")
            Text("Use them as a voice for your goals in Voices.")
                .font(Theme.text(13, relativeTo: .footnote))
                .foregroundStyle(Theme.muted)
            ForEach(saved) { yell in
                VoiceRow(voice: yell, isSelected: false, isLocked: false) {
                    if let clip = yell.clips.first, let url = MotivatorLibrary.shared.url(of: clip, in: yell) {
                        ClipPreview.shared.play(url)
                    }
                } remove: {
                    try? MotivatorLibrary.shared.remove(yell.id)
                    saved = Self.savedYells
                }
            }
        }
    }

    private static var savedYells: [Motivator] {
        MotivatorLibrary.shared.all.filter { $0.kind == .yell }
    }

    private var friendsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionLabel("FRIENDS")
            if friends.friends.isEmpty {
                Text("No friends yet. Invite one below.")
                    .font(Theme.text(15, relativeTo: .subheadline))
                    .foregroundStyle(Theme.muted)
            }
            ForEach(friends.friends, id: \.id) { friend in
                HStack {
                    Text(friend.displayName ?? "Friend")
                        .font(Theme.text(17, weight: .bold))
                    Spacer()
                    Menu {
                        Button("Remove friend", systemImage: "person.badge.minus") { run { try await friends.remove(friend) } }
                        Button("Block", systemImage: "hand.raised", role: .destructive) { confirmBlock = friend }
                    } label: {
                        Image(systemName: "ellipsis")
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel("Options for \(friend.displayName ?? "friend")")
                }
            }
        }
    }

    private var inviteSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionLabel("ADD A FRIEND")
            if let invite {
                VStack(alignment: .leading, spacing: 8) {
                    Text(invite.code)
                        .font(Theme.display(34))
                        .kerning(4)
                        .textSelection(.enabled)
                        .accessibilityLabel("Invite code \(invite.code.map(String.init).joined(separator: " "))")
                    Text("Works once, until \(invite.expiresAt.formatted(date: .abbreviated, time: .omitted)).")
                        .font(Theme.text(13, relativeTo: .footnote))
                        .foregroundStyle(Theme.muted)
                    ShareLink(item: invite.link, message: Text("Yell at me on Hypeman. My code: \(invite.code)")) {
                        Label("SHARE INVITE", systemImage: "square.and.arrow.up")
                            .font(Theme.display(20, relativeTo: .headline))
                            .kerning(1.2)
                            .frame(maxWidth: .infinity, minHeight: 48)
                            .background(Theme.accent, in: RoundedRectangle(cornerRadius: 12))
                            .foregroundStyle(Theme.onAccent)
                    }
                }
            } else {
                primaryButton("INVITE A FRIEND", systemImage: "person.badge.plus") {
                    run { invite = try await friends.createInvite() }
                }
            }
            HStack(spacing: 10) {
                TextField("Have a code?", text: $code)
                    .font(Theme.text(16))
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .padding(.horizontal, 14)
                    .frame(minHeight: 48)
                    .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
                Button("Add") {
                    run {
                        _ = try await friends.accept(code)
                        code = ""
                    }
                }
                .font(Theme.text(16, weight: .bold))
                .frame(minWidth: 64, minHeight: 48)
                .disabled(code.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }

    private func primaryButton(_ title: String, systemImage: String? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Group {
                if let systemImage {
                    Label(title, systemImage: systemImage)
                } else {
                    Text(title)
                }
            }
            .font(Theme.display(22, relativeTo: .headline))
            .kerning(1.3)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(Theme.accent, in: RoundedRectangle(cornerRadius: 14))
            .foregroundStyle(Theme.onAccent)
        }
        .buttonStyle(.plain)
    }

    private func refresh() async {
        loading = true
        defer { loading = false }
        do {
            try await friends.refresh()
        } catch {
            log.error("Could not load friends: \(error)")
            failure = (error as? APIError)?.message ?? "Couldn’t load your friends. Check your connection."
        }
    }

    private func run(_ action: @escaping () async throws -> Void) {
        busy = true
        Task {
            defer { busy = false }
            do {
                try await action()
            } catch {
                log.error("Friends action failed: \(error)")
                failure = (error as? APIError)?.message ?? "That didn’t work. Check your connection and try again."
            }
        }
    }
}

private struct InboxRow: View {
    let item: InboxItem
    let isSaved: Bool
    let play: () -> Void
    let save: () -> Void
    let hide: () -> Void
    let report: () -> Void
    let block: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Button(action: play) {
                Image(systemName: "play.fill")
                    .font(.system(size: 15, weight: .bold))
                    .frame(width: 44, height: 44)
                    .background(Theme.accent, in: Circle())
                    .foregroundStyle(Theme.onAccent)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Play yell from \(item.sender?.displayName ?? "a friend")")
            VStack(alignment: .leading, spacing: 4) {
                Text("“\(item.caption)”")
                    .font(Theme.text(16, weight: .semibold, relativeTo: .body))
                Text("\(item.sender?.displayName ?? "A friend") · \(item.role.title) · \(item.receivedAt.formatted(.relative(presentation: .named)))")
                    .font(Theme.text(13, relativeTo: .footnote))
                    .foregroundStyle(Theme.muted)
            }
            Spacer(minLength: 0)
            Menu {
                Button(isSaved ? "Saved" : "Save", systemImage: isSaved ? "checkmark" : "square.and.arrow.down", action: save)
                    .disabled(isSaved)
                Button("Hide", systemImage: "eye.slash", action: hide)
                Button("Report", systemImage: "exclamationmark.bubble", role: .destructive, action: report)
                if item.sender != nil {
                    Button("Block sender", systemImage: "hand.raised", role: .destructive, action: block)
                }
            } label: {
                Image(systemName: "ellipsis")
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel("Options")
        }
        .padding(12)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 16))
    }
}

/// Asks for notification permission if it hasn't been asked yet, then registers this phone for pushes, which is how
/// friends' yells arrive (FD-03).
enum PushRegistration {
    @MainActor
    static func enable() async {
        let center = UNUserNotificationCenter.current()
        var status = await center.notificationSettings().authorizationStatus
        if status == .notDetermined {
            _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
            status = await center.notificationSettings().authorizationStatus
        }
        if status == .authorized || status == .provisional {
            UIApplication.shared.registerForRemoteNotifications()
        }
    }

    /// Without asking: for launches and sign-ins, when the user already allowed notifications for reminders.
    @MainActor
    static func registerIfAllowed() async {
        guard Account.shared.isSignedIn else { return }
        let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        if status == .authorized || status == .provisional {
            UIApplication.shared.registerForRemoteNotifications()
        }
    }
}

extension ReportReason {
    var title: String {
        switch self {
        case .spam: "Spam"
        case .harassment: "Harassment or bullying"
        case .hate: "Hate"
        case .sexual: "Sexual content"
        case .other: "Something else"
        }
    }
}
