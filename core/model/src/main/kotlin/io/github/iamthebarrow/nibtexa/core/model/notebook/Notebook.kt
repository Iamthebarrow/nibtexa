package io.github.iamthebarrow.nibtexa.core.model.notebook

import io.github.iamthebarrow.nibtexa.core.model.InkDocument
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.Stroke

/**
 * What a region's content is, and therefore how it is converted and exported. Set by the
 * pen it was written with (Phase 6 can change it with the lasso).
 */
enum class RegionRole {
    MATH,
    TEXT,
    HEADING,
    FIGURE,
    EXCLUDED,
    ;

    companion object {
        fun forPen(pen: Pen): RegionRole = when (pen) {
            Pen.MATH -> MATH
            Pen.TEXT -> TEXT
            Pen.DRAWING -> FIGURE
            Pen.SCRATCH -> EXCLUDED
        }
    }
}

/** An axis-aligned rectangle in canvas units. */
data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun union(other: Bounds) = Bounds(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))

    companion object {
        fun of(stroke: Stroke): Bounds = Bounds(
            stroke.points.minOf { it.x },
            stroke.points.minOf { it.y },
            stroke.points.maxOf { it.x },
            stroke.points.maxOf { it.y },
        )

        fun of(strokes: Collection<Stroke>): Bounds? = strokes.map(::of).reduceOrNull(Bounds::union)
    }
}

/** Where a region's conversion stands. */
enum class RecognitionStatus {
    /** Created but never sent to the recognizer. */
    NOT_REQUESTED,

    /** A recognition request for [Region.sourceStrokeIds] is running. */
    RECOGNIZING,

    /** The recognizer returned [Region.recognizedValue]; the user has not confirmed it yet. */
    RECOGNIZED,

    /** The recognizer failed or the request was canceled. The ink is untouched. */
    FAILED,

    /** The user confirmed [Region.confirmedLatex] for [Region.sourceStrokeIds]. */
    CONFIRMED,
}

/**
 * A part of a page with one role. Conversion never touches the ink: the region refers to
 * its strokes by id, and keeps what the recognizer returned and what the user confirmed
 * separately, so neither can overwrite the other silently.
 *
 * @property strokeIds every stroke id the region has claimed. The current members are the
 *   claimed strokes still on the page ([Page.membersOf]); stroke ids are never reused, so an
 *   undone stroke that is redone rejoins its region.
 * @property sourceStrokeIds the strokes the current result or confirmation belongs to. When
 *   the members differ, the result is stale ([Page.isStale]).
 * @property confirmedLatex the user's last confirmed LaTeX (math regions). It survives
 *   reconversion until the user confirms something else.
 * @property recognizedLatex what the math recognizer returned for [sourceStrokeIds].
 * @property recognizedText what the text recognizer returned for [sourceStrokeIds] (text and
 *   heading regions, ADR-0007).
 * @property text the confirmed transcription of a text or heading region: the recognizer's
 *   result as the user accepted or corrected it, or typed.
 * @property headingLevel 1 (section) to 3 (subsubsection), for heading regions.
 * @property caption an optional caption for a figure region.
 * @property align where the region sits across the exported page, set by the user; null keeps
 *   the default ([Page.alignOf]).
 * @property auto made by automatic grouping ([AutoRegions]) and not yet confirmed, re-roled,
 *   captioned or remade by the user. Only automatic regions are ever changed by grouping.
 *
 * Structure (Phase 16):
 * @property marks formatting over [text] ([TextMarks]), confirmed with it.
 * @property theorem a text region that starts a definition, theorem, proof and so on.
 * @property listLevel a list item's depth, 0–2, set by the user; null follows the position.
 * @property newParagraph a text region's own choice to start a paragraph (true) or continue the
 *   one before (false); null follows the spacing.
 * @property numbered a math region numbered as an equation.
 * @property inline a math region's own choice to sit in the text (true) or on its own line
 *   (false); null follows the position.
 * @property pageBreak the exports start a new page before this region.
 * @property typed typed, not written (Phase 18): imported text or maths with no ink. It is confirmed as it
 *   is, never stale, and kept when it has no strokes.
 * @property picture a picture (Phase 20): a typed figure region that shows this image file (a name in
 *   the app's picture store) in [bounds] instead of ink.
 * @property graph a graph (Phase 25): a typed figure region that draws these equations in [bounds].
 * @property diagram a diagram (Phase 31): a typed figure region that draws these shapes, lines and labels, scaled to fill [bounds].
 */
data class Region(
    val id: String,
    val role: RegionRole,
    val strokeIds: Set<Long>,
    val bounds: Bounds,
    val readingOrder: Int,
    val status: RecognitionStatus = RecognitionStatus.NOT_REQUESTED,
    val sourceStrokeIds: Set<Long> = emptySet(),
    val recognizedLatex: String? = null,
    val confirmedLatex: String? = null,
    val error: String? = null,
    val text: String? = null,
    val headingLevel: Int = 1,
    val caption: String? = null,
    val recognizedText: String? = null,
    val auto: Boolean = false,
    val align: MathAlign? = null,
    val marks: List<TextMark> = emptyList(),
    val theorem: TheoremKind? = null,
    val listLevel: Int? = null,
    val newParagraph: Boolean? = null,
    val numbered: Boolean = false,
    val inline: Boolean? = null,
    val pageBreak: Boolean = false,
    val typed: Boolean = false,
    val picture: String? = null,
    val graph: Graph? = null,
    val diagram: Diagram? = null,
) {
    /** The recognizer's latest result for this role, if any. */
    val recognizedValue: String?
        get() = when (role) {
            RegionRole.MATH -> recognizedLatex
            RegionRole.TEXT, RegionRole.HEADING -> recognizedText
            RegionRole.FIGURE, RegionRole.EXCLUDED -> null
        }

    /** What the region exports as, if it has been confirmed: LaTeX for math, text otherwise. */
    val confirmedValue: String?
        get() = when (role) {
            RegionRole.MATH -> confirmedLatex
            RegionRole.TEXT, RegionRole.HEADING -> text
            RegionRole.FIGURE, RegionRole.EXCLUDED -> null
        }

    /**
     * Roles whose content the user confirms (math, text, heading); figures export their ink.
     * Scratch is recognized as text for search, but never confirmed or exported.
     */
    val needsConfirmation: Boolean get() = role == RegionRole.MATH || role == RegionRole.TEXT || role == RegionRole.HEADING
}

/**
 * How a page is shown (Phase 17): zoomed by [scale] (1 is 100%), with the page point ([left],
 * [top]), in page units, at the top-left corner of the screen. Display only: the ink stays in
 * page units, so the view never changes the notes or the exports.
 */
data class PageView(val scale: Float = 1f, val left: Float = 0f, val top: Float = 0f) {
    /** The same view with its scale kept in range. */
    fun clamped(): PageView = PageView(scale.coerceIn(MIN_SCALE, MAX_SCALE), left.coerceIn(-MAX_OFFSET, MAX_OFFSET), top.coerceIn(-MAX_OFFSET, MAX_OFFSET))

    /**
     * The view kept round [sheet] on a screen [width] x [height] dp (Phase 19): along each side, a
     * sheet smaller than the screen sits in the middle; a larger one can be moved until its edge,
     * plus [SHEET_MARGIN], reaches the screen's, but no further. Up and down it goes [PEN_BAR_ROOM]
     * further (Phase 29), so that the top and bottom of the page can come out from under the pen bar;
     * only a sheet that fits clear of the bar stays in the middle.
     */
    fun within(sheet: Sheet, width: Float, height: Float): PageView {
        val s = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        fun axis(start: Float, length: Float, screen: Float, room: Float = 0f): Float {
            val visible = screen / s
            return if (visible >= length + 2 * (SHEET_MARGIN + room)) (length - visible) / 2 else start.coerceIn(-SHEET_MARGIN - room, length + SHEET_MARGIN + room - visible)
        }
        if (width <= 0f || height <= 0f) return clamped()
        return PageView(s, axis(left, sheet.width, width), axis(top, sheet.height, height, PEN_BAR_ROOM / s))
    }

    /**
     * The view with [bounds] on a screen [width] x [height] dp at the same zoom (Phase 28). Along each side: as it is if the
     * region is in view; in the middle if it fits; from its start if it is too long to fit, so that a heading's first words
     * show. The top of the screen, where the pen bar can be, doesn't count as in view. As far as [sheet] allows.
     */
    fun showing(bounds: Bounds, sheet: Sheet, width: Float, height: Float): PageView {
        fun axis(start: Float, low: Float, high: Float, visible: Float, covered: Float = 0f) = when {
            low >= start + covered && high <= start + visible -> start
            high - low >= visible - covered -> low - covered
            else -> (low + high - visible - covered) / 2
        }
        val moved = copy(left = axis(left, bounds.left, bounds.right, width / scale), top = axis(top, bounds.top, bounds.bottom, height / scale, PEN_BAR_ROOM / scale))
        return if (moved == this) this else moved.within(sheet, width, height)
    }

    /** Zoomed to [scale] about the screen point ([x], [y]) in dp, which keeps showing the same page point. */
    fun zoomedAbout(scale: Float, x: Float, y: Float): PageView {
        val s = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        val pageX = left + x / this.scale
        val pageY = top + y / this.scale
        return PageView(s, pageX - x / s, pageY - y / s).clamped()
    }

    companion object {
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 4f
        private const val MAX_OFFSET = 100_000f

        /** Desk shown round the sheet when it is fitted to the screen, in page units. */
        const val SHEET_MARGIN = 24f

        /** How much of the screen, in dp, the pen bar can take at the top or bottom of the page. */
        const val PEN_BAR_ROOM = 96f

        /** The sheet's width across a screen [width] x [height] dp, from its top, which starts clear of the pen bar. */
        fun fitWidth(sheet: Sheet, width: Float, height: Float): PageView {
            val scale = width / (sheet.width + 2 * SHEET_MARGIN)
            return PageView(scale, -SHEET_MARGIN, -SHEET_MARGIN - PEN_BAR_ROOM / scale).within(sheet, width, height)
        }

        /** The whole sheet on the screen, clear of the pen bar. */
        fun wholePage(sheet: Sheet, width: Float, height: Float): PageView =
            PageView(minOf(width / (sheet.width + 2 * SHEET_MARGIN), (height - 2 * PEN_BAR_ROOM) / (sheet.height + 2 * SHEET_MARGIN))).within(sheet, width, height)
    }
}

/** Paper sizes for a booklet's pages (Phase 19), portrait, in inches, with their LaTeX option. */
enum class SheetSize(val widthInches: Float, val heightInches: Float, val latex: String) {
    A4(8.2677f, 11.6929f, "a4paper"),
    A5(5.8268f, 8.2677f, "a5paper"),
    LETTER(8.5f, 11f, "letterpaper"),
    LEGAL(8.5f, 14f, "legalpaper"),

    /** Any other size (Phase 20: an imported PDF's), from [Sheet.customWidthInches] and [Sheet.customHeightInches]. */
    CUSTOM(0f, 0f, ""),
}

/**
 * A booklet's page (Phase 19): its paper size, and whether it is turned on its side. Page units are
 * 1/160 inch (Android's dp), so writing is the same size on the sheet as on the screen at 100%.
 */
data class Sheet(
    val size: SheetSize = SheetSize.A4,
    val landscape: Boolean = false,
    val customWidthInches: Float = 0f,
    val customHeightInches: Float = 0f,
) {
    private val portraitWidth: Float get() = if (size == SheetSize.CUSTOM) customWidthInches else size.widthInches
    private val portraitHeight: Float get() = if (size == SheetSize.CUSTOM) customHeightInches else size.heightInches

    val width: Float get() = (if (landscape) portraitHeight else portraitWidth) * UNITS_PER_INCH
    val height: Float get() = (if (landscape) portraitWidth else portraitHeight) * UNITS_PER_INCH

    /** The page point ([x], [y]) is on the sheet, not the desk round it (Phase 20: ink is written only on the sheet). */
    fun contains(x: Float, y: Float): Boolean = x in 0f..width && y in 0f..height

    /**
     * The part of a stroke written on the sheet: its points up to where it first leaves it, ending
     * on the edge. Empty when it starts on the desk.
     */
    fun clip(points: List<InkPoint>): List<InkPoint> = Bounds(0f, 0f, width, height).clip(points)

    /** In PDF points (1/72 inch). */
    val widthPoints: Float get() = width / UNITS_PER_INCH * 72f
    val heightPoints: Float get() = height / UNITS_PER_INCH * 72f

    companion object {
        const val UNITS_PER_INCH = 160f

        /** A custom sheet of [widthPoints] x [heightPoints] PDF points, as a PDF page gives its size. */
        fun ofPoints(widthPoints: Float, heightPoints: Float) =
            Sheet(SheetSize.CUSTOM, customWidthInches = widthPoints / 72f, customHeightInches = heightPoints / 72f)
    }
}

/**
 * How a page's background is drawn. Display only: never exported (except in the annotated PDF).
 * [spacing] is the distance between lines in page units: ruled at 8 mm, as A4 lined paper (a German
 * Collegeblock); grid at 0.2 in (about 5 mm), as squared paper.
 */
enum class Paper(val spacing: Float) {
    BLANK(0f),
    RULED(8f / 25.4f * Sheet.UNITS_PER_INCH),
    GRID(0.2f * Sheet.UNITS_PER_INCH),
}

/** One page: its ink and the regions defined on it. */
data class Page(
    val id: String,
    val ink: InkDocument = InkDocument.EMPTY,
    val regions: List<Region> = emptyList(),
    /** The number the next region id is built from. Never reused. */
    val nextRegionNumber: Int = 1,
    val paper: Paper = Paper.BLANK,
    /**
     * The user arranged the reading order by hand. Until then (and again after "Order by
     * position") the order follows the regions' positions after every edit.
     */
    val manualOrder: Boolean = false,
    /** Whether the page is set in columns (Phase 30). */
    val columns: ColumnMode = ColumnMode.AUTO,
    /** This page's own margin, in place of the booklet's; null to use the booklet's (Phase 30). */
    val margin: PageMargin? = null,
    /** How the page was last shown on this device (Phase 17); null until it is zoomed or moved. Never exported. */
    val view: PageView? = null,
    /**
     * The picture under the ink (Phase 20: an imported PDF page), a name in the app's picture store,
     * filling the sheet; null for plain paper. Shown and in the annotated PDF, not in the other exports.
     */
    val background: String? = null,
) {
    fun region(id: String): Region? = regions.firstOrNull { it.id == id }

    /**
     * Where [region] sits across the exported page: its own alignment, or the default for its
     * role (equations as the booklet's [bookletMath], text and headings on the left, figures
     * centred).
     */
    fun alignOf(region: Region, bookletMath: MathAlign): MathAlign = region.align ?: when (region.role) {
        RegionRole.MATH -> bookletMath
        RegionRole.TEXT, RegionRole.HEADING -> MathAlign.LEFT
        RegionRole.FIGURE, RegionRole.EXCLUDED -> MathAlign.CENTER
    }

    /** Regions in the order they are read and exported. */
    val regionsInReadingOrder: List<Region> get() = regions.sortedBy { it.readingOrder }

    /** The region's strokes that are on the page now. */
    fun membersOf(region: Region): Set<Long> {
        val present = ink.strokes.mapTo(HashSet()) { it.id }
        return region.strokeIds.filterTo(linkedSetOf()) { it in present }
    }

    /** The ink is gone, or changed since the result or confirmation was produced. */
    fun isStale(region: Region): Boolean {
        // Typed regions (Phase 18) have no ink to go out of date.
        if (region.typed) return false
        val members = membersOf(region)
        return members.isEmpty() || (region.status != RecognitionStatus.NOT_REQUESTED && members != region.sourceStrokeIds)
    }

    /**
     * Ready for export: figures need ink; math, text and headings need a confirmation for
     * exactly the ink on the page now. Excluded regions are never exported.
     */
    fun isExportable(region: Region): Boolean = when {
        region.role == RegionRole.EXCLUDED -> false
        region.role == RegionRole.FIGURE -> region.picture != null || region.graph != null || region.diagram != null || membersOf(region).isNotEmpty()
        else -> region.status == RecognitionStatus.CONFIRMED && !region.confirmedValue.isNullOrBlank() && !isStale(region)
    }
}

/**
 * Theorem-style blocks (Phase 16), as `amsthm` sets them: [environment] is the LaTeX
 * environment, [style] its `\theoremstyle` (null for `proof`, which `amsthm` defines).
 */
enum class TheoremKind(val environment: String, val label: String, val style: String?) {
    DEFINITION("definition", "Definition", "definition"),
    THEOREM("theorem", "Theorem", "plain"),
    LEMMA("lemma", "Lemma", "plain"),
    PROOF("proof", "Proof", null),
    EXAMPLE("example", "Example", "definition"),
    REMARK("remark", "Remark", "remark"),

    /** A paper's abstract (Phase 30): LaTeX's own environment, not a theorem. */
    ABSTRACT("abstract", "Abstract", null),
    ;

    val numbered: Boolean get() = this != PROOF && this != ABSTRACT

    /** Plain-style bodies are italic. */
    val italicBody: Boolean get() = style == "plain"
}

/**
 * Where something sits across the page in the PDF and LaTeX exports: a booklet's equations
 * ([BookletInfo.mathAlign]), or one region of its own ([Region.align]).
 */
enum class MathAlign { LEFT, CENTER, RIGHT }

/**
 * What the exports print on the cover page, and how they place equations. Dates are calendar
 * days in the device's time zone, so an export is the same wherever it is compiled.
 */
data class BookletInfo(
    val author: String = "",
    val created: java.time.LocalDate? = null,
    val updated: java.time.LocalDate? = null,
    val mathAlign: MathAlign = MathAlign.CENTER,
    /** The paper the pages are, on screen and in the exports (Phase 19). */
    val sheet: Sheet = Sheet(),
    val style: DocumentStyle = DocumentStyle(),
)

/**
 * How a booklet is set as a document (Phase 30).
 * - [titleOnFirstPage]: the title, author and dates at the top of the first page, as a paper has them, in place of a cover page and
 *   table of contents;
 * - [numberedSections]: headings numbered 1, 1.1, 1.1.1;
 * - [runningHeader]: the booklet's title and the current section at the top of each page;
 * - [margin]: the page margin, on the page and in the exports.
 */
data class DocumentStyle(
    val titleOnFirstPage: Boolean = false,
    val numberedSections: Boolean = false,
    val runningHeader: Boolean = false,
    val margin: PageMargin = PageMargin.NORMAL,
)

/** A booklet: a titled, ordered set of pages. */
data class Booklet(
    val id: String,
    val title: String,
    val pages: List<Page>,
    val info: BookletInfo = BookletInfo(),
)
