package com.github.ljk0071.inlinecall

import com.intellij.lang.Language
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiWhiteSpace
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.getPossiblePsiSourceTypes
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.util.ClassSet
import java.util.concurrent.ConcurrentHashMap

/**
 * UAST 기반으로 "호출부 -> 프로젝트 소스에 정의된 메서드"를 찾는 유틸.
 */
object CallTargets {

    private val callSourceTypes = ConcurrentHashMap<Language, ClassSet<PsiElement>>()

    /**
     * UCallExpression 으로 변환될 수 있는 PSI 타입인지 빠르게 걸러낸다.
     * 모든 PSI 원소를 UAST로 변환하면 비싸므로, 먼저 클래스 타입으로만 판단한다.
     */
    private fun isCallCandidate(element: PsiElement): Boolean {
        val types = callSourceTypes.computeIfAbsent(element.language) {
            getPossiblePsiSourceTypes(it, UCallExpression::class.java)
        }
        return element.javaClass in types
    }

    /** [element]가 메서드 호출식의 source PSI 이면 해당 UCallExpression 을 돌려준다. */
    fun toCall(element: PsiElement): UCallExpression? {
        if (!isCallCandidate(element)) return null
        val call = element.toUElementOfType<UCallExpression>() ?: return null
        if (call.sourcePsi != element) return null
        if (call.kind != UastCallKind.METHOD_CALL) return null
        return call
    }

    /** 호출 대상이 프로젝트 소스(테스트 포함)에 정의된 메서드일 때만 돌려준다. 라이브러리/JDK 는 null. */
    fun resolveProjectMethod(call: UCallExpression): PsiMethod? {
        val method = call.resolve() ?: return null
        if (method is PsiCompiledElement) return null
        val declaration = declarationOf(method)
        val file = declaration.containingFile?.virtualFile ?: return null
        if (!ProjectFileIndex.getInstance(method.project).isInSourceContent(file)) return null
        return method
    }

    /** 실제 소스 선언 PSI (Kotlin light method 이면 KtNamedFunction 등). */
    fun declarationOf(method: PsiMethod): PsiElement =
        method.toUElementOfType<UMethod>()?.sourcePsi ?: method.navigationElement

    /** 접힌 상태에서 보여줄 "이름(파라미터...)" */
    fun signatureOf(method: PsiMethod): Pair<String, String> {
        val params = method.parameterList.parameters.joinToString(", ") { it.name }
        return method.name to "($params)"
    }

    /**
     * 메서드 선언의 원문을 줄 단위로 돌려준다.
     * 앞쪽 문서 주석(Javadoc/KDoc)은 빼고, 선언부 들여쓰기만큼 공통 들여쓰기를 제거한다.
     */
    fun bodyLines(method: PsiMethod): List<String>? {
        val declaration = declarationOf(method)
        val file = declaration.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null

        val firstCode = generateSequence(declaration.firstChild) { it.nextSibling }
            .firstOrNull { it !is PsiComment && it !is PsiWhiteSpace && it.textLength > 0 }
        val start = firstCode?.textRange?.startOffset ?: declaration.textRange.startOffset
        val end = declaration.textRange.endOffset
        if (start >= end) return null

        val lineStart = document.getLineStartOffset(document.getLineNumber(start))
        val indent = start - lineStart
        return document.charsSequence.subSequence(start, end).toString()
            .lines()
            .mapIndexed { i, line -> if (i == 0) line else line.dropLeadingWhitespace(indent) }
            .map { it.replace("\t", "    ").trimEnd() }
    }

    private fun String.dropLeadingWhitespace(max: Int): String {
        var i = 0
        while (i < length && i < max && (this[i] == ' ' || this[i] == '\t')) i++
        return substring(i)
    }
}
