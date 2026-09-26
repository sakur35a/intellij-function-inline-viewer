package demo;

/** 4단계 확인용: 체인 호출 / 오버로드 */
public class Builder {
    private final StringBuilder sb = new StringBuilder();

    public Builder add(String s) {
        sb.append(s);
        return this;
    }

    public Builder add(int n) {
        sb.append(n);
        return this;
    }

    public Builder space() {
        return add(" ");
    }

    public String build() {
        return sb.toString();
    }
}
