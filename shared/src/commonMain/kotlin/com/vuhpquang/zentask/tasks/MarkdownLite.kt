package com.vuhpquang.zentask.tasks

/**
 * The block structure of task notes for display: headings, bullets, numbered items, to-dos, quotes, code blocks
 * and paragraphs. Inline marks (bold, italic, `code`, links) are left in [MdBlock.text] for the platforms' own
 * inline Markdown. Pure; the notes stay plain text in storage.
 */
data class MdBlock(
    val kind: String,
    val text: String,
    /** Heading level 1–3, or indent depth for list items (0 = top). */
    val level: Int = 0,
    /** To-do items: ticked or not. */
    val checked: Boolean = false,
    /** Numbered items: their number. */
    val number: Int = 0,
) {
    companion object {
        const val PARAGRAPH = "p"
        const val HEADING = "h"
        const val BULLET = "li"
        const val NUMBERED = "ol"
        const val TODO = "todo"
        const val QUOTE = "quote"
        const val CODE = "code"
        const val RULE = "hr"
        const val BLANK = "blank"
    }
}

object MarkdownLite {
    private val heading = Regex("""^(#{1,3})\s+(.*)$""")
    private val todo = Regex("""^(\s*)[-*+]\s+\[([ xX])]\s+(.*)$""")
    private val bullet = Regex("""^(\s*)[-*+•]\s+(.*)$""")
    private val numbered = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
    private val rule = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")

    fun blocks(text: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val code = StringBuilder()
        var inCode = false
        for (raw in text.replace("\r\n", "\n").split('\n')) {
            if (raw.trimStart().startsWith("```")) {
                if (inCode) { out += MdBlock(MdBlock.CODE, code.toString().trimEnd('\n')); code.clear() }
                inCode = !inCode
                continue
            }
            if (inCode) { code.append(raw).append('\n'); continue }
            val line = raw.trimEnd()
            fun depth(indent: String) = indent.replace("\t", "  ").length / 2
            out += when {
                line.isBlank() -> MdBlock(MdBlock.BLANK, "")
                rule.matches(line) -> MdBlock(MdBlock.RULE, "")
                heading.matchEntire(line) != null -> heading.matchEntire(line)!!.let { m -> MdBlock(MdBlock.HEADING, m.groupValues[2], level = m.groupValues[1].length) }
                todo.matchEntire(line) != null -> todo.matchEntire(line)!!.let { m ->
                    MdBlock(MdBlock.TODO, m.groupValues[3], level = depth(m.groupValues[1]), checked = m.groupValues[2] != " ")
                }
                bullet.matchEntire(line) != null -> bullet.matchEntire(line)!!.let { m -> MdBlock(MdBlock.BULLET, m.groupValues[2], level = depth(m.groupValues[1])) }
                numbered.matchEntire(line) != null -> numbered.matchEntire(line)!!.let { m ->
                    MdBlock(MdBlock.NUMBERED, m.groupValues[3], level = depth(m.groupValues[1]), number = m.groupValues[2].toInt())
                }
                line.startsWith(">") -> MdBlock(MdBlock.QUOTE, line.removePrefix(">").trimStart())
                else -> MdBlock(MdBlock.PARAGRAPH, line)
            }
        }
        if (inCode) out += MdBlock(MdBlock.CODE, code.toString().trimEnd('\n'))
        // Collapse runs of blank lines and drop them at the ends.
        return out.filterIndexed { i, b -> b.kind != MdBlock.BLANK || (i > 0 && out[i - 1].kind != MdBlock.BLANK) }
            .dropWhile { it.kind == MdBlock.BLANK }.dropLastWhile { it.kind == MdBlock.BLANK }
    }

    /** One line without inline marks: **bold** → bold, [text](url) → text, `code` → code; to-do boxes dropped. */
    fun plain(line: String): String = line
        .replace(Regex("""^\s*[-*+]\s+\[[ xX]]\s+"""), "")
        .replace(Regex("""\[([^]]+)]\([^)]+\)"""), "$1")
        .replace(Regex("""\*\*(.+?)\*\*|__(.+?)__""")) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        .replace(Regex("""`([^`]+)`"""), "$1")

    /** Whether [text] uses any Markdown worth rendering (otherwise show it as typed). */
    fun hasMarkdown(text: String): Boolean =
        blocks(text).any { it.kind !in setOf(MdBlock.PARAGRAPH, MdBlock.BLANK) } || Regex("""\*\*|__|`|\[[^]]+]\([^)]+\)|(^|\s)[*_][^*_\s]""").containsMatchIn(text)

    /** Flips the [index]-th to-do (counting to-dos only) in [text]; other text is untouched. */
    fun toggleTodo(text: String, index: Int): String {
        var seen = -1
        return text.split('\n').joinToString("\n") { line ->
            val m = todo.matchEntire(line.trimEnd()) ?: return@joinToString line
            seen++
            if (seen != index) line else {
                val box = if (m.groupValues[2] == " ") "[x]" else "[ ]"
                line.replaceFirst(Regex("""\[[ xX]]"""), box)
            }
        }
    }
}
