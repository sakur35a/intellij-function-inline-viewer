package com.github.sakur35a.functioninlineviewer

import com.intellij.codeHighlighting.RainbowHighlighter
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

/** 이 플러그인의 색 키. 기본값은 IDE 의 비슷한 키를 따르고, Color Scheme 설정에서 테마별로 바꿀 수 있다. */
object InlineCallColors {

    /** 힌트/펼친 본문에 마우스를 올렸을 때 짝이 되는 호출된 이름 강조 */
    val CALLED_NAME: TextAttributesKey =
        createTextAttributesKey("INLINE_CALL_CALLED_NAME", EditorColors.IDENTIFIER_UNDER_CARET_ATTRIBUTES)

    /** 한 줄의 여러 호출을 구분하는 색(힌트, 호출된 이름, 펼친 본문 배경). 기본은 IDE 무지개 색. */
    val CALL_COLORS: List<TextAttributesKey> = RainbowHighlighter.RAINBOW_COLOR_KEYS.mapIndexed { index, key ->
        createTextAttributesKey("INLINE_CALL_COLOR_${index + 1}", key)
    }
}

/** Settings > Editor > Color Scheme > Function Inline Viewer */
class InlineCallColorSettingsPage : ColorSettingsPage {

    override fun getDisplayName(): String = InlineCallBundle.message("settings.display.name")

    override fun getIcon(): Icon? = null

    override fun getHighlighter(): SyntaxHighlighter = PlainSyntaxHighlighter()

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = arrayOf(
        AttributesDescriptor(InlineCallBundle.message("colors.called.name"), InlineCallColors.CALLED_NAME),
        *InlineCallColors.CALL_COLORS.mapIndexed { index, key ->
            AttributesDescriptor(InlineCallBundle.message("colors.call.color", index + 1), key)
        }.toTypedArray(),
    )

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDemoText(): String = """
        // Hovering a hint or an expanded body highlights the called name:
        int total = <name>add</name>(1, 2);

        // Calls sharing a line get their own colors (hint, name, expanded body background):
        return <c1>multiplyBy</c1>(n, <c2>factorial</c2>(n - 1));
        <c3>third</c3>() <c4>fourth</c4>() <c5>fifth</c5>()
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> =
        mapOf("name" to InlineCallColors.CALLED_NAME) +
            InlineCallColors.CALL_COLORS.mapIndexed { index, key -> "c${index + 1}" to key }
}
