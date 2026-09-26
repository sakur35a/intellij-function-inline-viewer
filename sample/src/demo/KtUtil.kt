package demo

/**
 * KDoc 도 펼친 본문에서 제외된다.
 */
fun multiply(a: Int, b: Int): Int = a * b

fun Int.twice(): Int {
    return this * 2
}

fun repeatTimes(n: Int, block: (Int) -> Unit) {
    for (i in 0 until n) block(i)
}

fun quadruple(x: Int): Int = x.twice().twice()
