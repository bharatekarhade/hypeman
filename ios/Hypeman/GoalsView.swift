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
    @State private var activePack = PackLibrary.shared.active
    @State private var showPacks = false

    private let log = Logger(subsystem: "com.hypeman.app", category: "GoalsView")

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
        .fullScreenCover(item: $focus) { focus in
            focusScreen(focus)
        }
        .sheet(isPresented: $showPacks) {
            PacksView(active: $activePack)
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
        return HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 4) {
                Text("TODAY")
                    .font(Theme.display(46))
                    .accessibilityAddTraits(.isHeader)
                Text("\(total) \(total == 1 ? "goal" : "goals") · \(doneToday) done")
                    .font(Theme.text(14, relativeTo: .subheadline))
                    .foregroundStyle(Theme.muted)
            }
            Spacer(minLength: 12)
            Button { showPacks = true } label: {
                HStack(spacing: 6) {
                    Image(systemName: "megaphone.fill")
                        .font(.system(size: 12, weight: .bold))
                    Text(activePack?.title ?? "Default pack")
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
            .accessibilityLabel("Packs, using \(activePack?.title ?? "Default pack")")
        }
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
                DoneView(goal: goal, next: goals.first, pack: activePack, doNext: doIt, close: { self.focus = nil })
            } else {
                DoItView(goal: goal, pack: activePack, done: { finish(goal) }, close: { self.focus = nil })
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

    private func doIt(_ goal: Goal) {
        if run({ try GoalStore.start(goal.id, in: context) }) {
            YellPlayer.shared.play(.start)
            focus = Focus(goalID: goal.id, finished: false)
        }
    }

    private func finish(_ goal: Goal) {
        if run({ try GoalStore.complete(goal.id, in: context) }) {
            YellPlayer.shared.play(.congrats)
            focus = Focus(goalID: goal.id, finished: true)
        }
    }

    private func complete(_ goal: Goal) {
        if run({ try GoalStore.complete(goal.id, in: context) }) {
            YellPlayer.shared.play(.congrats)
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
