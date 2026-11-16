package io.github.artcraftandroid.gridcraft.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import io.github.artcraftandroid.gridcraft.ui.AppViewModel
import io.github.artcraftandroid.gridcraft.ui.GridColors

/**
 * Editing a workbook: the header, the grid, then along the bottom the formula bar over either the
 * command bar or the command palette.
 */
@Composable
fun EditorScreen(vm: AppViewModel) {
    BackHandler {
        when {
            vm.moreOpen -> vm.moreOpen = false
            vm.cardEdit != null -> vm.cardEdit = null
            vm.cardOpen != null -> vm.cardOpen = null
            vm.cards != null -> vm.closeCards()
            vm.renaming != null -> vm.renaming = null
            vm.tabMenu != null -> vm.tabMenu = null
            vm.pickingList -> vm.pickingList = false
            vm.filterAt != null -> vm.closeFilter()
            vm.commentsOpen && !vm.paletteOpen -> vm.showComments(false)
            vm.cellMenu -> vm.cellMenu = false
            vm.findOpen -> vm.closeFind()
            vm.tabsOpen -> vm.tabsOpen = false
            vm.tellMeOpen && vm.panels.isEmpty() -> vm.tellMeOpen = false
            vm.panels.isNotEmpty() -> vm.popPanel()
            vm.paletteOpen -> vm.collapsePalette()
            vm.editing -> vm.cancelEdit()
            else -> vm.closeWorkbook()
        }
    }
    vm.cards?.let { t ->
        Box(Modifier.fillMaxSize().background(GridColors.StatusBand).statusBarsPadding().navigationBarsPadding().imePadding()) { CardsScreen(vm, t) }
        return
    }
    val screenFocus = remember { FocusRequester() }
    LaunchedEffect(vm.editing, vm.findOpen, vm.dialog, vm.editingShapeText, vm.moreOpen) {
        if (!vm.editing && !vm.findOpen && vm.dialog == null && vm.editingShapeText == null && !vm.moreOpen) {
            runCatching { screenFocus.requestFocus() }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(GridColors.StatusBand)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .focusRequester(screenFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val typingInField = vm.editing || vm.editingShapeText != null || vm.findOpen || vm.dialog != null
                if (typingInField) return@onPreviewKeyEvent false

                val ctrl = event.isCtrlPressed || event.isMetaPressed
                if (ctrl && event.key == Key.V) {
                    vm.paste()
                    return@onPreviewKeyEvent true
                }

                val obj = vm.selectedObject
                if (obj != null) {
                    val locked = vm.isObjectLocked(obj.id.toLong())
                    val step = if (event.isShiftPressed) 10f else 1f
                    when (event.key) {
                        Key.DirectionUp -> {
                            if (!locked) vm.nudgeSelectedObject(0f, -step)
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionDown -> {
                            if (!locked) vm.nudgeSelectedObject(0f, step)
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionLeft -> {
                            if (!locked) vm.nudgeSelectedObject(-step, 0f)
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionRight -> {
                            if (!locked) vm.nudgeSelectedObject(step, 0f)
                            return@onPreviewKeyEvent true
                        }
                        Key.Delete, Key.Backspace -> {
                            if (!locked) vm.deleteObject()
                            return@onPreviewKeyEvent true
                        }
                        else -> Unit
                    }
                }
                false
            },
    ) {
        val tellMe = vm.paletteOpen && vm.tellMeOpen && vm.panels.isEmpty()
        if (vm.findOpen) FindBar(vm) else if (vm.editingShapeText == null && !vm.cropping && (!vm.paletteOpen || tellMe)) EditorHeader(vm)
        val formulaBar = vm.showFormulaBar || vm.editing
        Box(Modifier.weight(1f).fillMaxWidth()) {
            GridView(vm, Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                FunctionSuggestions(vm)
                ListSuggestions(vm)
                SignatureTip(vm)
            }
            if (vm.moreOpen) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x66000000)).clickable { vm.moreOpen = false })
        }
        EditorDialog(vm)
        vm.renaming?.let { RenameDialog(vm, it) }
        if (vm.moreOpen) {
            MoreSheet(vm)
        } else if (vm.editing && vm.keypad) {
            FormulaBar(vm)
            Keypad(vm)
        } else if (vm.pickingList) {
            ValidationSheet(vm)
        } else if (vm.filterAt != null) {
            FilterSheet(vm)
        } else if (vm.commentsOpen && !vm.paletteOpen) {
            CommentsPane(vm)
        } else if (tellMe) {
            TellMeSheet(vm, Modifier.fillMaxWidth())
        } else if (vm.paletteOpen) {
            if (showsAggregates(vm)) AggregateBar(vm) else if (formulaBar) FormulaBar(vm)
            PaletteSheet(vm, Modifier.fillMaxWidth().height(PALETTE_HEIGHT))
        } else {
            if (vm.comment != null && !vm.editing) CommentCard(vm) else if (!vm.editing) InputMessage(vm)
            if (showsAggregates(vm)) AggregateBar(vm) else if (formulaBar) FormulaBar(vm)
            if (!vm.editing) {
                if (vm.sheetsBar) SheetsBar(vm) else CommandBar(vm)
            }
        }
    }
}
