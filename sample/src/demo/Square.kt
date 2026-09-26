package demo

class Square(private val side: Double) : Shape {
    override fun area(): Double = side * side
}
