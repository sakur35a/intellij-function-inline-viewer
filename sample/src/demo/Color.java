package demo;

public enum Color {
    RED("#f00"),
    GREEN("#0f0"),
    BLUE("#00f");

    private final String hex;

    Color(String hex) {
        this.hex = hex;
    }
}
