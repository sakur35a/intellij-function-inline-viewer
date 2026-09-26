package com.github.ljk0071.inlinecall

import com.intellij.openapi.diagnostic.Logger

/**
 * 성능 측정용 로그. 디버그 카테고리 `#com.github.ljk0071.inlinecall.perf` 가 켜져 있을 때만 시간을 잰다.
 * 한 줄 형식: `perf event=<이름> ms=<시간> <상세>` (idea.log 에서 grep/집계하기 쉽게 고정)
 */
object Perf {

    @PublishedApi
    internal val LOG: Logger = Logger.getInstance("#com.github.ljk0071.inlinecall.perf")

    val enabled: Boolean get() = LOG.isDebugEnabled

    /** [block] 의 실행 시간이 [thresholdMs] 이상이면 기록한다. */
    inline fun <T> measure(event: String, thresholdMs: Double = 0.0, detail: () -> String = { "" }, block: () -> T): T {
        if (!LOG.isDebugEnabled) return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            val ms = (System.nanoTime() - start) / 1e6
            if (ms >= thresholdMs) log(event, ms, detail())
        }
    }

    /** 비동기 작업처럼 시작/끝이 떨어져 있을 때 [startNanos] 부터의 시간을 기록한다. */
    fun since(event: String, startNanos: Long, detail: String = "") {
        if (LOG.isDebugEnabled) log(event, (System.nanoTime() - startNanos) / 1e6, detail)
    }

    fun log(event: String, ms: Double, detail: String) {
        LOG.debug("perf event=$event ms=${"%.2f".format(ms)} $detail".trimEnd())
    }
}
