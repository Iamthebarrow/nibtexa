package io.github.iamthebarrow.nibtexa.notebook

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.ink.onPaper
import io.github.iamthebarrow.nibtexa.core.export.Inline
import io.github.iamthebarrow.nibtexa.core.export.InlineMath
import io.github.iamthebarrow.nibtexa.core.export.Style
import io.github.iamthebarrow.nibtexa.core.model.notebook.MarkStyle
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextColor
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextMark
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextMarks
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextSize
import io.github.iamthebarrow.nibtexa.ui.theme.Nib
import io.github.iamthebarrow.nibtexa.ui.theme.NibPalette

// ---- Formatting text (Phase 16) -------------------------------------------------------------

/**
 * A text field that shows its [marks] as they will look, and keeps them on their words as the
 * text is edited ([TextMarks.follow]). `$…$` shows in the maths colour.
 */
@Composable
fun FormattedTextField(
    value: TextFieldValue,
    marks: List<TextMark>,
    onChange: (TextFieldValue, List<TextMark>) -> Unit,
    label: String,
    minLines: Int,
    modifier: Modifier = Modifier,
) {
    val p = Nib.palette
    val transformation = remember(marks, p) { MarksTransformation(marks, p) }
    OutlinedTextField(
        value = value,
        onValueChange = { next -> onChange(next, TextMarks.follow(marks, value.text, next.text)) },
        label = { Text(label) },
        visualTransformation = transformation,
        modifier = modifier,
        minLines = minLines,
    )
}

/** Draws marks over the text without changing it, so cursor positions stay the same. */
private class MarksTransformation(private val marks: List<TextMark>, private val p: NibPalette) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val styled = buildAnnotatedString {
            append(text.text)
            for (math in InlineMath.find(text.text)) addStyle(SpanStyle(color = p.math, fontFamily = FontFamily.Monospace), math.first, math.last + 1)
            for (m in TextMarks.normalize(marks, text.length)) {
                val style = when (val s = m.style) {
                    is MarkStyle.Footnote -> SpanStyle(background = p.primarySoft)
                    is MarkStyle.Link -> SpanStyle(color = p.primary, textDecoration = TextDecoration.Underline)
                    else -> spanStyle(styleOf(s), p)
                }
                addStyle(style, m.start, m.end)
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is MarksTransformation && other.marks == marks && other.p == p
    override fun hashCode() = marks.hashCode()
}

private fun styleOf(style: MarkStyle): Style = when (style) {
    MarkStyle.Bold -> Style(bold = true)
    MarkStyle.Italic -> Style(italic = true)
    MarkStyle.Emphasis -> Style(emphasis = true)
    MarkStyle.Underline -> Style(underline = true)
    MarkStyle.Typewriter -> Style(typewriter = true)
    is MarkStyle.Size -> Style(size = style.size)
    is MarkStyle.Color -> Style(color = style.color)
    is MarkStyle.Link -> Style(link = style.url)
    is MarkStyle.Footnote -> Style()
}

/** How a [Style] looks on the app's paper: as in the exports, except for colours hard to read there ([onPaper]). */
fun spanStyle(style: Style, p: NibPalette, baseItalic: Boolean = false): SpanStyle {
    val italic = (baseItalic || style.italic) != style.emphasis
    val color = style.color?.let { shownColor(it, p) } ?: Color.Unspecified
    return SpanStyle(
        fontWeight = if (style.bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (style.underline) TextDecoration.Underline else null,
        fontFamily = if (style.typewriter) FontFamily.Monospace else null,
        fontSize = style.size?.let { it.scale.em } ?: androidx.compose.ui.unit.TextUnit.Unspecified,
        color = color,
    )
}

fun colorOf(color: TextColor) = Color(0xFF000000 or color.rgb.toLong())

/** [color] as it shows on the app's paper. */
fun shownColor(color: TextColor, p: NibPalette) = color.onPaper(p.dark)?.let { Color(0xFF000000 or it.toLong()) } ?: p.ink

/**
 * The format buttons for [value]'s selection: bold, italic, emphasis, underline, typewriter,
 * size, colour, link, footnote and clear. Each applies to the selected words.
 */
@Composable
fun FormatBar(value: TextFieldValue, marks: List<TextMark>, onMarks: (List<TextMark>) -> Unit) {
    val start = value.selection.min
    val end = value.selection.max
    val length = value.text.length
    val selected = start < end
    var sizeMenu by remember { mutableStateOf(false) }
    var colorMenu by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    var noteDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).testTag(FORMAT_BAR_TAG), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable
            fun flag(style: MarkStyle, text: String, name: String, look: SpanStyle) {
                FilterChip(
                    selected = selected && TextMarks.covers(marks, start, end, style),
                    onClick = { onMarks(TextMarks.toggle(marks, start, end, style, length)) },
                    enabled = selected,
                    label = { Text(AnnotatedString(text, look)) },
                    modifier = Modifier.semantics { contentDescription = name },
                )
            }
            flag(MarkStyle.Bold, "B", stringResource(R.string.format_bold), SpanStyle(fontWeight = FontWeight.Bold))
            flag(MarkStyle.Italic, "I", stringResource(R.string.format_italic), SpanStyle(fontStyle = FontStyle.Italic))
            flag(MarkStyle.Emphasis, "Em", stringResource(R.string.format_emphasis), SpanStyle(fontStyle = FontStyle.Italic))
            flag(MarkStyle.Underline, "U", stringResource(R.string.format_underline), SpanStyle(textDecoration = TextDecoration.Underline))
            flag(MarkStyle.Typewriter, "tt", stringResource(R.string.format_typewriter), SpanStyle(fontFamily = FontFamily.Monospace))
            Box {
                FilterChip(selected = false, onClick = { sizeMenu = true }, enabled = selected, label = { Text(stringResource(R.string.format_size)) })
                DropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.format_none)) }, onClick = {
                        sizeMenu = false
                        onMarks(TextMarks.set(marks, start, end, TextMarks.SIZE, null, length))
                    })
                    TextSize.entries.forEach { size ->
                        DropdownMenuItem(text = { Text(sizeName(size), fontSize = (0.6f + 0.4f * size.scale).em) }, onClick = {
                            sizeMenu = false
                            onMarks(TextMarks.set(marks, start, end, TextMarks.SIZE, MarkStyle.Size(size), length))
                        })
                    }
                }
            }
            Box {
                FilterChip(selected = false, onClick = { colorMenu = true }, enabled = selected, label = { Text(stringResource(R.string.format_color)) })
                DropdownMenu(expanded = colorMenu, onDismissRequest = { colorMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.format_none)) }, onClick = {
                        colorMenu = false
                        onMarks(TextMarks.set(marks, start, end, TextMarks.COLOR, null, length))
                    })
                    TextColor.entries.forEach { color ->
                        val name = colorName(color)
                        DropdownMenuItem(
                            text = { Text(if (color.faint) stringResource(R.string.format_faint, name) else name) },
                            leadingIcon = { Box(Modifier.size(18.dp).clip(CircleShape).background(Nib.palette.line).padding(1.dp).clip(CircleShape).background(colorOf(color))) },
                            onClick = {
                                colorMenu = false
                                onMarks(TextMarks.set(marks, start, end, TextMarks.COLOR, MarkStyle.Color(color), length))
                            },
                        )
                    }
                }
            }
            FilterChip(selected = selected && marks.any { it.style is MarkStyle.Link && it.start < end && it.end > start }, onClick = { linkDialog = true }, enabled = selected, label = { Text(stringResource(R.string.format_link)) })
            FilterChip(selected = false, onClick = { noteDialog = true }, enabled = selected, label = { Text(stringResource(R.string.format_footnote)) })
            TextButton(onClick = { onMarks(TextMarks.clear(marks, start, end, length)) }, enabled = selected) { Text(stringResource(R.string.format_clear)) }
        }
        if (!selected) Text(stringResource(R.string.format_hint), style = MaterialTheme.typography.bodySmall, color = Nib.palette.muted)
    }

    if (linkDialog) {
        val existing = marks.firstOrNull { it.style is MarkStyle.Link && it.start < end && it.end > start }?.style as? MarkStyle.Link
        var url by remember { mutableStateOf(existing?.url ?: "https://") }
        val valid = TextMarks.isSafeUrl(url.trim())
        AlertDialog(
            onDismissRequest = { linkDialog = false },
            title = { Text(stringResource(R.string.format_link)) },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.link_address)) },
                    isError = !valid,
                    supportingText = { if (!valid) Text(stringResource(R.string.link_invalid)) },
                    modifier = Modifier.testTag(LINK_FIELD_TAG),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    linkDialog = false
                    onMarks(TextMarks.set(marks, start, end, TextMarks.LINK, MarkStyle.Link(url.trim()), length))
                }, enabled = valid) { Text(stringResource(R.string.apply)) }
            },
            dismissButton = {
                Row {
                    if (existing != null) TextButton(onClick = {
                        linkDialog = false
                        onMarks(TextMarks.set(marks, start, end, TextMarks.LINK, null, length))
                    }) { Text(stringResource(R.string.link_remove)) }
                    TextButton(onClick = { linkDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
    if (noteDialog) {
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { noteDialog = false },
            title = { Text(stringResource(R.string.format_footnote)) },
            text = {
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(stringResource(R.string.footnote_text)) }, modifier = Modifier.testTag(FOOTNOTE_FIELD_TAG))
            },
            confirmButton = {
                TextButton(onClick = {
                    noteDialog = false
                    onMarks(TextMarks.footnote(marks, start, end, note, length))
                }, enabled = note.isNotBlank()) { Text(stringResource(R.string.apply)) }
            },
            dismissButton = { TextButton(onClick = { noteDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun sizeName(size: TextSize): String = stringResource(
    when (size) {
        TextSize.TINY -> R.string.size_tiny
        TextSize.SCRIPT -> R.string.size_script
        TextSize.FOOTNOTE -> R.string.size_footnote
        TextSize.SMALL -> R.string.size_small
        TextSize.NORMAL -> R.string.size_normal
        TextSize.LARGE -> R.string.size_large
        TextSize.LARGER -> R.string.size_larger
        TextSize.LARGEST -> R.string.size_largest
        TextSize.HUGE -> R.string.size_huge
        TextSize.HUGEST -> R.string.size_hugest
    },
)

@Composable
private fun colorName(color: TextColor): String = stringResource(
    when (color) {
        TextColor.BLACK -> R.string.color_black
        TextColor.RED -> R.string.color_red
        TextColor.GREEN -> R.string.color_green
        TextColor.BLUE -> R.string.color_blue
        TextColor.CYAN -> R.string.color_cyan
        TextColor.MAGENTA -> R.string.color_magenta
        TextColor.YELLOW -> R.string.color_yellow
        TextColor.WHITE -> R.string.color_white
    },
)

const val FORMAT_BAR_TAG = "format_bar"
const val LINK_FIELD_TAG = "link_field"
const val FOOTNOTE_FIELD_TAG = "footnote_field"

// ---- Reading formatted text ---------------------------------------------------------------------

/**
 * [content] as an annotated string for the reading view: formatting as span styles, links that
 * open in a browser, footnote numbers raised, and each inline maths as inline content
 * `math-<n>`, numbered from [firstMath] through the page.
 */
fun readingText(content: List<Inline>, p: NibPalette, baseItalic: Boolean = false, firstMath: Int = 0, prefix: AnnotatedString? = null): AnnotatedString = buildAnnotatedString {
    prefix?.let(::append)
    var math = firstMath
    for (piece in content) {
        when (piece) {
            is Inline.Text -> {
                val look = spanStyle(piece.style, p, baseItalic)
                val url = piece.style.link
                if (url != null && TextMarks.isSafeUrl(url)) {
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(look.merge(SpanStyle(color = p.primary, textDecoration = TextDecoration.Underline))))) { append(piece.text) }
                } else {
                    withStyle(look) { append(piece.text) }
                }
            }
            is Inline.Math -> appendInlineContent("math-${math++}", "\$${piece.latex}\$")
            is Inline.Note -> withStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.7.em, color = p.primary)) { append(piece.number.toString()) }
        }
    }
}

/** A collapsed selection at the end of [text]. */
fun endOf(text: String) = TextFieldValue(text, TextRange(text.length))
