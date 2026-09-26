package demo

class Temperature(var celsius: Double) {
    /** 직접 작성한 getter/setter: Java 에서 getFahrenheit()/setFahrenheit() 호출에 힌트가 붙는다. */
    var fahrenheit: Double
        get() = celsius * 9 / 5 + 32
        set(value) {
            celsius = (value - 32) * 5 / 9
        }
}
