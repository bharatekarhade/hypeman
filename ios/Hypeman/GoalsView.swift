import HypemanKit
import OSLog
import SwiftData
import SwiftUI

struct GoalsView: View {
    @Environment(\.modelContext) private var context
    @Query(filter: #Predicate<Goal> { $0.completedAt == nil }, sort: \Goal.sortIndex) private var goals: [Goal]
    @Query(filter: #Predicate<Goal> { $0.completedAt != nil }, sort: \Goal.completedAt, order: .reverse) private var completedGoals: [Goal]
    @State private var newTitle = ""
    @State private var showDone = false
    @State private var editingGoal: Goal?
    @State private var editedTitle = ""
    @State private var showEditor = false
    @State private var showSaveError = false
    @State private var focus: Focus?
    @State private var voiceTitle = GoalsView.defaultVoiceTitle
    @State private var showPacks = false
    @State private var voiceGoal: Goal?
    @State private var showLock = false
    @State private var showAccount = false
    @State private var showFriends = false
    @State private var store = Store.shared
    @State private var reminderGoal: Goal?
    @State private var quietUntil = QuietMode.until
    /// A tapped notification's goal, waiting for an open sheet to finish closing.
    @State private var pendingStart: UUID?

    private let log = Logger(subsystem: "com.hypeman.app", category: "GoalsView")

    private static var defaultVoiceTitle: String {
        let library = MotivatorLibrary.shared
        return library.motivator(library.defaultID)?.title ?? "Default pack"
    }

    var body: some View {
        List {
            header
                .plainRow(top: 8, bottom: 18)

            if goals.isEmpty {
                EmptyCard()
                    .plainRow(bottom: 8)
            }

            ForEach(goals) { goal in
                Group {
                    if goal.id == goals.first?.id {
                        NextCard(goal: goal, doIt: { doIt(goal) })
                            .plainRow(bottom: 8)
                    } else {
                        GoalRow(goal: goal)
                            .listRowInsets(EdgeInsets(top: 0, leading: 20, bottom: 0, trailing: 20))
                            .listRowBackground(Color.clear)
                            .listRowSeparatorTint(Theme.line)
                    }
                }
                .swipeActions(edge: .leading) {
                    Button("Done") { complete(goal) }
                        .tint(.green)
                }
                .contextMenu {
                    Button(goal.reminderAt == nil ? "Remind me" : "Change reminder", systemImage: "bell") {
                        reminderGoal = goal
                    }
                    Button("Voice", systemImage: "megaphone") {
                        voiceGoal = goal
                    }
                    Button("Edit title", systemImage: "pencil") {
                        editingGoal = goal
                        editedTitle = goal.title
                        showEditor = true
                    }
                }
            }
            .onMove(perform: move)
            .onDelete(perform: delete)

            if !completedGoals.isEmpty {
                Button {
                    withAnimation { showDone.toggle() }
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "chevron.right")
                            .font(.system(size: 13, weight: .heavy))
                            .rotationEffect(.degrees(showDone ? 90 : 0))
                        Text("DONE · \(completedGoals.count)")
                            .font(Theme.text(14, weight: .bold, relativeTo: .subheadline))
                            .kerning(1.4)
                    }
                    .foregroundStyle(Theme.muted)
                    .frame(minHeight: 52)
                }
                .buttonStyle(.plain)
                .plainRow()

                if showDone {
                    ForEach(completedGoals) { goal in
                        CompletedRow(goal: goal) {
                            run { try GoalStore.undoCompletion(goal.id, in: context) }
                        }
                        .listRowInsets(EdgeInsets(top: 0, leading: 20, bottom: 0, trailing: 20))
                        .listRowBackground(Color.clear)
                        .listRowSeparatorTint(Theme.line)
                    }
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .foregroundStyle(Theme.ink)
        .safeAreaInset(edge: .bottom) { addBar }
        .onChange(of: NotificationRouter.shared.goalToStart, initial: true) { _, goalID in
            startFromNotification(goalID)
        }
        .onChange(of: NotificationRouter.shared.openFriends, initial: true) { _, open in
            guard open else { return }
            NotificationRouter.shared.openFriends = false
            showPacks = false
            showLock = false
            showAccount = false
            reminderGoal = nil
            voiceGoal = nil
            showFriends = true
        }
        .onChange(of: store.isPro) {
            // Pro starting or ending turns hard mode and the Live Activity on or off (PY-06).
            AppLock.sync(in: context)
            LiveActivities.sync(in: context)
        }
        .fullScreenCover(item: $focus) { focus in
            focusScreen(focus)
        }
        .sheet(isPresented: $showPacks, onDismiss: {
            voiceTitle = Self.defaultVoiceTitle
            Reminders.sync(in: context)
            startPending()
        }) {
            VoicesView()
        }
        .sheet(item: $voiceGoal, onDismiss: startPending) { goal in
            // A voice per goal is Pro (GL-08).
            if store.isPro {
                VoicePickerSheet(goal: goal)
            } else {
                PaywallSheet(headline: "Give every goal its own voice.")
            }
        }
        .sheet(isPresented: $showLock, onDismiss: startPending) {
            // Hard mode is Pro (PY-03); buying it here turns the paywall into the app picker.
            if store.isPro {
                LockSheet()
            } else {
                PaywallSheet(headline: "Lock your apps until you DO IT.")
            }
        }
        .sheet(isPresented: $showAccount, onDismiss: startPending) {
            AccountSheet()
        }
        .sheet(isPresented: $showFriends, onDismiss: startPending) {
            FriendsView()
        }
        .sheet(item: $reminderGoal, onDismiss: startPending) { goal in
            ReminderSheet(goal: goal) { date in
                run { try GoalStore.setReminder(goal.id, at: date, in: context) }
            }
        }
        .alert("Edit goal", isPresented: $showEditor) {
            TextField("Goal title", text: $editedTitle)
            Button("Cancel", role: .cancel) { editingGoal = nil }
            Button("Save") {
                if let goal = editingGoal {
                    run { try GoalStore.rename(goal.id, title: editedTitle, in: context) }
                }
                editingGoal = nil
            }
            .disabled(editedTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        } message: {
            Text("Up to \(Goal.maxTitleLength) characters.")
        }
        .alert("Couldn’t save your changes", isPresented: $showSaveError) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Please try again. Your previous goals are unchanged.")
        }
    }

    private var header: some View {
        let doneToday = completedGoals.filter { $0.completedAt.map(Calendar.current.isDateInToday) ?? false }.count
        let total = goals.count + doneToday
        return VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .top) {
                Text("TODAY")
                    .font(Theme.display(46))
                    // The voice chip truncates first.
                    .fixedSize()
                    .accessibilityAddTraits(.isHeader)
                headerButtons
            }
            HStack {
                Text("\(total) \(total == 1 ? "goal" : "goals") · \(doneToday) done")
                    .font(Theme.text(14, relativeTo: .subheadline))
                    .foregroundStyle(Theme.muted)
                Spacer(minLength: 12)
                QuietMenu(until: quietUntil) { until in
                    QuietMode.set(until, in: context)
                    quietUntil = QuietMode.until
                }
            }
        }
    }

    @ViewBuilder
    private var headerButtons: some View {
        Spacer(minLength: 12)
        Button { showAccount = true } label: {
            Image(systemName: "person.fill")
                .font(.system(size: 13, weight: .bold))
                .frame(width: 36, height: 36)
                .background(Theme.surface, in: Circle())
                .overlay(Circle().stroke(Theme.line))
                .frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
        .padding(.top, 8)
        .accessibilityLabel("Account")
        Button { showFriends = true } label: {
            Image(systemName: "person.2.fill")
                .font(.system(size: 13, weight: .bold))
                .frame(width: 36, height: 36)
                .background(Theme.surface, in: Circle())
                .overlay(Circle().stroke(Theme.line))
                .frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
        .padding(.top, 8)
        .accessibilityLabel("Friends")
        Button { showLock = true } label: {
            Image(systemName: "lock.fill")
                .font(.system(size: 13, weight: .bold))
                .frame(width: 36, height: 36)
                .background(Theme.surface, in: Circle())
                .overlay(Circle().stroke(Theme.line))
                .frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
        .padding(.top, 8)
        .accessibilityLabel("Lock apps")
        Button { showPacks = true } label: {
            HStack(spacing: 6) {
                Image(systemName: "megaphone.fill")
                    .font(.system(size: 12, weight: .bold))
                Text(voiceTitle)
                    .font(Theme.text(13, weight: .bold, relativeTo: .caption))
                    .lineLimit(1)
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 36)
            .background(Theme.surface, in: Capsule())
            .overlay(Capsule().stroke(Theme.line))
            .frame(minHeight: 44)
        }
        .buttonStyle(.plain)
        .padding(.top, 8)
        .accessibilityLabel("Voices, using \(voiceTitle)")
    }

    private var addBar: some View {
        HStack(spacing: 10) {
            TextField("Add a goal", text: $newTitle)
                .font(Theme.text(16))
                .padding(.horizontal, 16)
                .frame(height: 52)
                .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.line))
                .submitLabel(.done)
                .onSubmit(add)
            Button(action: add) {
                Image(systemName: "plus")
                    .font(.system(size: 20, weight: .bold))
                    .frame(width: 52, height: 52)
                    .background(Theme.ink, in: RoundedRectangle(cornerRadius: 14))
                    .foregroundStyle(Theme.background)
            }
            .accessibilityLabel("Add goal")
            .disabled(newTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(Theme.background)
    }

    @ViewBuilder
    private func focusScreen(_ focus: Focus) -> some View {
        if let goal = (goals + completedGoals).first(where: { $0.id == focus.goalID }) {
            if focus.finished {
                DoneView(goal: goal, next: goals.first, doNext: doIt, close: { self.focus = nil })
            } else {
                DoItView(goal: goal, done: { finish(goal) }, close: { self.focus = nil })
            }
        } else {
            // The goal was deleted from the widget or another device while this screen was up.
            Color.clear.onAppear { self.focus = nil }
        }
    }

    private func add() {
        if run({ try GoalStore.add(title: newTitle, in: context) }) {
            newTitle = ""
        }
    }

    /// A tapped reminder or nag: DO IT for that goal, if it's still on the list. An open sheet would block the DO IT
    /// screen, so it's closed first and the goal starts once it's gone.
    private func startFromNotification(_ goalID: UUID?) {
        guard let goalID else { return }
        NotificationRouter.shared.goalToStart = nil
        if showPacks || showLock || showAccount || showFriends || reminderGoal != nil || voiceGoal != nil {
            pendingStart = goalID
            showPacks = false
            showLock = false
            showAccount = false
            showFriends = false
            reminderGoal = nil
            voiceGoal = nil
        } else {
            start(goalID)
        }
    }

    private func startPending() {
        guard let goalID = pendingStart else { return }
        pendingStart = nil
        start(goalID)
    }

    private func start(_ goalID: UUID) {
        if let goal = goals.first(where: { $0.id == goalID }) {
            doIt(goal)
        }
    }

    private func doIt(_ goal: Goal) {
        if run({ try GoalStore.start(goal.id, in: context) }) {
            YellPlayer.shared.play(.start, motivatorID: goal.motivatorID)
            focus = Focus(goalID: goal.id, finished: false)
        }
    }

    private func finish(_ goal: Goal) {
        if run({ try GoalStore.complete(goal.id, in: context) }) {
            YellPlayer.shared.play(.congrats, motivatorID: goal.motivatorID)
            focus = Focus(goalID: goal.id, finished: true)
        }
    }

    private func complete(_ goal: Goal) {
        if run({ try GoalStore.complete(goal.id, in: context) }) {
            YellPlayer.shared.play(.congrats, motivatorID: goal.motivatorID)
        }
    }

    private func move(from source: IndexSet, to destination: Int) {
        var reordered = goals
        reordered.move(fromOffsets: source, toOffset: destination)
        run { try GoalStore.reorder(reordered, in: context) }
    }

    private func delete(at offsets: IndexSet) {
        for goal in offsets.map({ goals[$0] }) {
            run { try GoalStore.delete(goal, in: context) }
        }
    }

    @discardableResult
    private func run(_ action: () throws -> Void) -> Bool {
        do {
            try action()
            return true
        } catch {
            log.error("Goal update failed: \(error)")
            showSaveError = true
            return false
        }
    }
}

/// Which goal the full-screen DO IT flow shows, and whether it has reached the Done screen. The id is fixed so moving
/// from DO IT to Done, or on to the next goal, swaps the screen in place instead of dismissing and re-presenting it.
private struct Focus: Identifiable {
    var id: String { "focus" }
    var goalID: UUID
    var finished: Bool
}

private struct NextCard: View {
    let goal: Goal
    let doIt: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            HazardStripes()
                .frame(height: 10)
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    SectionLabel(goal.startedAt == nil ? "NEXT" : "STARTED")
                    Spacer()
                    if let startedAt = goal.startedAt {
                        Text(startedAt, style: .timer)
                            .font(Theme.text(12, relativeTo: .caption).monospacedDigit())
                            .foregroundStyle(Theme.muted)
                    } else if let reminderAt = goal.reminderAt {
                        Text(reminderAt, format: .dateTime.hour().minute())
                            .font(Theme.text(12, relativeTo: .caption))
                            .foregroundStyle(Theme.muted)
                    }
                }
                Text(goal.title)
                    .font(Theme.text(24, weight: .bold, relativeTo: .title2))
                    .lineSpacing(2)
                Button(action: doIt) {
                    Text("DO IT")
                        .font(Theme.display(26, relativeTo: .title2))
                        .kerning(1.5)
                        .frame(maxWidth: .infinity, minHeight: 56)
                        .background(Theme.accent, in: RoundedRectangle(cornerRadius: 14))
                        .foregroundStyle(Theme.onAccent)
                }
                .buttonStyle(.plain)
            }
            .padding(EdgeInsets(top: 16, leading: 18, bottom: 18, trailing: 18))
        }
        .background(Theme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 20))
    }
}

private struct GoalRow: View {
    let goal: Goal

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "line.3.horizontal")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Theme.muted)
                .accessibilityHidden(true)
            Text(goal.title)
                .font(Theme.text(17, weight: .medium))
                .frame(maxWidth: .infinity, alignment: .leading)
            if let reminderAt = goal.reminderAt {
                Text(reminderAt, format: .dateTime.hour().minute())
                    .font(Theme.text(14, relativeTo: .subheadline))
                    .foregroundStyle(Theme.muted)
            }
        }
        .frame(minHeight: 56)
    }
}

private struct CompletedRow: View {
    let goal: Goal
    let undo: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "checkmark")
                .font(.system(size: 15, weight: .bold))
                .accessibilityHidden(true)
            Text(goal.title)
                .font(Theme.text(17, weight: .medium))
                .strikethrough()
                .foregroundStyle(Theme.muted)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button("Undo", action: undo)
                .font(Theme.text(14, weight: .bold, relativeTo: .subheadline))
                .foregroundStyle(Theme.ink)
                .padding(.horizontal, 14)
                .frame(minHeight: 36)
                .overlay(Capsule().stroke(Theme.line))
                .buttonStyle(.plain)
                .accessibilityLabel("Undo completion of \(goal.title)")
        }
        .frame(minHeight: 56)
    }
}

private struct EmptyCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HazardStripes()
                .frame(height: 10)
            VStack(alignment: .leading, spacing: 8) {
                Text("NOTHING TO DO? SUSPICIOUS.")
                    .font(Theme.display(34))
                Text("Add a goal below.")
                    .font(Theme.text(15, relativeTo: .subheadline))
                    .foregroundStyle(Theme.muted)
            }
            .padding(18)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 20))
    }
}

private extension View {
    /// A list row with no background, separator or default insets, for the header and cards.
    func plainRow(top: CGFloat = 0, bottom: CGFloat = 0) -> some View {
        listRowInsets(EdgeInsets(top: top, leading: 20, bottom: bottom, trailing: 20))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }
}

#Preview {
    GoalsView()
        .modelContainer(for: Goal.self, inMemory: true)
}
