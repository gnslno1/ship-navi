package io.github.gnslno1.shipnavi

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val KN = 1.943844           // m/s → knot
const val DEG = Math.PI / 180
private const val R_EARTH = 6371008.8
private const val MAX_ACC = 30.0  // 이보다 부정확한 GPS 위치는 버림 (m)
const val STOP_SPEED = 0.05       // 이보다 느리면 "거의 정지" (m/s ≈ 0.1 kn)
private const val MAX_JUMP_SPEED = 30.0  // 이보다 빠른 위치 변화는 GPS 튐으로 본다 (m/s ≈ 58 kn)
private const val FLOOR_K = 0.0171       // 상관 오차 바닥값 = K × GPS 정확도(m) × (40/구간초)^P  (m/s)
private const val FLOOR_P = 0.66

fun norm360(a: Double) = ((a % 360) + 360) % 360
fun norm180(a: Double) = norm360(a).let { if (it > 180) it - 360 else it }

/** 내 정면 기준 상대 각도 → 시계 방향 (12시 = 정면) */
fun clockOf(rel: Double) = ((norm360(rel) / 30).roundToInt() % 12).let { if (it == 0) 12 else it }

data class Fix(val t: Double, val lat: Double, val lon: Double, val acc: Double)

/** speed m/s, bearing 진북 기준 °, se 축당 속도 표준오차 m/s, span 실제 평균 구간(초) */
data class Drift(val speed: Double, val bearing: Double, val se: Double, val n: Int, val span: Double) {
    val knots get() = speed * KN

    val quality: String
        get() {
            if (speed < STOP_SPEED) return "거의 정지"
            val ratio = speed / max(se, 1e-6)
            return if (ratio >= 6) "높음" else if (ratio >= 3) "보통" else "낮음"
        }
}

/**
 * 휴대폰 웹앱(app.js estimateDrift)과 같은 계산.
 * 측정 구간의 GPS 위치들에 가중 최소제곱으로 직선을 맞춰 속도 벡터를 구한다.
 */
class DriftEstimator(private val windowSec: Double = 60.0) {
    private val fixes = ArrayDeque<Fix>()

    fun add(f: Fix) {
        if (f.acc > MAX_ACC) return
        val last = fixes.lastOrNull()
        if (last != null && f.t <= last.t) return
        // 직전 위치에서 배가 갈 수 없는 속도로 튀었으면(첫 위치가 엉뚱한 곳 등) 처음부터 다시 잰다.
        if (last != null && distance(last, f) / (f.t - last.t) > MAX_JUMP_SPEED) fixes.clear()
        fixes.addLast(f)
        while (f.t - fixes.first().t > max(windowSec, 120.0)) fixes.removeFirst()
    }

    fun estimate(): Drift? {
        if (fixes.isEmpty()) return null
        val tEnd = fixes.last().t
        val pts = fixes.filter { tEnd - it.t <= windowSec }
        val span = tEnd - pts.first().t
        if (pts.size < 5 || span < min(10.0, windowSec / 2)) return null

        val ref = pts.last()
        val kx = R_EARTH * DEG * cos(ref.lat * DEG)
        val ky = R_EARTH * DEG
        val n = pts.size
        val t = DoubleArray(n) { pts[it].t - tEnd }
        val x = DoubleArray(n) { (pts[it].lon - ref.lon) * kx }
        val y = DoubleArray(n) { (pts[it].lat - ref.lat) * ky }
        val w = DoubleArray(n) { val a = max(pts[it].acc, 3.0); 1.0 / (a * a) }
        val sw = w.sum()
        val tm = (0 until n).sumOf { w[it] * t[it] } / sw
        val xm = (0 until n).sumOf { w[it] * x[it] } / sw
        val ym = (0 until n).sumOf { w[it] * y[it] } / sw

        var stt = 0.0
        var stx = 0.0
        var sty = 0.0
        for (i in 0 until n) {
            val dt = t[i] - tm
            stt += w[i] * dt * dt
            stx += w[i] * dt * (x[i] - xm)
            sty += w[i] * dt * (y[i] - ym)
        }
        val vx = stx / stt   // 동쪽 성분 (m/s)
        val vy = sty / stt   // 북쪽 성분 (m/s)

        var srr = 0.0
        for (i in 0 until n) {
            val dt = t[i] - tm
            val ex = x[i] - xm - vx * dt
            val ey = y[i] - ym - vy * dt
            srr += w[i] * (ex * ex + ey * ey)
        }
        // 잔차로 구한 표준오차는 매초 오차가 독립이라고 가정해서 실제 GPS(오차가 수십 초 쏠림)에선 3~7배 작다.
        // 시뮬레이션(tools/simulate_drift.py)으로 구한 상관 오차 바닥값을 더한다. 웹앱 app.js와 같은 식.
        val seWhite = sqrt(srr / (2 * (n - 2)) / stt)
        val accMean = pts.sumOf { max(it.acc, 3.0) } / n
        val floor = FLOOR_K * accMean * (40 / span).pow(FLOOR_P)
        val se = hypot(seWhite, floor)
        return Drift(hypot(vx, vy), norm360(atan2(vx, vy) / DEG), se, n, span)
    }

    private fun distance(a: Fix, b: Fix) = hypot(
        (b.lon - a.lon) * R_EARTH * DEG * cos(a.lat * DEG),
        (b.lat - a.lat) * R_EARTH * DEG,
    )
}
