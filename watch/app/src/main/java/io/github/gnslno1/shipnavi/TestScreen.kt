package io.github.gnslno1.shipnavi

import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private val Amber = Color(0xFFFFB020)
private val Muted = Color(0xFF8EA3B3)
private val Warn = Color(0xFFF2C230)
private val Bad = Color(0xFFFF5A5A)
private val Ring = Color(0xFF4D6577)
private val North = Color(0xFFFF4D4D)

private const val EARTH_FIELD = 50.0   // 한국 지구 자기장 세기 약 50µT

@Composable
fun TestScreen(r: Readings, keepScreenOn: Boolean, onToggleScreen: () -> Unit) {
    MaterialTheme {
        // 첫 화면 가운데에 다이얼과 속도가 함께 보이도록 맨 첫 항목을 가운데에 둔다
        val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
        Scaffold(
            timeText = { TimeText() },
            positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        ) {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    Column(Modifier.padding(top = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Dial(r)
                        Summary(r)
                    }
                }
                item { Info(gpsLine(r)) }
                item { Info(gpsSpeedLine(r)) }
                item { Info(driftLine(r)) }
                item { Info(headingLine(r)) }
                item { Info(tiltLine(r)) }
                item { Info(magLine(r)) }
                item { Info(batteryLine(r)) }
                item { Info("기록 ${r.logRows}행" to Muted) }
                item { Info((r.logFile ?: "-") to Muted, small = true) }
                item {
                    Chip(
                        onClick = onToggleScreen,
                        label = { Text(if (keepScreenOn) "화면 계속 켜짐" else "화면 자동 꺼짐") },
                        secondaryLabel = { Text("눌러서 바꾸기") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** 12시 = 내 정면. 빨간 점 = 북쪽, 노란 화살표 = 배가 밀려가는 방향 */
@Composable
private fun Dial(r: Readings) {
    val heading = r.heading ?: 0.0
    val d = r.drift
    Canvas(Modifier.size(104.dp)) {
        val c = center
        val rad = size.minDimension / 2
        drawCircle(Ring, rad - 2.dp.toPx(), style = Stroke(3.dp.toPx()))
        drawPath(
            Path().apply {
                moveTo(c.x, c.y - rad - 2.dp.toPx())
                lineTo(c.x - 7.dp.toPx(), c.y - rad + 10.dp.toPx())
                lineTo(c.x + 7.dp.toPx(), c.y - rad + 10.dp.toPx())
                close()
            },
            Color.White,
        )
        if (r.heading != null) {
            rotate(-heading.toFloat()) { drawCircle(North, 5.dp.toPx(), Offset(c.x, c.y - rad + 16.dp.toPx())) }
        }
        if (d != null && d.speed >= STOP_SPEED) {
            rotate((d.bearing - heading).toFloat()) {
                val len = rad * (0.45f + 0.35f * min(d.knots / 2, 1.0).toFloat())
                val headLen = 12.dp.toPx()
                drawLine(
                    Amber, Offset(c.x, c.y + rad * 0.25f), Offset(c.x, c.y - len + headLen / 2),
                    strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round,
                    alpha = if (d.quality == "낮음") 0.45f else 1f,
                )
                drawPath(
                    Path().apply {
                        moveTo(c.x, c.y - len - headLen)
                        lineTo(c.x - 10.dp.toPx(), c.y - len + headLen / 2)
                        lineTo(c.x + 10.dp.toPx(), c.y - len + headLen / 2)
                        close()
                    },
                    Amber,
                )
            }
        }
        drawCircle(Color.White, 4.dp.toPx(), c)
    }
}

@Composable
private fun Summary(r: Readings) {
    val d = r.drift
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            if (d == null) "-- kn" else String.format("%.1f kn", d.knots),
            fontSize = 26.sp, fontWeight = FontWeight.Bold,
        )
        val (text, color) = when {
            !r.hasPermission -> "위치 권한이 필요해요" to Bad
            !r.gpsEnabled -> "워치 위치 기능이 꺼져 있어요" to Bad
            d == null -> "측정 중…" to Muted
            d.speed < STOP_SPEED -> "배가 거의 멈춰 있어요" to Muted
            r.heading == null -> "흐름 방위 ${d.bearing.roundToInt()}°" to Amber
            else -> {
                val rel = norm180(d.bearing - r.heading)
                val a = abs(rel).roundToInt()
                val side = if (a <= 10) "정면" else if (a >= 170) "뒤쪽" else "${if (rel > 0) "오른쪽" else "왼쪽"} $a°"
                "${clockOf(rel)}시 · $side" to Amber
            }
        }
        Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Info(line: Pair<String, Color>, small: Boolean = false) {
    Text(
        line.first, color = line.second, fontSize = if (small) 11.sp else 13.sp,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
    )
}

private fun gpsLine(r: Readings): Pair<String, Color> {
    val sats = "위성 ${r.satsUsed}/${r.satsVisible}"
    val acc = r.gpsAcc ?: return "GPS 기다리는 중 · $sats" to Muted
    val age = r.fixAge ?: 0.0
    if (age > 5) return "GPS 끊김 ${age.roundToInt()}초 · $sats" to Bad
    return "GPS ±${acc.roundToInt()}m · $sats" to (if (acc <= 10) Color.White else if (acc <= 30) Warn else Bad)
}

private fun gpsSpeedLine(r: Readings): Pair<String, Color> {
    val v = r.gpsSpeed ?: return "GPS 순간속도 -" to Muted
    val b = r.gpsBearing?.let { " · ${it.roundToInt()}°" } ?: ""
    return String.format("GPS 순간속도 %.1fkn%s", v * KN, b) to Muted
}

private fun driftLine(r: Readings): Pair<String, Color> {
    val d = r.drift ?: return "흐름(40초) 측정 중" to Muted
    return String.format("흐름 %.0f초 %.2fkn · %d° · %s", d.span, d.knots, d.bearing.roundToInt(), d.quality) to
        (if (d.quality == "낮음") Warn else Color.White)
}

private fun headingLine(r: Readings): Pair<String, Color> {
    val h = r.heading ?: return "나침반 없음" to Bad
    return String.format("정면 %03d° (편차 %.1f°)", h.roundToInt() % 360, r.declination) to Color.White
}

private fun tiltLine(r: Readings): Pair<String, Color> {
    val tilt = r.tilt ?: return "기울기 -" to Muted
    val acc = r.headingAcc?.let { " · 방위오차 ±${it.roundToInt()}°" } ?: ""
    return "기울기 ${tilt.roundToInt()}°$acc" to (if (tilt > 50) Warn else Muted)
}

private fun magLine(r: Readings): Pair<String, Color> {
    val b = r.magField ?: return "자기장 센서 없음" to Bad
    val cal = when (r.magAccuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "보정 높음"
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "보정 보통"
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "보정 낮음"
        else -> "보정 필요(8자)"
    }
    val disturbed = abs(b - EARTH_FIELD) > 15
    return "자기장 ${b.roundToInt()}µT${if (disturbed) " 간섭!" else ""} · $cal" to
        (if (disturbed) Bad else if (r.magAccuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) Warn else Muted)
}

private fun batteryLine(r: Readings): Pair<String, Color> {
    val pct = r.battery ?: return "배터리 -" to Muted
    val rate = r.batteryPerHour?.let { String.format(" · 시간당 %.1f%%", it) } ?: " · 소모량 10분 후 표시"
    return "배터리 $pct%$rate" to Muted
}
