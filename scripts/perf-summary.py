#!/usr/bin/env python3
"""idea.log 의 `perf event=... ms=...` 줄을 이벤트별로 집계한다.

사용법: scripts/perf-summary.py [idea.log 경로 ...]
기본 경로는 -Pperf 로 띄운 샌드박스의 로그다.
"""
import glob
import re
import statistics
import sys
from collections import defaultdict

DEFAULT = ".intellijPlatform/sandbox/*/*/log_runIde/idea.log*"
LINE = re.compile(r"perf event=(\S+) ms=([\d.]+) ?(.*)$")


def main():
    paths = sys.argv[1:] or sorted(glob.glob(DEFAULT))
    events = defaultdict(list)
    for path in paths:
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                m = LINE.search(line)
                if m:
                    events[m.group(1)].append((float(m.group(2)), m.group(3)))
    if not events:
        print("perf 로그가 없습니다. ./gradlew runIde -Pperf 로 띄웠는지 확인하세요.")
        return

    print(f"{'event':<16}{'count':>7}{'p50':>9}{'p95':>9}{'max':>9}{'total':>10}  (ms)")
    for name in sorted(events):
        times = sorted(t for t, _ in events[name])
        p95 = times[min(len(times) - 1, int(len(times) * 0.95))]
        print(f"{name:<16}{len(times):>7}{statistics.median(times):>9.1f}{p95:>9.1f}{times[-1]:>9.1f}{sum(times):>10.1f}")

    print("\n가장 느린 10건")
    slowest = sorted(((t, name, d) for name, items in events.items() for t, d in items), reverse=True)[:10]
    for t, name, detail in slowest:
        print(f"{t:>9.1f}  {name:<16}{detail}")


if __name__ == "__main__":
    main()
