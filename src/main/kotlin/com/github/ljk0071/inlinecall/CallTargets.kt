package com.github.ljk0071.inlinecall

import com.intellij.lang.Language
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
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

    /**
     * 접힌 상태에서 보여줄 "이름(파라미터...)".
     * Kotlin light method 의 합성 파라미터(확장 receiver `$this$..`, suspend `$completion`)는 뺀다.
     */
    fun signatureOf(method: PsiMethod): Pair<String, String> {
        val params = method.parameterList.parameters
            .filterNot { it.name.startsWith("$") }
            .joinToString(", ") { it.name }
        return method.name to "($params)"
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
        val navigation = target.navigationElement
        val file = navigation.containingFile?.virtualFile ?: return null
        return OpenFileDescriptor(project, file, navigation.textOffset)
    }
}
