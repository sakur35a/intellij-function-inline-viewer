package demo

fun main() {
    // 2단계 확인용: 각 호출 뒤에 힌트가 보여야 한다.
    val product = multiply(3, 4)       // ▶ multiply(a, b)
    val doubled = product.twice()      // 확장 함수: ▶ twice()
    val sum = MathUtil.add(1, 2)       // Kotlin -> Java: ▶ add(a, b)

    repeatTimes(2) { i ->              // ▶ repeatTimes(n, block)
        println(multiply(i, doubled))  // 람다 안: multiply 에만 힌트, println 에는 없음
    }

    // 표준 라이브러리 호출에는 힌트가 없어야 한다.
    listOf(sum).forEach { println(it) }
}
