import Foundation

/// The detail pane's editable text. Only what the user actually changed is saved: a field is written
/// when it differs from the value the edit started from, never because the task changed underneath
/// (e.g. by sync) — that would silently revert another device's edit.
struct DetailDraft {
    enum Field { case title, notes }

    var title: String
    var notes: String
    private var startTitle: String
    private var startNotes: String

    init(title: String, notes: String) {
        self.title = title
        self.notes = notes
        startTitle = title
        startNotes = notes
    }

    /// The new title to save, or nil (unchanged by the user, empty, or already equal).
    func titleToSave(current: String) -> String? {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard title != startTitle, !t.isEmpty, t != current else { return nil }
        return t
    }

    func notesToSave(current: String) -> String? {
        guard notes != startNotes, notes != current else { return nil }
        return notes
    }

    /// After saving, the saved value is the new starting point.
    mutating func saved(_ field: Field) {
        switch field {
        case .title: startTitle = title
        case .notes: startNotes = notes
        }
    }

    /// Follows the task's latest values for fields the user isn't editing.
    mutating func follow(title newTitle: String, notes newNotes: String, editing: Field?) {
        if editing != .title, title == startTitle { title = newTitle; startTitle = newTitle }
        if editing != .notes, notes == startNotes { notes = newNotes; startNotes = newNotes }
    }
}
