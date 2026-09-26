package demo;

public class Main {

    public static void main(String[] args) {
        // 1단계 확인용: 아래 호출 뒤에 "▶ add(a, b)" 힌트가 보여야 한다.
        int sum = MathUtil.add(1, 2);

        // A-1 확인용: 펼친 본문 안의 add 를 Cmd+클릭하면 MathUtil.add 로 이동한다.
        int total = MathUtil.addAll(1, 2, 3);

        // A-2 확인용: 펼친 본문 안의 ▶ factorial / ▶ multiplyBy 를 클릭하면 한 단계 아래로 펼쳐진다.
        int f = MathUtil.factorial(5);

        // JDK 메서드에는 힌트가 붙지 않아야 한다.
        System.out.println(sum);
    }
}
