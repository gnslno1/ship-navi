"""배 흐름 추정(app.js estimateDrift / Drift.kt) 정확도 몬테카를로 시뮬레이션.

실제 GPS 위치 오차는 매초 독립이 아니라 수십 초 동안 한쪽으로 쏠린다(상관 오차).
이 경우 현재 코드의 표준오차(se)는 실제 오차보다 작게 나와 신뢰도를 후하게 매긴다.
여기서는 상관 오차를 넣은 가짜 GPS 궤적으로
  1) 속도·측정 구간별 방향 오차
  2) se가 실제 오차를 얼마나 과소평가하는지
  3) se에 더할 '상관 오차 바닥값(floor)'과 신뢰도 기준
을 구한다.

사용법: python tools/simulate_drift.py
"""
import numpy as np

KN = 1.943844
rng = np.random.default_rng(42)

# GPS 오차 모형: 상관 오차(가우스-마르코프, 표준편차 sc m, 상관시간 tau 초) + 흰 잡음(sw m) + 파도로 인한 흔들림
NOISE = {
    "좋음": dict(sc=1.5, tau=60, sw=0.5),
    "보통": dict(sc=3.0, tau=60, sw=1.0),
    "나쁨": dict(sc=5.0, tau=30, sw=2.0),
}
WAVE_AMP = 0.4      # m, 배 흔들림으로 인한 위치 진동
WAVE_PERIOD = 6.0   # 초


def gps_tracks(n_trials, n, v, bearing_deg, sc, tau, sw):
    """1초 간격 GPS 위치 (n_trials, n, 2) — 동, 북 (m)"""
    t = np.arange(n, dtype=float)
    b = np.radians(bearing_deg)
    truth = np.stack([v * np.sin(b) * t, v * np.cos(b) * t], axis=-1)
    phi = np.exp(-1.0 / tau)
    e = np.empty((n_trials, n, 2))
    e[:, 0] = rng.normal(0, sc, (n_trials, 2))
    q = sc * np.sqrt(1 - phi * phi)
    for k in range(1, n):
        e[:, k] = phi * e[:, k - 1] + rng.normal(0, q, (n_trials, 2))
    e += rng.normal(0, sw, e.shape)
    phase = rng.uniform(0, 2 * np.pi, (n_trials, 1, 1))
    e += WAVE_AMP * np.sin(2 * np.pi * t[None, :, None] / WAVE_PERIOD + phase)
    return truth[None] + e


def estimate(p):
    """estimateDrift와 같은 계산 (정확도가 일정하면 가중치는 상쇄됨). 반환: vx, vy, se"""
    n = p.shape[1]
    t = np.arange(n, dtype=float)
    dt = t - t.mean()
    stt = (dt * dt).sum()
    pm = p.mean(axis=1, keepdims=True)
    v = (dt[None, :, None] * (p - pm)).sum(axis=1) / stt
    resid = p - pm - v[:, None, :] * dt[None, :, None]
    srr = (resid ** 2).sum(axis=(1, 2))
    se = np.sqrt(srr / (2 * (n - 2)) / stt)
    return v[:, 0], v[:, 1], se


def ang_err(est_deg, true_deg):
    return np.abs((est_deg - true_deg + 180) % 360 - 180)


def run(n_trials=4000):
    windows = [20, 40, 60, 90]
    speeds_kn = [0.2, 0.3, 0.5, 0.8, 1.2, 2.0]
    true_b = 60.0

    print("=" * 78)
    print("1) 방향 오차 90퍼센타일 (°) — 10번 중 9번은 이 안에 든다")
    print("=" * 78)
    for name, nz in NOISE.items():
        print(f"\n[GPS {name}] 상관오차 {nz['sc']}m/{nz['tau']}초, 흰잡음 {nz['sw']}m")
        print("속도(kn) " + "".join(f"{w:>8}초" for w in windows))
        for s in speeds_kn:
            row = []
            for w in windows:
                vx, vy, _ = estimate(gps_tracks(n_trials, w + 1, s / KN, true_b, **nz))
                row.append(np.percentile(ang_err(np.degrees(np.arctan2(vx, vy)), true_b), 90))
            print(f"{s:>7.1f} " + "".join(f"{e:>9.0f}" for e in row))

    print("\n" + "=" * 78)
    print("2) 정지 상태에서 속도 추정의 실제 표준편차 vs 코드의 se (m/s, 축당)")
    print("=" * 78)
    floor = {}
    print("GPS     구간   실제σ    se평균   과소평가배수")
    for name, nz in NOISE.items():
        for w in windows:
            vx, vy, se = estimate(gps_tracks(n_trials, w + 1, 0.0, true_b, **nz))
            actual = np.sqrt((vx.var() + vy.var()) / 2)
            floor[(name, w)] = actual
            print(f"{name:<6}{w:>4}초  {actual:7.3f}  {se.mean():8.3f}  {actual / se.mean():8.1f}배")

    # 보정: se_eff = sqrt(se^2 + floor^2), floor = K × (GPS 정확도 m) × (40/W)^P  ('보통' GPS로 맞춤)
    ws = np.array(windows, dtype=float)
    fl = np.array([floor[("보통", w)] for w in windows])
    P, logA = np.polyfit(np.log(40 / ws), np.log(fl), 1)
    K = float(np.exp(logA)) / reported_acc(NOISE["보통"])
    print(f"\n→ 상관 오차 바닥값: floor = {K:.4f} × 정확도(m) × (40/W)^{P:.2f}  m/s")
    print("   (GPS가 알려주는 정확도 = 68% 반경 ≈ 1.51 × 축당 표준편차로 가정: "
          + ", ".join(f"{n} ±{reported_acc(nz):.1f}m" for n, nz in NOISE.items()) + ")")

    print("\n" + "=" * 78)
    print("3) 신뢰도 판정 검증 — 판정별 방향 오차 90퍼센타일 (°)과 비율")
    print("   목표: 높음 ≤ 15°, 보통 ≤ 30° (시계 1칸)")
    print("=" * 78)
    for label, use_floor in (("현재 기준 (se만 사용)", False), ("보정 기준 (se + 바닥값)", True)):
        print(f"\n[{label}]")
        print("GPS     구간   높음(비율)     보통(비율)     낮음(비율)")
        for name, nz in NOISE.items():
            for w in (40, 60):
                errs, ratios = [], []
                for s in speeds_kn:
                    vx, vy, se = estimate(gps_tracks(n_trials, w + 1, s / KN, true_b, **nz))
                    sp = np.hypot(vx, vy)
                    fl_w = K * reported_acc(nz) * (40 / w) ** P
                    se_eff = np.sqrt(se ** 2 + fl_w ** 2) if use_floor else se
                    errs.append(ang_err(np.degrees(np.arctan2(vx, vy)), true_b))
                    ratios.append(sp / se_eff)
                err = np.concatenate(errs)
                ratio = np.concatenate(ratios)
                cells = []
                for lo, hi in ((6, np.inf), (3, 6), (0, 3)):
                    m = (ratio >= lo) & (ratio < hi)
                    cells.append(f"{np.percentile(err[m], 90):5.0f}° ({m.mean() * 100:3.0f}%)" if m.any() else "    -        ")
                print(f"{name:<6}{w:>4}초  " + "   ".join(cells))

    print("\n" + "=" * 78)
    print("4) 정지 상태(실제 흐름 0)에서 보정 기준 판정 비율 — '높음'이 거의 없어야 정상")
    print("=" * 78)
    for name, nz in NOISE.items():
        for w in (40, 60):
            vx, vy, se = estimate(gps_tracks(n_trials, w + 1, 0.0, true_b, **nz))
            sp = np.hypot(vx, vy)
            ratio = sp / np.sqrt(se ** 2 + (K * reported_acc(nz) * (40 / w) ** P) ** 2)
            print(f"{name:<6}{w:>4}초  평균 {sp.mean() * KN:.2f}kn  "
                  f"높음 {np.mean(ratio >= 6) * 100:4.1f}%  보통 {np.mean((ratio >= 3) & (ratio < 6)) * 100:4.1f}%  "
                  f"낮음 {np.mean(ratio < 3) * 100:5.1f}%")
    return K, P


def reported_acc(nz):
    """GPS가 알려주는 정확도(68% 반경) 추정"""
    return 1.51 * np.sqrt(nz["sc"] ** 2 + nz["sw"] ** 2 + WAVE_AMP ** 2 / 2)


if __name__ == "__main__":
    run()
