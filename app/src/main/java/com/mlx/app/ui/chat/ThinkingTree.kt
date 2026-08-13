package com.mlx.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 思维链段状态：执行中末段 THINKING、其余 COMPLETE；历史全部 COMPLETE */
enum class SegStatus { THINKING, COMPLETE, ABORTED }

/** 思维链树节点：header = 匹配到的结构行（"1. 理解需求"），text = 该段推理正文 */
data class ThinkingSegment(
    val index: Int,
    val header: String,
    val text: String,
    val status: SegStatus,
)

const val MAX_THINKING_SEGMENTS = 50
const val PRELUDE_HEADER = "思考开始"
/** 无结构兜底单段标题（模型推理未按序号/标题组织时的整体段落） */
const val FALLBACK_HEADER = "思考过程"

/**
 * 推理文本按结构标记分段（纯函数）：
 * - 段起始行首锚定：阿拉伯序号 "1. "、中文序号 "一、"、序数 "第一步"、Markdown 标题 "## "
 * - 分隔符后必须有内容（\S）→ 流式只来了 "1." 不立即开段；行首数字后无分隔符（"12 个"）不误判
 * - 首个匹配前的非空内容 = 开场段（header "思考开始"）；无任何结构 → 兜底单段（header "思考过程"）
 * - live=true：末段 THINKING、其余 COMPLETE；live=false：全部 COMPLETE
 * - 段数上限 MAX_THINKING_SEGMENTS，超出部分合并进最后一段
 */
fun parseReasoningSegments(reasoning: String, live: Boolean = false): List<ThinkingSegment> {
    if (reasoning.isBlank()) return emptyList()
    // 段起始行（逐行匹配，^ 锚定行首防正文内数字列表误判）。
    // 分隔符后：空白+内容（"1. 理解"）或直接内容（"1、方案"/"一、现状"）均可；
    // 排除数字（"1.5 万"小数不误判）；分隔符后无任何内容（流式只来了 "1."）不构成段头
    val headerLine = Regex(
        "^\\s*(?:\\d{1,2}[.、．)）\\]](?:\\s+\\S|[^\\s\\d])" +
            "|第[一二三四五六七八九十百]+[步部分章节]" +
            "|[一二三四五六七八九十]+[、.](?:\\s+\\S|[^\\s\\d])" +
            "|#{1,6}\\s+\\S)"
    )

    val lines = reasoning.split("\n")
    val prelude = StringBuilder()
    val segs = mutableListOf<Pair<String, StringBuilder>>() // (header, text)
    var curHeader: String? = null
    var curText = StringBuilder()
    var hasStructure = false

    fun closeCurrent() {
        if (curHeader != null) {
            segs += curHeader!! to curText
            curHeader = null
            curText = StringBuilder()
        }
    }

    for (raw in lines) {
        // containsMatchIn + ^ 锚定 = 仅行首命中（行内中间位置的数字列表不误判）
        if (headerLine.containsMatchIn(raw)) {
            closeCurrent()
            curHeader = raw.trim()
            hasStructure = true
        } else if (curHeader == null) {
            if (raw.isNotBlank()) {
                prelude.appendLine(raw.trim())
            }
        } else {
            curText.appendLine(raw)
        }
    }
    closeCurrent()

    val all = mutableListOf<ThinkingSegment>()
    var idx = 0
    if (hasStructure && prelude.isNotBlank()) {
        all += ThinkingSegment(idx++, PRELUDE_HEADER, prelude.toString().trim(), SegStatus.COMPLETE)
    }
    for ((h, t) in segs) {
        all += ThinkingSegment(idx++, h, t.toString().trim(), SegStatus.COMPLETE)
    }
    if (all.isEmpty()) {
        // 无任何结构 → 兜底单段（header = 思考过程，text = 全文）
        all += ThinkingSegment(0, FALLBACK_HEADER, reasoning.trim(), SegStatus.COMPLETE)
    }
    // 段数上限：超出部分合并进第 50 段
    val capped = if (all.size > MAX_THINKING_SEGMENTS) {
        val keep = all.take(MAX_THINKING_SEGMENTS).toMutableList()
        val overflow = all.drop(MAX_THINKING_SEGMENTS)
        val merged = overflow.joinToString("\n") { s ->
            (if (s.header.isBlank()) "" else s.header) + (if (s.text.isBlank()) "" else "\n" + s.text)
        }
        val last = keep.last()
        keep[keep.lastIndex] = last.copy(text = (last.text + if (last.text.isBlank()) "" else "\n") + merged)
        keep
    } else {
        all
    }
    return if (live && capped.isNotEmpty()) {
        capped.mapIndexed { i, s ->
            s.copy(status = if (i == capped.lastIndex) SegStatus.THINKING else SegStatus.COMPLETE)
        }
    } else {
        capped
    }
}

/**
 * 思维链树卡片：
 * - 根行 "💭 思维链（N 段）" 默认展开（只展开第一层 = 段列表可见、段详情折叠）
 * - 执行中根行强制展开（LaunchedEffect 对齐 ToolCard 模式）；live 末段详情强制展开实时流式
 * - 段行：状态点（⏳ 当前段 / ✓ 完成）+ 结构行标题，点击展开段内推理全文
 */
@Composable
fun ThinkingTreeCard(
    segments: List<ThinkingSegment>,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    if (segments.isEmpty()) return
    var rootExpanded by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(live) {
        if (live) rootExpanded = true // 执行中强制展开（完成后保持用户状态/默认第一层）
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { rootExpanded = !rootExpanded }
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "💭 思维链（${segments.size} 段）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (rootExpanded) "▾" else "▸",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (rootExpanded) {
            segments.forEach { seg ->
                ThinkingSegmentRow(seg, live = live && seg.index == segments.lastIndex)
            }
        }
    }
}

@Composable
private fun ThinkingSegmentRow(seg: ThinkingSegment, live: Boolean) {
    // 段详情展开态（执行中末段强制展开，不参与状态；其余按段 index 记忆，仅追加不收缩 → 位置稳定）
    var expanded by rememberSaveable(seg.index) { mutableStateOf(false) }
    val showDetail = expanded || (live && seg.status == SegStatus.THINKING)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (seg.status == SegStatus.THINKING) {
                CircularProgressIndicator(
                    Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text("✓", style = MaterialTheme.typography.labelSmall, color = Color(0xFF34A853))
            }
            Text(
                seg.header.ifBlank { FALLBACK_HEADER },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            Text(
                if (showDetail) "▾" else "▸",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showDetail && seg.text.isNotBlank()) {
            // 二十二批：live 末段段内跟随底部 —— 最新推理文字自动可见（此前停在段头需手动滚）；
            // isScrollInProgress 保护用户手动滚动不被抢
            val scrollState = rememberScrollState()
            LaunchedEffect(seg.text.length, showDetail) {
                if (live && seg.status == SegStatus.THINKING && !scrollState.isScrollInProgress) {
                    scrollState.scrollTo(scrollState.maxValue)
                }
            }
            Text(
                seg.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 24.dp, end = 12.dp, bottom = 6.dp)
                    .heightIn(max = 220.dp)
                    .verticalScroll(scrollState),
            )
        }
    }
}
