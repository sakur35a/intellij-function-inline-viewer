package demo;

public class MathUtil {

    /**
     * Javadoc 은 펼친 본문에서 제외된다.
     */
    public static int add(int a, int b) {
        int result = a + b;
        return result;
    }

    public static int addAll(int... values) {
        int sum = 0;
        for (int v : values) {
            sum = add(sum, v);
        }
        return sum;
    }

    public static int factorial(int n) {
        return n <= 1 ? 1 : multiplyBy(n, factorial(n - 1));
    }

    private static int multiplyBy(int a, int b) {
        return a * b;
    }
}
