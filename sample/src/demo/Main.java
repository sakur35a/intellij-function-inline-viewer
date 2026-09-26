package demo;

public class Main {

    public static void main(String[] args) {
        // 1단계 확인용: 아래 호출 뒤에 "▶ add(a, b)" 힌트가 보여야 한다.
        int sum = MathUtil.add(1, 2);

        // A-1 확인용: 펼친 본문 안의 add 를 Cmd+클릭하면 MathUtil.add 로 이동한다.
        int total = MathUtil.addAll(1, 2, 3);

        // A-2 확인용: 펼친 본문 안의 ▶ factorial / ▶ multiplyBy 를 클릭하면 한 단계 아래로 펼쳐진다.
        int f = MathUtil.factorial(5);

        // 4단계 확인용
        // 같은 줄 체인: 끝에 "▶ add(String) → space() → add(int) → build()" 하나만 붙는다.
        String text = new Builder().add("a").space().add(1).build();
        // 줄마다 끊은 체인: 호출마다 힌트가 붙는다.
        String text2 = new Builder()
                .add("b")
                .build();
        // 인터페이스 메서드: 펼치면 선언 아래에 구현체 목록(▶ Circle.area(), ▶ Square.area())이 보인다.
        // 람다 구현(아래 () -> 1.0)은 목록에 나오지 않는다.
        Shape shape = () -> 1.0;
        double area = shape.area();
        String description = shape.describe();

        // enum values(): 펼치면 상수 목록. Kotlin 직접 작성 getter/setter 에는 힌트, 기본 getter(getCelsius)에는 없음.
        Color[] colors = Color.values();
        Temperature t = new Temperature(20);
        double f = t.getFahrenheit();
        t.setFahrenheit(100);
        double c = t.getCelsius();

        // JDK 메서드에는 힌트가 붙지 않아야 한다.
        System.out.println(sum);
    }
}
