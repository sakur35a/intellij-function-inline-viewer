package demo

fun main() {
    // 2단계 확인용: 각 호출 뒤에 힌트가 보여야 한다.
    val product = multiply(3, 4)       // ▶ multiply(a, b)
    val doubled = product.twice()      // 확장 함수: ▶ twice()
    val sum = MathUtil.add(1, 2)       // Kotlin -> Java: ▶ add(a, b)

    repeatTimes(2) { i ->              // ▶ repeatTimes(n, block)
        println(multiply(i, doubled))  // 람다 안: multiply 에만 힌트, println 에는 없음
    }

    // A-2 확인용: 펼친 본문 안의 ▶ twice() 를 다시 펼칠 수 있다.
    val q = quadruple(sum)

    // 프로퍼티 접근: 직접 작성한 get()/set() 이 있을 때만 힌트(읽기 ▶ fahrenheit, 대입 ▶ set fahrenheit).
    val temp = Temperature(20.0)
    val f = temp.fahrenheit
    temp.fahrenheit = 212.0
    val c = temp.celsius   // 기본 접근자: 힌트 없음

    // 표준 라이브러리 호출에는 힌트가 없어야 한다.
    listOf(sum).forEach { println(it) }
}
