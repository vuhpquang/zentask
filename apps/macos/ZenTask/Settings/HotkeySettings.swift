import AppKit
import SwiftUI

/// Settings → Hotkeys: global hotkeys you can record, and the app's shortcuts for reference.
struct HotkeySettings: View {
    @State private var version = 0

    private let appShortcuts: [(String, String)] = [
        ("New task", "⌘N"), ("Search", "⌘K"), ("Settings", "⌘,"),
        ("Tasks · Calendar · Habits · Countdown · Matrix", "⌘1 – ⌘5"),
        ("Complete the selected task", "Space"), ("Move the selected task to Trash", "⌫"),
        ("Calendar: today", "⌘T"), ("Calendar: previous / next", "⌘← / ⌘→"),
        ("Calendar: Day · Week · Month · Year · Agenda", "D W M Y A  or  1 – 5"),
        ("Calendar: zoom the hours", "⌘-scroll or pinch"), ("Calendar: show task checkboxes", "hold ⌥"),
        ("New task: pick a list · priority · tag", "~  !  #"),
    ]

    var body: some View {
        Form {
            Section {
                ForEach(HotKeys.Action.allCases, id: \.self) { action in
                    LabeledContent(action.title) {
                        HStack {
                            if HotKeys.shared.failed.contains(action) {
                                Text("In use by another app").font(.caption).foregroundStyle(.red)
                            }
                            ShortcutRecorder(combo: HotKeys.shared.combo(action)) { HotKeys.shared.set(action, $0); version += 1 }
                        }
                    }
                }
            } header: {
                Text("Global")
            } footer: {
                Text("Work even when ZenTask is in the background.").foregroundStyle(.secondary)
            }
            Section("In the app") {
                ForEach(appShortcuts, id: \.0) { title, keys in
                    LabeledContent(title) { Text(keys).font(.callout.monospaced()).foregroundStyle(.secondary) }
                }
            }
        }
        .formStyle(.grouped)
        .id(version)
    }
}

/// Click, then press a key combination (with ⌘, ⌥ or ⌃); Esc cancels, ⌫ clears.
struct ShortcutRecorder: View {
    let combo: KeyCombo?
    let set: (KeyCombo?) -> Void
    @State private var recording = false
    @State private var monitor: Any?

    var body: some View {
        HStack(spacing: Theme.Space.xs) {
            Button(recording ? "Press keys…" : (combo?.display ?? "Record shortcut")) { recording ? stop() : start() }
                .frame(minWidth: Theme.Size.tagField + Theme.Space.xxl)
            if combo != nil && !recording {
                Button { set(nil) } label: { Image(systemName: "xmark.circle.fill") }
                    .buttonStyle(.borderless)
                    .foregroundStyle(.secondary)
                    .help("Clear")
            }
        }
        .onDisappear(perform: stop)
    }

    private func start() {
        recording = true
        monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            MainActor.assumeIsolated {
                switch Int(event.keyCode) {
                case 53: stop() // Esc
                case 51: set(nil); stop() // Delete
                default:
                    let flags = event.modifierFlags.intersection([.command, .option, .control, .shift])
                    guard !flags.intersection([.command, .option, .control]).isEmpty else { return }
                    set(KeyCombo(keyCode: UInt32(event.keyCode), modifiers: flags))
                    stop()
                }
            }
            return nil
        }
    }

    private func stop() {
        recording = false
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil
    }
}
