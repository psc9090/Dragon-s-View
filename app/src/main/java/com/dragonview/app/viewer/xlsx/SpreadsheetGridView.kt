// app/src/main/java/com/dragonview/app/viewer/xlsx/SpreadsheetGridView.kt
package com.dragonview.app.viewer.xlsx

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Custom 4-pane Spreadsheet Grid View for XLSX rendering.
 *
 * Architecture:
 * - Top-Left: Fixed Corner Cell (Column A / Row 1 intersection)
 * - Top Header: Frozen Row 1 (Columns B..N), scrolls horizontally with data
 * - Left Column: Frozen Column A (Rows 2..N), scrolls vertically with data
 * - Main Grid: Scrollable cell data (Rows 2..N, Columns B..N)
 *
 * Features:
 * - Frozen headers both vertically and horizontally
 * - Synchronized vertical scrolling with recursion guard
 * - Recycled ViewHolders for optimal memory footprint
 * - Cell selection callback for formula bar updates
 */
class SpreadsheetGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val colWidthPx: Int = dpToPx(115f)
    private val rowHeightPx: Int = dpToPx(38f)
    private val leftColWidthPx: Int = dpToPx(105f)

    private val colorBackground = Color.parseColor("#0F1117")
    private val colorSurface = Color.parseColor("#181B22")
    private val colorHeaderBg = Color.parseColor("#1F2430")
    private val colorBorder = Color.parseColor("#2E3444")
    private val colorTextPrimary = Color.parseColor("#E6E1E5")
    private val colorTextSecondary = Color.parseColor("#9E9E9E")
    private val colorFlame = Color.parseColor("#FF5722")
    private val colorSelectedCellBg = Color.parseColor("#33FF5722")

    private var leftRecyclerView: RecyclerView
    private var mainRecyclerView: RecyclerView
    private var horizontalScrollView: HorizontalScrollView
    private var cornerView: FrameLayout
    private var topHeaderRowLayout: LinearLayout

    private var isSyncingScroll = false
    private var currentSheet: XlsxSheet? = null
    private var onCellSelectedListener: ((XlsxCell) -> Unit)? = null
    private var selectedCellCoordinate: Pair<Int, Int>? = null // (colIndex, rowIndex)

    init {
        setBackgroundColor(colorBackground)

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // 1. Left Pane: Top-Left Corner + Frozen Column A
        val leftPane = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(leftColWidthPx, LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(colorSurface)
        }

        cornerView = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(leftColWidthPx, rowHeightPx)
            setBackgroundColor(colorHeaderBg)
        }
        leftPane.addView(cornerView)

        leftRecyclerView = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(leftColWidthPx, 0, 1f)
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            setHasFixedSize(true)
            setItemViewCacheSize(4)
        }
        leftPane.addView(leftRecyclerView)
        rootLayout.addView(leftPane)

        // Divider between frozen column and main grid
        val colDivider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(1.5f), LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(colorFlame)
        }
        rootLayout.addView(colDivider)

        // 2. Right Pane: HorizontalScrollView hosting Top Header Row + Main RecyclerView
        horizontalScrollView = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            isFillViewport = false
        }

        val rightContentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        topHeaderRowLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, rowHeightPx)
            setBackgroundColor(colorHeaderBg)
        }
        rightContentContainer.addView(topHeaderRowLayout)

        // Divider below frozen top header row
        val rowDivider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1.5f))
            setBackgroundColor(colorFlame)
        }
        rightContentContainer.addView(rowDivider)

        mainRecyclerView = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1f)
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            setHasFixedSize(true)
            setItemViewCacheSize(4)
            isNestedScrollingEnabled = true
        }
        rightContentContainer.addView(mainRecyclerView)

        horizontalScrollView.addView(rightContentContainer)
        rootLayout.addView(horizontalScrollView)

        addView(rootLayout)

        setupScrollSynchronization()
    }

    fun setOnCellSelectedListener(listener: (XlsxCell) -> Unit) {
        this.onCellSelectedListener = listener
    }

    fun setSheetData(sheet: XlsxSheet) {
        this.currentSheet = sheet
        selectedCellCoordinate = null

        val rows = sheet.rows
        val maxCol = sheet.maxColumnIndex.coerceAtLeast(0)

        // Extract Row 1 (header row) and Column A (frozen column)
        val row1 = rows.firstOrNull()
        val dataRows = if (rows.size > 1) rows.subList(1, rows.size) else emptyList()

        // 1. Populate Top-Left Corner (Row 1, Column A)
        populateCorner(row1?.cells?.get(0))

        // 2. Populate Top Header Row (Row 1, Columns B..N)
        populateTopHeaderRow(row1, maxCol)

        // 3. Populate Left Frozen Column (Rows 2..N, Column A)
        leftRecyclerView.adapter = FrozenLeftColumnAdapter(
            dataRows = dataRows,
            rowHeightPx = rowHeightPx,
            onCellClick = { cell -> handleCellSelected(cell) }
        )

        // 4. Populate Main Content Grid (Rows 2..N, Columns B..N)
        mainRecyclerView.adapter = MainGridAdapter(
            dataRows = dataRows,
            maxColIndex = maxCol,
            colWidthPx = colWidthPx,
            rowHeightPx = rowHeightPx,
            onCellClick = { cell -> handleCellSelected(cell) }
        )
    }

    private fun populateCorner(cornerCell: XlsxCell?) {
        cornerView.removeAllViews()
        val textView = createCellTextView(
            text = cornerCell?.displayValue ?: "A1",
            isBold = true,
            isHeader = true,
            widthPx = leftColWidthPx,
            heightPx = rowHeightPx
        )
        if (cornerCell != null) {
            textView.setOnClickListener { handleCellSelected(cornerCell) }
        }
        cornerView.addView(textView)
    }

    private fun populateTopHeaderRow(row1: XlsxRow?, maxColIndex: Int) {
        topHeaderRowLayout.removeAllViews()

        for (c in 1..maxColIndex) {
            val cell = row1?.cells?.get(c)
            val headerLetter = indexToColumnLetter(c)
            val displayText = if (cell != null && cell.displayValue.isNotBlank()) {
                cell.displayValue
            } else {
                headerLetter
            }

            val headerTextView = createCellTextView(
                text = displayText,
                isBold = true,
                isHeader = true,
                widthPx = colWidthPx,
                heightPx = rowHeightPx
            )

            if (cell != null) {
                headerTextView.setOnClickListener { handleCellSelected(cell) }
            } else {
                headerTextView.setOnClickListener {
                    val virtualCell = XlsxCell(
                        colIndex = c,
                        rowIndex = 1,
                        rawValue = "",
                        displayValue = headerLetter,
                        type = CellType.BLANK
                    )
                    handleCellSelected(virtualCell)
                }
            }

            topHeaderRowLayout.addView(headerTextView)
        }
    }

    private fun handleCellSelected(cell: XlsxCell) {
        selectedCellCoordinate = Pair(cell.colIndex, cell.rowIndex)
        onCellSelectedListener?.invoke(cell)
    }

    private fun setupScrollSynchronization() {
        mainRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                val flinging = (newState == RecyclerView.SCROLL_STATE_SETTLING)
                (mainRecyclerView.adapter as? MainGridAdapter)?.let { adapter ->
                    if (adapter.isFlinging != flinging) {
                        adapter.isFlinging = flinging
                    }
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!isSyncingScroll) {
                    isSyncingScroll = true
                    leftRecyclerView.scrollBy(0, dy)
                    isSyncingScroll = false
                }
            }
        })

        leftRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                val flinging = (newState == RecyclerView.SCROLL_STATE_SETTLING)
                (mainRecyclerView.adapter as? MainGridAdapter)?.let { adapter ->
                    if (adapter.isFlinging != flinging) {
                        adapter.isFlinging = flinging
                    }
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!isSyncingScroll) {
                    isSyncingScroll = true
                    mainRecyclerView.scrollBy(0, dy)
                    isSyncingScroll = false
                }
            }
        })
    }

    private fun createCellTextView(
        text: String,
        isBold: Boolean,
        isHeader: Boolean,
        widthPx: Int,
        heightPx: Int,
        colorHex: String? = null
    ): TextView {
        return TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(widthPx, heightPx)
            this.text = text
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dpToPx(8f), 0, dpToPx(8f), 0)
            includeFontPadding = false
            maxLines = 1
            textSize = 12.5f

            if (isHeader) {
                setBackgroundColor(colorHeaderBg)
                setTextColor(colorTextPrimary)
                setTypeface(null, Typeface.BOLD)
            } else {
                setBackgroundColor(colorSurface)
                val textColor = if (colorHex != null) {
                    try { Color.parseColor(colorHex) } catch (_: Exception) { colorTextPrimary }
                } else colorTextPrimary
                setTextColor(textColor)
                if (isBold) setTypeface(null, Typeface.BOLD)
            }
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }

    /**
     * Adapter for Left Frozen Column (Column A + row indices).
     */
    private class FrozenLeftColumnAdapter(
        private val dataRows: List<XlsxRow>,
        private val rowHeightPx: Int,
        private val onCellClick: (XlsxCell) -> Unit
    ) : RecyclerView.Adapter<FrozenLeftColumnAdapter.ViewHolder>() {

        class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeightPx)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(18, 0, 18, 0)
                textSize = 12f
                maxLines = 1
                includeFontPadding = false
                setBackgroundColor(Color.parseColor("#181B22"))
                setTextColor(Color.parseColor("#E6E1E5"))
            }
            return ViewHolder(tv)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val row = dataRows[position]
            val cellA = row.cells[0]
            val rowNum = row.rowIndex
            val text = cellA?.displayValue ?: "$rowNum"
            holder.textView.text = text

            if (position % 2 == 1) {
                holder.textView.setBackgroundColor(Color.parseColor("#15181F"))
            } else {
                holder.textView.setBackgroundColor(Color.parseColor("#1A1D25"))
            }

            holder.textView.setOnClickListener {
                if (cellA != null) {
                    onCellClick(cellA)
                } else {
                    onCellClick(
                        XlsxCell(
                            colIndex = 0,
                            rowIndex = rowNum,
                            rawValue = "",
                            displayValue = "$rowNum",
                            type = CellType.BLANK
                        )
                    )
                }
            }
        }

        override fun getItemCount(): Int = dataRows.size
    }

    /**
     * Adapter for Main Content Grid (Columns B..N for Rows 2..N).
     */
    private class MainGridAdapter(
        private val dataRows: List<XlsxRow>,
        private val maxColIndex: Int,
        private val colWidthPx: Int,
        private val rowHeightPx: Int,
        private val onCellClick: (XlsxCell) -> Unit
    ) : RecyclerView.Adapter<MainGridAdapter.RowViewHolder>() {

        var isFlinging: Boolean = false
            set(value) {
                if (field != value) {
                    field = value
                    if (!value) {
                        notifyItemRangeChanged(0, itemCount)
                    }
                }
            }

        class RowViewHolder(
            val rowContainer: LinearLayout,
            val cellViews: MutableList<TextView>
        ) : RecyclerView.ViewHolder(rowContainer)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
            val rowLayout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    rowHeightPx
                )
            }

            val cellViews = mutableListOf<TextView>()
            for (c in 1..maxColIndex) {
                val cellTv = TextView(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(colWidthPx, rowHeightPx)
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    setPadding(16, 0, 16, 0)
                    textSize = 12f
                    maxLines = 1
                    includeFontPadding = false
                }
                rowLayout.addView(cellTv)
                cellViews.add(cellTv)
            }

            return RowViewHolder(rowLayout, cellViews)
        }

        override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
            val row = dataRows[position]
            val isEven = position % 2 == 0
            val defaultBg = if (isEven) Color.parseColor("#12141A") else Color.parseColor("#181B22")
            val defaultText = Color.parseColor("#E6E1E5")

            for (c in 1..maxColIndex) {
                val viewIdx = c - 1
                if (viewIdx >= holder.cellViews.size) break

                val tv = holder.cellViews[viewIdx]
                val cell = row.cells[c]

                tv.setBackgroundColor(defaultBg)
                if (cell != null) {
                    tv.text = cell.displayValue
                    if (isFlinging) {
                        // Fling fast path: skip Color.parseColor and setTypeface reflection
                        tv.setTextColor(defaultText)
                        tv.setTypeface(null, Typeface.NORMAL)
                    } else {
                        val textColor = if (cell.colorHex != null) {
                            try { Color.parseColor(cell.colorHex) } catch (_: Exception) { defaultText }
                        } else defaultText
                        tv.setTextColor(textColor)
                        tv.setTypeface(null, if (cell.isBold) Typeface.BOLD else Typeface.NORMAL)
                    }

                    tv.setOnClickListener { onCellClick(cell) }
                } else {
                    tv.text = ""
                    tv.setOnClickListener {
                        val virtualCell = XlsxCell(
                            colIndex = c,
                            rowIndex = row.rowIndex,
                            rawValue = "",
                            displayValue = "",
                            type = CellType.BLANK
                        )
                        onCellClick(virtualCell)
                    }
                }
            }
        }

        override fun getItemCount(): Int = dataRows.size
    }
}
