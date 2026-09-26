package com.github.sakur35a.functioninlineviewer

import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import junit.framework.TestCase

private val HINT_MARKER = Regex("""/\*<# (.*?) #>\*/""")

/**
 * [expected] 의 `/*<# ▶ label #>*/` 표시를 뺀 코드로 파일을 열고 하이라이팅(힌트 패스)을 돌린 뒤,
 * 붙은 호출부 힌트를 같은 표시로 되돌려 [expected] 와 비교한다.
 */
fun CodeInsightTestFixture.checkCallHints(fileName: String, expected: String) {
    configureByText(fileName, expected.replace(HINT_MARKER, ""))
    doHighlighting()
    TestCase.assertEquals(expected, textWithCallHints())
}

/** 에디터 문서에 호출부 힌트를 `/*<# ▶ label #>*/` 로 끼워 넣은 글 */
fun CodeInsightTestFixture.textWithCallHints(): String {
    val text = StringBuilder(editor.document.text)
    for (inlay in CallHints.hints(editor).sortedByDescending { it.offset }) {
        text.insert(inlay.offset, "/*<# ${inlay.renderer.text(inlay)} #>*/")
    }
    return text.toString()
}
