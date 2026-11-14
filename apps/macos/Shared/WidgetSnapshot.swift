import Foundation

/// What the desktop widgets show, written by the app as JSON into its Application Support folder
/// (`widget.json`) and read by the sandboxed widget extension (read-only exception for that folder).
struct WidgetSnapshot: Codable, Equatable {
    struct TaskItem: Codable, Equatable {
        var id: String
        var title: String
        /// "10:30", "Yesterday"… (empty for an all-day task due today).
        var detail: String
        var overdue: Bool
        /// 0 none, 1 low, 3 medium, 5 high.
        var priority: Int
    }

    struct CountdownItem: Codable, Equatable {
        var name: String
        var daysLeft: Int
        var date: String
    }

    var today: [TaskItem] = []
    var countdowns: [CountdownItem] = []

    static let fileName = "widget.json"

    /// `~/Library/Application Support/ZenTask[-dev]/` (the real home, also from inside the sandbox).
    static func folder(dev: Bool) -> URL {
        let home: String = {
            if let pw = getpwuid(getuid()), let dir = pw.pointee.pw_dir { return String(cString: dir) }
            return NSHomeDirectory()
        }()
        return URL(fileURLWithPath: home).appendingPathComponent("Library/Application Support/\(dev ? "ZenTask-dev" : "ZenTask")", isDirectory: true)
    }

    static func load(dev: Bool) -> WidgetSnapshot? {
        guard let data = try? Data(contentsOf: folder(dev: dev).appendingPathComponent(fileName)) else { return nil }
        return try? JSONDecoder().decode(WidgetSnapshot.self, from: data)
    }
}
