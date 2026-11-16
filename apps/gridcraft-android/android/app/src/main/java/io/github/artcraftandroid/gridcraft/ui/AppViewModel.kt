package io.github.artcraftandroid.gridcraft.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.gridcraft.GridcraftApplication
import io.github.artcraftandroid.gridcraft.core.CellFormat
import io.github.artcraftandroid.gridcraft.core.CellRange
import io.github.artcraftandroid.gridcraft.core.ColorChoices
import io.github.artcraftandroid.gridcraft.core.ChartPrim
import io.github.artcraftandroid.gridcraft.core.CommentView
import io.github.artcraftandroid.gridcraft.core.SheetObject
import io.github.artcraftandroid.gridcraft.core.SelectionView
import io.github.artcraftandroid.gridcraft.core.SheetView
import io.github.artcraftandroid.gridcraft.core.Viewport
import io.github.artcraftandroid.gridcraft.core.WorkbookInfo
import io.github.artcraftandroid.gridcraft.engine.Engine
import io.github.artcraftandroid.gridcraft.engine.RecentFile
import io.github.artcraftandroid.gridcraft.engine.RecentFiles
import io.github.artcraftandroid.gridcraft.engine.WorkbookFiles
import io.github.artcraftandroid.gridcraft.engine.userMessage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { Start, Editor, Settings }

/** The size the chart galleries' previews are drawn at (chart units), scaled to their tiles. */
const val CHART_PREVIEW_W = 380f
const val CHART_PREVIEW_H = 234.6f

/** The size the Shapes gallery's tiles are drawn at (shape points), scaled to their tiles. */
const val SHAPE_TILE = 44f

/** The grid's visible block, in sheet units from the sheet's top left corner. */
data class GridWindow(val x: Double, val y: Double, val width: Double, val height: Double)

/**
 * The app's state and what it does: the open workbook, the grid's viewport, the selection and the
 * formula bar, the palette. Every edit goes to the engine as a command and the state is read back.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    var engine by mutableStateOf<Engine?>(null)
        private set
    var screen by mutableStateOf(Screen.Start)
    var message by mutableStateOf<String?>(null)

    var info by mutableStateOf<WorkbookInfo?>(null)
        private set
    var sheetView by mutableStateOf<SheetView?>(null)
        private set
    var selection by mutableStateOf<SelectionView?>(null)
        private set
    var format by mutableStateOf<CellFormat?>(null)
        private set
    var viewport by mutableStateOf<Viewport?>(null)
        private set
    var colors by mutableStateOf<ColorChoices?>(null)
        private set

    /** Frozen panes: the frozen rows' height and columns' width (sheet units), and their cells. */
    var frozenW by mutableStateOf(0.0)
        private set
    var frozenH by mutableStateOf(0.0)
        private set
    var frozenTop by mutableStateOf<Viewport?>(null)
        private set
    var frozenLeft by mutableStateOf<Viewport?>(null)
        private set
    var frozenCorner by mutableStateOf<Viewport?>(null)
        private set

    /** The grid's scroll position (sheet units) and zoom (1 = 100%). */
    var scrollX by mutableStateOf(0.0)
    var scrollY by mutableStateOf(0.0)
    var zoom by mutableFloatStateOf(1f)

    /** The formula bar is editing the active cell; what it holds. */
    var editing by mutableStateOf(false)
        private set
    /** What the formula bar holds, with its cursor; [editText] sets it with the cursor at the end. */
    var editValue by mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    var editText: String
        get() = editValue.text
        set(v) {
            editValue = androidx.compose.ui.text.input.TextFieldValue(v, androidx.compose.ui.text.TextRange(v.length))
        }

    /** Every function's name and signature, for the suggestions while a formula is typed. */
    private var functionList = listOf<Pair<String, String>>()

    /** Functions whose names start with the word being typed in a formula (at most eight). */
    val suggestions: List<Pair<String, String>>
        get() {
            if (!editing || !editText.startsWith("=")) return emptyList()
            val word = Regex("[A-Za-z][A-Za-z0-9.]*$").find(editText)?.value ?: return emptyList()
            // a cell reference such as B12 isn't a function's start
            if (Regex("^[A-Za-z]{1,3}[0-9]+$").matches(word)) return emptyList()
            return functionList.filter { it.first.startsWith(word, ignoreCase = true) }.take(8)
        }

    /** The function the cursor is in while a formula is typed: its name, arguments and the current one. */
    val signatureTip: Triple<String, List<String>, Int>?
        get() {
            if (!editing) return null
            val (name, index) = io.github.artcraftandroid.gridcraft.ui.editor.callAt(editValue.text, editValue.selection.end) ?: return null
            val signature = functionList.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second ?: return null
            val args = io.github.artcraftandroid.gridcraft.ui.editor.signatureArgs(signature)
            return Triple(name, args, io.github.artcraftandroid.gridcraft.ui.editor.argumentShown(args, index))
        }

    /** Put function [name] in place of the word being typed, with its opening bracket. */
    fun takeSuggestion(name: String) {
        val word = Regex("[A-Za-z][A-Za-z0-9.]*$").find(editText)?.value ?: ""
        editText = editText.dropLast(word.length) + name + "("
    }

    /** Typing with the number keypad instead of the phone's keyboard. */
    var keypad by mutableStateOf(false)
        private set

    /** The command palette: open, its tab, the pages open over it, the tab list. */
    var paletteOpen by mutableStateOf(false)
    var paletteTab by mutableStateOf("Home")
    var panels by mutableStateOf(listOf<String>())
        private set
    var tabsOpen by mutableStateOf(false)

    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    /** Settings: the theme (`system`, `light`, `dark`) and AutoSave. */
    var themeChoice by mutableStateOf(prefs.getString("theme", "system") ?: "system")
        private set
    var autoSaveOn by mutableStateOf(prefs.getBoolean("autoSave", true))
        private set

    fun chooseTheme(choice: String) {
        themeChoice = choice
        prefs.edit().putString("theme", choice).apply()
    }

    fun chooseAutoSave(on: Boolean) {
        autoSaveOn = on
        prefs.edit().putBoolean("autoSave", on).apply()
    }

    /** Where Settings was opened from: closing it goes back there. */
    private var settingsFrom = Screen.Start

    fun openSettings() {
        moreOpen = false
        settingsFrom = screen
        screen = Screen.Settings
    }

    fun closeSettings() {
        screen = settingsFrom
    }

    /** The header's ⋯ sheet is up. */
    var moreOpen by mutableStateOf(false)

    /** Draw with Pen (the header's ⋯): the Draw tab, with the pen in hand. */
    fun drawWithPen() {
        moreOpen = false
        if (editing) commitEdit("none")
        showTab("Draw")
        paletteOpen = true
        pickPen(penIndex)
    }

    /** The cell menu (a long press) is up; the sheet whose tab menu is up; a sheet being renamed. */
    var cellMenu by mutableStateOf(false)

    /** The workbook theme's fonts, headings then body, read as the font list opens. */
    var themeFonts by mutableStateOf<List<String>>(emptyList())
        private set

    fun loadThemeFonts() = launchEngine { e -> themeFonts = e.command { themeFonts() } }

    /** Something has been copied or cut in the workbook (or an image is in the clipboard), so Paste is on. */
    var canPaste by mutableStateOf(false)
        private set

    private fun hasClipboardImage(): Boolean {
        val app = getApplication<Application>()
        val cm = app.getSystemService(android.content.ClipboardManager::class.java) ?: return false
        val clip = cm.primaryClip ?: return false
        if (clip.description.hasMimeType("image/*")) return true
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val type = app.contentResolver.getType(uri).orEmpty()
            if (type.startsWith("image/")) return true
        }
        return false
    }

    private suspend fun readClipboardImageBytes(): ByteArray? = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val cm = app.getSystemService(android.content.ClipboardManager::class.java) ?: return@withContext null
        val clip = cm.primaryClip ?: return@withContext null
        val hasImageMime = clip.description.hasMimeType("image/*")
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val type = app.contentResolver.getType(uri).orEmpty()
            if (hasImageMime || type.startsWith("image/")) {
                val raw = runCatching { app.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                if (raw != null && raw.isNotEmpty()) {
                    val isPngOrJpg = (raw.size > 3 && raw[0] == 0x89.toByte() && raw[1] == 'P'.code.toByte()) ||
                        (raw.size > 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte())
                    if (isPngOrJpg) return@withContext raw
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
                    if (bmp != null) {
                        val out = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                        return@withContext out.toByteArray()
                    }
                }
            }
        }
        null
    }

    fun loadCanPaste() = launchEngine { e ->
        canPaste = hasClipboardImage() || e.command { disabledReason("edit.pasteSpecial") } == null
    }

    fun pasteSpecial(what: String) = launchEngine { e ->
        cellMenu = false
        e.command { execute("edit.pasteSpecial", org.json.JSONObject().put("what", what).toString()) }
        refresh(e)
    }

    /** The colours Fill Color and Font Color put on with one tap (the JSON colour and its ARGB): the last ones chosen. */
    var lastFill by mutableStateOf("\"#FFFF00\"" to 0xFFFFFF00.toInt())
        private set
    var lastFontColor by mutableStateOf("\"#FF0000\"" to 0xFFFF0000.toInt())
        private set

    private fun swatchArgb(color: String): Int? =
        colors?.let { c -> (c.theme.flatten() + c.standard).firstOrNull { it.color == color }?.argb }

    fun setFill(color: String) {
        swatchArgb(color)?.let { lastFill = color to it }
        command("home.fillColor", "{\"color\": $color}")
    }

    fun setFontColor(color: String) {
        swatchArgb(color)?.let { lastFontColor = color to it }
        command("home.fontColor", "{\"color\": $color}")
    }

    fun applyLastFill() = command("home.fillColor", "{\"color\": ${lastFill.first}}")

    fun applyLastFontColor() = command("home.fontColor", "{\"color\": ${lastFontColor.first}}")

    /** Format Painter: the selection's formats are picked up, and the next cell tapped takes them. */
    var formatPainting by mutableStateOf(false)
        private set

    fun startFormatPainter() = launchEngine { e ->
        e.command { execute("edit.formatPainter", "{}") }
        formatPainting = true
        collapsePalette()
    }

    /** Recommended charts for the selection: their names, types and subtypes, best first. */
    var chartRecommendations by mutableStateOf(listOf<Triple<String, String, String>>())
        private set

    /** [range]: the data to recommend for ("" the selection's). */
    fun loadChartRecommendations(range: String = "") = launchEngine { e ->
        val p = if (range.isEmpty()) "{}" else org.json.JSONObject().put("range", range).toString()
        val a = runCatching { org.json.JSONArray(e.command { execute("insert.chartRecommendations", p) }) }.getOrNull() ?: org.json.JSONArray()
        chartRecommendations = (0 until minOf(a.length(), 20)).map { i ->
            val o = a.getJSONObject(i)
            Triple(o.optString("label"), o.optString("type"), o.optString("subtype"))
        }
    }

    /** The workbook has notes, so Convert All Notes has work to do. */
    var hasNotes by mutableStateOf(false)
        private set

    fun loadHasNotes() = launchEngine { e -> hasNotes = e.view { hasNotes() } }

    /** Fill (from the cell menu): arrows on the selection's right and bottom edges drag its series that way. */
    var fillMode by mutableStateOf(false)

    /** The block a Fill drag would fill, while the finger is down. */
    var fillPreview by mutableStateOf<CellRange?>(null)

    /** Fill [to] from [from]: series continue, other cells copy (`edit.autoFill`). */
    fun autoFill(from: CellRange, to: CellRange) {
        fillMode = false
        fun a1(r: CellRange): String {
            val l = io.github.artcraftandroid.gridcraft.ui.editor.columnLetters(r.firstCol.toInt()) + (r.firstRow.toLong() + 1)
            return l + ":" + io.github.artcraftandroid.gridcraft.ui.editor.columnLetters(r.lastCol.toInt()) + (r.lastRow.toLong() + 1)
        }
        command("edit.autoFill", org.json.JSONObject().put("source", a1(from)).put("target", a1(to)).toString())
    }
    var tabMenu by mutableStateOf<Int?>(null)

    /**
     * The bottom shows the sheets bar (the tabs, the phone app's way to switch sheets) rather than
     * the floating command bar: so when a workbook opens, until a cell is picked; the command bar's
     * sheet chip brings it back and its toggle goes back.
     */
    var sheetsBar by mutableStateOf(true)
        private set

    fun showSheetsBar(on: Boolean) {
        sheetsBar = on
        tabMenu = null
    }

    /** Move sheet [index] before sheet [before], or to the end when [before] is null. */
    fun moveSheetBefore(index: Int, before: Int?) {
        tabMenu = null
        val n = info?.sheets?.size ?: return
        val to = when {
            before == null -> n - 1
            index < before -> before - 1
            else -> before
        }
        if (to != index) command("sheet.move", "{\"sheet\": $index, \"to\": $to}")
    }
    var renaming by mutableStateOf<Int?>(null)

    /** The charts, pictures and shapes over the sheet; each chart's drawing and each picture. */
    var objects by mutableStateOf(listOf<SheetObject>())
        private set
    var chartDrawings by mutableStateOf(mapOf<UInt, List<ChartPrim>>())
        private set
    var pictures by mutableStateOf(mapOf<UInt, androidx.compose.ui.graphics.ImageBitmap>())
        private set

    /** Pictures placed in cells, decoded small, by sheet, row and column; an edit starts them afresh. */
    var cellPictures by mutableStateOf(mapOf<Triple<UInt, UInt, UInt>, androidx.compose.ui.graphics.ImageBitmap>())
        private set
    private var cellPicturesRevision: ULong? = null

    /** The chart, picture or shape selected (a tap on it), and where a drag is taking it. */
    var selectedObject by mutableStateOf<SheetObject?>(null)
        private set
    var objectDrag by mutableStateOf<Pair<androidx.compose.ui.geometry.Offset, androidx.compose.ui.geometry.Offset>?>(null)

    /** IDs of objects whose position/size is currently locked. */
    var lockedObjectIds by mutableStateOf<Set<Long>>(emptySet())
        private set

    fun isObjectLocked(id: Long): Boolean = id in lockedObjectIds

    fun toggleObjectLock(id: Long) {
        objectDrag = null
        objectTurn = null
        cropping = false
        lockedObjectIds = if (id in lockedObjectIds) lockedObjectIds - id else lockedObjectIds + id
    }

    /** Nudge the currently selected object by (dx, dy) sheet units if not locked. */
    fun nudgeSelectedObject(dx: Float, dy: Float) {
        val o = selectedObject ?: return
        if (isObjectLocked(o.id.toLong())) return
        placeObject(maxOf(0.0, o.x + dx), maxOf(0.0, o.y + dy), o.width, o.height)
    }

    /** The selected object being turned by its rotate handle: the degrees turned so far. */
    var objectTurn by mutableStateOf<Float?>(null)

    /** The rotate handle let go: the selected shape or picture turns [by] degrees clockwise. */
    fun rotateObject(by: Float) {
        val o = selectedObject ?: return
        objectTurn = null
        if (isObjectLocked(o.id.toLong())) return
        if (o.kind == "chart" || by == 0f) return
        command("arrange.rotate", org.json.JSONObject().put("kind", if (o.kind == "image") "image" else "shape").put("id", o.id.toLong()).put("by", by.toDouble()).toString())
    }

    /** Drawing: the tool (`` to select, `pen`, `eraser`), the pen's colour and width, the stroke being drawn (sheet units). */
    var drawTool by mutableStateOf("")
        private set
    var penColor by mutableStateOf("#1F5FC9")
        private set
    var penWidth by mutableStateOf(2f)
        private set
    var stroke by mutableStateOf(listOf<androidx.compose.ui.geometry.Offset>())

    fun chooseTool(tool: String) {
        drawTool = tool
        if (tool.isNotEmpty() && editing) commitEdit("none")
        selectObject(null)
    }

    fun choosePen(color: String, width: Float = penWidth) {
        penColor = color
        penWidth = width
        drawTool = "pen"
    }

    /**
     * The Draw tab's pens, left to right: a black pen, a red pen and a yellow highlighter to start,
     * then those added; kept between runs.
     */
    var pens by mutableStateOf(
        prefs.getString("pens", null)?.split(';')?.mapNotNull { Pen.parse(it) }?.ifEmpty { null } ?: DEFAULT_PENS,
    )
        private set

    /** Which of [pens] is in hand. */
    var penIndex by mutableIntStateOf(0)
        private set

    /** Take pen [i] and draw with it. */
    fun pickPen(i: Int) {
        val pen = pens.getOrNull(i) ?: return
        penIndex = i
        chooseTool("pen")
        choosePen(pen.color, pen.width)
    }

    /** Change the pen in hand's colour or width. */
    fun changePen(color: String? = null, width: Float? = null) {
        val pen = pens.getOrNull(penIndex) ?: return
        val changed = pen.copy(color = color ?: pen.color, width = width ?: pen.width)
        savePens(pens.toMutableList().also { it[penIndex] = changed })
        pickPen(penIndex)
    }

    /** Add a pen of [kind] at the end of the strip, and take it. */
    fun addPen(kind: String) {
        savePens(pens + Pen.of(kind))
        pickPen(pens.size - 1)
    }

    /** Take the pen in hand away (one always stays). */
    fun deletePen() {
        if (pens.size <= 1) return
        savePens(pens.filterIndexed { i, _ -> i != penIndex })
        pickPen(penIndex.coerceAtMost(pens.size - 1))
    }

    private fun savePens(list: List<Pen>) {
        pens = list
        prefs.edit().putString("pens", list.joinToString(";") { "${it.kind}|${it.color}\vert{}${it.width}" }).apply()
    }

    /** Keep the stroke just drawn as ink. */
    fun finishStroke() {
        val pts = stroke
        stroke = emptyList()
        if (pts.size < 2) return
        val a = org.json.JSONArray()
        for (p in pts) a.put(org.json.JSONArray().put(p.x.toDouble()).put(p.y.toDouble()))
        command("draw.stroke", org.json.JSONObject().put("points", a).put("color", penColor).put("width", penWidth.toDouble()).toString())
    }

    /** The eraser touched (`x`, `y`): the ink there goes. */
    fun eraseAt(x: Double, y: Double) {
        val hit = objects.lastOrNull { it.kind == "shape" && it.shape == "ink" && x >= it.x - 4 && x <= it.x + it.width + 4 && y >= it.y - 4 && y <= it.y + it.height + 4 } ?: return
        objects = objects - hit
        command("object.delete", org.json.JSONObject().put("kind", "shape").put("id", hit.id.toLong()).toString())
    }

    /** The active cell's validation (its list to pick from, its input message); the list is up. */
    var validation by mutableStateOf<io.github.artcraftandroid.gridcraft.core.ValidationView?>(null)
        private set
    var pickingList by mutableStateOf(false)

    /** The cell's validation list items that start with what is being typed (at most eight). */
    val listSuggestions: List<String>
        get() {
            val typed = editText
            if (!editing || typed.isEmpty() || typed.startsWith("=")) return emptyList()
            val items = validation?.items ?: return emptyList()
            return items.filter { it.startsWith(typed, ignoreCase = true) && it != typed }.take(8)
        }

    /** The active cell holds dates or times (its number format's kind), for the picker. */
    val pickerKind: String?
        get() = when (format?.numberKind) {
            "Date" -> "date"
            "Time" -> "time"
            else -> null
        }

    /** A date or time from the picker: typed into the cell and entered. */
    fun pickDateTime(text: String) {
        dialog = null
        if (!editing) beginEdit("")
        editText = text
        commitEdit("none")
    }

    /** Put a value picked from the validation list in the active cell. */
    fun pickValue(v: String) {
        pickingList = false
        editText = v
        editing = true
        commitEdit("none")
    }

    /** The aggregate bar's figures for a selection of several cells. */
    var stats by mutableStateOf<io.github.artcraftandroid.gridcraft.core.SelectionStats?>(null)
        private set

    /** The reference a tap put at the end of the formula being typed (a next tap replaces it). */
    private var pointed: String? = null

    /** A formula is being typed and its end takes a reference: after `=`, an operator, `(` or `,`. */
    val pointing: Boolean
        get() = editing && editText.startsWith("=") && (pointed != null && editText.endsWith(pointed!!) || editText.last() in "=+-*/^&(,:<>;")

    /** A tap on a cell while a formula is typed: its reference goes into the formula. */
    fun pointAt(ref: String) {
        val last = pointed
        editText = if (last != null && editText.endsWith(last)) editText.dropLast(last.length) + ref else editText + ref
        pointed = ref
    }

    /** The cell the reference tapped into a formula starts at, and the block it reaches now. */
    private var pointedFrom: Pair<UInt, UInt>? = null
    var pointedBlock by mutableStateOf<io.github.artcraftandroid.gridcraft.core.CellRange?>(null)
        private set

    /** The tapped reference is still the formula's end, so its handle can stretch it. */
    val pointedStretchable: Boolean
        get() = editing && pointedBlock != null && pointed?.let { editText.endsWith(it) } == true

    private fun cellName(row: UInt, col: UInt) = io.github.artcraftandroid.gridcraft.ui.editor.columnLetters(col.toInt()) + (row.toLong() + 1)

    fun pointAtCell(row: UInt, col: UInt) {
        pointedFrom = row to col
        pointedBlock = io.github.artcraftandroid.gridcraft.core.CellRange(row, col, row, col)
        pointAt(cellName(row, col))
    }

    /** The reference's handle dragged to (`row`, `col`): the reference becomes the block between. */
    fun stretchPointed(row: UInt, col: UInt) {
        val (r0, c0) = pointedFrom ?: return
        if (!pointedStretchable) return
        val block = io.github.artcraftandroid.gridcraft.core.CellRange(minOf(r0, row), minOf(c0, col), maxOf(r0, row), maxOf(c0, col))
        pointedBlock = block
        val one = block.firstRow == block.lastRow && block.firstCol == block.lastCol
        pointAt(if (one) cellName(r0, c0) else cellName(block.firstRow, block.firstCol) + ":" + cellName(block.lastRow, block.lastCol))
