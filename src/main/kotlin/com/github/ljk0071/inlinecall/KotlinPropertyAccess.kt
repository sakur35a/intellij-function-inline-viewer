package com.github.ljk0071.inlinecall

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.kotlin.asJava.LightClassUtil
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import java.util.concurrent.ConcurrentHashMap

/**
 * Kotlin 프로퍼티 접근(`t.fahrenheit`, `t.fahrenheit = x`)에 붙일 힌트. get()/set() 을 직접 작성했을 때만.
 * Kotlin 요소에서만 호출되므로(CallTargets.hintFor 가 클래스 이름으로 거른다) Kotlin 플러그인이 없으면 로드되지 않는다.
 */
internal object KotlinPropertyAccess {

    fun hint(element: PsiElement): CallTargets.Hint? {
        val reference = element as? KtNameReferenceExpression ?: return null
        // 함수 호출의 이름 부분은 호출식 쪽에서 처리한다.
        if ((reference.parent as? KtCallExpression)?.calleeExpression == reference) return null
        val name = reference.getReferencedName()
        val write = isAssignmentTarget(reference)
        val accessorName = if (write) JvmAbi.setterName(name) else JvmAbi.getterName(name)
        // 파일의 모든 이름 참조를 resolve 하면 비싸므로, 그 이름의 직접 작성한 접근자가 프로젝트에 있을 때만 resolve 한다.
        if (!hasWrittenAccessorNamed(reference.project, accessorName)) return null

        val property = reference.mainReference.resolve() as? KtProperty ?: return null
        val accessors = LightClassUtil.getLightClassPropertyMethods(property)
        val method = (if (write) accessors.setter else accessors.getter) ?: return null
        if (!CallTargets.isProjectDeclaration(method)) return null
        return CallTargets.Hint(reference.textRange, listOf(method), if (write) "set $name" else name)
    }

    /** `x.p = v`, `p += v` 처럼 대입의 왼쪽이면 setter 로 본다. */
    private fun isAssignmentTarget(reference: KtNameReferenceExpression): Boolean {
        val expression = (reference.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == reference } ?: reference
        val assignment = expression.parent as? KtBinaryExpression ?: return false
        return assignment.left == expression && assignment.operationToken in KtTokens.ALL_ASSIGNMENTS
    }

    /** 이름이 [accessorName] 인 메서드 중 직접 작성한 Kotlin 접근자(또는 프로젝트 메서드)가 있는지. PSI 가 바뀔 때까지 캐시한다. */
    private fun hasWrittenAccessorNamed(project: Project, accessorName: String): Boolean {
        val cache = CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(ConcurrentHashMap<String, Boolean>(), PsiModificationTracker.MODIFICATION_COUNT)
        }
        return cache.getOrPut(accessorName) {
            PsiShortNamesCache.getInstance(project)
                .getMethodsByName(accessorName, GlobalSearchScope.projectScope(project))
                .any { CallTargets.isProjectDeclaration(it) }
        }
    }
}
