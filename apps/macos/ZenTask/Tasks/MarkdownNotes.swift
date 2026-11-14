import SwiftUI
@preconcurrency import ZenTaskShared

/// Task notes drawn from `MarkdownLite` blocks; inline marks (bold, italic, code, links) via `AttributedString`.
struct MarkdownNotes: View {
    let text: String
    /// Ticks or unticks the i-th to-do.
    let toggle: (Int) -> Void

    var body: some View {
        let blocks = MarkdownLite.shared.blocks(text: text)
        let todoIndex = Dictionary(uniqueKeysWithValues: blocks.enumerated().filter { $0.element.kind == MdBlock.companion.TODO }.enumerated().map { ($1.offset, $0) })
        VStack(alignment: .leading, spacing: Theme.Space.xs) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { i, b in
                block(b, todo: todoIndex[i])
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .textSelection(.enabled)
    }

    @ViewBuilder
    private func block(_ b: MdBlock, todo: Int?) -> some View {
        let indent = CGFloat(b.level) * Theme.Space.l
        switch b.kind {
        case MdBlock.companion.HEADING:
            inline(b.text).font(b.level == 1 ? .title3.weight(.bold) : b.level == 2 ? .headline : .subheadline.weight(.semibold))
                .padding(.top, Theme.Space.xxs)
        case MdBlock.companion.BULLET:
            HStack(alignment: .firstTextBaseline, spacing: Theme.Space.s) { Text("•").foregroundStyle(.secondary); inline(b.text) }.padding(.leading, indent)
        case MdBlock.companion.NUMBERED:
            HStack(alignment: .firstTextBaseline, spacing: Theme.Space.s) { Text("\(b.number).").monospacedDigit().foregroundStyle(.secondary); inline(b.text) }.padding(.leading, indent)
        case MdBlock.companion.TODO:
            HStack(alignment: .firstTextBaseline, spacing: Theme.Space.s) {
                Button { if let todo { toggle(todo) } } label: {
                    Image(systemName: b.checked ? "checkmark.square.fill" : "square").foregroundStyle(b.checked ? AnyShapeStyle(.secondary) : AnyShapeStyle(.tint))
                }
                .buttonStyle(.plain)
                inline(b.text).strikethrough(b.checked).foregroundStyle(b.checked ? .secondary : .primary)
            }
            .padding(.leading, indent)
        case MdBlock.companion.QUOTE:
            HStack(spacing: Theme.Space.s) {
                RoundedRectangle(cornerRadius: 1).fill(.tertiary).frame(width: Theme.List.barWidth)
                inline(b.text).foregroundStyle(.secondary)
            }
            .fixedSize(horizontal: false, vertical: true)
        case MdBlock.companion.CODE:
            Text(b.text).font(.callout.monospaced())
                .padding(Theme.Space.s)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.quaternary, in: RoundedRectangle(cornerRadius: Theme.Radius.chartBar * 2, style: .continuous))
        case MdBlock.companion.RULE:
            Divider()
        case MdBlock.companion.BLANK:
            Spacer().frame(height: Theme.Space.xxs)
        default:
            inline(b.text)
        }
    }

    private func inline(_ s: String) -> Text {
        let attributed = (try? AttributedString(markdown: s, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(s)
        return Text(attributed)
    }
}
