"""소켓 대기를 상대 포트로 갈라 집계한다. 5432=PostgreSQL, 6379=Redis, 나머지=HTTP(클라이언트)."""
import re, subprocess, collections, os

OUT = "/home/ubuntu/it87/k6/out172"
def jdk(args):
    return ["docker", "run", "--rm", "-v", f"{OUT}:/out", "eclipse-temurin:21-jdk", "jfr"] + args

def label(port):
    return {"5432": "PostgreSQL", "6379": "Redis(세션)"}.get(port, "HTTP(k6 클라이언트)")

DUR = re.compile(r"duration\s*=\s*([\d.]+)\s*(ms|s|us|ns)\b")
PORT = re.compile(r"port\s*=\s*(\d+)")
UNIT = {"ns": 1e-6, "us": 1e-3, "ms": 1.0, "s": 1000.0}

for phase in ("before", "after"):
    path = f"{OUT}/profile-{phase}.jfr"
    if not os.path.exists(path):
        continue
    print(f"\n{'='*66}\n== {phase}\n{'='*66}")
    grand_n = grand_ms = 0
    for ev in ("jdk.SocketRead", "jdk.SocketWrite"):
        cnt, ms = collections.Counter(), collections.Counter()
        p = subprocess.Popen(jdk(["print", "--events", ev, f"/out/profile-{phase}.jfr"]),
                             stdout=subprocess.PIPE, text=True, encoding="utf-8",
                             errors="replace", bufsize=1 << 20)
        cur_d, cur_p = None, None
        for line in p.stdout:
            t = line.strip()
            if t.startswith(ev + " {"):
                cur_d, cur_p = None, None
            m = DUR.search(t)
            if m and cur_d is None:
                cur_d = float(m.group(1)) * UNIT[m.group(2)]
            m = PORT.search(t)
            if m and cur_p is None:
                cur_p = m.group(1)
            if t == "}" and cur_d is not None:
                k = label(cur_p or "")
                cnt[k] += 1; ms[k] += cur_d
                cur_d, cur_p = None, None
        p.wait()
        tn, tms = sum(cnt.values()), sum(ms.values())
        grand_n += tn; grand_ms += tms
        print(f"\n[{ev.split('.')[-1]}]  {tn:,} 건 · 누적 {tms:,.0f} ms")
        for k in sorted(cnt, key=lambda x: -ms[x]):
            print("   %-22s %7d 건  누적 %9.0f ms  건당 %6.3f ms" % (k, cnt[k], ms[k], ms[k] / cnt[k]))
    print(f"\n  소켓 합계: {grand_n:,} 건 · {grand_ms:,.0f} ms")
