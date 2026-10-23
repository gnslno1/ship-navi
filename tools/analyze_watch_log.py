"""워치 시험 앱 기록(CSV) 분석 → HTML 보고서.

사용법:
    python tools/analyze_watch_log.py log_20261023_190000.csv
    python tools/analyze_watch_log.py log.csv --stationary     # 정지 시험으로 강제 분석

같은 폴더에 log_….html 보고서를 만든다 (브라우저로 열기). 파이썬 기본 기능만 쓴다.

정지 시험(워치를 한자리에 둔 기록)이면 GPS 노이즈를 재서, 앱의 상관 오차 바닥값(FLOOR_K)이
실제 기기에 맞는지 확인하고 권장값을 알려준다.
"""
import argparse
import csv
import html
import math
import statistics
import sys
from datetime import datetime
from pathlib import Path

KN = 1.943844
R_EARTH = 6371008.8
DEG = math.pi / 180
FLOOR_K = 0.0171     # app.js / Drift.kt와 같은 값
FLOOR_P = 0.66
EARTH_FIELD = 50.0   # 한국 지구 자기장 약 50µT
NUM = ["lat", "lon", "acc_m", "fix_age_s", "gps_speed_mps", "gps_bearing", "sats_used", "sats_visible",
       "heading_true", "heading_acc", "tilt", "mag_uT", "mag_acc", "drift_kn", "drift_bearing", "drift_se_mps", "battery"]


def load(path):
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            row = {"time": datetime.fromisoformat(r["time"])}
            for k in NUM:
                v = r.get(k, "")
                row[k] = float(v) if v not in ("", None) else None
            rows.append(row)
    if not rows:
        sys.exit("기록이 비어 있어요.")
    t0 = rows[0]["time"]
    for r in rows:
        r["min"] = (r["time"] - t0).total_seconds() / 60
    return rows


def vals(rows, key):
    return [r[key] for r in rows if r[key] is not None]


def pct(xs, q):
    xs = sorted(xs)
    if not xs:
        return None
    i = (len(xs) - 1) * q / 100
    lo, hi = math.floor(i), math.ceil(i)
    return xs[lo] + (xs[hi] - xs[lo]) * (i - lo)


def linfit(xs, ys):
    """기울기, 절편"""
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    if sxx == 0:
        return 0.0, my
    b = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx
    return b, my - b * mx


def fresh_fixes(rows):
    """새로 받은 GPS 위치만 (같은 위치가 매초 반복 기록되므로 fix_age로 거름) → (초, x m, y m, acc)"""
    out, ref, last = [], None, None
    for r in rows:
        if r["lat"] is None or r["fix_age_s"] is None or r["fix_age_s"] > 1.5:
            continue
        key = (r["lat"], r["lon"])
        if key == last:
            continue
        last = key
        if ref is None:
            ref = r
        kx = R_EARTH * DEG * math.cos(ref["lat"] * DEG)
        out.append((r["min"] * 60, (r["lon"] - ref["lon"]) * kx, (r["lat"] - ref["lat"]) * R_EARTH * DEG, r["acc_m"] or 5))
    return out


def window_fits(fixes, window, step=10):
    """window초 구간을 step초씩 밀며 직선 맞춤 → [(vx, vy, seWhite, accMean)]"""
    out = []
    if not fixes:
        return out
    t_start, t_end = fixes[0][0], fixes[-1][0]
    t = t_start
    while t + window <= t_end:
        pts = [f for f in fixes if t <= f[0] <= t + window]
        if len(pts) >= window * 0.7:
            n = len(pts)
            tm = sum(p[0] for p in pts) / n
            xm = sum(p[1] for p in pts) / n
            ym = sum(p[2] for p in pts) / n
            stt = sum((p[0] - tm) ** 2 for p in pts)
            vx = sum((p[0] - tm) * (p[1] - xm) for p in pts) / stt
            vy = sum((p[0] - tm) * (p[2] - ym) for p in pts) / stt
            srr = sum((p[1] - xm - vx * (p[0] - tm)) ** 2 + (p[2] - ym - vy * (p[0] - tm)) ** 2 for p in pts)
            se = math.sqrt(srr / (2 * (n - 2)) / stt)
            out.append((vx, vy, se, sum(max(p[3], 3) for p in pts) / n))
        t += step
    return out


# ---------------------------------------------------------------- SVG 그래프

COLORS = ["#d98a00", "#2b7bd6", "#2e9e5b", "#c94c4c", "#8a5cc2"]


def nice_step(raw):
    """눈금 간격을 1·2·5 × 10^k로 맞춘다 → (간격, 소수 자리수)"""
    k = math.floor(math.log10(raw))
    step = next(m for m in (1, 2, 5, 10) if m * 10 ** k >= raw) * 10 ** k
    return step, max(0, -math.floor(math.log10(step)))


def chart(title, series, unit="", band=None, scatter=False, y_range=None, height=200):
    """series: [(이름, [(분, 값)], 색)]"""
    w, h, ml, mr, mt, mb = 760, height, 52, 14, 26, 30
    pts = [p for _, s, _ in series for p in s]
    if not pts:
        return f"<h3>{html.escape(title)}</h3><p class='muted'>값 없음</p>"
    x0, x1 = min(p[0] for p in pts), max(p[0] for p in pts)
    if y_range:
        y0, y1 = y_range
    else:
        y0, y1 = min(p[1] for p in pts), max(p[1] for p in pts)
        if band:
            y0, y1 = min(y0, band[0]), max(y1, band[1])
        pad = (y1 - y0) * 0.08 or 1
        y0, y1 = y0 - pad, y1 + pad
    x1 = x1 if x1 > x0 else x0 + 1
    sx = lambda x: ml + (x - x0) / (x1 - x0) * (w - ml - mr)
    sy = lambda y: mt + (1 - (y - y0) / (y1 - y0)) * (h - mt - mb)
    out = [f'<svg viewBox="0 0 {w} {h}" class="chart" role="img" aria-label="{html.escape(title)}">']
    if band:
        out.append(f'<rect x="{ml}" y="{sy(band[1]):.1f}" width="{w - ml - mr}" height="{sy(band[0]) - sy(band[1]):.1f}" class="band"/>')
    step, digits = nice_step((y1 - y0) / 4)
    yv = math.ceil(y0 / step) * step
    while yv <= y1 + 1e-9:
        out.append(f'<line x1="{ml}" x2="{w - mr}" y1="{sy(yv):.1f}" y2="{sy(yv):.1f}" class="grid"/>')
        out.append(f'<text x="{ml - 6}" y="{sy(yv) + 4:.1f}" class="tick" text-anchor="end">{yv:.{digits}f}</text>')
        yv += step
    for i in range(6):
        xv = x0 + (x1 - x0) * i / 5
        out.append(f'<text x="{sx(xv):.1f}" y="{h - 10}" class="tick" text-anchor="middle">{xv:.0f}분</text>')
    for name, s, color in series:
        if scatter:
            out += [f'<circle cx="{sx(x):.1f}" cy="{sy(y):.1f}" r="1.6" fill="{color}"/>' for x, y in s]
        else:
            segs, cur, prev = [], [], None
            for x, y in s:
                if prev is not None and x - prev > 0.2:   # 12초 넘게 비면 선을 끊는다
                    segs.append(cur); cur = []
                cur.append(f"{sx(x):.1f},{sy(y):.1f}")
                prev = x
            segs.append(cur)
            out += [f'<polyline points="{" ".join(c)}" fill="none" stroke="{color}" stroke-width="1.6"/>' for c in segs if c]
    out.append("</svg>")
    legend = " ".join(f'<span class="key"><i style="background:{c}"></i>{html.escape(n)}</span>' for n, _, c in series)
    return f'<h3>{html.escape(title)} <small>{html.escape(unit)}</small></h3><div class="legend">{legend}</div>' + "".join(out)


def track_plot(fixes):
    if len(fixes) < 2:
        return ""
    xs, ys = [f[1] for f in fixes], [f[2] for f in fixes]
    span = max(max(xs) - min(xs), max(ys) - min(ys), 10)
    cx, cy = (max(xs) + min(xs)) / 2, (max(ys) + min(ys)) / 2
    size, m = 360, 20
    s = (size - 2 * m) / span
    dots = "".join(f'<circle cx="{size / 2 + (x - cx) * s:.1f}" cy="{size / 2 - (y - cy) * s:.1f}" r="1.5" fill="#2b7bd6"/>'
                   for x, y in zip(xs, ys))
    return (f'<h3>이동 궤적 <small>위가 북쪽, 가로세로 {span:.0f}m</small></h3>'
            f'<svg viewBox="0 0 {size} {size}" class="track">{dots}'
            f'<circle cx="{size / 2 + (xs[0] - cx) * s:.1f}" cy="{size / 2 - (ys[0] - cy) * s:.1f}" r="5" fill="#2e9e5b"/>'
            f'<circle cx="{size / 2 + (xs[-1] - cx) * s:.1f}" cy="{size / 2 - (ys[-1] - cy) * s:.1f}" r="5" fill="#c94c4c"/></svg>'
            '<p class="muted">초록 = 시작, 빨강 = 끝</p>')


# ---------------------------------------------------------------- 분석

def analyze(rows, force_stationary):
    dur_min = rows[-1]["min"]
    fixes = fresh_fixes(rows)
    findings, table = [], []

    def add(k, v):
        table.append((k, v))

    add("기록 시간", f"{dur_min:.0f}분 ({len(rows)}행)")

    # GPS
    acc = vals(rows, "acc_m")
    lost = sum(1 for r in rows if r["fix_age_s"] is None or r["fix_age_s"] > 5) / len(rows)
    sats = vals(rows, "sats_used")
    if acc:
        add("GPS 정확도", f"중앙값 ±{statistics.median(acc):.1f}m, 90%가 ±{pct(acc, 90):.1f}m 이내")
    add("GPS 끊김(5초 이상)", f"{lost * 100:.1f}% 시간")
    if sats:
        add("사용 위성", f"중앙값 {statistics.median(sats):.0f}개")
    if lost > 0.05:
        findings.append(("bad", f"GPS가 {lost * 100:.0f}% 시간 동안 끊겼어요. 하늘이 가려졌거나, 화면이 꺼져 앱이 멈췄을 수 있어요."))
    if acc and statistics.median(acc) > 10:
        findings.append(("warn", f"GPS 정확도 중앙값이 ±{statistics.median(acc):.0f}m로 나쁜 편이에요. 실내·건물 옆이면 정상이고, 바다에선 ±5m 안팎이 나와야 해요."))

    # 배터리
    bat = [(r["min"] / 60, r["battery"]) for r in rows if r["battery"] is not None]
    if len(bat) > 10 and bat[-1][0] - bat[0][0] > 0.15:
        slope, _ = linfit([b[0] for b in bat], [b[1] for b in bat])
        per_h = -slope
        add("배터리", f"{bat[0][1]:.0f}% → {bat[-1][1]:.0f}%, 시간당 {per_h:.1f}%")
        if per_h > 0:
            hours = 100 / per_h
            add("100%에서 예상 사용 시간", f"약 {hours:.1f}시간 (화면 계속 켜짐 기준)")
            findings.append(("good" if hours >= 8 else "warn",
                             f"배터리는 100%에서 약 {hours:.1f}시간 버틸 것 같아요" + ("." if hours >= 8 else " — 하루 낚시엔 빠듯해요.")))

    # 나침반·자기장
    mag = vals(rows, "mag_uT")
    if mag:
        disturbed = sum(1 for m in mag if abs(m - EARTH_FIELD) > 15) / len(mag)
        add("자기장", f"중앙값 {statistics.median(mag):.0f}µT (범위 {min(mag):.0f}~{max(mag):.0f}), 간섭 의심 {disturbed * 100:.1f}% 시간")
        if disturbed > 0.02:
            findings.append(("warn", f"자기장이 {disturbed * 100:.0f}% 시간 동안 정상 범위(35~65µT)를 벗어났어요. 그동안 나침반 값은 믿기 어려워요. 그래프에서 언제였는지 보세요."))
    head = [(r["min"], r["heading_true"], r["tilt"]) for r in rows if r["heading_true"] is not None and r["tilt"] is not None]
    reading = [h for h in head if h[2] < 45]
    if head:
        add("화면 보는 자세(기울기 45° 미만)", f"{len(reading) / len(head) * 100:.0f}% 시간")

    # 흐름
    ratios = [r["drift_kn"] / KN / r["drift_se_mps"] for r in rows
              if r["drift_kn"] is not None and r["drift_se_mps"] and r["drift_kn"] / KN >= 0.05]
    if ratios:
        hi = sum(1 for x in ratios if x >= 6) / len(ratios)
        mid = sum(1 for x in ratios if 3 <= x < 6) / len(ratios)
        add("흐름 신뢰도 비율", f"높음 {hi * 100:.0f}% · 보통 {mid * 100:.0f}% · 낮음 {(1 - hi - mid) * 100:.0f}%")
    dk = vals(rows, "drift_kn")
    if dk:
        add("흐름 속도", f"중앙값 {statistics.median(dk):.2f}kn")

    # 정지 시험: 실제 노이즈로 바닥값 확인
    stationary = None
    if fixes:
        disp = math.hypot(fixes[-1][1] - fixes[0][1], fixes[-1][2] - fixes[0][2])
        auto = dur_min >= 10 and disp < 30 and (not dk or statistics.median(dk) < 0.4)
        if force_stationary or auto:
            stationary = stationary_test(fixes)
            if stationary:
                findings += stationary["findings"]
                # 앱이 실제로 보여준 판정(도플러 확인 포함)이 위치만 쓴 노이즈 분석보다 중요하다
                if ratios:
                    hi_mid = sum(1 for x in ratios if x >= 3) / len(ratios)
                    findings.append(("good" if hi_mid < 0.05 else "bad",
                                     f"정지 상태에서 앱이 '보통' 이상으로 잘못 표시한 시간: {hi_mid * 100:.1f}% "
                                     "(도플러 속도 확인 포함, 5% 미만이면 정상)"))
    return table, findings, fixes, stationary


def stationary_test(fixes):
    res, findings, ks = [], [], []
    for w in (40, 60, 90):
        fits = window_fits(fixes, w)
        if len(fits) < 5:
            continue
        sigma = math.sqrt(sum(f[0] ** 2 + f[1] ** 2 for f in fits) / (2 * len(fits)))
        se_white = statistics.mean(f[2] for f in fits)
        acc = statistics.mean(f[3] for f in fits)
        floor_now = FLOOR_K * acc * (40 / w) ** FLOOR_P
        se_app = math.hypot(se_white, floor_now)
        k = math.sqrt(max(sigma ** 2 - se_white ** 2, 0)) / (acc * (40 / w) ** FLOOR_P)
        ks.append(k)
        false_kn = statistics.median(math.hypot(f[0], f[1]) for f in fits) * KN
        res.append((w, len(fits), sigma, se_white, se_app, false_kn, k))
    if not res:
        return None
    k_med = statistics.median(ks)
    ratio = k_med / FLOOR_K
    if 0.67 <= ratio <= 1.5:
        findings.append(("good", f"정지 시험: 앱의 신뢰도 기준(FLOOR_K={FLOOR_K})이 이 기기 GPS와 잘 맞아요 (실측 {k_med:.4f})."))
    else:
        findings.append(("warn", f"정지 시험: 위치만 보면 GPS 노이즈가 모형의 {ratio:.1f}배예요 (FLOOR_K 실측 {k_med:.4f}, 앱 {FLOOR_K}). "
                                 "창가·실내·건물 옆이면 반사 때문에 원래 크게 나오니, 하늘이 트인 곳에서 다시 재서 비슷하면 "
                                 "app.js와 Drift.kt의 FLOOR_K를 바꾸세요."))
    w60 = [r for r in res if r[0] == 60]
    if w60:
        findings.append(("info", f"가만히 있어도 60초 구간에서 흐름이 중앙값 {w60[0][5]:.2f}kn로 나와요. 이보다 느린 흐름은 방향을 믿기 어려워요."))
    return {"rows": res, "k": k_med, "findings": findings}


# ---------------------------------------------------------------- 보고서

def report(path, rows, table, findings, fixes, stationary):
    s = lambda key: [(r["min"], r[key]) for r in rows if r[key] is not None]
    charts = [
        chart("흐름 속도", [("흐름(앱 계산)", s("drift_kn"), COLORS[0]),
                          ("GPS 순간속도", [(m, v * KN) for m, v in s("gps_speed_mps")], COLORS[1])], "kn"),
        chart("방위", [("흐름 방위", s("drift_bearing"), COLORS[0]), ("내 정면", s("heading_true"), COLORS[1])],
              "° (진북)", scatter=True, y_range=(0, 360)),
        chart("GPS 정확도 · 위성", [("정확도(m)", s("acc_m"), COLORS[2]), ("사용 위성", s("sats_used"), COLORS[4])]),
        chart("자기장 세기", [("자기장", s("mag_uT"), COLORS[3])], "µT — 회색 띠가 정상 범위(35~65)", band=(35, 65)),
        chart("기울기", [("기울기", s("tilt"), COLORS[4])], "° — 0 = 화면이 하늘을 봄, 45° 미만이 화면 보는 자세"),
        chart("배터리", [("배터리", s("battery"), COLORS[2])], "%"),
    ]
    st_html = ""
    if stationary:
        trs = "".join(f"<tr><td>{w}초</td><td>{n}</td><td>{sg:.3f}</td><td>{sw:.3f}</td><td>{sa:.3f}</td><td>{fk:.2f}</td><td>{k:.4f}</td></tr>"
                      for w, n, sg, sw, sa, fk, k in stationary["rows"])
        st_html = ("<h2>정지 시험 — GPS 노이즈</h2>"
                   "<p class='muted'>워치가 움직이지 않았다고 보고, 구간별로 계산한 흐름 속도가 얼마나 흔들리는지 잽니다. "
                   "'앱 오차'가 '실제 흔들림'과 비슷해야 신뢰도 판정이 정확합니다.</p>"
                   "<table><tr><th>구간</th><th>표본</th><th>실제 흔들림 σ (m/s)</th><th>단순 오차 (m/s)</th>"
                   f"<th>앱 오차 (m/s)</th><th>가짜 흐름 (kn)</th><th>K 실측</th></tr>{trs}</table>")
    icon = {"good": "✅", "warn": "⚠️", "bad": "❌", "info": "ℹ️"}
    fhtml = "".join(f"<li>{icon[k]} {html.escape(t)}</li>" for k, t in findings) or "<li>특이사항 없음</li>"
    thtml = "".join(f"<tr><th>{html.escape(k)}</th><td>{html.escape(v)}</td></tr>" for k, v in table)
    return f"""<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>워치 기록 분석</title>
<style>
:root {{ --bg:#fff; --fg:#1b2733; --muted:#66788a; --line:#e1e7ec; --band:#eef2f5; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg:#0f1820; --fg:#e8eef3; --muted:#8ea3b3; --line:#23323f; --band:#18242f; }} }}
body {{ margin:0; padding:20px 16px 40px; background:var(--bg); color:var(--fg);
  font-family:-apple-system,"Malgun Gothic","Noto Sans KR",sans-serif; line-height:1.5; }}
main {{ max-width:800px; margin:0 auto; }}
h1 {{ font-size:22px; margin:0 0 4px; }} h2 {{ font-size:18px; margin-top:28px; }}
h3 {{ font-size:15px; margin:22px 0 2px; }} h3 small, .muted {{ color:var(--muted); font-weight:normal; font-size:13px; }}
table {{ border-collapse:collapse; width:100%; font-size:14px; }}
th, td {{ border-bottom:1px solid var(--line); padding:6px 8px; text-align:left; vertical-align:top; }}
th {{ font-weight:600; white-space:nowrap; }}
ul {{ padding-left:4px; list-style:none; }} li {{ margin:6px 0; }}
.chart {{ width:100%; height:auto; display:block; }} .track {{ width:100%; max-width:360px; display:block; }}
.grid {{ stroke:var(--line); }} .tick {{ fill:var(--muted); font-size:11px; }} .band {{ fill:var(--band); }}
.legend {{ font-size:12px; color:var(--muted); }} .key {{ margin-right:12px; }}
.key i {{ display:inline-block; width:10px; height:10px; border-radius:2px; margin-right:4px; vertical-align:middle; }}
</style></head><body><main>
<h1>워치 기록 분석</h1><p class="muted">{html.escape(Path(path).name)}</p>
<h2>요약</h2><ul>{fhtml}</ul>
<table>{thtml}</table>
{st_html}
<h2>그래프</h2>
{"".join(charts)}
{track_plot(fixes)}
</main></body></html>"""


def main():
    ap = argparse.ArgumentParser(description="워치 시험 앱 CSV 기록 분석")
    ap.add_argument("csv")
    ap.add_argument("--stationary", action="store_true", help="정지 시험으로 보고 GPS 노이즈 분석")
    a = ap.parse_args()
    rows = load(a.csv)
    table, findings, fixes, stationary = analyze(rows, a.stationary)
    out = Path(a.csv).with_suffix(".html")
    out.write_text(report(a.csv, rows, table, findings, fixes, stationary), encoding="utf-8")
    for k, v in table:
        print(f"{k}: {v}")
    for _, t in findings:
        print("- " + t)
    print(f"\n보고서: {out}")


if __name__ == "__main__":
    main()
