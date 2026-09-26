package demo;

public class Main {

    public static void main(String[] args) {
        // 1단계 확인용: 아래 호출 뒤에 "▶ add(a, b)" 힌트가 보여야 한다.
        int sum = MathUtil.add(1, 2);

        // JDK 메서드에는 힌트가 붙지 않아야 한다.
        System.out.println(sum);
    }
}
