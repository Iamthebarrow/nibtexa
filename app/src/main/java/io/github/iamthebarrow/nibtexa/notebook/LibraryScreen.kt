package io.github.iamthebarrow.nibtexa.notebook

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageMargin
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.Paper
import io.github.iamthebarrow.nibtexa.core.model.notebook.Sheet
import io.github.iamthebarrow.nibtexa.core.model.notebook.SheetSize
import io.github.iamthebarrow.nibtexa.storage.BookletSummary
import io.github.iamthebarrow.nibtexa.storage.SearchHit
import io.github.iamthebarrow.nibtexa.storage.NotebookDatabase
import io.github.iamthebarrow.nibtexa.ui.theme.Nib
import io.github.iamthebarrow.nibtexa.ui.theme.NibIcons
import io.github.iamthebarrow.nibtexa.ui.theme.NibLogo
import io.github.iamthebarrow.nibtexa.ui.theme.entrance
import io.github.iamthebarrow.nibtexa.ui.theme.pressScale

/** What a booklet's long-press menu asks the screen to do. */
sealed interface BookletAction {
    val id: String

    data class Rename(override val id: String) : BookletAction
    data class Cover(override val id: String) : BookletAction
    /** Export, choosing the format first (Phase 18). */
    data class Export(override val id: String) : BookletAction
    data class Delete(override val id: String) : BookletAction
}

/**
 * The home screen (Phases 12 and 13): a coloured header with search and New booklet, then every
 * booklet as a card on its cover colour, newest first. Tap to open; long-press for rename, cover
 * colour, duplicate, export and delete. Search matches titles and the handwriting's recognized
 * text.
 */
@Composable
fun LibraryScreen(
    state: NotebookUiState,
    viewModel: NotebookViewModel,
    onAction: (BookletAction) -> Unit,
    menu: @Composable (onNewFolder: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    onImport: () -> Unit = {},
) {
    val p = Nib.palette
    var creating by remember { mutableStateOf(false) }
    val searching = state.searchQuery.isNotBlank()
    val folderIds = state.folders.mapTo(HashSet()) { it.id }
    // Search covers every folder (Phase 27); a booklet whose folder is gone is at the top level.
    val shown = state.searchResults ?: state.library.filter { it.folderId?.takeIf(folderIds::contains) == state.openFolder }
    var newFolder by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<String?>(null) }
    if (state.openFolder != null && !searching) BackHandler { viewModel.openFolder(null) }
    val searchLabel = stringResource(R.string.search_hint)
    Column(modifier.fillMaxSize().background(p.ground)) {
        // Wide screens: one row. Narrow ones (portrait): the search gets a row of its own (Phase 16).
        BoxWithConstraints(Modifier.fillMaxWidth().background(p.header)) {
            val wide = maxWidth >= WIDE_HEADER.dp
            val gutter = if (maxWidth < COMPACT.dp) 16.dp else 32.dp
            val brand = @Composable {
                NibLogo.Tile(Modifier.size(52.dp))
                Column {
                    NibLogo.Wordmark(30.sp)
                    Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodySmall, color = p.muted)
                }
            }
            val search = @Composable { m: Modifier -> SearchField(state, viewModel, searching, searchLabel, m) }
            val actions = @Composable {
                ImportButton(onImport)
                NewBookletButton { creating = true }
                menu { newFolder = true }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    brand()
                    Spacer(Modifier.weight(1f))
                    search(Modifier.widthIn(max = 400.dp).weight(1f, fill = false))
                    actions()
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        brand()
                        Spacer(Modifier.weight(1f))
                        actions()
                    }
                    search(Modifier.fillMaxWidth())
                }
            }
        }
        LibraryBody(state, viewModel, onAction, searching, shown, onCreate = { creating = true }, onMove = { moving = it })
    }
    if (newFolder) {
        FolderDialog(
            title = stringResource(R.string.folder_new),
            initialName = "",
            initialCover = state.folders.size % NotebookDatabase.COVER_COUNT,
            onDone = { name, cover -> newFolder = false; viewModel.createFolder(name, cover) },
            onDismiss = { newFolder = false },
        )
    }
    moving?.let { id -> MoveToFolderDialog(state, id, viewModel, onDismiss = { moving = null }) }
    if (creating) {
        NewBookletDialog(
            defaultCover = state.library.size % NotebookDatabase.COVER_COUNT,
            onCreate = { title, paper, cover, sheet, margin -> creating = false; viewModel.createBooklet(title, paper, cover, sheet, margin) },
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun SearchField(state: NotebookUiState, viewModel: NotebookViewModel, searching: Boolean, searchLabel: String, modifier: Modifier) {
    val p = Nib.palette
    OutlinedTextField(
        value = state.searchQuery,
        onValueChange = viewModel::setSearch,
        placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(NibIcons.Search, contentDescription = null, tint = p.muted) },
        trailingIcon = if (searching) {
            { IconButton(onClick = { viewModel.setSearch("") }) { Icon(NibIcons.Close, contentDescription = stringResource(R.string.clear_search), tint = p.muted) } }
        } else {
            null
        },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = p.surface, unfocusedContainerColor = p.surface, unfocusedBorderColor = p.line),
        // The placeholder disappears while typing, so the field also has a fixed accessible name.
        modifier = modifier.testTag(SEARCH_TAG).semantics { contentDescription = searchLabel },
    )
}

@Composable
private fun NewBookletButton(onClick: () -> Unit) {
    val p = Nib.palette
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
        contentPadding = PaddingValues(start = 16.dp, end = 22.dp),
        modifier = Modifier.height(48.dp),
    ) {
        Icon(NibIcons.Plus, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.new_booklet_button), maxLines = 1)
    }
}

/** Import, which asks for the format and then the file (Phase 18). */
@Composable
private fun ImportButton(onClick: () -> Unit) {
    val p = Nib.palette
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = p.ink),
        contentPadding = PaddingValues(horizontal = 18.dp),
        modifier = Modifier.height(48.dp),
    ) {
        Text(stringResource(R.string.import_button), maxLines = 1)
    }
}

/** The library's title line and its booklets, or why there are none. */
@Composable
private fun LibraryBody(state: NotebookUiState, viewModel: NotebookViewModel, onAction: (BookletAction) -> Unit, searching: Boolean, shown: List<BookletSummary>, onCreate: () -> Unit, onMove: (String) -> Unit) {
    val p = Nib.palette
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Narrow screens keep two columns of cards, a little smaller, with narrower margins.
        val compact = maxWidth < COMPACT.dp
        val gutter = if (compact) 16.dp else 32.dp
        val gap = if (compact) 16.dp else 28.dp
        Column(Modifier.fillMaxSize()) {
            val open = state.folders.firstOrNull { it.id == state.openFolder }.takeIf { !searching }
            Row(Modifier.padding(start = if (open != null) gutter - 12.dp else gutter, end = gutter, top = 24.dp, bottom = 12.dp), verticalAlignment = if (open != null) Alignment.CenterVertically else Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (open != null) IconButton(onClick = { viewModel.openFolder(null) }) { Icon(NibIcons.Back, contentDescription = stringResource(R.string.folder_back), tint = p.ink) }
                Text(
                    open?.name ?: if (searching) stringResource(R.string.search_results_title) else stringResource(R.string.library_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = p.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // At the top level, every booklet, those in folders too.
                val count = if (!searching && open == null) state.library.size else shown.size
                Text(
                    pluralStringResource(R.plurals.booklet_count, count, count) + if (searching) "" else " · " + stringResource(R.string.library_order),
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.muted,
                )
            }
            when {
                state.libraryLoaded && state.library.isEmpty() && state.folders.isEmpty() -> EmptyLibrary(onCreate = onCreate)
                open != null && shown.isEmpty() -> Box(Modifier.fillMaxSize().padding(horizontal = gutter), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.folder_empty), style = MaterialTheme.typography.bodyLarge, color = p.muted, textAlign = TextAlign.Center)
                }
                searching && state.searchResults?.isEmpty() == true -> Box(Modifier.fillMaxSize().padding(horizontal = gutter), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.search_empty, state.searchQuery.trim()), style = MaterialTheme.typography.bodyLarge, color = p.muted, textAlign = TextAlign.Center)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (compact) COMPACT_CARD_WIDTH.dp else CARD_WIDTH.dp),
                    modifier = Modifier.fillMaxSize().testTag(LIBRARY_TAG),
                    contentPadding = PaddingValues(start = gutter, end = gutter, top = 4.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    // Folders first, at the top level only.
                    if (!searching && state.openFolder == null) {
                        items(state.folders, key = { it.id }) { folder -> FolderCard(folder, viewModel, Modifier.animateItem()) }
                    }
                    // Cards rise in one after another, and slide into place when one is added, deleted or reordered.
                    itemsIndexed(shown, key = { _, b -> b.id }) { index, booklet ->
                        val folder = if (searching) state.folders.firstOrNull { it.id == booklet.folderId }?.name else null
                        BookletCard(booklet, state.thumbnails[booklet.id], state.searchSnippets[booklet.id], folder, viewModel, onAction, onMove, Modifier.animateItem().entrance(index))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onCreate: () -> Unit) {
    val p = Nib.palette
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(112.dp).clip(CircleShape).background(p.primarySoft), contentAlignment = Alignment.Center) {
            NibLogo.Mark(background = p.primarySoft, modifier = Modifier.size(96.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.titleLarge, color = p.ink)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.library_empty), style = MaterialTheme.typography.bodyLarge, color = p.muted)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onCreate, colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent)) {
            Text(stringResource(R.string.new_booklet_button))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookletCard(booklet: BookletSummary, firstPage: Page?, snippet: SearchHit?, folder: String?, viewModel: NotebookViewModel, onAction: (BookletAction) -> Unit, onMove: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = Nib.palette
    var menu by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val edited = when {
        booklet.updatedAt <= 0 -> stringResource(R.string.edited_never)
        now - booklet.updatedAt < DateUtils.MINUTE_IN_MILLIS -> stringResource(R.string.edited_just_now)
        else -> DateUtils.getRelativeTimeSpanString(booklet.updatedAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
    val pages = pluralStringResource(R.plurals.booklet_pages, booklet.pageCount, booklet.pageCount)
    val waiting = if (booklet.pending > 0) pluralStringResource(R.plurals.to_confirm, booklet.pending, booklet.pending) else null
    val description = stringResource(R.string.booklet_card, booklet.title, pages, edited) + (waiting?.let { ", $it" } ?: "")
    val press = remember { MutableInteractionSource() }
    Box(modifier) {
        Column(
            Modifier
                .pressScale(press)
                .clip(RoundedCornerShape(18.dp))
                .combinedClickable(interactionSource = press, indication = ripple(), onClick = { viewModel.openBooklet(booklet.id, snippet) }, onLongClick = { menu = true }, onLongClickLabel = stringResource(R.string.more))
                .semantics { contentDescription = description }
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(COVER_ASPECT).clip(RoundedCornerShape(16.dp)).background(p.cover(booklet.cover))
                    .padding(start = 20.dp, top = 14.dp, end = 14.dp),
            ) {
                // The page in its own shape (Phase 20); the cover cuts off what does not fit.
                val page = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).aspectRatio(booklet.paper().aspect())
                    .shadow(2.dp, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                if (firstPage != null) PageThumbnail(firstPage, booklet.paper(), page) else Box(page.background(p.paper))
                if (waiting != null) {
                    Text(
                        waiting,
                        style = MaterialTheme.typography.labelMedium,
                        color = p.onPrimary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 4.dp).clip(RoundedCornerShape(12.dp)).background(p.primary).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Column(Modifier.padding(horizontal = 4.dp)) {
                Text(booklet.title, style = MaterialTheme.typography.titleMedium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$pages · $edited", style = MaterialTheme.typography.bodySmall, color = p.muted)
                // In search results, where it is (Phase 27).
                if (folder != null) Text(stringResource(R.string.folder_in, folder), style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (snippet?.snippet != null) {
                    // Scratch matches say so: they are notes to self, not part of the booklet.
                    val quoted = "“${snippet.snippet.trim()}”"
                    Text(if (snippet.scratch) stringResource(R.string.search_scratch, quoted) else quoted, style = MaterialTheme.typography.bodySmall, color = p.primaryInk, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.rename_booklet)) }, onClick = { menu = false; onAction(BookletAction.Rename(booklet.id)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.cover_colour)) }, onClick = { menu = false; onAction(BookletAction.Cover(booklet.id)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, onClick = { menu = false; viewModel.duplicateBooklet(booklet.id) })
            DropdownMenuItem(text = { Text(stringResource(R.string.folder_move)) }, onClick = { menu = false; onMove(booklet.id) })
            DropdownMenuItem(text = { Text(stringResource(R.string.export_more)) }, onClick = { menu = false; onAction(BookletAction.Export(booklet.id)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = p.error) }, onClick = { menu = false; onAction(BookletAction.Delete(booklet.id)) })
        }
    }
}

/** The cover colours as selectable swatches. */
@Composable
fun CoverSwatches(selected: Int, onSelect: (Int) -> Unit) {
    val p = Nib.palette
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(NotebookDatabase.COVER_COUNT) { i ->
            val label = stringResource(R.string.cover_swatch, i + 1)
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(p.cover(i))
                    .border(if (i == selected) 3.dp else 1.dp, if (i == selected) p.ink else p.line, CircleShape)
                    .clickable(onClickLabel = label) { onSelect(i) }
                    .semantics { contentDescription = label; this.selected = i == selected; role = Role.RadioButton },
            )
        }
    }
}

@Composable
private fun NewBookletDialog(defaultCover: Int, onCreate: (String, Paper, Int, Sheet, PageMargin) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var paper by remember { mutableStateOf(Paper.RULED) }
    var sheet by remember { mutableStateOf(Sheet()) }
    var cover by remember { mutableIntStateOf(defaultCover) }
    var margin by remember { mutableStateOf(PageMargin.NORMAL) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_booklet)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text(stringResource(R.string.booklet_title)) })
                Text(stringResource(R.string.paper_for_new), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Paper.entries.forEach { p -> FilterChip(selected = paper == p, onClick = { paper = p }, label = { Text(paperName(p)) }) }
                }
                SheetChoice(sheet) { sheet = it }
                MarginChoice(margin) { margin = it ?: PageMargin.NORMAL }
                Text(stringResource(R.string.cover_colour), style = MaterialTheme.typography.labelLarge)
                CoverSwatches(cover) { cover = it }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(title, paper, cover, sheet, margin) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * The page margin (Phase 30): for a new booklet, in Cover and layout, and for one page. With [sameAsBooklet], a page can go back to
 * the booklet's, which is null.
 */
@Composable
fun MarginChoice(margin: PageMargin?, sameAsBooklet: Boolean = false, onChange: (PageMargin?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.margins), style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sameAsBooklet) FilterChip(selected = margin == null, onClick = { onChange(null) }, label = { Text(stringResource(R.string.margin_booklet)) })
            listOf(PageMargin.CHEAT_SHEET to R.string.margin_cheat_sheet, PageMargin.NARROW to R.string.margin_narrow, PageMargin.NORMAL to R.string.margin_normal, PageMargin.WIDE to R.string.margin_wide).forEach { (value, name) ->
                FilterChip(selected = margin == value, onClick = { onChange(value) }, label = { Text(stringResource(name)) })
            }
        }
    }
}

/** The paper size and its orientation (Phase 19): for a new booklet, and in Cover and layout. */
@Composable
fun SheetChoice(sheet: Sheet, onChange: (Sheet) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.page_size), style = MaterialTheme.typography.labelLarge)
        // A custom size (an imported PDF's, Phase 20) is offered only to the booklet that has it, so it can go back to it.
        val custom = remember { sheet.takeIf { it.size == SheetSize.CUSTOM } }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetSize.entries.filter { it != SheetSize.CUSTOM }.forEach { size ->
                FilterChip(selected = sheet.size == size, onClick = { onChange(sheet.copy(size = size)) }, label = { Text(sheetName(size)) })
            }
            custom?.let { c -> FilterChip(selected = sheet.size == SheetSize.CUSTOM, onClick = { onChange(c) }, label = { Text(sheetName(c)) }) }
        }
        if (sheet.size != SheetSize.CUSTOM) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !sheet.landscape, onClick = { onChange(sheet.copy(landscape = false)) }, label = { Text(stringResource(R.string.portrait)) })
            FilterChip(selected = sheet.landscape, onClick = { onChange(sheet.copy(landscape = true)) }, label = { Text(stringResource(R.string.landscape)) })
        }
    }
}

@Composable
fun sheetName(size: SheetSize): String = stringResource(
    when (size) {
        SheetSize.A4 -> R.string.sheet_a4
        SheetSize.A5 -> R.string.sheet_a5
        SheetSize.LETTER -> R.string.sheet_letter
        SheetSize.LEGAL -> R.string.sheet_legal
        SheetSize.CUSTOM -> R.string.page_size
    },
)

/** A sheet's name; a custom one by its measures in inches (Phase 20). */
@Composable
fun sheetName(sheet: Sheet): String = if (sheet.size == SheetSize.CUSTOM) {
    stringResource(R.string.sheet_custom, "%.1f".format(sheet.width / Sheet.UNITS_PER_INCH), "%.1f".format(sheet.height / Sheet.UNITS_PER_INCH))
} else {
    sheetName(sheet.size)
}

const val LIBRARY_TAG = "library"
const val SEARCH_TAG = "library_search"
private const val CARD_WIDTH = 200
private const val COMPACT_CARD_WIDTH = 150

/** At this width and more the header is one row: search beside the title. */
private const val WIDE_HEADER = 960

/** Narrower than this (a phone, or a tablet in split screen), margins and gaps shrink. */
private const val COMPACT = 600
private const val COVER_ASPECT = 0.78f
