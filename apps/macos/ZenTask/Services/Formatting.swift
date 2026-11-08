import Foundation

/// User-facing summaries (sentence case, no trailing period).
enum Formatting {
    static func plan(created: Int, updated: Int, trashed: Int, restored: Int) -> String {
        var parts: [String] = []
        if created > 0 { parts.append("\(created) new") }
        if updated > 0 { parts.append("\(updated) updated") }
        if trashed > 0 { parts.append("\(trashed) to Trash") }
        if restored > 0 { parts.append("\(restored) restored") }
        return parts.isEmpty ? "Already up to date" : parts.joined(separator: ", ")
    }

    static func relative(_ date: Date?, now: Date = .now) -> String {
        guard let date else { return "Never" }
        return date.formatted(.relative(presentation: .named, unitsStyle: .wide))
    }
}
