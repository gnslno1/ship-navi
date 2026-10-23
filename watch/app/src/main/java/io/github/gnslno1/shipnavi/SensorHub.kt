package io.github.gnslno1.shipnavi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

const val DEFAULT_DECLINATION = -8.5   // 한국 자북 편차 (GPS 위치를 받으면 실제 값으로 바뀜)

/** 화면에 보여줄 값 묶음 (0.25초마다 갱신) */
data class Readings(
    val hasPermission: Boolean = false,
    val gpsEnabled: Boolean = true,
    val gpsAcc: Double? = null,
    val gpsSpeed: Double? = null,      // m/s (GPS 칩이 직접 계산한 순간 속도)
    val gpsBearing: Double? = null,
    val fixAge: Double? = null,        // 마지막 GPS 수신 후 경과 초
    val satsUsed: Int = 0,
    val satsVisible: Int = 0,
    val drift: Drift? = null,
    val heading: Double? = null,       // 진북 기준 내 정면 방위
    val declination: Double = DEFAULT_DECLINATION,
    val headingAcc: Double? = null,    // 센서가 추정한 방위 오차 (°)
    val tilt: Double? = null,          // 화면이 수평에서 기운 각도 (°)
    val magField: Double? = null,      // 자기장 세기 (µT)
    val magAccuracy: Int = -1,         // SensorManager.SENSOR_STATUS_*
    val battery: Int? = null,
    val batteryPerHour: Double? = null,
    val logFile: String? = null,
    val logRows: Int = 0,
)

/** 워치 GPS·방위 센서·배터리를 읽고, 1초마다 CSV로 기록한다. */
class SensorHub(private val ctx: Context) : SensorEventListener, LocationListener {
    var readings by mutableStateOf(Readings())
        private set

    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val bm = ctx.getSystemService(BatteryManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val estimator = DriftEstimator()

    private var locationOn = false
    private var lastLoc: Location? = null
    private var drift: Drift? = null
    private var satsUsed = 0
    private var satsVisible = 0
    private var declination: Double? = null

    private var hs = 0.0              // 방위 스무딩용 sin/cos 누적값
    private var hc = 0.0
    private var hasHeading = false
    private var headingAcc: Double? = null
    private var tilt: Double? = null
    private var magField: Double? = null
    private var magAccuracy = -1
    private val rot = FloatArray(9)

    private val startedAt = SystemClock.elapsedRealtime()
    private var startBattery: Int? = null
    private var log: BufferedWriter? = null
    private var logFile: File? = null
    private var logRows = 0
    private var ticks = 0

    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satsVisible = status.satelliteCount
            satsUsed = (0 until status.satelliteCount).count { status.usedInFix(it) }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            publish()
            if (++ticks % 4 == 0) writeLog()
            handler.postDelayed(this, 250)
        }
    }

    fun start() {
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        startBattery = batteryPct()
        startLog()
        handler.post(tick)
    }

    // 워치 자체 GPS를 재려고 FusedLocationProvider 대신 GPS_PROVIDER를 직접 쓴다.
    // (Fused는 휴대폰과 연결돼 있으면 휴대폰 위치를 빌려 쓸 수 있다.)
    @SuppressLint("MissingPermission")
    fun startLocation() {
        if (locationOn || !hasPermission()) return
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        lm.registerGnssStatusCallback(gnss, handler)
        locationOn = true
    }

    fun flush() {
        log?.flush()
    }

    fun stop() {
        handler.removeCallbacks(tick)
        sm.unregisterListener(this)
        if (locationOn) {
            lm.removeUpdates(this)
            lm.unregisterGnssStatusCallback(gnss)
        }
        log?.close()
        log = null
    }

    private fun hasPermission() =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onLocationChanged(loc: Location) {
        lastLoc = loc
        estimator.add(Fix(loc.elapsedRealtimeNanos / 1e9, loc.latitude, loc.longitude, loc.accuracy.toDouble()))
        drift = estimator.estimate()
        declination = GeomagneticField(
            loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), loc.time,
        ).declination.toDouble()
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                // 기기 좌표의 (위쪽 + 화면 반대쪽) 벡터 (0, 1, -1)을 지구 좌표(동, 북, 위)로 회전.
                // 손목을 들어 화면을 보면 워치 12시 쪽이 내 정면을 향한다. 웹앱의 lookHeading과 같은 방향.
                val h = atan2((rot[1] - rot[2]).toDouble(), (rot[4] - rot[5]).toDouble())
                if (!hasHeading) {
                    hs = sin(h); hc = cos(h); hasHeading = true
                } else {
                    hs += 0.2 * (sin(h) - hs)
                    hc += 0.2 * (cos(h) - hc)
                }
                tilt = acos(rot[8].toDouble().coerceIn(-1.0, 1.0)) / DEG
                headingAcc = if (e.values.size > 4 && e.values[4] >= 0) e.values[4] / DEG else null
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magField = sqrt(e.values.sumOf { (it * it).toDouble() })
                magAccuracy = e.accuracy
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) magAccuracy = accuracy
    }

    private fun publish() {
        val loc = lastLoc
        val decl = declination ?: DEFAULT_DECLINATION
        readings = Readings(
            hasPermission = hasPermission(),
            gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER),
            gpsAcc = loc?.accuracy?.toDouble(),
            gpsSpeed = loc?.takeIf { it.hasSpeed() }?.speed?.toDouble(),
            gpsBearing = loc?.takeIf { it.hasBearing() }?.bearing?.toDouble(),
            fixAge = loc?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1e9 },
            satsUsed = satsUsed,
            satsVisible = satsVisible,
            drift = drift,
            heading = if (hasHeading) norm360(atan2(hs, hc) / DEG + decl) else null,
            declination = decl,
            headingAcc = headingAcc,
            tilt = tilt,
            magField = magField,
            magAccuracy = magAccuracy,
            battery = batteryPct(),
            batteryPerHour = batteryPerHour(),
            logFile = logFile?.name,
            logRows = logRows,
        )
    }

    private fun batteryPct() = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }

    /** 시작 후 10분이 지나야 시간당 소모량을 계산한다. */
    private fun batteryPerHour(): Double? {
        val start = startBattery ?: return null
        val now = batteryPct() ?: return null
        val hours = (SystemClock.elapsedRealtime() - startedAt) / 3_600_000.0
        return if (hours < 1.0 / 6) null else (start - now) / hours
    }

    // ---------------------------------------------------------------- CSV 기록

    private fun startLog() {
        val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
        val file = File(dir, "log_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv")
        logFile = file
        log = file.bufferedWriter().apply {
            write(
                "time,lat,lon,acc_m,fix_age_s,gps_speed_mps,gps_bearing,sats_used,sats_visible," +
                    "heading_true,heading_acc,tilt,mag_uT,mag_acc,drift_kn,drift_bearing,drift_se_mps,battery\n",
            )
        }
    }

    private fun writeLog() {
        val w = log ?: return
        val r = readings
        val loc = lastLoc
        fun f(v: Double?, digits: Int = 1) = v?.let { String.format(Locale.US, "%.${digits}f", it) } ?: ""
        val row = listOf(
            TIME.format(Date()), f(loc?.latitude, 7), f(loc?.longitude, 7), f(r.gpsAcc), f(r.fixAge),
            f(r.gpsSpeed, 2), f(r.gpsBearing), r.satsUsed, r.satsVisible,
            f(r.heading), f(r.headingAcc), f(r.tilt), f(r.magField), r.magAccuracy,
            f(r.drift?.knots, 2), f(r.drift?.bearing), f(r.drift?.se, 3), r.battery ?: "",
        )
        w.write(row.joinToString(",") + "\n")
        if (++logRows % 10 == 0) w.flush()
    }

    private companion object {
        val TIME = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }
}
