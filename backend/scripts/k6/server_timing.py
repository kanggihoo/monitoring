import re, json, pathlib
OUT = pathlib.Path("/home/ubuntu/it87/k6/out172")

def parse(f):
    sums, counts = {}, {}
    for line in (OUT / f).read_text().splitlines():
        m = re.match(r'http_server_requests_seconds_(sum|count)\{.*uri="([^"]+)".*\}\s+([\d.eE+-]+)', line)
        if m:
            (sums if m.group(1) == "sum" else counts)[m.group(2)] = float(m.group(3))
    return sums, counts

print("=== 서버가 스스로 잰 처리 시간 (측정 창 안에서만, pre/post 차분)")
srv = {}
for phase in ("before", "after"):
    s0, c0 = parse(f"http-{phase}-pre.txt")
    s1, c1 = parse(f"http-{phase}-post.txt")
    print(f"-- {phase}")
    srv[phase] = {}
    for uri in sorted(set(s1) | set(s0)):
        if "api/v1" not in uri:
            continue
        ds, dc = s1.get(uri, 0) - s0.get(uri, 0), c1.get(uri, 0) - c0.get(uri, 0)
        if dc > 0:
            srv[phase][uri] = ds / dc * 1000
            print("   %-30s count=%-6d 서버측 평균=%6.2f ms" % (uri, dc, ds / dc * 1000))

print("\n=== k6 가 잰 클라이언트 왕복 (같은 창)")
NAME2URI = {"GET /accounts/me": "/api/v1/accounts/me", "GET /market/prices": "/api/v1/market/prices",
            "GET /news/ticker (control)": "/api/v1/news/ticker"}
for phase in ("before", "after"):
    d = json.loads((OUT / f"summary-{phase}.json").read_text())["metrics"]
    print(f"-- {phase}")
    for k, v in sorted(d.items()):
        if k.startswith("http_req_duration{name"):
            name = k[19:-1]
            uri = NAME2URI.get(name)
            s = srv[phase].get(uri)
            gap = ("  서버 %5.2f ms → 밖에서 %+6.2f ms" % (s, v["avg"] - s)) if s else ""
            print("   %-30s k6 평균=%6.2f p95=%7.2f%s" % (name, v["avg"], v["p(95)"], gap))
    print("   http_reqs=%d rate=%.1f/s" % (d["http_reqs"]["count"], d["http_reqs"]["rate"]))
