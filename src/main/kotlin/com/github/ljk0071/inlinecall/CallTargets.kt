package com.github.ljk0071.inlinecall

import com.intellij.lang.Language
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
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

    /**
     * 힌트에 보여줄 "이름(파라미터...)".
     * 같은 이름의 오버로드가 있으면 구분되도록 파라미터 이름 대신 타입을 보여준다.
     * Kotlin light method 의 합성 파라미터(확장 receiver `$this$..`, suspend `$completion`)는 뺀다.
     */
    fun signatureOf(method: PsiMethod): String {
        val overloaded = (method.containingClass?.findMethodsByName(method.name, false)?.size ?: 1) > 1
        val params = method.parameterList.parameters
            .filterNot { it.name.startsWith("$") }
            .joinToString(", ") { if (overloaded) it.type.presentableText else it.name }
        return "${method.name}($params)"
    }

    /** 체인을 합친 힌트 라벨: "foo() → bar(x)" */
    fun labelOf(methods: List<PsiMethod>): String = methods.joinToString(" → ", transform = ::signatureOf)

    /**
     * [call] 뒤에 붙일 힌트가 가리키는 프로젝트 메서드들(체인 안쪽부터). 힌트를 붙이지 않으면 null.
     * [mergeChains] 이면 같은 줄의 체인 `a.foo().bar()` 는 가장 바깥 호출 뒤에 힌트 하나로 합친다.
     */
    fun hintTargets(
        call: UCallExpression,
        mergeChains: Boolean = InlineCallSettings.getInstance().state.mergeChains,
    ): List<PsiMethod>? {
        if (!mergeChains) return resolveProjectMethod(call)?.let(::listOf)
        if (outerChainCall(call) != null) return null
        return sameLineChain(call).mapNotNull(::resolveProjectMethod).ifEmpty { null }
    }

    /** 힌트 오프셋(호출식 끝)에서 힌트 대상 호출을 찾는다: (호출 범위, 대상 메서드들). 읽기 작업 안에서 호출. */
    fun hintAt(file: PsiFile, callEndOffset: Int): Pair<TextRange, List<PsiMethod>>? {
        if (callEndOffset <= 0) return null
        // 힌트는 호출식 끝에 붙으므로, 끝 오프셋이 같은 부모들 중 호출식을 찾는다.
        var element = file.findElementAt(callEndOffset - 1)
        while (element != null && element.textRange.endOffset == callEndOffset) {
            val call = toCall(element)
            if (call != null) return hintTargets(call)?.let { element.textRange to it }
            element = element.parent
        }
        return null
    }

    /** [call] 을 receiver 로 쓰는 같은 줄의 바깥 호출 (`a.foo().bar()` 에서 foo 에 대한 bar) */
    private fun outerChainCall(call: UCallExpression): UCallExpression? {
        var self: UElement = call
        var parent = call.uastParent
        // Kotlin/Java 모두 foo() 는 먼저 a.foo() 한정식의 selector 로 감싸진다.
        while (parent is UQualifiedReferenceExpression && parent.selector.sourcePsi == self.sourcePsi) {
            self = parent
            parent = parent.uastParent
        }
        val outer = when {
            parent is UQualifiedReferenceExpression && parent.receiver.sourcePsi == self.sourcePsi -> parent.selector as? UCallExpression
            parent is UCallExpression && innerCall(parent.receiver)?.sourcePsi == call.sourcePsi -> parent
            else -> null
        } ?: return null
        if (outer.kind != UastCallKind.METHOD_CALL || !onSameLine(call, outer)) return null
        return outer
    }

    /** [outermost] 부터 receiver 를 따라 같은 줄에 있는 체인 호출들. 안쪽(먼저 실행되는 쪽)부터. */
    private fun sameLineChain(outermost: UCallExpression): List<UCallExpression> {
        val chain = arrayListOf(outermost)
        while (true) {
            val inner = innerCall(chain.last().receiver) ?: break
            if (inner.kind != UastCallKind.METHOD_CALL || !onSameLine(inner, chain.last())) break
            chain += inner
        }
        return chain.asReversed()
    }

    private fun innerCall(expression: UExpression?): UCallExpression? = when (expression) {
        is UCallExpression -> expression
        is UQualifiedReferenceExpression -> expression.selector as? UCallExpression
        is UParenthesizedExpression -> innerCall(expression.expression)
        else -> null
    }

    /** 안쪽 호출 끝과 바깥 호출 끝 사이에 줄바꿈이 없는지 */
    private fun onSameLine(inner: UCallExpression, outer: UCallExpression): Boolean {
        val innerEnd = inner.sourcePsi?.textRange?.endOffset ?: return false
        val outerPsi = outer.sourcePsi ?: return false
        val outerEnd = outerPsi.textRange.endOffset
        if (innerEnd > outerEnd) return false
        return !StringUtil.containsLineBreak(outerPsi.containingFile.viewProvider.contents.subSequence(innerEnd, outerEnd))
    }

    /** 메서드 선언의 원문(하이라이팅/원본 위치 포함). 읽기 작업 안에서 호출. */
    fun body(method: PsiMethod): FunctionBody? = FunctionBody.of(declarationOf(method))

    fun bodyLines(method: PsiMethod): List<String>? = body(method)?.lines?.map { it.text }

    /**
     * 본문 원본 파일의 [offset] 에서 Cmd+클릭으로 이동할 위치. 읽기 작업 안에서 호출.
     * 참조면 resolve 결과로, 선언 이름이면 그 선언 자신으로 이동한다.
     */
    fun navigationTarget(project: Project, body: FunctionBody, offset: Int): Navigatable? {
        if (!body.file.isValid) return null
        val psiFile = PsiManager.getInstance(project).findFile(body.file) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        if (document.modificationStamp != body.modificationStamp) return null

        val reference = psiFile.findReferenceAt(offset)
        val target = reference?.resolve()
            ?: (reference as? PsiPolyVariantReference)?.multiResolve(false)?.firstNotNullOfOrNull { it.element }
            ?: psiFile.findElementAt(offset)?.parent?.takeIf {
                it is PsiNameIdentifierOwner && it.nameIdentifier?.textRange?.contains(offset) == true
            }
            ?: return null
        return descriptorOf(target)
    }

    /** [element] 선언 위치로 이동하는 Navigatable. 읽기 작업 안에서 호출. */
    fun descriptorOf(element: PsiElement): Navigatable? {
        val navigation = element.navigationElement
        val file = navigation.containingFile?.virtualFile ?: return null
        return OpenFileDescriptor(element.project, file, navigation.textOffset)
    }
}
