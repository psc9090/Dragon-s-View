// app/src/main/java/com/dragonview/app/viewer/markdown/MarkdownRenderer.kt
package com.dragonview.app.viewer.markdown

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.otaliastudios.zoom.ZoomLayout
import com.dragonview.app.ui.ZoomHelper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dragonview.app.performance.JankMonitor
import com.dragonview.app.router.FormatDetectionResult
import com.dragonview.app.ui.theme.DragonDarkBackground
import com.dragonview.app.ui.theme.DragonDarkBorder
import com.dragonview.app.ui.theme.DragonDarkSurface
import com.dragonview.app.ui.theme.DragonDarkTextMuted
import com.dragonview.app.ui.theme.DragonDarkTextPrimary
import com.dragonview.app.ui.theme.DragonDarkTextSecondary
import com.dragonview.app.ui.theme.DragonFlame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * Obsidian "Reading View" style Markdown Renderer for DragonView.
 *
 * Renders structured Markdown blocks via high-performance RecyclerView with:
 * - Proportional headings (H1-H6) with distinct weight and subtle accent divider
 * - Monospace code blocks with dark reddish container
 * - Checkbox lists with inline checkbox glyphs (☐/☑)
 * - Blockquotes with crimson vertical left border
 * - Tables with formatted headers and alternating row backgrounds
 * - Images with memory-disciplined inSampleSize downsampling (<= 1080px)
 * - Collapsible YAML frontmatter card
 */
@Composable
fun MarkdownRenderer(
    uri: Uri,
    detectionResult: FormatDetectionResult,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var markdownDoc by remember { mutableStateOf<MarkdownDocument?>(null) }
    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var zoomLayoutRef by remember { mutableStateOf<ZoomLayout?>(null) }

    DisposableEffect(Unit) {
        JankMonitor.start()
        onDispose {
            JankMonitor.stop()
        }
    }

    LaunchedEffect(uri) {
        isLoading = true
        errorMessage = null
        val result = MarkdownParser.parse(context, uri)
        result.onSuccess { doc ->
            markdownDoc = doc
            isLoading = false
        }.onFailure { error ->
            errorMessage = error.localizedMessage ?: "Failed to parse Markdown document."
            isLoading = false
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = DragonDarkBackground
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Bar
            Surface(
                color = DragonDarkSurface,
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("md_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = DragonDarkTextPrimary
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = markdownDoc?.title ?: detectionResult.fileName,
                            style = MaterialTheme.typography.titleMedium,
                            color = DragonDarkTextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Reading View",
                                style = MaterialTheme.typography.labelSmall,
                                color = ComposeColor(0xFFAB47BC),
                                fontWeight = FontWeight.Bold
                            )
                            if (markdownDoc != null) {
                                Text(
                                    text = "${markdownDoc!!.blocks.size} blocks",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DragonDarkTextMuted
                                )
                            }
                        }
                    }

                    // Zoom Controls
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${(zoomScale * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = DragonDarkTextSecondary,
                            modifier = Modifier
                                .clickable {
                                    zoomLayoutRef?.engine?.zoomTo(1.0f, true)
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                        IconButton(
                            onClick = {
                                zoomLayoutRef?.engine?.let {
                                    val nextZoom = (it.realZoom / 1.2f).coerceAtLeast(1.0f)
                                    it.zoomTo(nextZoom, true)
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text("-", color = DragonDarkTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(
                            onClick = {
                                zoomLayoutRef?.engine?.let {
                                    val nextZoom = (it.realZoom * 1.2f).coerceAtMost(5.0f)
                                    it.zoomTo(nextZoom, true)
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text("+", color = DragonDarkTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Body Content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ComposeColor(0xFF130708))
            ) {
                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = DragonFlame)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Rendering Markdown...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = DragonDarkTextSecondary
                                )
                            }
                        }
                    }

                    errorMessage != null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Error",
                                    tint = DragonFlame,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = errorMessage!!,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = DragonDarkTextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    markdownDoc != null -> {
                        AndroidMarkdownRecyclerView(
                            document = markdownDoc!!,
                            baseUri = uri,
                            onZoomLayoutCreated = { zoomLayoutRef = it },
                            onZoomChanged = { zoomScale = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

/**
 * Native RecyclerView wrapped in ZoomLayout for memory-efficient document recycling and smooth 2D zoom/pan.
 */
@Composable
private fun AndroidMarkdownRecyclerView(
    document: MarkdownDocument,
    baseUri: Uri,
    onZoomLayoutCreated: (ZoomLayout) -> Unit,
    onZoomChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val recyclerView = RecyclerView(ctx).apply {
                tag = "MarkdownRecyclerView_Content"
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                layoutManager = LinearLayoutManager(ctx)
                setItemViewCacheSize(4)
                setBackgroundColor(Color.parseColor("#130708"))
                val hPad = dpToPx(ctx, 20)
                val vPad = dpToPx(ctx, 16)
                setPadding(hPad, vPad, hPad, dpToPx(ctx, 48))
                clipToPadding = false

                val mdAdapter = MarkdownAdapter(ctx, document.blocks, baseUri)
                adapter = mdAdapter
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        super.onScrollStateChanged(recyclerView, newState)
                        val flinging = (newState == RecyclerView.SCROLL_STATE_SETTLING)
                        if (mdAdapter.isFlinging != flinging) {
                            mdAdapter.isFlinging = flinging
                        }
                    }
                })
            }

            ZoomHelper.createZoomLayout(
                context = ctx,
                contentView = recyclerView,
                tag = "ZoomLayout_Markdown",
                onZoomChanged = onZoomChanged
            ).also { zoomLayout ->
                onZoomLayoutCreated(zoomLayout)
            }
        },
        update = { zoomLayout ->
            val recyclerView = zoomLayout.findViewWithTag<RecyclerView>("MarkdownRecyclerView_Content")
            if (recyclerView != null) {
                (recyclerView.adapter as? MarkdownAdapter)?.updateBlocks(document.blocks)
            }
        }
    )
}

/**
 * RecyclerView Adapter supporting all Markdown block types.
 */
class MarkdownAdapter(
    private val context: Context,
    private var blocks: List<MarkdownBlock>,
    private val baseUri: Uri
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var isFlinging: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (!value) {
                    notifyItemRangeChanged(0, itemCount)
                }
            }
        }

    companion object {
        private const val TYPE_FRONTMATTER = 1
        private const val TYPE_HEADING = 2
        private const val TYPE_PARAGRAPH = 3
        private const val TYPE_BULLET_LIST = 4
        private const val TYPE_NUMBERED_LIST = 5
        private const val TYPE_CHECKBOX = 6
        private const val TYPE_CODE_BLOCK = 7
        private const val TYPE_BLOCKQUOTE = 8
        private const val TYPE_TABLE = 9
        private const val TYPE_IMAGE = 10
        private const val TYPE_HORIZONTAL_RULE = 11
    }

    fun updateBlocks(newBlocks: List<MarkdownBlock>) {
        this.blocks = newBlocks
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (blocks[position]) {
            is MarkdownBlock.Frontmatter -> TYPE_FRONTMATTER
            is MarkdownBlock.Heading -> TYPE_HEADING
            is MarkdownBlock.Paragraph -> TYPE_PARAGRAPH
            is MarkdownBlock.BulletList -> TYPE_BULLET_LIST
            is MarkdownBlock.NumberedList -> TYPE_NUMBERED_LIST
            is MarkdownBlock.Checkbox -> TYPE_CHECKBOX
            is MarkdownBlock.CodeBlock -> TYPE_CODE_BLOCK
            is MarkdownBlock.Blockquote -> TYPE_BLOCKQUOTE
            is MarkdownBlock.Table -> TYPE_TABLE
            is MarkdownBlock.Image -> TYPE_IMAGE
            is MarkdownBlock.HorizontalRule -> TYPE_HORIZONTAL_RULE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        return when (viewType) {
            TYPE_FRONTMATTER -> FrontmatterViewHolder(createFrontmatterView(ctx))
            TYPE_HEADING -> HeadingViewHolder(createHeadingView(ctx))
            TYPE_PARAGRAPH -> ParagraphViewHolder(createParagraphView(ctx))
            TYPE_BULLET_LIST -> BulletListViewHolder(createVerticalLayout(ctx))
            TYPE_NUMBERED_LIST -> NumberedListViewHolder(createVerticalLayout(ctx))
            TYPE_CHECKBOX -> CheckboxViewHolder(createCheckboxView(ctx))
            TYPE_CODE_BLOCK -> CodeBlockViewHolder(createCodeBlockView(ctx))
            TYPE_BLOCKQUOTE -> BlockquoteViewHolder(createBlockquoteView(ctx))
            TYPE_TABLE -> TableViewHolder(createTableView(ctx))
            TYPE_IMAGE -> ImageViewHolder(createImageView(ctx))
            TYPE_HORIZONTAL_RULE -> HorizontalRuleViewHolder(createHorizontalRuleView(ctx))
            else -> throw IllegalArgumentException("Unknown Markdown block viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val block = blocks[position]) {
            is MarkdownBlock.Frontmatter -> (holder as FrontmatterViewHolder).bind(block)
            is MarkdownBlock.Heading -> (holder as HeadingViewHolder).bind(block, isFlinging)
            is MarkdownBlock.Paragraph -> (holder as ParagraphViewHolder).bind(block, isFlinging)
            is MarkdownBlock.BulletList -> (holder as BulletListViewHolder).bind(block, isFlinging)
            is MarkdownBlock.NumberedList -> (holder as NumberedListViewHolder).bind(block, isFlinging)
            is MarkdownBlock.Checkbox -> (holder as CheckboxViewHolder).bind(block, isFlinging)
            is MarkdownBlock.CodeBlock -> (holder as CodeBlockViewHolder).bind(block)
            is MarkdownBlock.Blockquote -> (holder as BlockquoteViewHolder).bind(block, isFlinging)
            is MarkdownBlock.Table -> (holder as TableViewHolder).bind(block)
            is MarkdownBlock.Image -> (holder as ImageViewHolder).bind(block, baseUri, isFlinging)
            is MarkdownBlock.HorizontalRule -> Unit
        }
    }

    override fun getItemCount(): Int = blocks.size

    // Factory helper methods for programmatic views
    private fun createVerticalLayout(ctx: Context): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(ctx, 4), 0, dpToPx(ctx, 6))
            }
        }
    }

    private fun createParagraphView(ctx: Context): TextView {
        return TextView(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(ctx, 4), 0, dpToPx(ctx, 8))
            }
            setTextColor(Color.parseColor("#FDE8EA"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextIsSelectable(true)
            setLineSpacing(dpToPx(ctx, 3).toFloat(), 1.25f)
        }
    }

    private fun createHeadingView(ctx: Context): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val titleView = TextView(ctx).apply {
                tag = "heading_text"
                setTextIsSelectable(true)
            }
            addView(titleView)
            val dividerView = View(ctx).apply {
                tag = "heading_divider"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(ctx, 1)
                ).apply {
                    setMargins(0, dpToPx(ctx, 4), 0, 0)
                }
                setBackgroundColor(Color.parseColor("#4A1F26"))
                visibility = View.GONE
            }
            addView(dividerView)
        }
    }

    private fun createCheckboxView(ctx: Context): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(ctx, 2), 0, dpToPx(ctx, 4))
            }
            gravity = Gravity.CENTER_VERTICAL

            val glyphView = TextView(ctx).apply {
                tag = "check_glyph"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, 0, dpToPx(ctx, 8), 0)
            }
            addView(glyphView)

            val textView = TextView(ctx).apply {
                tag = "check_text"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Color.parseColor("#FDE8EA"))
                setTextIsSelectable(true)
            }
            addView(textView)
        }
    }

    private fun createCodeBlockView(ctx: Context): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(ctx, 10), 0, dpToPx(ctx, 12))
            }
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#200C10"))
                setStroke(dpToPx(ctx, 1), Color.parseColor("#3B151C"))
                cornerRadius = dpToPx(ctx, 6).toFloat()
            }
            background = bg
            setPadding(dpToPx(ctx, 12), dpToPx(ctx, 8), dpToPx(ctx, 12), dpToPx(ctx, 10))

            val langView = TextView(ctx).apply {
                tag = "code_lang"
                setTextColor(Color.parseColor("#FF8A80"))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                visibility = View.GONE
                setPadding(0, 0, 0, dpToPx(ctx, 4))
            }
            addView(langView)

            val hScroll = HorizontalScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                val codeView = TextView(ctx).apply {
                    tag = "code_text"
                    setTextColor(Color.parseColor("#EDE7F6"))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setLineSpacing(dpToPx(ctx, 2).toFloat(), 1.2f)
                }
                addView(codeView)
            }
            addView(hScroll)
        }
    }

    private fun createBlockquoteView(ctx: Context): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(ctx, 6), 0, dpToPx(ctx, 8))
            }
            val barView = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(ctx, 3),
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.parseColor("#D32F2F"))
            }
            addView(barView)

            val contentContainer = LinearLayout(ctx).apply {
                tag = "quote_content"
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1.0f
                ).apply {
                    setMargins(dpToPx(ctx, 12), 0, 0, 0)
                }
            }
            addView(contentContainer)
        }
    }

    private fun createTableView(ctx: Context): HorizontalScrollView {
        return HorizontalScrollView(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(ctx, 8), 0, dpToPx(ctx, 12))
            }
            val tableContainer = LinearLayout(ctx).apply {
                tag = "table_container"
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            addView(tableContainer)
        }
    }

    private fun createImageView(ctx: Context): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(ctx, 8), 0, dpToPx(ctx, 12))
            }
            val img = ImageView(ctx).apply {
                tag = "md_image"
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                maxHeight = dpToPx(ctx, 380)
            }
            addView(img)

            val caption = TextView(ctx).apply {
                tag = "md_image_caption"
                setTextColor(Color.parseColor("#946A72"))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                setPadding(0, dpToPx(ctx, 4), 0, 0)
                visibility = View.GONE
            }
            addView(caption)
        }
    }

    private fun createHorizontalRuleView(ctx: Context): View {
        return View(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(ctx, 1)
            ).apply {
                setMargins(dpToPx(ctx, 16), dpToPx(ctx, 14), dpToPx(ctx, 16), dpToPx(ctx, 14))
            }
            setBackgroundColor(Color.parseColor("#4A1F26"))
        }
    }

    private fun createFrontmatterView(ctx: Context): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(ctx, 4), 0, dpToPx(ctx, 12))
            }
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1B0B0E"))
                setStroke(dpToPx(ctx, 1), Color.parseColor("#38141B"))
                cornerRadius = dpToPx(ctx, 6).toFloat()
            }
            background = bg
            setPadding(dpToPx(ctx, 12), dpToPx(ctx, 8), dpToPx(ctx, 12), dpToPx(ctx, 8))

            val headerRow = LinearLayout(ctx).apply {
                tag = "frontmatter_header"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )

                val label = TextView(ctx).apply {
                    text = "Properties"
                    setTextColor(Color.parseColor("#FF8A80"))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
                }
                addView(label)

                val chevron = TextView(ctx).apply {
                    tag = "frontmatter_chevron"
                    text = "▲"
                    setTextColor(Color.parseColor("#D4A5AB"))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                }
                addView(chevron)
            }
            addView(headerRow)

            val contentContainer = LinearLayout(ctx).apply {
                tag = "frontmatter_content"
                orientation = LinearLayout.VERTICAL
                setPadding(0, dpToPx(ctx, 6), 0, 0)
            }
            addView(contentContainer)
        }
    }
}

// -------------------------------------------------------------------------
// ViewHolders
// -------------------------------------------------------------------------

class FrontmatterViewHolder(private val container: LinearLayout) : RecyclerView.ViewHolder(container) {
    private var isExpanded = true

    fun bind(block: MarkdownBlock.Frontmatter) {
        val header = container.findViewWithTag<LinearLayout>("frontmatter_header")
        val chevron = container.findViewWithTag<TextView>("frontmatter_chevron")
        val content = container.findViewWithTag<LinearLayout>("frontmatter_content")

        content.removeAllViews()
        val ctx = container.context

        for ((key, value) in block.entries) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, dpToPx(ctx, 2), 0, dpToPx(ctx, 2))
                }
                val keyView = TextView(ctx).apply {
                    text = key
                    setTextColor(Color.parseColor("#D4A5AB"))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(dpToPx(ctx, 90), ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                addView(keyView)

                val valView = TextView(ctx).apply {
                    text = value
                    setTextColor(Color.parseColor("#FDE8EA"))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
                }
                addView(valView)
            }
            content.addView(row)
        }

        header.setOnClickListener {
            isExpanded = !isExpanded
            content.visibility = if (isExpanded) View.VISIBLE else View.GONE
            chevron.text = if (isExpanded) "▲" else "▼"
        }
    }
}

class HeadingViewHolder(private val container: LinearLayout) : RecyclerView.ViewHolder(container) {
    fun bind(heading: MarkdownBlock.Heading, isFlinging: Boolean) {
        val textView = container.findViewWithTag<TextView>("heading_text")
        val divider = container.findViewWithTag<View>("heading_divider")
        val ctx = container.context

        val (sizeSp, topMarginDp, botMarginDp, colorHex, isBold, showDivider) = when (heading.level) {
            1 -> HeadingStyle(24f, 20, 8, "#FF8A80", isBold = true, showDivider = true)
            2 -> HeadingStyle(20f, 16, 6, "#FDE8EA", isBold = true, showDivider = false)
            3 -> HeadingStyle(18f, 14, 4, "#FDE8EA", isBold = true, showDivider = false)
            4 -> HeadingStyle(16f, 10, 4, "#D4A5AB", isBold = true, showDivider = false)
            5 -> HeadingStyle(14f, 8, 2, "#D4A5AB", isBold = true, showDivider = false)
            else -> HeadingStyle(13f, 6, 2, "#946A72", isBold = true, showDivider = false)
        }

        val lp = container.layoutParams as ViewGroup.MarginLayoutParams
        lp.setMargins(0, dpToPx(ctx, topMarginDp), 0, dpToPx(ctx, botMarginDp))
        container.layoutParams = lp

        textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp)
        textView.setTextColor(Color.parseColor(colorHex))
        textView.typeface = if (isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        if (isFlinging) {
            textView.text = heading.text
        } else {
            textView.text = buildSpannable(heading.runs, defaultColorHex = colorHex)
        }

        divider.visibility = if (showDivider) View.VISIBLE else View.GONE
    }

    private data class HeadingStyle(
        val sizeSp: Float,
        val topMarginDp: Int,
        val botMarginDp: Int,
        val colorHex: String,
        val isBold: Boolean,
        val showDivider: Boolean
    )
}

class ParagraphViewHolder(private val textView: TextView) : RecyclerView.ViewHolder(textView) {
    fun bind(paragraph: MarkdownBlock.Paragraph, isFlinging: Boolean) {
        if (isFlinging) {
            textView.text = paragraph.runs.joinToString("") { it.text }
        } else {
            textView.text = buildSpannable(paragraph.runs)
        }
    }
}

class BulletListViewHolder(private val container: LinearLayout) : RecyclerView.ViewHolder(container) {
    fun bind(list: MarkdownBlock.BulletList, isFlinging: Boolean) {
        container.removeAllViews()
        val ctx = container.context

        for (item in list.items) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, dpToPx(ctx, 2), 0, dpToPx(ctx, 2))
                }
                setPadding(dpToPx(ctx, item.indentLevel * 18), 0, 0, 0)

                val bulletView = TextView(ctx).apply {
                    text = if (item.indentLevel == 0) "•" else "◦"
                    setTextColor(Color.parseColor("#EF5350"))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                    setPadding(0, 0, dpToPx(ctx, 8), 0)
                }
                addView(bulletView)

                val textView = TextView(ctx).apply {
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(Color.parseColor("#FDE8EA"))
                    setTextIsSelectable(true)
                    text = if (isFlinging) item.text else buildSpannable(item.runs)
                }
                addView(textView)
            }
            container.addView(row)
        }
    }
}

class NumberedListViewHolder(private val container: LinearLayout) : RecyclerView.ViewHolder(container) {
    fun bind(list: MarkdownBlock.NumberedList, isFlinging: Boolean) {
        container.removeAllViews()
        val ctx = container.context

        for ((index, item) in list.items.withIndex()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, dpToPx(ctx, 2), 0, dpToPx(ctx, 2))
                }
                setPadding(dpToPx(ctx, item.indentLevel * 18), 0, 0, 0)

                val numView = TextView(ctx).apply {
                    text = "${item.number ?: (index + 1)}."
                    setTextColor(Color.parseColor("#EF5350"))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, dpToPx(ctx, 8), 0)
                }
                addView(numView)

                val textView = TextView(ctx).apply {
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(Color.parseColor("#FDE8EA"))
                    setTextIsSelectable(true)
                    text = if (isFlinging) item.text else buildSpannable(item.runs)
                }
                addView(textView)
            }
            container.addView(row)
        }
    }
}

class CheckboxViewHolder(private val container: LinearLayout) : RecyclerView.ViewHolder(container) {
    fun bind(checkbox: MarkdownBlock.Checkbox, isFlinging: Boolean) {
        val glyph = container.findViewWithTag<TextView>("check_glyph")
        val textView = container.findViewWithTag<TextView>("check_text")
        val ctx = container.context

        container.setPadding(dpToPx(ctx, checkbox.indentLevel * 18), 0, 0, 0)

        if (checkbox.checked) {
            glyph.text = "☑"
            glyph.setTextColor(Color.parseColor("#FF5722"))
            textView.setTextColor(Color.parseColor("#D4A5AB"))
        } else {
            glyph.text = "☐"
            glyph.setTextColor(Color.parseColor("#946A72"))
            textView.setTextColor(Color.parseColor("#FDE8EA"))
        }

        if (isFlinging) {
            textView.text = checkbox.text
        } else {
            textView.text = buildSpannable(checkbox.runs)
        }
    }
}

class CodeBlockViewHolder(private val container: LinearLayout) : RecyclerView.ViewHolder(container) {
    fun bind(codeBlock: MarkdownBlock.CodeBlock) {
        val langView = container.findViewWithTag<TextView>("code_lang")
        val codeView = container.findViewWithTag<TextView>("code_text")

        if (codeBlock.language.isNotBlank()) {
            langView.text = codeBlock.language.uppercase()
            langView.visibility = View.VISIBLE
        } else {
            langView.visibility = View.GONE
        }
        codeView.text = codeBlock.code
    }
}

class BlockquoteViewHolder(private val container: LinearLayout) : RecyclerView.ViewHolder(container) {
    fun bind(quote: MarkdownBlock.Blockquote, isFlinging: Boolean) {
        val content = container.findViewWithTag<LinearLayout>("quote_content")
        content.removeAllViews()
        val ctx = container.context

        for (inner in quote.content) {
            when (inner) {
                is MarkdownBlock.Paragraph -> {
                    val tv = TextView(ctx).apply {
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14.5f)
                        setTextColor(Color.parseColor("#D4A5AB"))
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                        setTextIsSelectable(true)
                        text = if (isFlinging) inner.runs.joinToString("") { it.text } else buildSpannable(inner.runs, "#D4A5AB")
                        setPadding(0, dpToPx(ctx, 2), 0, dpToPx(ctx, 4))
                    }
                    content.addView(tv)
                }
                is MarkdownBlock.Heading -> {
                    val tv = TextView(ctx).apply {
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                        setTextColor(Color.parseColor("#FF8A80"))
                        typeface = Typeface.DEFAULT_BOLD
                        setTextIsSelectable(true)
                        text = inner.text
                        setPadding(0, dpToPx(ctx, 4), 0, dpToPx(ctx, 2))
                    }
                    content.addView(tv)
                }
                else -> {
                    // Fallback for nested lists/other blocks
                }
            }
        }
    }
}

class TableViewHolder(private val scrollContainer: HorizontalScrollView) : RecyclerView.ViewHolder(scrollContainer) {
    fun bind(table: MarkdownBlock.Table) {
        val container = scrollContainer.findViewWithTag<LinearLayout>("table_container")
        container.removeAllViews()
        val ctx = scrollContainer.context

        val borderColor = Color.parseColor("#4A1F26")

        // 1. Header Row
        if (table.headers.isNotEmpty()) {
            val headerRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#2E1218"))
            }
            for (header in table.headers) {
                val th = TextView(ctx).apply {
                    text = header
                    setTextColor(Color.parseColor("#FDE8EA"))
                    typeface = Typeface.DEFAULT_BOLD
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    setPadding(dpToPx(ctx, 12), dpToPx(ctx, 8), dpToPx(ctx, 12), dpToPx(ctx, 8))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    val cellBg = GradientDrawable().apply {
                        setColor(Color.parseColor("#2E1218"))
                        setStroke(dpToPx(ctx, 1), borderColor)
                    }
                    background = cellBg
                }
                headerRow.addView(th)
            }
            container.addView(headerRow)
        }

        // 2. Data Rows
        for ((rowIndex, rowData) in table.rows.withIndex()) {
            val dataRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                val rowBgColor = if (rowIndex % 2 == 0) "#1B0B0E" else "#240E13"
                setBackgroundColor(Color.parseColor(rowBgColor))
            }
            for ((colIndex, cell) in rowData.withIndex()) {
                val td = TextView(ctx).apply {
                    text = cell
                    setTextColor(Color.parseColor("#FDE8EA"))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextIsSelectable(true)
                    setPadding(dpToPx(ctx, 12), dpToPx(ctx, 8), dpToPx(ctx, 12), dpToPx(ctx, 8))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    val rowBgColor = if (rowIndex % 2 == 0) "#1B0B0E" else "#240E13"
                    val cellBg = GradientDrawable().apply {
                        setColor(Color.parseColor(rowBgColor))
                        setStroke(dpToPx(ctx, 1), borderColor)
                    }
                    background = cellBg
                }
                dataRow.addView(td)
            }
            container.addView(dataRow)
        }
    }
}

class ImageViewHolder(private val container: LinearLayout) : RecyclerView.ViewHolder(container) {
    fun bind(imageBlock: MarkdownBlock.Image, baseUri: Uri, isFlinging: Boolean) {
        val imageView = container.findViewWithTag<ImageView>("md_image")
        val captionView = container.findViewWithTag<TextView>("md_image_caption")
        val ctx = container.context

        if (imageBlock.altText.isNotBlank()) {
            captionView.text = imageBlock.altText
            captionView.visibility = View.VISIBLE
        } else {
            captionView.visibility = View.GONE
        }

        if (isFlinging) {
            imageView.setImageDrawable(null)
            return
        }

        // Load image with downsampling discipline
        val bitmap = decodeDownsampledImage(ctx, imageBlock.pathOrUrl, baseUri, maxDimensionPx = 1080)
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap)
            imageView.visibility = View.VISIBLE
        } else {
            // Placeholder for unresolved local image
            val placeholder = GradientDrawable().apply {
                setColor(Color.parseColor("#200C10"))
                setStroke(dpToPx(ctx, 1), Color.parseColor("#4A1F26"))
                cornerRadius = dpToPx(ctx, 6).toFloat()
            }
            imageView.setImageDrawable(placeholder)
            imageView.minimumHeight = dpToPx(ctx, 100)
            captionView.text = "🖼 ${imageBlock.altText.ifBlank { imageBlock.pathOrUrl }}"
            captionView.visibility = View.VISIBLE
        }
    }

    private fun decodeDownsampledImage(
        context: Context,
        pathOrUrl: String,
        baseUri: Uri,
        maxDimensionPx: Int
    ): Bitmap? {
        return try {
            val inputStream: InputStream? = when {
                pathOrUrl.startsWith("content://") -> {
                    context.contentResolver.openInputStream(Uri.parse(pathOrUrl))
                }
                pathOrUrl.startsWith("file://") -> {
                    context.contentResolver.openInputStream(Uri.parse(pathOrUrl))
                }
                else -> {
                    // Check local relative file next to the baseUri file if applicable
                    val baseFile = File(baseUri.path ?: "")
                    val parent = baseFile.parentFile
                    val targetFile = if (parent != null) File(parent, pathOrUrl) else File(pathOrUrl)
                    if (targetFile.exists() && targetFile.canRead()) {
                        targetFile.inputStream()
                    } else {
                        null
                    }
                }
            }

            inputStream?.use { stream ->
                val bytes = stream.readBytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

                val origW = bounds.outWidth
                val origH = bounds.outHeight
                if (origW <= 0 || origH <= 0) return null

                var sampleSize = 1
                while ((origW / sampleSize) > maxDimensionPx || (origH / sampleSize) > maxDimensionPx) {
                    sampleSize *= 2
                }

                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            }
        } catch (_: Exception) {
            null
        }
    }
}

class HorizontalRuleViewHolder(view: View) : RecyclerView.ViewHolder(view)

// -------------------------------------------------------------------------
// Spannable Styling Engine
// -------------------------------------------------------------------------

private fun buildSpannable(
    runs: List<MarkdownInlineRun>,
    defaultColorHex: String = "#FDE8EA"
): SpannableStringBuilder {
    val ssb = SpannableStringBuilder()
    for (run in runs) {
        val start = ssb.length
        ssb.append(run.text)
        val end = ssb.length

        // Bold & Italic
        if (run.isBold && run.isItalic) {
            ssb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (run.isBold) {
            ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (run.isItalic) {
            ssb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Inline Code
        if (run.isCode) {
            ssb.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(BackgroundColorSpan(Color.parseColor("#261014")), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(ForegroundColorSpan(Color.parseColor("#FF8A80")), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (run.linkUrl != null) {
            // Link
            ssb.setSpan(ForegroundColorSpan(Color.parseColor("#EF5350")), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            ssb.setSpan(ForegroundColorSpan(Color.parseColor(defaultColorHex)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Strikethrough
        if (run.isStrikethrough) {
            ssb.setSpan(StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    return ssb
}

private fun dpToPx(context: Context, dp: Int): Int {
    return (dp * context.resources.displayMetrics.density).toInt()
}
