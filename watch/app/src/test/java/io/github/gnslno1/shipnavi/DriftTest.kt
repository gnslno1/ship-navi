package io.github.gnslno1.shipnavi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class DriftTest {
    private val lat0 = 34.7
    private val lon0 = 127.75
    private val ky = 6371008.8 * DEG
    private val kx = ky * cos(lat0 * DEG)

    /** 동 x, 북 y (m) → Fix */
    private fun fix(t: Double, x: Double, y: Double, acc: Double = 4.0) = Fix(t, lat0 + y / ky, lon0 + x / kx, acc)

    /** 일정한 속도(kn)·방위로 움직이는 잡음 없는 궤적 */
    private fun line(est: DriftEstimator, kn: Double, bearing: Double, seconds: Int, t0: Double = 0.0, acc: Double = 4.0) {
        val v = kn / KN
        for (i in 0..seconds) {
            val t = t0 + i
            est.add(fix(t, v * sin(bearing * DEG) * t, v * cos(bearing * DEG) * t, acc))
        }
    }

    private fun angleDiff(a: Double, b: Double) = abs(norm180(a - b))

    @Test
    fun straightLineGivesExactSpeedAndBearing() {
        val est = DriftEstimator(60.0)
        line(est, 0.8, 60.0, 60)
        val d = assertNotNullDrift(est.estimate())
        assertEquals(0.8, d.knots, 1e-3)
        assertEquals(60.0, d.bearing, 0.05)
        // 잡음이 없으면 오차는 상관 오차 바닥값만 남는다: 0.0171 × 4m × (40/60)^0.66
        assertEquals(0.0171 * 4 * (40.0 / 60).pow(0.66), d.se, 1e-4)
        assertEquals(61, d.n)
    }

    @Test
    fun matchesWebAppOnSameInput() {
        // app.js estimateDrift에 같은 입력을 넣어 얻은 값 (브라우저에서 계산)
        val fixes = (0 until 45).map { i ->
            val v = 0.8 / KN
            val x = v * sin(60 * DEG) * i + 2 * sin(1.3 * i)
            val y = v * cos(60 * DEG) * i + 1.5 * cos(0.7 * i)
            fix(1000.0 + i, x, y, 4.0 + i % 3)
        }
        val est60 = DriftEstimator(60.0).apply { fixes.forEach(::add) }
        val d = assertNotNullDrift(est60.estimate())
        assertEquals(0.40342049268316416, d.speed, 1e-9)
        assertEquals(60.381770254876756, d.bearing, 1e-7)
        assertEquals(0.0816026111067195, d.se, 1e-9)
        assertEquals(45, d.n)

        val est20 = DriftEstimator(20.0).apply { fixes.forEach(::add) }
        val d20 = assertNotNullDrift(est20.estimate())
        assertEquals(0.3830149555249393, d20.speed, 1e-9)
        assertEquals(61.41867222068987, d20.bearing, 1e-7)
        assertEquals(0.14270744945719713, d20.se, 1e-9)
        assertEquals(21, d20.n)
    }

    @Test
    fun needsEnoughPointsAndTime() {
        val est = DriftEstimator(60.0)
        assertNull(est.estimate())
        line(est, 1.0, 90.0, 8)          // 9개 위치, 8초 — 최소 10초 필요
        assertNull(est.estimate())
        line(est, 1.0, 90.0, 12)         // 같은 시각은 무시되고 9~12초가 추가됨
        assertNotNull(est.estimate())
    }

    @Test
    fun onlyRecentWindowCounts() {
        // 처음 2분은 북쪽, 다음 60초는 동쪽으로 → 60초 구간이면 동쪽만 보여야 함
        val est = DriftEstimator(60.0)
        val v = 1.0 / KN
        for (i in 0..120) est.add(fix(i.toDouble(), 0.0, v * i))
        for (i in 1..60) est.add(fix(120.0 + i, v * i, v * 120))
        val d = assertNotNullDrift(est.estimate())
        assertTrue("방위 ${d.bearing}", angleDiff(d.bearing, 90.0) < 3)
        assertEquals(1.0, d.knots, 0.05)
    }

    @Test
    fun ignoresInaccurateDuplicateAndOldFixes() {
        val est = DriftEstimator(60.0)
        line(est, 0.5, 200.0, 30)
        val before = est.estimate()
        est.add(fix(31.0, 500.0, 500.0, acc = 50.0))  // 정확도 30m 초과 → 버림
        est.add(fix(30.0, 500.0, 500.0))              // 같은 시각 → 버림
        est.add(fix(10.0, 500.0, 500.0))              // 과거 시각 → 버림
        assertEquals(before, est.estimate())
    }

    @Test
    fun gpsJumpRestartsMeasurement() {
        val est = DriftEstimator(60.0)
        line(est, 0.8, 60.0, 30)
        assertNotNull(est.estimate())
        // 1초 만에 5km 이동 → 배가 갈 수 없는 속도이므로 기록을 비우고 새로 잰다
        est.add(fix(31.0, 5000.0, 0.0))
        assertNull(est.estimate())
        line(est, 0.8, 60.0, 20, t0 = 32.0)
        val d = assertNotNullDrift(est.estimate())
        assertTrue(d.n <= 22)
    }

    @Test
    fun stationaryWithCorrelatedNoiseIsNeverHighConfidence() {
        // 실제 GPS처럼 오차가 쏠리는(상관시간 60초) 정지 상태: 신뢰도 "높음"이 나오면 안 된다
        val rnd = Random(7)
        var high = 0
        repeat(300) {
            val est = DriftEstimator(60.0)
            gpsNoise(rnd, 61).forEachIndexed { i, (ex, ey) -> est.add(fix(i.toDouble(), ex, ey, 4.8)) }
            if (est.estimate()!!.quality == "높음") high++
        }
        assertTrue("정지인데 높음 $high/300", high <= 3)
    }

    @Test
    fun highConfidenceMeansDirectionWithin15Degrees() {
        // 0.3~2kn, 보통 GPS: "높음"으로 판정된 경우 90% 이상이 ±15° 안이어야 한다
        val rnd = Random(11)
        val errs = mutableListOf<Double>()
        for (kn in listOf(0.3, 0.5, 0.8, 1.2, 2.0)) repeat(200) {
            val est = DriftEstimator(60.0)
            val v = kn / KN
            gpsNoise(rnd, 61).forEachIndexed { i, (ex, ey) ->
                est.add(fix(i.toDouble(), v * sin(60 * DEG) * i + ex, v * cos(60 * DEG) * i + ey, 4.8))
            }
            val d = est.estimate()!!
            if (d.quality == "높음") errs += angleDiff(d.bearing, 60.0)
        }
        assertTrue("높음 표본 ${errs.size}", errs.size > 200)
        val within = errs.count { it <= 15 }.toDouble() / errs.size
        assertTrue("높음 중 ±15° 안: ${(within * 100).toInt()}%", within >= 0.9)
    }

    /** 실기기 정지 기록 재생 → 측정 구간이 찬 뒤(60초 이후) 판정들 */
    private fun replay(file: String, useDoppler: Boolean): List<Drift> {
        val lines = javaClass.getResource(file)!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("t,") }
        val est = DriftEstimator(60.0)
        val out = mutableListOf<Drift>()
        for (line in lines) {
            val c = line.split(",")
            val t = c[0].toDouble()
            val speed = c[4].toDoubleOrNull()?.takeIf { useDoppler }
            est.add(fix(t, c[1].toDouble(), c[2].toDouble(), c[3].toDouble()).copy(speed = speed))
            if (t >= 60) est.estimate()?.let { out += it }
        }
        return out
    }

    @Test
    fun realStationaryWatchIsNotHighConfidenceWithDoppler() {
        // 위치만 쓰면 창가의 GPS 반사 때문에 가짜 흐름이 "높음"으로 나오던 실제 기록
        val positionsOnly = replay("/stationary_balcony.csv", useDoppler = false)
        assertTrue("위치만으로는 높음이 나와야 이 데이터가 의미 있음", positionsOnly.any { it.quality == "높음" })

        // 창문 열린 베란다 4분, 닫힌 창문 안쪽 30분 (자석 버클 제거)
        for (file in listOf("/stationary_balcony.csv", "/stationary_window_30min.csv")) {
            val withDoppler = replay(file, useDoppler = true)
            assertTrue(withDoppler.size > 100)
            val high = withDoppler.count { it.quality == "높음" }
            val mid = withDoppler.count { it.quality == "보통" }
            assertEquals("$file: 정지인데 높음", 0, high)
            assertTrue("$file: 정지인데 보통 $mid/${withDoppler.size}", mid * 20 <= withDoppler.size)
            // 창유리 반사로 도플러까지 흔들리면 가짜 속도가 1kn 가까이 나올 수 있다 → 대부분 "낮음"이어야 한다.
            // 위치와 도플러가 같은 방향으로 함께 속는 순간(30분 중 약 2초)은 이 방식으로 못 잡으므로 1%까지 허용.
            val fakeConfident = withDoppler.count { it.knots >= 0.5 && it.quality != "낮음" }
            assertTrue("$file: 0.5kn 이상 가짜 흐름이 보통 이상 $fakeConfident/${withDoppler.size}", fakeConfident * 100 <= withDoppler.size)
        }
    }

    @Test
    fun dopplerAgreesWhenReallyMoving() {
        // 실제로 0.8kn로 움직이면 도플러도 0.8kn → 판정이 깎이지 않아야 한다
        val rnd = Random(5)
        val v = 0.8 / KN
        var ok = 0
        repeat(200) {
            val est = DriftEstimator(60.0)
            gpsNoise(rnd, 61).forEachIndexed { i, (ex, ey) ->
                est.add(
                    fix(i.toDouble(), v * sin(60 * DEG) * i + ex, v * cos(60 * DEG) * i + ey, 4.8)
                        .copy(speed = v + rnd.nextGaussian() * 0.05),
                )
            }
            val d = est.estimate()!!
            if (d.quality != "낮음" && angleDiff(d.bearing, 60.0) <= 30) ok++
        }
        assertTrue("움직일 때 보통 이상 + ±30°: $ok/200", ok >= 170)
    }

    @Test
    fun clockDirections() {
        assertEquals(12, clockOf(0.0))
        assertEquals(12, clockOf(14.0))
        assertEquals(1, clockOf(16.0))
        assertEquals(3, clockOf(90.0))
        assertEquals(6, clockOf(180.0))
        assertEquals(9, clockOf(-90.0))
        assertEquals(11, clockOf(-30.0))
        assertEquals(-170.0, norm180(190.0), 1e-9)
        assertEquals(350.0, norm360(-10.0), 1e-9)
    }

    /** 상관 오차(3m, 60초) + 흰 잡음(1m) — tools/simulate_drift.py의 '보통' GPS와 같은 모형 */
    private fun gpsNoise(rnd: Random, n: Int): List<Pair<Double, Double>> {
        val phi = exp(-1.0 / 60)
        val q = 3.0 * sqrt(1 - phi * phi)
        var ex = rnd.nextGaussian() * 3
        var ey = rnd.nextGaussian() * 3
        return List(n) {
            if (it > 0) {
                ex = phi * ex + rnd.nextGaussian() * q
                ey = phi * ey + rnd.nextGaussian() * q
            }
            (ex + rnd.nextGaussian()) to (ey + rnd.nextGaussian())
        }
    }

    private fun assertNotNullDrift(d: Drift?): Drift {
        assertNotNull(d)
        return d!!
    }
}
