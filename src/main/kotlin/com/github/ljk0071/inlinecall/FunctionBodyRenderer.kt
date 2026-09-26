package com.github.ljk0071.inlinecall

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.util.EditorUIUtil
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Point
import java.awt.geom.Rectangle2D

/**
 * 펼쳐진 본문 하나. [children] 은 본문 안에서 다시 펼친 호출들(체인이면 호출 하나에 본문 여러 개).
 * [extraLines] 는 "… more lines" 를 눌러 최대 줄 수보다 더 보여주는 줄 수.
 */
class BodyNode(body: FunctionBody, val depth: Int, extraLines: Int = 0, parentCall: BodyCall? = null) {
    var body: FunctionBody = body
        internal set
    var extraLines: Int = extraLines
        internal set

    /** 이 본문을 펼친 바깥 본문의 호출(최상위 본문이면 null). 색 짝과 마우스 오버 강조에 쓴다. */
    var parentCall: BodyCall? = parentCall
        internal set
    val children = LinkedHashMap<BodyCall, List<BodyNode>>()
}

/** 다시 계산할 때 펼침 상태를 옮기기 위한 스냅샷. [BodyCall.key] 로 새 본문의 호출과 맞춘다. */
class BodySnapshot(val body: FunctionBody, val extraLines: Int, val children: Map<String, List<BodySnapshot>>)

/** "더 보기" 줄의 종류 */
enum class MoreKind { LINES, RESULTS }

/** "더 보기" 줄의 버튼: 한 페이지 더([all] = false) 또는 전체 */
class MoreAction(val kind: MoreKind, val all: Boolean)

/** 렌더러 안에서 클릭된 대상 */
sealed interface BodyHit {
    class Token(val node: BodyNode, val token: BodyToken, val sourceOffset: Int) : BodyHit
    class Call(val node: BodyNode, val call: BodyCall) : BodyHit
    class More(val node: BodyNode, val action: MoreAction) : BodyHit
}

/**
 * 호출부 아래에 함수 원문을 문법 색상과 함께 그리는 block inlay 렌더러.
 * 본문 안의 프로젝트 함수 호출에도 ▶ 힌트를 그리고, 펼치면 그 줄 아래에 한 단계 들여서 중첩 본문을 그린다.
 * 원본 위치를 알고 있어서 [hitTest] 로 클릭 지점의 원본 오프셋을 구할 수 있다.
 */
class FunctionBodyRenderer(
    bodies: List<FunctionBody>,
    private val indentPx: Int,
    private var maxLines: Int = DEFAULT_MAX_LINES,
    private var maxDepth: Int = DEFAULT_MAX_DEPTH,
) : EditorCustomElementRenderer {

    companion object {
        const val DEFAULT_MAX_LINES = 30

        private val FALLBACK_RAINBOW = listOf(
            JBColor(0x9B3B6A, 0xE8BA36), JBColor(0x114D77, 0x54A857), JBColor(0xBC8650, 0x359FF4),
            JBColor(0x005910, 0x6E7ED9), JBColor(0xBC5150, 0x179FFF),
        )

        /** 최상위 본문이 depth 0. 이 깊이의 본문에는 더 펼칠 힌트를 그리지 않는다. */
        const val DEFAULT_MAX_DEPTH = 4
    }

    /** 최상위 본문들. 합친 체인이면 여러 개. */
    var roots: List<BodyNode> = bodies.map { BodyNode(it, 0) }
        private set

    /** 펼침/접힘/교체마다 증가. 백그라운드 재계산 결과가 그 사이 사용자 조작을 덮어쓰지 않게 한다. */
    var version = 0
        private set

    /** Cmd 를 누른 채 마우스를 올린 토큰. 밑줄로 표시한다. EDT 에서만 바꾼다. */
    var hovered: BodyToken? = null

    /** 마우스를 올린 중첩 본문. 이 본문을 펼친 바깥 본문의 호출된 이름을 강조한다. EDT 에서만 바꾼다. */
    var hoveredNode: BodyNode? = null

    /**
     * 한 줄: 본문의 한 줄이거나, 클릭하면 더 불러오는 "… more" 안내([more]).
     * [separator] 면 위에 구분선을 긋는다.
     */
    private class Row(
        val node: BodyNode,
        val line: BodyLine?,
        val moreText: String?,
        val separator: Boolean = false,
        val more: MoreKind? = null,
        /** 깊이 0..depth 각 본문의 호출 구분 색 번호(펼친 호출에 색이 없으면 null). 배경과 왼쪽 막대에 쓴다. */
        val colors: List<Int?> = emptyList(),
    )

    private sealed interface Piece {
        val x: Int
        val width: Int

        /** [rainbow] 는 같은 줄의 다른 호출과 구분하는 무지개 색 번호(호출된 이름 토큰 / 그 힌트) */
        class Text(
            val token: BodyToken,
            val attrs: TextAttributes?,
            override val x: Int,
            override val width: Int,
            val rainbow: Int? = null,
            val index: Int = -1,
        ) : Piece
        class Hint(val call: BodyCall, val text: String, override val x: Int, override val width: Int) : Piece
        class Label(val text: String, override val x: Int, override val width: Int) : Piece
        class Action(val action: MoreAction, val text: String, override val x: Int, override val width: Int) : Piece
    }

    /** "… more lines" 줄의 남은 줄 수([MoreAction] "next N" 라벨용). flatten 때 채운다. */
    private val remainingLines = HashMap<BodyNode, Int>()

    private var rows: List<Row> = flatten()

    private fun flatten(): List<Row> {
        remainingLines.clear()
        val result = ArrayList<Row>()
        fun visit(nodes: List<BodyNode>, inherited: List<Int?>) {
            nodes.forEachIndexed { index, node ->
                val body = node.body
                val colors = inherited + node.parentCall?.color
                var first = index > 0
                fun add(line: BodyLine) {
                    if (line.nestedOnly && !canExpand(node)) return
                    result += Row(node, line, null, separator = first, colors = colors)
                    first = false
                    for (call in line.calls) node.children[call]?.let { visit(it, colors) }
                }
                // 원본 줄만 최대 줄 수(+ 더 보기로 늘린 만큼)로 자르고, 덧붙인 안내 줄(구현체/재정의 목록)은 항상 보여준다.
                val source = body.lines.subList(0, body.sourceLineCount)
                val shown = maxLines + node.extraLines
                source.take(shown).forEach(::add)
                if (source.size > shown) {
                    val text = InlineCallBundle.message("body.more.lines", source.size - shown)
                    remainingLines[node] = source.size - shown
                    result += Row(node, null, text, separator = first, more = MoreKind.LINES, colors = colors)
                    first = false
                }
                body.lines.subList(body.sourceLineCount, body.lines.size).forEach(::add)
                if (body.hasMoreResults && canExpand(node)) {
                    result += Row(node, null, "    " + InlineCallBundle.message("body.more.results"), more = MoreKind.RESULTS, colors = colors)
                }
            }
        }
        visit(roots, emptyList())
        return result
    }

    private fun changed() {
        rows = flatten()
        version++
    }

    /** EDT 전용. 이미 펼쳐져 있었으면 접고 true. 호출 후 inlay.update() 필요. */
    fun collapse(node: BodyNode, call: BodyCall): Boolean {
        node.children.remove(call) ?: return false
        changed()
        return true
    }

    /** EDT 전용. 호출 후 inlay.update() 필요. */
    fun expand(node: BodyNode, call: BodyCall, bodies: List<FunctionBody>): Boolean {
        if (bodies.isEmpty() || node.children.containsKey(call) || !canExpand(node)) return false
        node.children[call] = bodies.map { BodyNode(it, node.depth + 1, parentCall = call) }
        changed()
        return true
    }

    /**
     * EDT 전용. 자리표시([pending])로 펼쳐 둔 호출을 실제 본문으로 바꾼다. 결과가 없으면 접는다.
     * 그 사이 사용자가 접었거나 다른 내용으로 바뀌었으면 false. 호출 후 inlay.update() 필요.
     */
    fun resolvePending(node: BodyNode, call: BodyCall, pending: List<BodyNode>, bodies: List<FunctionBody>): Boolean {
        if (node.children[call] !== pending) return false
        if (bodies.isEmpty()) node.children.remove(call) else node.children[call] = bodies.map { BodyNode(it, node.depth + 1, parentCall = call) }
        changed()
        return true
    }

    /** EDT 전용. 다시 계산한 트리로 바꾼다. 호출 후 inlay.update() 필요. */
    fun replaceRoots(newRoots: List<BodyNode>) {
        hovered = null
        roots = newRoots
        changed()
    }

    /** EDT 전용. 설정이 바뀌면 호출. 새 최대 깊이보다 깊게 펼쳐진 본문은 접는다. 호출 후 inlay.update() 필요. */
    fun updateLimits(maxLines: Int, maxDepth: Int) {
        if (maxLines == this.maxLines && maxDepth == this.maxDepth) return
        this.maxLines = maxLines
        this.maxDepth = maxDepth
        fun prune(node: BodyNode) {
            if (!canExpand(node)) node.children.clear() else node.children.values.forEach { it.forEach(::prune) }
        }
        roots.forEach(::prune)
        changed()
    }

    /** "… more lines" 를 한 번 누를 때 늘리는 줄 수(= 최대 줄 수) */
    val linesPerPage: Int get() = maxLines

    /**
     * EDT 전용. "… more" 로 다시 계산한 본문으로 [node] 를 바꾼다. 펼쳐 둔 중첩 본문은 [BodyCall.key] 로 옮긴다.
     * 호출 후 inlay.update() 필요.
     */
    fun replaceBody(node: BodyNode, body: FunctionBody, extraLines: Int): Boolean {
        if (!contains(node)) return false
        val oldChildren = node.children.entries.associate { (call, nodes) -> call.key to nodes }
        node.children.clear()
        for (call in body.calls) oldChildren[call.key]?.let { children ->
            children.forEach { it.parentCall = call }
            node.children[call] = children
        }
        node.body = body
        node.extraLines = extraLines
        changed()
        return true
    }

    private fun contains(target: BodyNode): Boolean {
        fun visit(nodes: List<BodyNode>): Boolean = nodes.any { it === target || visit(it.children.values.flatten()) }
        return visit(roots)
    }

    /** EDT 전용. 현재 펼침 상태 스냅샷. */
    fun snapshot(): List<BodySnapshot> {
        fun snap(node: BodyNode): BodySnapshot =
            BodySnapshot(node.body, node.extraLines, node.children.entries.associate { (call, nodes) -> call.key to nodes.map(::snap) })
        return roots.map(::snap)
    }

    /** 보이는 본문들의 원본 파일. 이 파일이 바뀌면 다시 계산한다. */
    fun files(): Set<VirtualFile> {
        val result = HashSet<VirtualFile>()
        fun visit(node: BodyNode) {
            result += node.body.file
            node.children.values.forEach { it.forEach(::visit) }
        }
        roots.forEach(::visit)
        return result
    }

    private fun canExpand(node: BodyNode) = node.depth < maxDepth

    /** 테스트용: 현재 보이는 줄 텍스트(깊이만큼 탭으로 들여쓴다) */
    fun visibleText(): List<String> = rows.map { "\t".repeat(it.node.depth) + (it.line?.text ?: it.moreText) }

    // ---- 레이아웃 ----

    private val barWidth get() = JBUI.scale(2)
    private val gap get() = JBUI.scale(8)
    private val verticalPadding get() = JBUI.scale(2)
    private val hintPadding get() = JBUI.scale(3)

    private fun nestStep(editor: Editor) = plainMetrics(editor).stringWidth("    ")

    private fun barX(editor: Editor, inlayX: Int, depth: Int) = inlayX + indentPx + depth * nestStep(editor)

    private fun textX(editor: Editor, inlayX: Int, depth: Int) = barX(editor, inlayX, depth) + barWidth + gap

    private fun attributes(editor: Editor, token: BodyToken): TextAttributes? =
        token.keys.fold(null as TextAttributes?) { acc, key ->
            val attrs = editor.colorsScheme.getAttributes(key) ?: return@fold acc
            if (acc == null) attrs else TextAttributes.merge(acc, attrs)
        }

    private fun font(editor: Editor, attrs: TextAttributes?): Font =
        editor.colorsScheme.getFont(EditorFontType.forJavaStyle(attrs?.fontType ?: Font.PLAIN))

    private fun plainMetrics(editor: Editor) = editor.contentComponent.getFontMetrics(font(editor, null))

    /**
     * 펼친 본문 배경. 에디터 배경에 글자색을 살짝 섞고(깊이마다 조금 더), [tint] 색 번호가 있으면 그 색을 옅게 섞는다.
     * 글자는 문법 색 그대로 그리므로, 가독성을 위해 색은 라이트 12% / 다크 18% 까지만 섞는다.
     */
    private fun backgroundFor(editor: Editor, depth: Int, tint: Int? = null): Color {
        val scheme = editor.colorsScheme
        val base = ColorUtil.mix(scheme.defaultBackground, scheme.defaultForeground, 0.04 + 0.03 * depth)
        if (tint == null) return base
        val ratio = if (ColorUtil.isDark(scheme.defaultBackground)) 0.18 else 0.12
        return ColorUtil.mix(base, rainbowColor(editor, tint), ratio)
    }

    /** 호출된 이름 강조 배경(Color Scheme 의 "Called name"). 없으면 선택 영역 색을 옅게. */
    private fun calledNameBackground(editor: Editor): Color =
        editor.colorsScheme.getAttributes(InlineCallColors.CALLED_NAME)?.backgroundColor
            ?: ColorUtil.withAlpha(editor.colorsScheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR) ?: JBColor.YELLOW, 0.5)

    private fun hintAttributes(editor: Editor): TextAttributes? =
        editor.colorsScheme.getAttributes(DefaultLanguageHighlighterColors.INLAY_DEFAULT)

    private fun layout(editor: Editor, row: Row, inlayX: Int): List<Piece> {
        if (row.more != null && row.moreText != null) return moreLayout(editor, row, row.more, row.moreText, inlayX)
        val line = row.line ?: return emptyList()
        val pieces = ArrayList<Piece>()
        var x = textX(editor, inlayX, row.node.depth)
        val showHints = canExpand(row.node)
        line.tokens.forEachIndexed { index, token ->
            val attrs = attributes(editor, token)
            val width = editor.contentComponent.getFontMetrics(font(editor, attrs)).stringWidth(token.text)
            pieces += Piece.Text(token, attrs, x, width, line.tokenColors[index], index)
            x += width
            if (!showHints) return@forEachIndexed
            for (call in line.calls) {
                if (call.afterToken != index) continue
                val arrow = if (row.node.children.containsKey(call)) "▼ " else "▶ "
                val text = arrow + call.label
                x += hintPadding
                val hintWidth = plainMetrics(editor).stringWidth(text) + hintPadding * 2
                pieces += Piece.Hint(call, text, x, hintWidth)
                x += hintWidth
            }
        }
        return pieces
    }

    /** "… 64 more lines  [next 30] [all]" */
    private fun moreLayout(editor: Editor, row: Row, kind: MoreKind, text: String, inlayX: Int): List<Piece> {
        val metrics = plainMetrics(editor)
        var x = textX(editor, inlayX, row.node.depth)
        val pieces = ArrayList<Piece>()
        pieces += Piece.Label(text, x, metrics.stringWidth(text))
        x += metrics.stringWidth(text) + gap
        val page = when (kind) {
            MoreKind.LINES -> minOf(maxLines, remainingLines[row.node] ?: maxLines)
            MoreKind.RESULTS -> FunctionBody.RESULT_PAGE
        }
        for (action in listOf(MoreAction(kind, all = false), MoreAction(kind, all = true))) {
            val label = if (action.all) InlineCallBundle.message("body.more.all") else InlineCallBundle.message("body.more.next", page)
            val width = metrics.stringWidth(label) + hintPadding * 2
            pieces += Piece.Action(action, label, x, width)
            x += width + hintPadding
        }
        return pieces
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val right = rows.maxOfOrNull { row ->
            layout(editor, row, 0).lastOrNull()?.let { it.x + it.width } ?: 0
        } ?: 0
        return right + gap
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int =
        inlay.editor.lineHeight * rows.size + verticalPadding * 2

    override fun paint(inlay: Inlay<*>, g: Graphics2D, targetRegion: Rectangle2D, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val lineHeight = editor.lineHeight
        val scheme = editor.colorsScheme
        val x = targetRegion.x.toInt()
        val y = targetRegion.y.toInt() + verticalPadding
        val plain = plainMetrics(editor)
        val baselineShift = (lineHeight - plain.height) / 2 + plain.ascent
        val hintAttrs = hintAttributes(editor)

        EditorUIUtil.setupAntialiasing(g)

        // 펼친 본문 영역 배경. 에디터 배경에 글자색을 살짝 섞어서(라이트는 어둡게, 다크는 밝게) 실제 코드와 구분한다.
        val regionRight = x + targetRegion.width.toInt()
        g.color = backgroundFor(editor, 0)
        g.fillRect(x + indentPx, targetRegion.y.toInt(), regionRight - x - indentPx, targetRegion.height.toInt())

        rows.forEachIndexed { index, row ->
            val rowY = y + index * lineHeight
            val baseline = rowY + baselineShift

            // 중첩 본문은 깊이마다 조금 더 진하게. 색이 있는 호출을 펼친 본문은 그 색을 옅게 섞어 짝을 보여준다.
            if (row.node.depth > 0) {
                val left = barX(editor, x, row.node.depth)
                val tint = row.colors.lastOrNull { it != null }
                g.color = backgroundFor(editor, row.node.depth, tint)
                g.fillRect(left, rowY, regionRight - left, lineHeight)
            }

            // 깊이마다 왼쪽 세로 막대로 "펼쳐진 본문" 영역임을 표시(색이 있는 호출이면 그 색)
            for (depth in 0..row.node.depth) {
                g.color = row.colors.getOrNull(depth)?.let { rainbowColor(editor, it) }
                    ?: ColorUtil.withAlpha(scheme.defaultForeground, 0.35)
                g.fillRect(barX(editor, x, depth), rowY, barWidth, lineHeight)
            }
            g.color = ColorUtil.withAlpha(scheme.defaultForeground, 0.35)

            // 마우스를 올린 중첩 본문을 펼친 호출의 이름을 강조한다.
            val highlightedNames = hoveredNode?.parentCall?.takeIf { call -> row.line?.calls?.any { it === call } == true }?.nameTokens.orEmpty()
            // 합친 체인의 본문 사이 구분선
            if (row.separator) {
                val left = barX(editor, x, row.node.depth)
                g.fillRect(left, rowY, (calcWidthInPixels(inlay) - (left - x)).coerceAtLeast(0), JBUI.scale(1))
            }

            for (piece in layout(editor, row, x)) {
                when (piece) {
                    is Piece.Text -> {
                        if (piece.index in highlightedNames) {
                            g.color = calledNameBackground(editor)
                            g.fillRect(piece.x, rowY, piece.width, lineHeight)
                        }
                        g.font = font(editor, piece.attrs)
                        g.color = piece.rainbow?.let { rainbowColor(editor, it) } ?: piece.attrs?.foregroundColor ?: scheme.defaultForeground
                        g.drawString(piece.token.text, piece.x, baseline)
                        if (piece.token === hovered) g.fillRect(piece.x, baseline + JBUI.scale(1), piece.width, JBUI.scale(1))
                    }
                    is Piece.Hint -> drawPill(g, editor, piece.text, piece.x, piece.width, rowY, baseline, piece.call.color?.let { rainbowColor(editor, it) })
                    is Piece.Action -> drawPill(g, editor, piece.text, piece.x, piece.width, rowY, baseline)
                    is Piece.Label -> {
                        g.font = font(editor, null)
                        g.color = ColorUtil.withAlpha(scheme.defaultForeground, 0.5)
                        g.drawString(piece.text, piece.x, baseline)
                    }
                }
            }
        }
    }

    /** 클릭할 수 있는 조각(본문 안 ▶ 힌트, 더 보기 버튼)은 인레이 힌트처럼 둥근 배경 위에 그린다. */
    /** [accent] 가 있으면(무지개 색) 배경에 살짝 섞고 글자를 그 색으로 그린다. */
    private fun drawPill(g: Graphics2D, editor: Editor, text: String, x: Int, width: Int, rowY: Int, baseline: Int, accent: Color? = null) {
        val hintAttrs = hintAttributes(editor)
        val lineHeight = editor.lineHeight
        val background = hintAttrs?.backgroundColor ?: editor.colorsScheme.defaultBackground
        g.color = if (accent != null) ColorUtil.mix(background, accent, 0.25) else background
        val arc = JBUI.scale(6)
        g.fillRoundRect(x, rowY + JBUI.scale(1), width, lineHeight - JBUI.scale(2), arc, arc)
        g.font = font(editor, null)
        g.color = accent ?: hintAttrs?.foregroundColor ?: JBColor.GRAY
        g.drawString(text, x + hintPadding, baseline)
    }

    /** 호출 구분 색(기본은 IDE 무지개 색, Color Scheme 에서 변경 가능). 스킴에 없으면 기본 팔레트. */
    private fun rainbowColor(editor: Editor, index: Int): Color {
        val keys = InlineCallColors.CALL_COLORS
        return editor.colorsScheme.getAttributes(keys[index % keys.size])?.foregroundColor
            ?: FALLBACK_RAINBOW[index % FALLBACK_RAINBOW.size]
    }

    /** [point](에디터 content 좌표)가 속한 본문. 없으면 null. */
    fun nodeAt(inlay: Inlay<*>, point: Point): BodyNode? {
        val bounds = inlay.bounds ?: return null
        return rows.getOrNull((point.y - bounds.y - verticalPadding).floorDiv(inlay.editor.lineHeight))?.node
    }

    /** [point](에디터 content 좌표) 아래의 토큰 또는 호출 힌트. 없으면 null. */
    fun hitTest(inlay: Inlay<*>, point: Point): BodyHit? {
        val editor = inlay.editor
        val bounds = inlay.bounds ?: return null
        val row = rows.getOrNull((point.y - bounds.y - verticalPadding).floorDiv(editor.lineHeight)) ?: return null
        val piece = layout(editor, row, bounds.x).firstOrNull { point.x >= it.x && point.x < it.x + it.width } ?: return null
        return when (piece) {
            is Piece.Hint -> BodyHit.Call(row.node, piece.call)
            is Piece.Action -> BodyHit.More(row.node, piece.action)
            is Piece.Label -> null
            is Piece.Text -> {
                val fm = editor.contentComponent.getFontMetrics(font(editor, piece.attrs))
                val text = piece.token.text
                var index = 0
                while (index < text.length && piece.x + fm.stringWidth(text.substring(0, index + 1)) <= point.x) index++
                BodyHit.Token(row.node, piece.token, piece.token.sourceOffsetAt(index))
            }
        }
    }
}
