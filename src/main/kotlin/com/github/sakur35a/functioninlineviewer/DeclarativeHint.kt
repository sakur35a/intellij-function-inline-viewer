package com.github.sakur35a.functioninlineviewer

import com.intellij.codeInsight.hints.declarative.impl.inlayRenderer.DeclarativeInlayRendererBase
import com.intellij.codeInsight.hints.declarative.impl.views.InlayPresentationList
import com.intellij.codeInsight.hints.declarative.impl.views.TextInlayPresentationEntry
import com.intellij.openapi.editor.Inlay
import java.awt.Point
import java.lang.reflect.Method

/**
 * 화면에 그려진 선언형 힌트를 읽는다(내부 API, 읽기 전용). 이 플러그인의 내부 API 사용은 이 파일에만 둔다.
 * - 공개 API 로는 inlay 가 어느 provider 의 힌트인지 알 수 없어 providerId 를 읽는다.
 * - ▶/▼ 는 플랫폼 토글이 관리하므로, 본문 펼침 상태를 사용자가 보는 화살표에 맞추기 위해 읽는다.
 * - 플랫폼은 글자 영역 클릭만 토글하므로, 같은 판정으로 손가락 커서를 보여준다.
 */
object DeclarativeHint {

    /** 이 플러그인의 호출부 힌트인지 */
    fun isOurs(inlay: Inlay<*>): Boolean = try {
        inlay.placement == Inlay.Placement.INLINE &&
            (inlay.renderer as? DeclarativeInlayRendererBase<*>)?.providerId == InlineCallHintsProvider.PROVIDER_ID
    } catch (_: LinkageError) {
        false
    }

    /** getEntries() 는 바이트코드상 public 이지만 Kotlin 메타데이터가 private 라서 직접 부를 수 없다. */
    private val getEntries: Method? by lazy {
        runCatching { InlayPresentationList::class.java.getMethod("getEntries") }.getOrNull()
    }

    /**
     * [pointInInlay](inlay 기준 좌표)가 플랫폼이 클릭을 처리하는 글자 영역 위인지(여백이면 false).
     * 판정할 수 없으면 null.
     */
    fun isOverText(inlay: Inlay<*>, pointInInlay: Point): Boolean? = try {
        val renderer = inlay.renderer as? DeclarativeInlayRendererBase<*>
        renderer?.presentationLists?.any { it.findEntryAtPoint(pointInInlay, renderer.textMetricsStorage)?.entry != null }
    } catch (_: LinkageError) {
        null
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
