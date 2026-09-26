package com.github.ljk0071.inlinecall

import com.intellij.codeInsight.hints.declarative.impl.inlayRenderer.DeclarativeInlayRendererBase
import com.intellij.codeInsight.hints.declarative.impl.views.InlayPresentationList
import com.intellij.codeInsight.hints.declarative.impl.views.TextInlayPresentationEntry
import com.intellij.openapi.editor.Inlay
import java.lang.reflect.Method

/**
 * 호출부 힌트에 지금 화면에 그려진 화살표(▶/▼)를 읽는다(내부 API, 읽기 전용).
 * ▶/▼ 는 플랫폼 토글이 관리하므로, 본문 펼침 상태를 사용자가 보는 화살표에 맞추기 위해 쓴다.
 */
object HintArrow {

    /** getEntries() 는 바이트코드상 public 이지만 Kotlin 메타데이터가 private 라서 직접 부를 수 없다. */
    private val getEntries: Method? by lazy {
        runCatching { InlayPresentationList::class.java.getMethod("getEntries") }.getOrNull()
    }

    /** ▼ 이면 true, ▶ 이면 false. 읽을 수 없으면(IDE 버전에 따른 구조 변경 등) null. */
    fun isExpanded(inlay: Inlay<*>): Boolean? = try {
        val renderer = inlay.renderer as? DeclarativeInlayRendererBase<*>
        val method = getEntries
        if (renderer == null || method == null) null
        else renderer.presentationLists.asSequence()
            .flatMap { (method.invoke(it) as Array<*>).asSequence() }
            .filterIsInstance<TextInlayPresentationEntry>()
            .firstNotNullOfOrNull {
                when {
                    it.text.startsWith("▼") -> true
                    it.text.startsWith("▶") -> false
                    else -> null
                }
            }
    } catch (_: LinkageError) {
        null
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: ClassCastException) {
        null
    }
}
