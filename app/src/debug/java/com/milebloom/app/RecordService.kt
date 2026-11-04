package com.milebloom.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener

import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.listener.EventListener as TtsEventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsOnInitializeListener

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData

/**
 * 화면 없이 주행을 기록할 수 있는지 확인하는 포그라운드 서비스 (open question #29).
 *
 * 서비스 안에서 Vehicle SDK 리스너와 AAOS GEAR_SELECTION 콜백을 등록하고,
 * P 전환 시 FusedLocation 단발 조회까지 해 본다. 생존 시간을 재기 위해 60초마다 heartbeat를 남긴다.
 * 결과는 Logcat 태그 `MBBG`.
 */
class RecordService : Service() {

    companion object {
        const val TAG = "MBBG"
        private const val CHANNEL_ID = "milebloom_record"
        private const val NOTIFICATION_ID = 7001
        private const val HEARTBEAT_MS = 60_000L

        private const val AAOS_GEAR_PARK = 0x0004
        private const val AAOS_GEAR_DRIVE = 0x0008

        fun start(context: Context, reason: String) {
            val intent = Intent(context, RecordService::class.java).putExtra("reason", reason)
            context.startForegroundService(intent)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var beats = 0

    private var vehicle: Vehicle? = null
    private var car: android.car.Car? = null
    private var fusedLocation: FusedLocation? = null

    private val heartbeat = object : Runnable {
        override fun run() {
            beats++
            val mins = (SystemClock.elapsedRealtime() - startedAt) / 60_000
            Log.i(TAG, "heartbeat #$beats — 생존 ${mins}분 pid=${Process.myPid()}")
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra("reason") ?: "restart(null intent)"
        Log.i(TAG, "onStartCommand reason=$reason startId=$startId")

        if (startedAt == 0L) {
            startedAt = SystemClock.elapsedRealtime()
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                Log.i(TAG, "startForeground 성공 (type=specialUse)")
            } catch (t: Throwable) {
                Log.e(TAG, "startForeground 실패: ${t.javaClass.simpleName}: ${t.message}")
                stopSelf()
                return START_NOT_STICKY
            }
            registerSignals()
            handler.postDelayed(heartbeat, HEARTBEAT_MS)
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "주행 기록", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Milebloom")
            .setContentText("주행을 기록하고 있어요")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    // ───────────── 신호 등록 ─────────────

    private fun registerSignals() {
        val v = Vehicle(this).also { vehicle = it }
        try {
            v.initialize()
            Log.i(TAG, "Vehicle.initialize OK (서비스 안)")
        } catch (t: Throwable) {
            Log.e(TAG, "Vehicle.initialize 실패: ${t.message}")
        }

        // 서비스 바인딩에 시간을 준 뒤 리스너 등록
        handler.postDelayed({
            try {
                v.getWiper().registerWiperState(
                    WindowArea(WindowValue.FRONT_WINDSHIELD),
                    object : WiperStateListener {
                        override fun onWiperStateUpdated(state: WiperState?) =
                            bg("WiperState", "$state")
                        override fun onFailed(e: Exception) = bg("WiperState 오류", "${e.message}")
                    }
                )
                v.getOdometer().registerDistanceDriven(object : DistanceDrivenListener {
                    override fun onDistanceDrivenUpdated(value: Float?) = bg("DistanceDriven", "$value")
                    override fun onFailed(e: Exception) = bg("DistanceDriven 오류", "${e.message}")
                })
                v.getLight().registerLowBeamLightState(object : LightStateListener {
                    override fun onLightStateUpdated(state: LightState?) = bg("LowBeam", "$state")
                    override fun onFailed(e: Exception) = bg("LowBeam 오류", "${e.message}")
                })
                Log.i(TAG, "Vehicle 리스너 3종 등록 OK (와이퍼·주행거리·헤드램프)")
            } catch (t: Throwable) {
                Log.e(TAG, "Vehicle 리스너 등록 실패: ${t.javaClass.simpleName}: ${t.message}")
            }
            registerGear()
            loadAmbientArea()
        }, 1500)
    }

    /** 같은 값이 반복되는 리스너(주행거리)는 직전과 같으면 생략한다. */
    private val lastValue = HashMap<String, String>()

    private fun bg(name: String, value: String) {
        if (lastValue[name] == value) return
        lastValue[name] = value
        Log.i(TAG, "▶ [화면 없음] $name = $value")
    }

    private fun registerGear() {
        try {
            val c = android.car.Car.createCar(this).also { car = it }
            val cpm = c?.getCarManager(android.car.Car.PROPERTY_SERVICE)
                    as? android.car.hardware.property.CarPropertyManager
            if (cpm == null) {
                Log.e(TAG, "CarPropertyManager 획득 실패")
                return
            }
            val id = android.car.VehiclePropertyIds.GEAR_SELECTION
            val ok = cpm.registerCallback(
                object : android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback {
                    override fun onChangeEvent(v: android.car.hardware.CarPropertyValue<*>) {
                        val g = v.value as? Int ?: -1
                        bg("GEAR_SELECTION", "$g")
                        if (g == AAOS_GEAR_PARK) onPark()
                    }
                    override fun onErrorEvent(propertyId: Int, zone: Int) =
                        bg("GEAR_SELECTION 오류", "zone=$zone")
                },
                id,
                android.car.hardware.property.CarPropertyManager.SENSOR_RATE_ONCHANGE
            )
            Log.i(TAG, "AAOS GEAR_SELECTION 콜백 등록=$ok (서비스 안)")
        } catch (t: Throwable) {
            Log.e(TAG, "AAOS 기어 등록 실패: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ───────────── P 체결 연출: 앰비언트 색 · TTS (14-8, 14-9) ─────────────

    /** 앰비언트 색 프로퍼티가 받는 area. 5석 묶음 1개라 capability가 돌려준 객체를 그대로 쓴다. */
    private var ambientArea: SeatArea? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private fun loadAmbientArea() {
        val v = vehicle ?: return
        try {
            v.getLight().checkAmbientLightBrightnessColorCapabilities(
                onComplete = { config: CapabilityConfig, areas: List<SeatArea> ->
                    ambientArea = areas.firstOrNull()
                    Log.i(TAG, "앰비언트 capability (서비스 안) access=${config.accessType} areas=${areas.size}")
                },
                onFailed = { e -> Log.e(TAG, "앰비언트 capability 실패: ${e.message}") }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "앰비언트 capability 예외: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun setAmbientOnPark() {
        val v = vehicle ?: return
        val area = ambientArea
        if (area == null) {
            Log.e(TAG, "앰비언트 area 없음 — 색 변경 건너뜀")
            return
        }
        val target = 0xFF8C78C8.toInt()   // 보랏빛. 이전 검증색 #78C88C와 구분
        try {
            v.getLight().setAmbientLightBrightnessColor(
                area = area,
                value = target,
                onComplete = { _, applied ->
                    Log.i(TAG, "▶ [화면 없음] 앰비언트 set 성공 요청=${hex(target)} 적용=${hex(applied)}")
                    v.getLight().getAmbientLightBrightnessColor(
                        area = area,
                        onComplete = { back ->
                            val same = back != null && (back and 0xFFFFFF) == (target and 0xFFFFFF)
                            Log.i(TAG, "▶ [화면 없음] 앰비언트 되읽기=${back?.let { hex(it) }} RGB일치=$same")
                        },
                        onFailed = { e -> Log.e(TAG, "앰비언트 되읽기 실패: ${e.message}") }
                    )
                },
                onFailed = { e -> Log.e(TAG, "앰비언트 set 실패: ${e.javaClass.simpleName}: ${e.message}") }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "앰비언트 set 예외: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun hex(argb: Int) = String.format("#%08X", argb)

    private fun speakOnPark() {
        val line = "오늘 숲이 조금 자랐어요"
        try {
            val t = tts ?: TextToSpeech(applicationContext, TtsMode.ON_DEVICE).also { tts = it }
            if (ttsReady) {
                t.speak(line)
                Log.i(TAG, "TTS speak 요청 (재사용)")
                return
            }
            t.initialize(object : TtsOnInitializeListener {
                override fun onSuccess() {
                    ttsReady = true
                    Log.i(TAG, "TTS initialize 성공 (서비스 안)")
                    try {
                        t.addEventListener(object : TtsEventListener {
                            override fun onReady() { Log.i(TAG, "TTS onReady") }
                            override fun onStart() { Log.i(TAG, "▶ [화면 없음] TTS onStart — 발화 시작") }
                            override fun onUpdatedRms(rms: Double) = Unit
                            override fun onDone() { Log.i(TAG, "▶ [화면 없음] TTS onDone — 발화 완료") }
                            override fun onStop() { Log.i(TAG, "TTS onStop") }
                            override fun onError(errorType: TtsErrorType) { Log.e(TAG, "TTS onError $errorType") }
                        })
                        t.setLocale(java.util.Locale.KOREA)
                        t.speak(line)
                        Log.i(TAG, "TTS speak 요청: \"$line\"")
                    } catch (e: Throwable) {
                        Log.e(TAG, "TTS speak 예외: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }

                override fun onFailure(errorType: TtsErrorType) {
                    Log.e(TAG, "TTS initialize 실패: $errorType")
                }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "TTS 예외: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ───────────── P 지점 좌표 ─────────────

    private var parkCount = 0

    private fun onPark() {
        parkCount++
        // 첫 콜백은 등록 직후 현재값(P)이다. 실제 P 전환만 조회한다.
        if (parkCount == 1) return
        setAmbientOnPark()
        speakOnPark()
        try {
            val fl = fusedLocation ?: FusedLocation(this).also {
                it.initialize()
                fusedLocation = it
            }
            handler.postDelayed({
                try {
                    fl.getLastKnownLocation(object : FusedLocationListener {
                        override fun onChanged(location: LocationData?) {
                            Log.i(
                                TAG,
                                "▶ [화면 없음] P 지점 좌표 = " +
                                    (location?.let { "${it.latitude}, ${it.longitude}" } ?: "null")
                            )
                        }
                        override fun onError(exception: Exception) {
                            Log.e(TAG, "P 지점 좌표 onError: ${exception.message}")
                        }
                    })
                } catch (t: Throwable) {
                    Log.e(TAG, "P 지점 좌표 동기 예외: ${t.javaClass.simpleName}: ${t.message}")
                }
            }, 1200)
        } catch (t: Throwable) {
            Log.e(TAG, "FusedLocation 초기화 실패: ${t.message}")
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "onTimeout — 시스템이 FGS 시간 제한으로 종료 요청 (type=$fgsType)")
        stopSelf()
    }

    override fun onDestroy() {
        Log.w(TAG, "onDestroy — 생존 ${(SystemClock.elapsedRealtime() - startedAt) / 1000}초")
        handler.removeCallbacksAndMessages(null)
        try { fusedLocation?.release() } catch (_: Exception) {}
        try { tts?.release() } catch (_: Exception) {}
        try { car?.disconnect() } catch (_: Exception) {}
        try { vehicle?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
