// app/src/test/java/com/example/MarkdownParserTest.kt
package com.example

import com.dragonview.app.viewer.markdown.MarkdownBlock
import com.dragonview.app.viewer.markdown.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun testYamlFrontmatterParsing() {
        val md = """
            ---
            title: Obsidian Notes
            author: Dragon
            tags: [android, markdown]
            draft: false
            ---

            # First Heading
            Body content here.
        """.trimIndent()

        val doc = MarkdownParser.parseText(md)
        assertEquals("Obsidian Notes", doc.title)
        assertNotNull(doc.frontmatter)
        assertEquals("Obsidian Notes", doc.frontmatter?.get("title"))
        assertEquals("Dragon", doc.frontmatter?.get("author"))
        assertEquals("[android, markdown]", doc.frontmatter?.get("tags"))
        assertEquals("false", doc.frontmatter?.get("draft"))

        assertTrue(doc.blocks[0] is MarkdownBlock.Frontmatter)
        assertTrue(doc.blocks[1] is MarkdownBlock.Heading)
        assertEquals(1, (doc.blocks[1] as MarkdownBlock.Heading).level)
    }

    @Test
    fun testHeadingsAndInlineFormatting() {
        val md = """
            # Heading 1
            ## Heading 2
            ### Heading 3
            #### Heading 4
            ##### Heading 5
            ###### Heading 6

            Paragraph with **bold**, *italic*, `code`, ~~strikethrough~~, and [Google](https://google.com).
            Also preserved: [[My Wikilink]] and ![[embed.png]] and #my-tag.
        """.trimIndent()

        val doc = MarkdownParser.parseText(md)
        val headings = doc.blocks.filterIsInstance<MarkdownBlock.Heading>()
        assertEquals(6, headings.size)
        for (i in 1..6) {
            assertEquals(i, headings[i - 1].level)
            assertEquals("Heading $i", headings[i - 1].text)
        }

        val paras = doc.blocks.filterIsInstance<MarkdownBlock.Paragraph>()
        assertEquals(1, paras.size)
        val runs = paras[0].runs

        assertTrue(runs.any { it.text == "bold" && it.isBold })
        assertTrue(runs.any { it.text == "italic" && it.isItalic })
        assertTrue(runs.any { it.text == "code" && it.isCode })
        assertTrue(runs.any { it.text == "strikethrough" && it.isStrikethrough })
        assertTrue(runs.any { it.text == "Google" && it.linkUrl == "https://google.com" })

        // Check raw wikilinks and tags are intact
        val combinedText = runs.joinToString("") { it.text }
        assertTrue(combinedText.contains("[[My Wikilink]]"))
        assertTrue(combinedText.contains("![[embed.png]]"))
        assertTrue(combinedText.contains("#my-tag"))
    }

    @Test
    fun testCheckboxesAndLists() {
        val md = """
            - [ ] Task 1 unchecked
            - [x] Task 2 completed
              - [X] Nested completed task

            - Bullet one
            - Bullet two
              - Nested bullet

            1. First item
            2. Second item
        """.trimIndent()

        val doc = MarkdownParser.parseText(md)
        val checkboxes = doc.blocks.filterIsInstance<MarkdownBlock.Checkbox>()
        assertEquals(3, checkboxes.size)
        assertFalse(checkboxes[0].checked)
        assertEquals("Task 1 unchecked", checkboxes[0].text)
        assertEquals(0, checkboxes[0].indentLevel)

        assertTrue(checkboxes[1].checked)
        assertEquals("Task 2 completed", checkboxes[1].text)

        assertTrue(checkboxes[2].checked)
        assertEquals("Nested completed task", checkboxes[2].text)
        assertEquals(1, checkboxes[2].indentLevel)

        val bulletLists = doc.blocks.filterIsInstance<MarkdownBlock.BulletList>()
        assertEquals(1, bulletLists.size)
        assertEquals(3, bulletLists[0].items.size)
        assertEquals("Bullet one", bulletLists[0].items[0].text)
        assertEquals(0, bulletLists[0].items[0].indentLevel)
        assertEquals(1, bulletLists[0].items[2].indentLevel)

        val numberedLists = doc.blocks.filterIsInstance<MarkdownBlock.NumberedList>()
        assertEquals(1, numberedLists.size)
        assertEquals(2, numberedLists[0].items.size)
        assertEquals(1, numberedLists[0].items[0].number)
        assertEquals(2, numberedLists[0].items[1].number)
    }

    @Test
    fun testCodeBlocksAndBlockquotes() {
        val md = """
            ```kotlin
            val message = "Hello Obsidian"
            println(message)
            ```

            > This is a quote.
            > It has multiple lines.

            ---
        """.trimIndent()

        val doc = MarkdownParser.parseText(md)
        val codeBlocks = doc.blocks.filterIsInstance<MarkdownBlock.CodeBlock>()
        assertEquals(1, codeBlocks.size)
        assertEquals("kotlin", codeBlocks[0].language)
        assertTrue(codeBlocks[0].code.contains("val message = \"Hello Obsidian\""))

        val quotes = doc.blocks.filterIsInstance<MarkdownBlock.Blockquote>()
        assertEquals(1, quotes.size)
        assertTrue(quotes[0].content.isNotEmpty())

        val hr = doc.blocks.filterIsInstance<MarkdownBlock.HorizontalRule>()
        assertEquals(1, hr.size)
    }

    @Test
    fun testTableParsing() {
        val md = """
            | Item | Quantity | Price |
            | --- | :---: | ---: |
            | Apple | 10 | $1.50 |
            | Banana | 25 | $0.80 |
        """.trimIndent()

        val doc = MarkdownParser.parseText(md)
        val tables = doc.blocks.filterIsInstance<MarkdownBlock.Table>()
        assertEquals(1, tables.size)
        val table = tables[0]
        assertEquals(listOf("Item", "Quantity", "Price"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Apple", "10", "$1.50"), table.rows[0])
        assertEquals(listOf("Banana", "25", "$0.80"), table.rows[1])
    }

    @Test
    fun testImageParsing() {
        val md = """
            ![Dragon Architecture](diagram.png)
        """.trimIndent()

        val doc = MarkdownParser.parseText(md)
        val images = doc.blocks.filterIsInstance<MarkdownBlock.Image>()
        assertEquals(1, images.size)
        assertEquals("Dragon Architecture", images[0].altText)
        assertEquals("diagram.png", images[0].pathOrUrl)
    }
}
