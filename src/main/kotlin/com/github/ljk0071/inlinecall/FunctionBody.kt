package com.github.ljk0071.inlinecall

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace

/** 본문의 한 조각. [sourceOffset] 은 원본 파일에서 [text] 가 시작하는 위치. */
class BodyToken(
    val text: String,
    val sourceOffset: Int,
    val keys: Array<TextAttributesKey>,
    /** 탭을 공백으로 바꾸지 않아 원본과 글자 단위로 대응하는지 */
    private val exact: Boolean = true,
) {
    /** Cmd+클릭 대상 후보: 키워드가 아닌 식별자 */
    val isNavigable: Boolean =
        text.isNotEmpty() && Character.isJavaIdentifierStart(text[0]) && text.all(Character::isJavaIdentifierPart) &&
            keys.none { it.inheritsFrom(DefaultLanguageHighlighterColors.KEYWORD) }

    fun sourceOffsetAt(index: Int): Int = if (exact) sourceOffset + index.coerceIn(0, text.length) else sourceOffset

    fun withText(newText: String) = BodyToken(newText, sourceOffset, keys, exact)
}

class BodyLine(val tokens: List<BodyToken>) {
    val text: String get() = tokens.joinToString("") { it.text }
}

/**
 * 펼쳐서 보여줄 함수 원문. 원본 위치를 함께 들고 있어서 Cmd+클릭으로 이동할 수 있다.
 * [modificationStamp] 이후 원본 파일이 바뀌면 오프셋이 어긋나므로 이동하지 않는다.
 */
class FunctionBody(
    val file: VirtualFile,
    val modificationStamp: Long,
    val lines: List<BodyLine>,
) {
    companion object {
        /**
         * [declaration] 의 원문을 렉서 하이라이팅과 함께 줄 단위 토큰으로 만든다. 읽기 작업 안에서 호출.
         * 앞쪽 문서 주석(Javadoc/KDoc)은 빼고, 선언부 들여쓰기만큼 공통 들여쓰기를 제거한다.
         */
        fun of(declaration: PsiElement): FunctionBody? {
            val psiFile = declaration.containingFile ?: return null
            val file = psiFile.virtualFile ?: return null
            val document = PsiDocumentManager.getInstance(psiFile.project).getDocument(psiFile) ?: return null

            val firstCode = generateSequence(declaration.firstChild) { it.nextSibling }
                .firstOrNull { it !is PsiComment && it !is PsiWhiteSpace && it.textLength > 0 }
            val start = firstCode?.textRange?.startOffset ?: declaration.textRange.startOffset
            val end = declaration.textRange.endOffset
            if (start >= end) return null

            val chars = document.immutableCharSequence
            val highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(psiFile.project, file)
            highlighter.setText(chars)

            val firstLine = document.getLineNumber(start)
            val indent = start - document.getLineStartOffset(firstLine)
            val lines = (firstLine..document.getLineNumber(end)).map { line ->
                val lineEnd = minOf(document.getLineEndOffset(line), end)
                var from = if (line == firstLine) start else document.getLineStartOffset(line)
                if (line != firstLine) {
                    val limit = minOf(from + indent, lineEnd)
                    while (from < limit && (chars[from] == ' ' || chars[from] == '\t')) from++
                }
                val tokens = ArrayList<BodyToken>()
                val it = highlighter.createIterator(from)
                while (!it.atEnd() && it.start < lineEnd) {
                    val s = maxOf(it.start, from)
                    val e = minOf(it.end, lineEnd)
                    if (s < e) {
                        val raw = chars.substring(s, e)
                        tokens += BodyToken(raw.replace("\t", "    "), s, it.textAttributesKeys, exact = '\t' !in raw)
                    }
                    it.advance()
                }
                BodyLine(trimEnd(tokens))
            }
            return FunctionBody(file, document.modificationStamp, lines)
        }

        private fun CharSequence.substring(start: Int, end: Int): String = subSequence(start, end).toString()

        private fun trimEnd(tokens: MutableList<BodyToken>): List<BodyToken> {
            while (tokens.isNotEmpty()) {
                val last = tokens.last()
                val trimmed = last.text.trimEnd()
                if (trimmed == last.text) break
                tokens.removeAt(tokens.lastIndex)
                if (trimmed.isNotEmpty()) {
                    tokens += last.withText(trimmed)
                    break
                }
            }
            return tokens
        }
    }
}

private fun TextAttributesKey.inheritsFrom(target: TextAttributesKey): Boolean =
    generateSequence(this) { it.fallbackAttributeKey }.any { it == target }
