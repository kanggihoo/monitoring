"""32MB JFR 을 스트리밍으로 읽어 집계한다. print 출력 전체를 메모리에 올리지 않는다."""
import re, subprocess, collections, sys, os

OUT = "/home/ubuntu/it87/k6/out172"
def jdk(args):
    return ["docker", "run", "--rm", "-v", f"{OUT}:/out", "eclipse-temurin:21-jdk", "jfr"] + args

SUBSYS = [
    ("우리 코드", re.compile(r"com\.ssafy\.a605")),
    ("Jackson 직렬화", re.compile(r"fasterxml\.jackson|tools\.jackson")),
    ("Hibernate/JPA", re.compile(r"org\.hibernate|jakarta\.persistence")),
    ("Spring Security", re.compile(r"springframework\.security")),
    ("Session/Redis", re.compile(r"springframework\.session|io\.lettuce|RedisSession")),
    ("Spring MVC/Web", re.compile(r"springframework\.web|springframework\.boot\.web")),
    ("Tomcat", re.compile(r"apache\.(tomcat|catalina|coyote)")),
    ("PostgreSQL JDBC", re.compile(r"org\.postgresql")),
    ("HikariCP", re.compile(r"zaxxer\.hikari")),
    ("트랜잭션", re.compile(r"springframework\.transaction")),
    ("JFR 자신", re.compile(r"jdk\.jfr|jdk\.internal\.event")),
]
STRIP = re.compile(r" line: \d+$")

def analyse(phase):
    path = f"{OUT}/profile-{phase}.jfr"
    if not os.path.exists(path):
        print(f"== {phase}: 파일 없음"); return
    print(f"\n{'='*74}\n== {phase}\n{'='*74}")

    # 1) summary — 이벤트 종류별 건수
    out = subprocess.run(jdk(["summary", f"/out/profile-{phase}.jfr"]),
                         capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    print("[이벤트 건수]")
    for line in out.splitlines():
        if re.search(r"ExecutionSample|SocketRead|SocketWrite|GarbageCollection|ThreadPark|JavaMonitor|JavaErrorThrow", line):
            print("  " + line.strip())

    # 2) ExecutionSample 스트리밍 집계
    subsys = collections.Counter(); selftime = collections.Counter(); ours = collections.Counter()
    total = 0
    p = subprocess.Popen(jdk(["print", "--events", "jdk.ExecutionSample", f"/out/profile-{phase}.jfr"]),
                         stdout=subprocess.PIPE, text=True, encoding="utf-8", errors="replace", bufsize=1 << 20)
    frames, instack, top, ourframe = [], False, None, None
    for line in p.stdout:
        t = line.strip()
        if t.startswith("stackTrace = ["):
            instack, frames, top, ourframe = True, [], None, None
            continue
        if instack:
            if t == "]" or t.startswith("}"):
                instack = False
                if top:
                    total += 1
                    selftime[top] += 1
                    if ourframe:
                        ours[ourframe] += 1
                    blob = "\n".join(frames)
                    for label, pat in SUBSYS:
                        if pat.search(blob):
                            subsys[label] += 1
                continue
            frames.append(t)
            if top is None:
                top = STRIP.sub("", t)
            if ourframe is None and "com.ssafy.a605" in t:
                ourframe = STRIP.sub("", t)
    p.wait()

    if not total:
        print("  ExecutionSample 없음"); return
    print(f"\n[CPU 표본 {total:,} 개 · 10 ms 간격 → 약 {total*10/1000:.0f} 초 상당의 CPU]")
    print("\n[하위 시스템 — 스택에 등장한 표본 비율 (포괄, 중복 계산됨)]")
    for label, _ in SUBSYS:
        n = subsys[label]
        if n:
            print("   %-18s %7d  %5.1f%%" % (label, n, 100 * n / total))
    print("\n[자기 시간 상위 12]")
    for name, n in selftime.most_common(12):
        print("   %6d %5.1f%%  %s" % (n, 100 * n / total, name[:88]))
    print("\n[스택에서 가장 안쪽 우리 코드 상위 10]")
    for name, n in ours.most_common(10):
        print("   %6d %5.1f%%  %s" % (n, 100 * n / total, name[:88]))

for ph in ("before", "after"):
    analyse(ph)
