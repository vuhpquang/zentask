import QuickLookUI
import SwiftUI
import UniformTypeIdentifiers
@preconcurrency import ZenTaskShared

/// Task detail → Attachments: image thumbnails and file chips; "Add…", drop files here; click opens, right-click
/// for Quick Look, Save as… and Remove. Files over 10 MB are refused; images are shrunk.
struct AttachmentsSection: View {
    @Environment(TasksModel.self) private var tasks
    let task: ZTask
    @State private var error: String?
    @State private var dropping = false

    private var items: [Attachment] {
        _ = tasks.index // refresh with the data
        return tasks.core.attachmentActions.of(taskId: task.id)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            HStack {
                Label("Attachments", systemImage: "paperclip").font(.headline).foregroundStyle(.secondary)
                Spacer()
                Button("Add…", systemImage: "plus") { pick() }.buttonStyle(.borderless)
            }
            let list = items
            if list.isEmpty {
                Text("Drop files here, or Add…. Up to 10 MB each (bigger photos are shrunk to fit).")
                    .font(.callout).foregroundStyle(.secondary)
            } else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: Theme.Attach.tile), spacing: Theme.Space.s)], alignment: .leading, spacing: Theme.Space.s) {
                    ForEach(list, id: \.id) { a in tile(a) }
                }
            }
            if let error { Text(error).font(.caption).foregroundStyle(.red) }
        }
        .padding(dropping ? Theme.Space.xs : 0)
        .background(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous).strokeBorder(.tint, lineWidth: dropping ? 2 : 0))
        .dropDestination(for: URL.self) { urls, _ in
            add(urls.filter(\.isFileURL)); return true
        } isTargeted: { dropping = $0 }
    }

    @ViewBuilder
    private func tile(_ a: Attachment) -> some View {
        let url = tasks.files.url(a.fileId)
        VStack(spacing: Theme.Space.xxs) {
            ZStack {
                RoundedRectangle(cornerRadius: Theme.Attach.radius, style: .continuous).fill(.quaternary)
                if a.isImage, let url, let image = NSImage(contentsOf: url) {
                    Image(nsImage: image).resizable().scaledToFill()
                        .frame(width: Theme.Attach.tile, height: Theme.Attach.tile)
                        .clipShape(RoundedRectangle(cornerRadius: Theme.Attach.radius, style: .continuous))
                } else if url == nil {
                    ProgressView().controlSize(.small)
                } else {
                    Image(nsImage: NSWorkspace.shared.icon(for: UTType(mimeType: a.mime) ?? .data)).resizable().scaledToFit().padding(Theme.Space.m)
                }
            }
            .frame(width: Theme.Attach.tile, height: Theme.Attach.tile)
            Text(a.name).font(.caption).lineLimit(1).truncationMode(.middle).frame(width: Theme.Attach.tile)
            Text(ByteCountFormatter.string(fromByteCount: a.size, countStyle: .file)).font(.caption2).foregroundStyle(.secondary)
        }
        .contentShape(Rectangle())
        .onTapGesture { if let url { NSWorkspace.shared.open(url.withName(a.name)) } }
        .help(url == nil ? "Downloading…" : "Open \(a.name)")
        .contextMenu {
            Button("Open") { if let url { NSWorkspace.shared.open(url.withName(a.name)) } }.disabled(url == nil)
            Button("Save As…") { if let url { save(url, name: a.name) } }.disabled(url == nil)
            Divider()
            Button("Remove", role: .destructive) { tasks.core.attachmentActions.remove(id: a.id) }
        }
    }

    private func pick() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        if panel.runModal() == .OK { add(panel.urls) }
    }

    private func add(_ urls: [URL]) {
        error = nil
        for url in urls {
            do { try tasks.files.add(url, to: task.id) } catch { self.error = error.localizedDescription }
        }
    }

    private func save(_ url: URL, name: String) {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = name
        if panel.runModal() == .OK, let dest = panel.url {
            try? FileManager.default.removeItem(at: dest)
            try? FileManager.default.copyItem(at: url, to: dest)
        }
    }
}

private extension URL {
    /// A copy in the temporary folder under the attachment's real name, so the opening app shows (and types) it right.
    func withName(_ name: String) -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("ZenTaskAttachments/\(lastPathComponent)", isDirectory: true)
        let dest = dir.appendingPathComponent(name)
        if !FileManager.default.fileExists(atPath: dest.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try? FileManager.default.copyItem(at: self, to: dest)
        }
        return dest
    }
}
