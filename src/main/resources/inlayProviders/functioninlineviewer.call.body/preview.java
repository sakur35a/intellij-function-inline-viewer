class Preview {
    int add(int a, int b) {
        return a + b;
    }

    void run() {
        int sum = add(1, 2)/*<# ▶ add(a, b) #>*/;
    }
}
