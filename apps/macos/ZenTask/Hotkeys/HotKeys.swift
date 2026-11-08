import AppKit
import Carbon.HIToolbox
import SwiftUI

/// A key combination: Carbon key code + Cocoa modifier flags, stored as "code:flags".
struct KeyCombo: Equatable, Sendable {
    let keyCode: UInt32
    let modifiers: NSEvent.ModifierFlags.RawValue

    init(keyCode: UInt32, modifiers: NSEvent.ModifierFlags) {
        self.keyCode = keyCode
        self.modifiers = modifiers.intersection([.command, .option, .control, .shift]).rawValue
    }

    init?(stored: String) {
        let p = stored.split(separator: ":").compactMap { UInt($0) }
        guard p.count == 2 else { return nil }
        self.init(keyCode: UInt32(p[0]), modifiers: NSEvent.ModifierFlags(rawValue: p[1]))
    }

    var stored: String { "\(keyCode):\(modifiers)" }

    var flags: NSEvent.ModifierFlags { NSEvent.ModifierFlags(rawValue: modifiers) }

    var carbonModifiers: UInt32 {
        var m: UInt32 = 0
        if flags.contains(.command) { m |= UInt32(cmdKey) }
        if flags.contains(.option) { m |= UInt32(optionKey) }
        if flags.contains(.control) { m |= UInt32(controlKey) }
        if flags.contains(.shift) { m |= UInt32(shiftKey) }
        return m
    }

    /// "⌃⌥N"
    var display: String {
        var s = ""
        if flags.contains(.control) { s += "⌃" }
        if flags.contains(.option) { s += "⌥" }
        if flags.contains(.shift) { s += "⇧" }
        if flags.contains(.command) { s += "⌘" }
        return s + KeyCombo.keyName(keyCode)
    }

    static func keyName(_ code: UInt32) -> String {
        let special: [UInt32: String] = [
            UInt32(kVK_Space): "Space", UInt32(kVK_Return): "↩", UInt32(kVK_Tab): "⇥", UInt32(kVK_Escape): "⎋",
            UInt32(kVK_Delete): "⌫", UInt32(kVK_LeftArrow): "←", UInt32(kVK_RightArrow): "→", UInt32(kVK_UpArrow): "↑", UInt32(kVK_DownArrow): "↓",
            UInt32(kVK_F1): "F1", UInt32(kVK_F2): "F2", UInt32(kVK_F3): "F3", UInt32(kVK_F4): "F4", UInt32(kVK_F5): "F5", UInt32(kVK_F6): "F6",
        ]
        if let name = special[code] { return name }
        // The character the key types on the current layout.
        guard let source = TISCopyCurrentKeyboardLayoutInputSource()?.takeRetainedValue(),
              let raw = TISGetInputSourceProperty(source, kTISPropertyUnicodeKeyLayoutData) else { return "#\(code)" }
        let data = Unmanaged<CFData>.fromOpaque(raw).takeUnretainedValue() as Data
        var dead: UInt32 = 0
        var chars = [UniChar](repeating: 0, count: 4)
        var length = 0
        let status = data.withUnsafeBytes { buf -> OSStatus in
            guard let layout = buf.baseAddress?.assumingMemoryBound(to: UCKeyboardLayout.self) else { return -1 }
            return UCKeyTranslate(layout, UInt16(code), UInt16(kUCKeyActionDisplay), 0, UInt32(LMGetKbdType()),
                                  OptionBits(kUCKeyTranslateNoDeadKeysBit), &dead, chars.count, &length, &chars)
        }
        guard status == noErr, length > 0 else { return "#\(code)" }
        return String(utf16CodeUnits: chars, count: length).uppercased()
    }
}

/// Global hotkeys (they work while ZenTask is in the background), through Carbon's RegisterEventHotKey —
/// no Accessibility permission needed. Choices are stored in UserDefaults (`hotkey.<name>`).
@MainActor
final class HotKeys {
    static let shared = HotKeys()

    enum Action: UInt32, CaseIterable {
        case quickAdd = 1, toggleWindow = 2

        var title: String {
            switch self {
            case .quickAdd: "Quick Add"
            case .toggleWindow: "Show or hide ZenTask"
            }
        }

        var defaultsKey: String { "hotkey.\(self)" }

        /// ⌃⌥N for Quick Add (TickTick's ⌃⇧\\ stays free for TickTick while both are installed).
        var fallback: KeyCombo? {
            switch self {
            case .quickAdd: KeyCombo(keyCode: UInt32(kVK_ANSI_N), modifiers: [.control, .option])
            case .toggleWindow: nil
            }
        }
    }

    private var refs: [Action: EventHotKeyRef] = [:]
    private var handlers: [Action: () -> Void] = [:]
    private var installed = false
    /// Combos the system refused (taken by another app).
    private(set) var failed: Set<Action> = []

    func combo(_ action: Action) -> KeyCombo? {
        let d = UserDefaults.standard
        if let s = d.string(forKey: action.defaultsKey) { return s.isEmpty ? nil : KeyCombo(stored: s) }
        return action.fallback
    }

    func set(_ action: Action, _ combo: KeyCombo?) {
        UserDefaults.standard.set(combo?.stored ?? "", forKey: action.defaultsKey)
        register(action)
    }

    func start(handlers: [Action: () -> Void]) {
        self.handlers = handlers
        installHandler()
        Action.allCases.forEach(register)
    }

    private func register(_ action: Action) {
        if let ref = refs.removeValue(forKey: action) { UnregisterEventHotKey(ref) }
        failed.remove(action)
        guard installed, let combo = combo(action) else { return }
        var ref: EventHotKeyRef?
        let id = EventHotKeyID(signature: OSType(0x5A54_4B59), id: action.rawValue) // "ZTKY"
        let status = RegisterEventHotKey(combo.keyCode, combo.carbonModifiers, id, GetApplicationEventTarget(), 0, &ref)
        if status == noErr, let ref { refs[action] = ref } else { failed.insert(action) }
    }

    fileprivate func fire(_ raw: UInt32) {
        guard let action = Action(rawValue: raw) else { return }
        handlers[action]?()
    }

    private func installHandler() {
        guard !installed else { return }
        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        let status = InstallEventHandler(GetApplicationEventTarget(), { _, event, _ in
            var id = EventHotKeyID()
            GetEventParameter(event, EventParamName(kEventParamDirectObject), EventParamType(typeEventHotKeyID), nil, MemoryLayout<EventHotKeyID>.size, nil, &id)
            let raw = id.id
            DispatchQueue.main.async { MainActor.assumeIsolated { HotKeys.shared.fire(raw) } }
            return noErr
        }, 1, &spec, nil, nil)
        installed = status == noErr
    }
}

/// The floating Quick Add card (global hotkey): the new-task card in a panel above every app.
@MainActor
final class QuickAddWindow {
    static let shared = QuickAddWindow()
    private var panel: NSPanel?

    private final class KeyPanel: NSPanel {
        override var canBecomeKey: Bool { true }
        override func cancelOperation(_ sender: Any?) { close() }
    }

    func show(tasks: TasksModel) {
        panel?.close()
        let p = KeyPanel(contentRect: NSRect(x: 0, y: 0, width: Theme.Composer.width, height: 300),
                         styleMask: [.titled, .fullSizeContentView, .closable], backing: .buffered, defer: false)
        p.titleVisibility = .hidden
        p.titlebarAppearsTransparent = true
        p.isFloatingPanel = true
        p.level = .floating
        p.hidesOnDeactivate = false
        p.isReleasedWhenClosed = false
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        let host = NSHostingView(rootView: TaskComposer(start: nil) { [weak p] in p?.close() }.environment(tasks))
        p.contentView = host
        p.setContentSize(host.fittingSize)
        p.center()
        if var origin = p.screen.map({ NSPoint(x: $0.visibleFrame.midX - p.frame.width / 2, y: $0.visibleFrame.maxY - $0.visibleFrame.height * 0.28) }) {
            origin.y -= p.frame.height / 2
            p.setFrameOrigin(origin)
        }
        panel = p
        NSApp.activate()
        p.makeKeyAndOrderFront(nil)
    }
}

/// Shows ZenTask's main window, or hides the app when it's already in front.
@MainActor
func toggleMainWindow() {
    if NSApp.isActive, NSApp.windows.contains(where: { $0.isVisible && $0.isKeyWindow && !($0 is NSPanel) }) {
        NSApp.hide(nil)
    } else {
        NSApp.activate()
        NSApp.windows.first { $0.canBecomeMain && !($0 is NSPanel) }?.makeKeyAndOrderFront(nil)
    }
}
