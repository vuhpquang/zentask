package com.vuhpquang.zentask.tasks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownLiteTest {
    @Test
    fun blocks() {
        val md = "# Trip\n\nPack **light**\n- passport\n  - copy\n- [x] visa\n- [ ] tickets\n1. book\n> note\n---\n```\nlet x\n```"
        val b = MarkdownLite.blocks(md)
        assertEquals(
            listOf("h", "blank", "p", "li", "li", "todo", "todo", "ol", "quote", "hr", "code"),
            b.map { it.kind },
        )
        assertEquals(1, b[0].level)
        assertEquals(1, b[4].level)
        assertTrue(b[5].checked)
        assertFalse(b[6].checked)
        assertEquals("let x", b.last().text)
    }

    @Test
    fun toggleTodoFlipsOnlyThatOne() {
        val md = "- [ ] a\ntext\n- [x] b"
        assertEquals("- [x] a\ntext\n- [x] b", MarkdownLite.toggleTodo(md, 0))
        assertEquals("- [ ] a\ntext\n- [ ] b", MarkdownLite.toggleTodo(md, 1))
    }

    @Test
    fun plainDropsInlineMarks() {
        assertEquals("Bring water from Drive now", MarkdownLite.plain("Bring **water** from [Drive](https://x.y) `now`"))
        assertEquals("legs", MarkdownLite.plain("- [ ] legs"))
    }

    @Test
    fun plainTextIsNotMarkdown() {
        assertFalse(MarkdownLite.hasMarkdown("Call the bank about the card"))
        assertTrue(MarkdownLite.hasMarkdown("Call **now**"))
        assertTrue(MarkdownLite.hasMarkdown("- milk\n- eggs"))
    }
}
