import AppKit
import SwiftUI

/// A round button showing the chosen emoji (or a placeholder); click for a grid of common ones, the system
/// emoji viewer, or Remove.
struct EmojiPicker: View {
    @Binding var emoji: String
    var placeholder = "face.smiling"
    @State private var open = false

    private static let common = [
        "📋", "✅", "⭐️", "🔥", "💡", "🎯", "📌", "🗂️", "📅", "⏰", "🏠", "🏢",
        "💼", "🛒", "✈️", "🎁", "💰", "📚", "🎓", "💻", "🧠", "🏃", "💪", "🧘",
        "🍎", "💧", "😴", "☀️", "🌙", "❤️", "🎉", "🎂", "💍", "🏖️", "🎮", "🎵",
        "📝", "📈", "🐛", "🚀", "🔒", "🧹", "🌱", "🐶", "👶", "🙏", "⚡️", "🍀",
    ]

    var body: some View {
        Button { open = true } label: {
            ZStack {
                Circle().fill(.quaternary)
                if emoji.isEmpty {
                    Image(systemName: placeholder).foregroundStyle(.secondary)
                } else {
                    Text(emoji).font(.title3)
                }
            }
            .frame(width: Theme.Size.iconField, height: Theme.Size.iconField)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .help("Choose an emoji")
        .accessibilityLabel(emoji.isEmpty ? "Choose an emoji" : "Emoji \(emoji)")
        .popover(isPresented: $open, arrowEdge: .bottom) {
            VStack(alignment: .leading, spacing: Theme.Space.m) {
                LazyVGrid(columns: Array(repeating: GridItem(.fixed(Theme.Size.emojiCell), spacing: Theme.Space.xs), count: 8), spacing: Theme.Space.xs) {
                    ForEach(Self.common, id: \.self) { e in
                        Button { emoji = e; open = false } label: {
                            Text(e).font(.title3)
                                .frame(width: Theme.Size.emojiCell, height: Theme.Size.emojiCell)
                                .background(RoundedRectangle(cornerRadius: Theme.Radius.tile - Theme.Space.xs, style: .continuous)
                                    .fill(emoji == e ? AnyShapeStyle(.tint.opacity(Theme.Opacity.chip)) : AnyShapeStyle(.clear)))
                        }
                        .buttonStyle(.plain)
                    }
                }
                HStack {
                    TextField("Type or paste", text: $emoji).textFieldStyle(.roundedBorder).frame(width: Theme.Size.tagField)
                    Button("More…") { NSApp.orderFrontCharacterPalette(nil) }
                    Spacer()
                    Button("Remove") { emoji = ""; open = false }.disabled(emoji.isEmpty)
                }
            }
            .padding(Theme.Space.l)
        }
    }
}
