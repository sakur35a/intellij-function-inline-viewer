package com.github.sakur35a.functioninlineviewer

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.InlineCallBundle"

// DynamicBundle(String) 은 2026.3 에서 deprecated 다. 번들을 읽을 클래스 로더를 알려 주는 생성자를 쓴다.
object InlineCallBundle : DynamicBundle(InlineCallBundle::class.java, BUNDLE) {
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}
