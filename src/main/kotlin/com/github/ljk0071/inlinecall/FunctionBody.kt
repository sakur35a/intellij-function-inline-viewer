package com.github.ljk0071.inlinecall

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DirectClassInheritorsSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.util.Processor
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.SyntaxTraverser
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UEnumConstant
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElementOfType
import java.util.TreeMap

/** 본문의 한 조각. [sourceOffset] 은 원본 파일에서 [text] 가 시작하는 위치. */
class BodyToken(
    val text: String,
    val sourceOffset: Int,
    val keys: Array<TextAttributesKey>,
    /** 탭을 공백으로 바꾸지 않아 원본과 글자 단위로 대응하는지 */
    private val exact: Boolean = true,
) {
    /** Cmd+클릭 대상 후보: 키워드가 아닌 식별자 */
    val isNavigable: Boolean =
        text.isNotEmpty() && Character.isJavaIdentifierStart(text[0]) && text.all(Character::isJavaIdentifierPart) &&
            keys.none { it.inheritsFrom(DefaultLanguageHighlighterColors.KEYWORD) }

    fun sourceOffsetAt(index: Int): Int = if (exact) sourceOffset + index.coerceIn(0, text.length) else sourceOffset

    fun withText(newText: String) = BodyToken(newText, sourceOffset, keys, exact)

    /** 렉서 색 위에 [key] 를 덧칠한다(나중 키가 우선). */
    fun withKey(key: TextAttributesKey) = BodyToken(text, sourceOffset, keys + key, exact)
}

/**
 * 본문 안의 프로젝트 함수 호출. [afterToken] 번째 토큰 뒤에 "▶ [label]" 힌트를 그린다.
 * [targets] 는 호출 대상 선언들(체인을 합쳤으면 여러 개, Kotlin 이면 KtNamedFunction)이며 펼칠 때 다시 읽는다.
 * [key] 는 본문이 다시 계산돼도 같은 호출을 가리키도록 "라벨#순번" 으로 만든다.
 */
class BodyCall(
    val afterToken: Int,
    val label: String,
    val targets: List<SmartPsiElementPointer<PsiElement>>,
    val key: String,
    /** true 면 대상 본문 대신 대상 메서드의 재정의 목록을 펼친다(클릭할 때 검색). */
    val searchesOverrides: Boolean = false,
    /** 한 줄에 호출이 여럿일 때 무지개 색 번호(힌트와 호출된 이름을 같은 색으로 칠한다). 하나뿐이면 null. */
    val color: Int? = null,
    /** 이 호출의 호출된 이름 토큰 번호(같은 줄 기준). 펼친 본문에 마우스를 올리면 강조한다. */
    val nameTokens: List<Int> = emptyList(),
) {
    /** 이 힌트를 펼쳤을 때 보여줄 본문. 읽기 작업 안에서 호출. */
    fun load(target: PsiElement): FunctionBody? =
        if (searchesOverrides) FunctionBody.overridesOf(target) else FunctionBody.of(target)
}

/** [nestedOnly] 면 더 펼칠 수 없는 깊이(최대 깊이)에서는 줄 자체를 숨긴다(구현체 목록처럼 힌트만 있는 줄). */
class BodyLine(
    val tokens: List<BodyToken>,
    val calls: List<BodyCall> = emptyList(),
    val nestedOnly: Boolean = false,
    /** 토큰 번호 -> 무지개 색 번호. 호출된 함수 이름 토큰을 그 호출의 힌트와 같은 색으로 칠한다. */
    val tokenColors: Map<Int, Int> = emptyMap(),
) {
    val text: String get() = tokens.joinToString("") { it.text }
}

/**
 * 펼쳐서 보여줄 함수 원문. 원본 위치를 함께 들고 있어서 Cmd+클릭으로 이동할 수 있다.
 * [modificationStamp] 이후 원본 파일이 바뀌면 오프셋이 어긋나므로 이동하지 않는다(그 사이 [ExpandedCalls] 가 다시 계산한다).
 * [hasBody] 가 false 면 추상/인터페이스 메서드라 선언만 있다.
 */
class FunctionBody(
    val file: VirtualFile,
    val modificationStamp: Long,
    val lines: List<BodyLine>,
    val target: SmartPsiElementPointer<PsiElement>,
    val hasBody: Boolean,
    /** [lines] 중 원본 줄 수. 그 뒤는 덧붙인 안내 줄(구현체/재정의 목록)이라 최대 줄 수로 자르지 않는다. */
    val sourceLineCount: Int = lines.size,
    /** 구현체/재정의 목록 본문이면 몇 개까지 찾았는지(0 이면 목록이 아님)와 더 있는지. */
    val resultLimit: Int = 0,
    val hasMoreResults: Boolean = false,
) {
    val isResultList: Boolean get() = resultLimit > 0

    val calls: Sequence<BodyCall> get() = lines.asSequence().flatMap { it.calls }

    /** 원본 문서가 그대로면 다시 계산할 필요가 없다. */
    fun isUpToDate(): Boolean =
        file.isValid && FileDocumentManager.getInstance().getCachedDocument(file)?.modificationStamp == modificationStamp

    companion object {
        /**
         * [declaration] 의 원문을 렉서 하이라이팅과 함께 줄 단위 토큰으로 만든다. 읽기 작업 안에서 호출.
         * 앞쪽 문서 주석(Javadoc/KDoc)은 빼고, 선언부 들여쓰기만큼 공통 들여쓰기를 제거한다.
         */
        /**
         * [analyzedLines] 는 문법(의미) 색과 중첩 힌트를 계산할 원본 줄 수. 기본은 설정의 최대 줄 수이고,
         * "… more lines" 를 눌러 더 보여줄 때 늘린다.
         */
        fun of(declaration: PsiElement, analyzedLines: Int = InlineCallSettings.getInstance().state.maxLines): FunctionBody? =
            Perf.measure("body", detail = { "name=${(declaration as? PsiNamedElement)?.name} file=${declaration.containingFile?.name}" }) {
                build(declaration, analyzedLines)
            }

        /** [old] 와 같은 종류(본문 / 구현체·재정의 목록)와 범위로 [declaration] 을 다시 만든다. 읽기 작업 안에서 호출. */
        fun rebuildLike(old: FunctionBody, declaration: PsiElement, analyzedLines: Int): FunctionBody? =
            if (old.isResultList) overridesOf(declaration, old.resultLimit) else of(declaration, analyzedLines)

        private fun build(declaration: PsiElement, analyzedLines: Int): FunctionBody? {
            val psiFile = declaration.containingFile ?: return null
            val file = psiFile.virtualFile ?: return null
            val document = PsiDocumentManager.getInstance(psiFile.project).getDocument(psiFile) ?: return null

            // enum 의 values()/valueOf() 는 컴파일러가 만든 메서드라 본문 대신 enum 상수 선언부를 보여준다.
            val enumClass = declaration.toUElementOfType<UClass>()?.takeIf { it.isEnum }
            val header: BodyLine?
            val start: Int
            val end: Int
            if (enumClass != null) {
                header = note(InlineCallBundle.message("body.enum.constants", enumClass.name ?: "enum"))
                val constants = enumClass.fields.filterIsInstance<UEnumConstant>().mapNotNull { it.sourcePsi?.textRange }
                if (constants.isEmpty()) {
                    return FunctionBody(file, document.modificationStamp, listOf(header), pointerTo(declaration), hasBody = true)
                }
                start = constants.minOf { it.startOffset }
                end = constants.maxOf { it.endOffset }
            } else {
                header = null
                val firstCode = generateSequence(declaration.firstChild) { it.nextSibling }
                    .firstOrNull { it !is PsiComment && it !is PsiWhiteSpace && it.textLength > 0 }
                // 합성(가상) 요소는 위치가 없다. resolveProjectMethod 에서 걸러지지만 여기서도 방어한다.
                val range = declaration.textRange ?: return null
                start = firstCode?.textRange?.startOffset ?: range.startOffset
                end = range.endOffset
            }
            if (start >= end) return null

            val chars = document.immutableCharSequence
            // 파일 전체가 아니라 선언 범위만 렉싱한다(선언 시작은 문자열/주석 밖이므로 렉서 초기 상태에서 시작해도 된다).
            // 이터레이터 오프셋은 [start] 기준이다.
            val highlighter = Perf.measure("body.lex", detail = { "file=${psiFile.name} chars=${end - start}" }) {
                EditorHighlighterFactory.getInstance().createEditorHighlighter(psiFile.project, file)
                    .also { it.setText(chars.subSequence(start, end)) }
            }

            val maxLines = analyzedLines
            val lastVisibleLine = minOf(document.getLineNumber(start) + maxLines - 1, document.getLineNumber(end))
            val visibleEnd = minOf(document.getLineEndOffset(lastVisibleLine), end)
            val callEnds = Perf.measure("body.calls", detail = { "name=${(declaration as? PsiNamedElement)?.name}" }) {
                // 본문 안 호출 resolve 도 화면에 보이는 줄(최대 줄 수)까지만 한다(수천 줄짜리 메서드 대비).
                collectCalls(declaration, start, visibleEnd)
            }
            var semanticNanos = 0L
            var semanticTokens = 0

            val labelCounts = HashMap<String, Int>()
            val semanticLineLimit = maxLines
            val firstLine = document.getLineNumber(start)
            val indent = start - document.getLineStartOffset(firstLine)
            val lines = (firstLine..document.getLineNumber(end)).map { line ->
                val lineEnd = minOf(document.getLineEndOffset(line), end)
                var from = if (line == firstLine) start else document.getLineStartOffset(line)
                if (line != firstLine) {
                    val limit = minOf(from + indent, lineEnd)
                    while (from < limit && (chars[from] == ' ' || chars[from] == '\t')) from++
                }
                val tokens = ArrayList<BodyToken>()
                val it = highlighter.createIterator(from - start)
                while (!it.atEnd() && it.start + start < lineEnd) {
                    val s = maxOf(it.start + start, from)
                    val e = minOf(it.end + start, lineEnd)
                    if (s < e) {
                        val raw = chars.substring(s, e)
                        tokens += BodyToken(raw.replace("\t", "    "), s, it.textAttributesKeys, exact = '\t' !in raw)
                    }
                    it.advance()
                }
                var trimmed = trimEnd(tokens)
                // 의미 분석 색은 식별자마다 resolve 가 필요하므로 화면에 보이는 줄(최대 줄 수)까지만 칠한다.
                if (line - firstLine < semanticLineLimit) {
                    val semanticStart = System.nanoTime()
                    trimmed = trimmed.map { token ->
                        if (!token.isNavigable) return@map token
                        semanticTokens++
                        SemanticColors.keyAt(psiFile, token.sourceOffset)?.let(token::withKey) ?: token
                    }
                    semanticNanos += System.nanoTime() - semanticStart
                }
                // 빈 줄은 from == lineEnd 라 범위가 뒤집히므로 건너뛴다.
                val lineCalls = if (from < lineEnd) callEnds.subMap(from + 1, true, lineEnd, true) else emptyMap()
                // 한 줄에 호출이 여럿이면 호출마다 다른 색(무지개)으로, 힌트와 호출된 이름을 같은 색으로 칠한다.
                val rainbow = lineCalls.size >= 2
                val tokenColors = HashMap<Int, Int>()
                val calls = lineCalls.entries.mapIndexed { order, (callEnd, call) ->
                    // 호출식 마지막 글자를 담은 토큰 뒤에 힌트를 붙인다.
                    val index = trimmed.indexOfLast { token -> token.sourceOffset < callEnd }
                    val ordinal = labelCounts.merge(call.label, 1, Int::plus)
                    val color = if (rainbow) order else null
                    val nameTokens = trimmed.indices.filter { i ->
                        call.names.any { trimmed[i].sourceOffset in it.startOffset until it.endOffset }
                    }
                    if (color != null) nameTokens.forEach { tokenColors[it] = color }
                    BodyCall(index, call.label, call.targets, "${call.label}#$ordinal", color = color, nameTokens = nameTokens)
                }
                BodyLine(trimmed, calls.filter { it.afterToken >= 0 }, tokenColors = tokenColors)
            }
            if (Perf.enabled) {
                Perf.log("body.semantic", semanticNanos / 1e6, "name=${(declaration as? PsiNamedElement)?.name} identifiers=$semanticTokens")
            }
            val target = pointerTo(declaration)
            val sourceLines = listOfNotNull(header) + lines
            // 함수 선언일 때만 본문 유무/재정의를 본다. enum 클래스(주 생성자로도 변환된다)나 Kotlin 프로퍼티는 제외.
            val method = if (enumClass != null) null else declaration.toUElementOfType<UMethod>()?.takeIf { it.sourcePsi == declaration }
            val hasBody = method?.let { it.uastBody != null } ?: true
            val allLines = when {
                // 구현체 검색은 계층 전체를 훑어 비싸므로(구현 114개에서 0.7초) 재정의처럼 클릭할 때만 한다.
                // 최대 깊이에서도 "본문 없음" 은 알 수 있게 헤더 줄은 남긴다(힌트만 숨는다).
                !hasBody -> sourceLines + findLine(declaration, "body.implementations", nestedOnly = false)
                method != null && isOverridable(method.javaPsi) -> sourceLines + findLine(declaration, "body.overrides", nestedOnly = true)
                else -> sourceLines
            }
            return FunctionBody(file, document.modificationStamp, allLines, target, hasBody, sourceLineCount = sourceLines.size)
        }

        private fun pointerTo(element: PsiElement): SmartPsiElementPointer<PsiElement> =
            SmartPointerManager.getInstance(element.project).createSmartPsiElementPointer(element)

        /**
         * 구현체/재정의 목록만 담은 본문. "▶ find" 를 클릭했을 때 한 단계 아래에 펼친다. 읽기 작업 안에서 호출.
         * 검색은 계층 전체를 훑으므로 본문을 펼칠 때가 아니라 클릭할 때만, [limit] 개까지 한다("… more" 로 늘린다).
         */
        fun overridesOf(declaration: PsiElement, limit: Int = RESULT_PAGE): FunctionBody? {
            val uMethod = declaration.toUElementOfType<UMethod>() ?: return null
            val psiFile = declaration.containingFile ?: return null
            val file = psiFile.virtualFile ?: return null
            val document = PsiDocumentManager.getInstance(psiFile.project).getDocument(psiFile) ?: return null
            val abstract = uMethod.uastBody == null
            val (lines, hasMore) = overridingLines(
                uMethod.javaPsi, limit,
                emptyKey = if (abstract) "body.no.implementations" else "body.no.overrides",
                event = if (abstract) "body.impls" else "body.overrides",
            )
            return FunctionBody(
                file, document.modificationStamp, lines, pointerTo(declaration), hasBody = true,
                sourceLineCount = 0, resultLimit = limit, hasMoreResults = hasMore,
            )
        }

        /** 재정의될 수 있는 인스턴스 메서드인지(인터페이스 default, final 아닌 클래스의 final 아닌 메서드, Kotlin open) */
        private fun isOverridable(method: PsiMethod): Boolean {
            if (method.isConstructor) return false
            if (method.hasModifierProperty(PsiModifier.STATIC) || method.hasModifierProperty(PsiModifier.PRIVATE) ||
                method.hasModifierProperty(PsiModifier.FINAL)
            ) return false
            val containingClass = method.containingClass ?: return false
            if (!containingClass.isInterface && containingClass.hasModifierProperty(PsiModifier.FINAL)) return false
            // 상속한 클래스가 하나도 없으면 "find" 줄은 소음이다. 직접 상속 여부만 스텁 인덱스로 싸게 확인한다.
            return Perf.measure("body.inheritors", detail = { "class=${containingClass.name}" }) {
                DirectClassInheritorsSearch.search(containingClass, GlobalSearchScope.projectScope(method.project)).findFirst() != null
            }
        }

        /**
         * "▶ find" 를 누른 직후 검색 결과가 오기 전까지 보여줄 자리표시 본문("// searching…").
         * 처음 검색은 캐시가 비어 있어 0.5~1.5초 걸리므로(Exposed) 클릭이 먹혔는지 바로 보여준다.
         * 목록 본문으로 표시해 두어, 그 사이 다시 계산되면 실제 검색으로 대체된다.
         */
        fun searching(parent: FunctionBody, call: BodyCall): FunctionBody =
            FunctionBody(
                parent.file, modificationStamp = -1, listOf(note(InlineCallBundle.message("body.searching"))),
                call.targets.firstOrNull() ?: parent.target, hasBody = true, sourceLineCount = 0, resultLimit = RESULT_PAGE,
            )

        /** "// <헤더> ▶ find": 클릭하면 구현체/재정의를 검색해 한 단계 아래에 펼친다. */
        private fun findLine(declaration: PsiElement, headerKey: String, nestedOnly: Boolean): BodyLine {
            val call = BodyCall(0, InlineCallBundle.message("body.find.overrides"), listOf(pointerTo(declaration)), "overrides", searchesOverrides = true)
            val header = BodyToken(InlineCallBundle.message(headerKey), 0, arrayOf(DefaultLanguageHighlighterColors.LINE_COMMENT), exact = false)
            return BodyLine(listOf(header), listOf(call), nestedOnly = nestedOnly)
        }

        /** [method] 를 재정의/구현한 본문 있는 메서드마다 펼칠 수 있는 힌트 한 줄. [limit] 개까지 찾고, 더 있는지도 돌려준다. */
        private fun overridingLines(method: PsiMethod, limit: Int, emptyKey: String, event: String): Pair<List<BodyLine>, Boolean> {
            val found = ArrayList<PsiMethod>()
            Perf.measure(event, detail = { "name=${method.containingClass?.name}.${method.name} limit=$limit found=${found.size}" }) {
                OverridingMethodsSearch.search(method, GlobalSearchScope.projectScope(method.project), true)
                    .forEach(Processor { found += it; found.size <= limit })
            }
            val implementations = found.take(limit).filter {
                CallTargets.isProjectDeclaration(it) && CallTargets.declarationOf(it).toUElementOfType<UMethod>()?.uastBody != null
            }
            if (implementations.isEmpty() && found.size <= limit) return listOf(note(InlineCallBundle.message(emptyKey))) to false

            val pointers = SmartPointerManager.getInstance(method.project)
            val lines = implementations.map { implementation ->
                val label = (implementation.containingClass?.name ?: "<anonymous>") + "." + CallTargets.signatureOf(implementation)
                val target = pointers.createSmartPsiElementPointer(CallTargets.declarationOf(implementation))
                val call = BodyCall(0, label, listOf(target), "impl:$label")
                BodyLine(listOf(BodyToken("    ", 0, emptyArray(), exact = false)), listOf(call), nestedOnly = true)
            }
            return lines to (found.size > limit)
        }

        /** 주석 색으로 그리는 안내 줄 (원본 위치 없음) */
        private fun note(text: String) =
            BodyLine(listOf(BodyToken(text, 0, arrayOf(DefaultLanguageHighlighterColors.LINE_COMMENT), exact = false)))

        /** 구현체/재정의 목록을 한 번에 찾는 개수("… more" 를 누를 때마다 이만큼 늘린다) */
        const val RESULT_PAGE = 20

        /** "all" 로 불러올 때의 상한(계층이 비정상적으로 큰 경우 대비) */
        const val ALL_RESULTS = 10_000

        private class FoundCall(val label: String, val targets: List<SmartPsiElementPointer<PsiElement>>, val names: List<TextRange>)

        /** 본문 안의 프로젝트 함수 호출: 호출식 끝 오프셋 -> 호출. 힌트 규칙은 에디터 힌트와 같다. */
        private fun collectCalls(
            declaration: PsiElement,
            start: Int,
            end: Int,
        ): TreeMap<Int, FoundCall> {
            val pointers = SmartPointerManager.getInstance(declaration.project)
            val result = TreeMap<Int, FoundCall>()
            for (element in SyntaxTraverser.psiTraverser(declaration)) {
                val callEnd = element.textRange.endOffset
                if (callEnd <= start || callEnd > end) continue
                val hint = CallTargets.hintFor(element) ?: continue
                result[callEnd] = FoundCall(
                    hint.label,
                    hint.methods.map { pointers.createSmartPsiElementPointer(CallTargets.declarationOf(it)) },
                    hint.names,
                )
            }
            return result
        }

        private fun CharSequence.substring(start: Int, end: Int): String = subSequence(start, end).toString()

        private fun trimEnd(tokens: MutableList<BodyToken>): List<BodyToken> {
            while (tokens.isNotEmpty()) {
                val last = tokens.last()
                val trimmed = last.text.trimEnd()
                if (trimmed == last.text) break
                tokens.removeAt(tokens.lastIndex)
                if (trimmed.isNotEmpty()) {
                    tokens += last.withText(trimmed)
                    break
                }
            }
            return tokens
        }
    }
}

private fun TextAttributesKey.inheritsFrom(target: TextAttributesKey): Boolean =
    generateSequence(this) { it.fallbackAttributeKey }.any { it == target }
