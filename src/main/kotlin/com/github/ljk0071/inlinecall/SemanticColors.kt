package com.github.ljk0071.inlinecall

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UField
import org.jetbrains.uast.ULocalVariable
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.toUElementOfType

/**
 * 렉서가 칠하지 못하는 식별자(메서드 호출, 필드, 파라미터, 지역 변수, 클래스)의 색상 키를 정한다.
 * 참조를 resolve 한 대상 종류를 UAST 로 판별해서 Java/Kotlin 모두 공통 키
 * ([com.intellij.openapi.editor.DefaultLanguageHighlighterColors])로 매핑한다. 읽기 작업 안에서 호출.
 */
object SemanticColors {

    fun keyAt(file: PsiFile, offset: Int): TextAttributesKey? {
        val reference = file.findReferenceAt(offset)
        if (reference != null) {
            val target = reference.resolve()
                ?: (reference as? PsiPolyVariantReference)?.multiResolve(false)?.firstNotNullOfOrNull { it.element }
                ?: return null
            return keyFor(target, isDeclaration = false)
        }
        // 참조가 아니면 선언 이름(메서드/파라미터/변수 선언)인지 확인한다.
        val owner = file.findElementAt(offset)?.parent as? PsiNameIdentifierOwner ?: return null
        if (owner.nameIdentifier?.textRange?.contains(offset) != true) return null
        return keyFor(owner, isDeclaration = true)
    }

    private fun keyFor(target: PsiElement, isDeclaration: Boolean): TextAttributesKey? =
        // Kotlin 생성자 프로퍼티(`class Box(val width: Int)`)는 파라미터이면서 필드다. IDE 처럼 필드로 본다.
        when (val element = target.toUElementOfType<UField>() ?: target.toUElement()) {
            is UMethod -> when {
                element.isConstructor -> Colors.CLASS_REFERENCE
                isDeclaration -> Colors.FUNCTION_DECLARATION
                // Kotlin 최상위 함수도 JVM 에서는 static 이지만 IDE 는 일반 함수 호출 색으로 칠한다.
                element.isStatic && target.language == JavaLanguage.INSTANCE -> Colors.STATIC_METHOD
                else -> Colors.FUNCTION_CALL
            }
            is UParameter -> Colors.PARAMETER
            is ULocalVariable -> Colors.LOCAL_VARIABLE
            is UField -> when {
                element.isStatic && element.isFinal -> Colors.CONSTANT
                element.isStatic -> Colors.STATIC_FIELD
                else -> Colors.INSTANCE_FIELD
            }
            is UClass -> if (element.isInterface) Colors.INTERFACE_NAME else Colors.CLASS_REFERENCE
            else -> null
        }
}
