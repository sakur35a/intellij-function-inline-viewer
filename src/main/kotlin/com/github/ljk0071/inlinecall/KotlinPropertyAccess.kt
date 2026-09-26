package com.github.ljk0071.inlinecall

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.LightClassUtil
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.idea.stubindex.KotlinPropertyShortNameIndex
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgumentName
import java.util.concurrent.ConcurrentHashMap

/**
 * Kotlin 프로퍼티 접근(`t.fahrenheit`, `t.fahrenheit = x`)에 붙일 힌트. get()/set() 을 직접 작성했을 때만.
 * Kotlin 요소에서만 호출되므로(CallTargets.hintFor 가 클래스 이름으로 거른다) Kotlin 플러그인이 없으면 로드되지 않는다.
 */
internal object KotlinPropertyAccess {

    fun hint(element: PsiElement): CallTargets.Hint? {
        val reference = element as? KtNameReferenceExpression ?: return null
        if (!isValueReference(reference)) return null
        val name = reference.getReferencedName()
        val write = isAssignmentTarget(reference)
        // 파일의 모든 이름 참조를 resolve 하면 비싸므로, 그 이름의 프로퍼티 중 해당 접근자를 직접 작성한 것이
        // 프로젝트에 있을 때만 resolve 한다(스텁 인덱스만 보므로 resolve/light 클래스 생성이 없다).
        if (!hasWrittenAccessor(reference.project, name, write)) return null

        val property = reference.mainReference.resolve() as? KtProperty ?: return null
        // 같은 이름의 다른 프로퍼티일 수 있으니 light 메서드를 만들기 전에 이 프로퍼티의 접근자를 확인한다.
        if ((if (write) property.setter else property.getter)?.hasBody() != true) return null
        val accessors = LightClassUtil.getLightClassPropertyMethods(property)
        val method = (if (write) accessors.setter else accessors.getter) ?: return null
        if (!CallTargets.isProjectDeclaration(method)) return null
        return CallTargets.Hint(reference.textRange, listOf(method), if (write) "set $name" else name, listOf(reference.textRange))
    }

    /** `x.p = v`, `p += v` 처럼 대입의 왼쪽이면 setter 로 본다. */
    private fun isAssignmentTarget(reference: KtNameReferenceExpression): Boolean {
        val expression = (reference.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == reference } ?: reference
        val assignment = expression.parent as? KtBinaryExpression ?: return false
        return assignment.left == expression && assignment.operationToken in KtTokens.ALL_ASSIGNMENTS
    }

    /** 값(프로퍼티) 참조가 될 수 있는 위치인지. import/package, 타입, 함수 이름, `::` 참조, 이름 붙은 인자는 제외. */
    private fun isValueReference(reference: KtNameReferenceExpression): Boolean {
        val parent = reference.parent
        if ((parent as? KtCallExpression)?.calleeExpression == reference) return false
        if (parent is KtUserType || parent is KtValueArgumentName || parent is KtCallableReferenceExpression) return false
        return PsiTreeUtil.getParentOfType(reference, KtImportDirective::class.java, KtPackageDirective::class.java) == null
    }

    /**
     * 이름이 [name] 인 프로젝트 프로퍼티 중 get()([write] 면 set())을 직접 작성한 것이 있는지.
     * 스텁 인덱스와 스텁만 읽는다. PSI 가 바뀔 때까지 캐시한다.
     */
    private fun hasWrittenAccessor(project: Project, name: String, write: Boolean): Boolean {
        val cache = CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(ConcurrentHashMap<String, Boolean>(), PsiModificationTracker.MODIFICATION_COUNT)
        }
        return cache.getOrPut(if (write) "set:$name" else "get:$name") {
            StubIndex.getElements(
                KotlinPropertyShortNameIndex.Helper.indexKey, name, project,
                GlobalSearchScope.projectScope(project), KtNamedDeclaration::class.java,
            ).any { declaration ->
                val accessor = (declaration as? KtProperty)?.let { if (write) it.setter else it.getter }
                accessor?.hasBody() == true
            }
        }
    }
}
