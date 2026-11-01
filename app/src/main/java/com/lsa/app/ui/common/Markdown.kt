package com.lsa.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MdText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

private val parser: Parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()

/** Parses CommonMark (+ GFM tables). Never throws: anything odd just renders as text. */
fun parseMarkdown(text: String): Node = parser.parse(text)

/** The inline content (bold, italic, code, links) of a paragraph, heading or table cell as one styled string. */
fun inlineMarkdown(parent: Node, codeBackground: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    fun AnnotatedString.Builder.walk(n: Node?) {
        var cur = n
        while (cur != null) {
            val c = cur
            when (c) {
                is MdText -> append(c.literal)
                is SoftLineBreak -> append(' ')
                is HardLineBreak -> append('\n')
                is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(c.literal) }
                is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { walk(c.firstChild) }
                is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { walk(c.firstChild) }
                is Link -> withLink(
                    LinkAnnotation.Url(c.destination, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))),
                ) { walk(c.firstChild) }
                is HtmlInline -> append(c.literal)
                else -> walk(c.firstChild)
            }
            cur = c.next
        }
    }
    walk(parent.firstChild)
}

/** Renders Markdown text with Material styles. Used for chat messages. */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    val doc = remember(text) { parseMarkdown(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) { Blocks(doc, style) }
}

@Composable
private fun Blocks(parent: Node, style: TextStyle) {
    var n = parent.firstChild
    while (n != null) {
        Block(n, style)
        n = n.next
    }
}

@Composable
private fun Block(n: Node, style: TextStyle) {
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val link = MaterialTheme.colorScheme.primary
    when (n) {
        is Paragraph -> Text(inlineMarkdown(n, codeBg, link), style = style)
        is Heading -> Text(
            inlineMarkdown(n, codeBg, link),
            style = when (n.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            fontWeight = FontWeight.Bold,
        )
        is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            var index = (n as? OrderedList)?.markerStartNumber ?: 1
            var item = n.firstChild
            while (item != null) {
                if (item is ListItem) {
                    val marker = if (n is OrderedList) "${index++}." else "•"
                    Row {
                        Text(marker, style = style, modifier = Modifier.width(if (n is OrderedList) 28.dp else 20.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Blocks(item, style) }
                    }
                }
                item = item.next
            }
        }
        is FencedCodeBlock -> Code(n.literal.trimEnd('\n'), style)
        is IndentedCodeBlock -> Code(n.literal.trimEnd('\n'), style)
        is BlockQuote -> Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Surface(Modifier.width(3.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.outline) {}
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Blocks(n, style) }
        }
        is ThematicBreak -> HorizontalDivider()
        is TableBlock -> Table(n, style, codeBg, link)
        is HtmlBlock -> Text(n.literal.trim(), style = style)
        else -> Blocks(n, style)
    }
}

@Composable
private fun Code(code: String, style: TextStyle) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)) {
        Text(
            code, style = style.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
        )
    }
}

@Composable
private fun Table(t: TableBlock, style: TextStyle, codeBg: Color, link: Color) {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        var section = t.firstChild
        while (section != null) {
            if (section is TableHead || section is TableBody) {
                var row = section.firstChild
                while (row != null) {
                    if (row is TableRow) {
                        Row {
                            var cell = row.firstChild
                            while (cell != null) {
                                if (cell is TableCell) {
                                    Text(
                                        inlineMarkdown(cell, codeBg, link),
                                        style = style, fontWeight = if (cell.isHeader) FontWeight.Bold else null,
                                        modifier = Modifier.width(120.dp).padding(horizontal = 6.dp, vertical = 4.dp),
                                    )
                                }
                                cell = cell.next
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                    row = row.next
                }
            }
            section = section.next
        }
    }
}
