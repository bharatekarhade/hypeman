import HypemanKit
import OSLog
import SwiftData
import SwiftUI

struct GoalsView: View {
    @Environment(\.modelContext) private var context
    @Query(filter: #Predicate<Goal> { $0.completedAt == nil }, sort: \Goal.sortIndex) private var goals: [Goal]
    @State private var newTitle = ""
    @Query(filter: #Predicate<Goal> { $0.completedAt != nil }, sort: \Goal.completedAt, order: .reverse) private var completedGoals: [Goal]
    @State private var showDone = false
    @State private var editingGoal: Goal?
    @State private var editedTitle = ""
    @State private var showEditor = false
    @State private var showSaveError = false

    private let log = Logger(subsystem: "com.hypeman.app", category: "GoalsView")

    var body: some View {
        NavigationStack {
            List {
                ForEach(goals) { goal in
                    GoalRow(
                        goal: goal,
                        isNext: goal.id == goals.first?.id,
                        start: { start(goal) },
                        complete: { complete(goal) }
                    )
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
                    DisclosureGroup("Done · \(completedGoals.count)", isExpanded: $showDone) {
                        ForEach(completedGoals) { goal in
                            HStack {
                                Text(goal.title).strikethrough().foregroundStyle(.secondary)
                                Spacer()
                                Button("Undo") {
                                    run { try GoalStore.undoCompletion(goal.id, in: context) }
                                }
                                .buttonStyle(.bordered)
                                .accessibilityLabel("Undo completion of \(goal.title)")
                            }
                        }
                    }
                }
            }
            .overlay {
                if goals.isEmpty && completedGoals.isEmpty {
                    ContentUnavailableView("Nothing to do? Suspicious.", systemImage: "checkmark", description: Text("Add a goal below."))
                }
            }
            .safeAreaInset(edge: .bottom) {
                HStack {
                    TextField("Add a goal", text: $newTitle)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit(add)
                    Button("Add goal", systemImage: "plus", action: add)
                        .labelStyle(.iconOnly)
                        .buttonStyle(.borderedProminent)
                        .disabled(newTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                .padding()
                .background(.bar)
            }
            .navigationTitle("Today")
            .toolbar { EditButton() }
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
    }

    private func add() {
        if run({ try GoalStore.add(title: newTitle, in: context) }) {
            newTitle = ""
        }
    }

    private func start(_ goal: Goal) {
        if run({ try GoalStore.start(goal.id, in: context) }) {
            YellPlayer.shared.play(.start)
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

private struct GoalRow: View {
    let goal: Goal
    let isNext: Bool
    let start: () -> Void
    let complete: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(goal.title)
                    .font(isNext ? .headline : .body)
                if let startedAt = goal.startedAt {
                    Text(startedAt, style: .timer)
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            if goal.startedAt != nil {
                Button("Done", action: complete)
                    .buttonStyle(.bordered)
            } else if isNext {
                Button("DO IT", action: start)
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.accent)
                    .foregroundStyle(.black)
            }
        }
    }
}

#Preview {
    GoalsView()
        .modelContainer(for: Goal.self, inMemory: true)
}
