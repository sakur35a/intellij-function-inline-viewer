package demo;

/** 4단계 확인용: 인터페이스 메서드는 선언만 보인다. */
public interface Shape {
    double area();

    /** 본문이 있는 default 메서드: 펼치면 "// overrides: ▶ find" 로 재정의를 찾을 수 있다. */
    default String describe() {
        return "shape";
    }
}
