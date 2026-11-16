        // a selected chart marks its source cells, as the phone app does
        val source = vm.selectedObject?.takeIf { it.kind == "chart" && !vm.editing }?.let { vm.chartSources[it.id] }?.let(::chartSourceBlocks).orEmpty()
        val x0 = rowHeaderW + fw
        val y0 = colHeaderH + fh
        clipRect(x0, y0, size.width, size.height) {
            drawCells(context, v, unit, ::screenX, ::screenY, grid, density, cellPicture)
            // the cell selection goes under the objects floating over the sheet
            drawSelection(vm, v, ::screenX, ::screenY, density)
            drawChartSource(source, v, ::screenX, ::screenY, density)
            drawFormulaRefs(refs, v, ::screenX, ::screenY, density)
            drawObjects(vm, unit, ::screenX, ::screenY, density, iconPainters)
        }
        vm.frozenTop?.takeIf { fr > 0u }?.let { t ->
            clipRect(x0, colHeaderH, size.width, y0) {
                drawRect(GridColors.Cells, Offset(x0, colHeaderH), Size(size.width - x0, fh))
                drawCells(context, t, unit, ::screenX, ::fixedY, grid, density, cellPicture)
                drawSelection(vm, t, ::screenX, ::fixedY, density)
                drawChartSource(source, t, ::screenX, ::fixedY, density)
                drawFormulaRefs(refs, t, ::screenX, ::fixedY, density)
            }
        }
        vm.frozenLeft?.takeIf { fc > 0u }?.let { l ->
            clipRect(rowHeaderW, y0, x0, size.height) {
                drawRect(GridColors.Cells, Offset(rowHeaderW, y0), Size(fw, size.height - y0))
                drawCells(context, l, unit, ::fixedX, ::screenY, grid, density, cellPicture)
                drawSelection(vm, l, ::fixedX, ::screenY, density)
                drawChartSource(source, l, ::fixedX, ::screenY, density)
                drawFormulaRefs(refs, l, ::fixedX, ::screenY, density)
            }
        }
        vm.frozenCorner?.takeIf { fr > 0u && fc > 0u }?.let { c ->
            clipRect(rowHeaderW, colHeaderH, x0, y0) {
                drawRect(GridColors.Cells, Offset(rowHeaderW, colHeaderH), Size(fw, fh))
                drawCells(context, c, unit, ::fixedX, ::fixedY, grid, density, cellPicture)
                drawSelection(vm, c, ::fixedX, ::fixedY, density)
                drawChartSource(source, c, ::fixedX, ::fixedY, density)
                drawFormulaRefs(refs, c, ::fixedX, ::fixedY, density)
            }
        }
