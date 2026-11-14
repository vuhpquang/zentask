package com.vuhpquang.zentask.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.vuhpquang.zentask.tasks.MarkdownLite
import com.vuhpquang.zentask.tasks.MdBlock

/** Task notes drawn from `MarkdownLite` blocks, with inline **bold**, _italic_, `code` and [links](url). */
@Composable
fun MarkdownNotes(text: String, toggle: (Int) -> Unit) {
    val blocks = MarkdownLite.blocks(text)
    var todo = 0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (b in blocks) {
            val indent = (b.level * 16).dp
            when (b.kind) {
                MdBlock.HEADING -> Text(inline(b.text), style = when (b.level) { 1 -> MaterialTheme.typography.titleLarge; 2 -> MaterialTheme.typography.titleMedium; else -> MaterialTheme.typography.titleSmall }, fontWeight = FontWeight.Bold)
                MdBlock.BULLET -> Row(Modifier.padding(start = indent)) { Text("•  ", color = MaterialTheme.colorScheme.onSurfaceVariant); Text(inline(b.text)) }
                MdBlock.NUMBERED -> Row(Modifier.padding(start = indent)) { Text("${b.number}.  ", color = MaterialTheme.colorScheme.onSurfaceVariant); Text(inline(b.text)) }
                MdBlock.TODO -> {
                    val i = todo++
                    Row(Modifier.padding(start = indent), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (b.checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, if (b.checked) "Done" else "To do",
                            Modifier.size(22.dp).clickable { toggle(i) }, tint = if (b.checked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(inline(b.text), textDecoration = if (b.checked) TextDecoration.LineThrough else null,
                            color = if (b.checked) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
                    }
                }
                MdBlock.QUOTE -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Spacer(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                    Text(inline(b.text), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                }
                MdBlock.CODE -> Text(b.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(8.dp))
                MdBlock.RULE -> HorizontalDivider()
                MdBlock.BLANK -> Spacer(Modifier.height(2.dp))
                else -> Text(inline(b.text))
            }
        }
    }
}

private val INLINE = Regex("""\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|\[([^]]+)]\(([^)\s]+)\)|(?<![\w*])[*_]([^*_\s][^*_]*?)[*_](?![\w*])""")

/** Inline Markdown → styled text (bold, italic, code, links). */
@Composable
private fun inline(s: String): AnnotatedString {
    val link = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var at = 0
        for (m in INLINE.findAll(s)) {
            append(s.substring(at, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() || g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[1].ifEmpty { g[2] }) }
                g[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22888888))) { append(g[3]) }
                g[4].isNotEmpty() -> withLink(LinkAnnotation.Url(g[5], TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) { append(g[4]) }
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[6]) }
            }
            at = m.range.last + 1
        }
        append(s.substring(at))
    }
}
