package com.milebloom.app.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener

/**
 * Pleos Vehicle SDK 래퍼.
 *
 * 규약 (docs/07-sdk-reference.md 2장):
 * 1. `initialize()` 후 도메인마다 `checkXxxCapabilities()`로 지원 여부를 먼저 확인하고, 지원될 때만 조회·리스너 등록
 * 2. 결과는 모두 비동기 콜백. **콜백이 남아 있는 동안 `release()`하지 않는다** → [stop]이 대기 후 해제
 *
 * initialize() 직후의 호출은 SDK가 연결될 때까지 큐에 쌓아 두므로 바로 호출해도 된다 (BaseServiceConnector.cachedCall).
 */
class PleosVehicleSource(
    context: Context,
    private val update: (String, Any?, VehicleSnapshot.() -> VehicleSnapshot) -> Unit,
    private val onSupport: (Signal, SignalSupport) -> Unit,
) {
    private val vehicle = Vehicle(context)
    private val handler = Handler(Looper.getMainLooper())

    /** 응답을 기다리는 비동기 호출 수. 0이 되어야 release()한다. */
    private val inFlight = AtomicInteger(0)
    private val unregisters = mutableListOf<() -> Unit>()
    private var started = false

    /** 앰비언트 색 area. 5석 묶음 1개라 capability가 준 객체를 그대로 써야 한다 (직접 만들면 IllegalArgumentException). */
    @Volatile private var ambientArea: SeatArea? = null

    private val windshield = WindowArea(WindowValue.FRONT_WINDSHIELD)

    fun start() {
        if (started) return
        started = true
        try {
            vehicle.initialize()
        } catch (t: Throwable) {
            Log.e(TAG, "Vehicle.initialize 실패: ${t.message}")
            Signal.entries.filter { it != Signal.GEAR && it != Signal.UX_RESTRICTIONS }
                .forEach { onSupport(it, SignalSupport.Unsupported("initialize 실패")) }
            return
        }
        startOdometer()
        startWiper()
        startLowBeam()
        startWindows()
        startDoors()
        startSteering()
        startBrake()
        startAmbient()
    }

    // ───────────── 비동기 호출 추적 ─────────────

    /** SDK 비동기 호출 1건을 감싼다. 성공·실패 어느 쪽이든 정확히 한 번 [done]이 불려야 한다. */
    private fun call(name: String, body: (done: () -> Unit) -> Unit) {
        inFlight.incrementAndGet()
        var finished = false
        val done = {
            if (!finished) {
                finished = true
                inFlight.decrementAndGet()
            }
        }
        try {
            body(done)
        } catch (t: Throwable) {
            Log.e(TAG, "$name 예외: ${t.javaClass.simpleName}: ${t.message}", t)
            done()
        }
    }

    private fun failed(signal: Signal, step: String, e: Exception) {
        Log.w(TAG, "${signal.name} $step 실패: ${e.message}")
        onSupport(signal, SignalSupport.Unsupported("$step: ${e.message}"))
    }

    private fun register(signal: Signal, body: () -> Unit, unregister: () -> Unit) {
        try {
            body()
            synchronized(unregisters) { unregisters += unregister }
        } catch (t: Throwable) {
            Log.e(TAG, "${signal.name} 리스너 등록 실패: ${t.javaClass.simpleName}: ${t.message}")
            onSupport(signal, SignalSupport.Unsupported("register: ${t.javaClass.simpleName}"))
        }
    }

    // ───────────── 주행거리 ─────────────

    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) = setOdometer(value)
        override fun onFailed(e: Exception) { Log.w(TAG, "주행거리 리스너: ${e.message}") }
    }

    private fun setOdometer(km: Float?) = update("odometerKm", km) { copy(odometerKm = km) }

    private fun startOdometer() = call("odometer capability") { done ->
        vehicle.getOdometer().checkCurrentDistanceDrivenCapabilities(
            onComplete = { _ ->
                done()
                onSupport(Signal.ODOMETER, SignalSupport.Supported)
                call("odometer get") { d ->
                    vehicle.getOdometer().getCurrentDistanceDriven(
                        onComplete = { km -> setOdometer(km); d() },
                        onFailed = { e -> Log.w(TAG, "주행거리 조회: ${e.message}"); d() },
                    )
                }
                register(Signal.ODOMETER,
                    { vehicle.getOdometer().registerDistanceDriven(odometerListener) },
                    { vehicle.getOdometer().unregisterDistanceDriven(odometerListener) })
            },
            onFailed = { e -> failed(Signal.ODOMETER, "capability", e); done() },
        )
    }

    // ───────────── 와이퍼 ─────────────

    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) = setWiperState(state)
        override fun onFailed(e: Exception) { Log.w(TAG, "와이퍼 리스너: ${e.message}") }
    }

    /**
     * WiperState는 OFF/ON/SERVICE뿐이다. 단수는 스위치를 따로 읽어야 한다 (13번 11장).
     * 스위치 리스너가 SDK에 없고, ON인 채로 HIGH→AUTO처럼 바뀌면 상태 리스너가 오지 않는다 (5-A 실측)
     * → 와이퍼가 켜져 있는 동안만 [WIPER_POLL_MS]마다 스위치를 다시 읽는다.
     */
    private fun setWiperState(state: WiperState?) {
        val on = state?.let { it == WiperState.ON }
        update("wiperOn", state) { copy(wiperOn = on) }
        readWiperSwitch()
        handler.removeCallbacks(wiperPoll)
        if (on == true && started) handler.postDelayed(wiperPoll, WIPER_POLL_MS)
    }

    private val wiperPoll = object : Runnable {
        override fun run() {
            if (!started) return
            readWiperSwitch()
            handler.postDelayed(this, WIPER_POLL_MS)
        }
    }

    private fun readWiperSwitch() = call("wiper switch get") { done ->
        vehicle.getWiper().getCurrentWiperSwitch(
            area = windshield,
            onComplete = { s ->
                val level = s?.toLevel()
                update("wiperSwitch", level) { copy(wiperSwitch = level) }
                done()
            },
            onFailed = { e -> Log.w(TAG, "와이퍼 스위치 조회: ${e.message}"); done() },
        )
    }

    private fun startWiper() = call("wiper capability") { done ->
        vehicle.getWiper().checkWiperStateCapabilities(
            onComplete = { _, areas ->
                done()
                if (!areas.containsKey(windshield)) {
                    onSupport(Signal.WIPER, SignalSupport.Unsupported("앞유리 area 없음"))
                    return@checkWiperStateCapabilities
                }
                onSupport(Signal.WIPER, SignalSupport.Supported)
                call("wiper state get") { d ->
                    vehicle.getWiper().getCurrentWiperState(
                        area = windshield,
                        onComplete = { s -> setWiperState(s); d() },
                        onFailed = { e -> Log.w(TAG, "와이퍼 상태 조회: ${e.message}"); d() },
                    )
                }
                register(Signal.WIPER,
                    { vehicle.getWiper().registerWiperState(windshield, wiperListener) },
                    { vehicle.getWiper().unregisterWiperState(windshield, wiperListener) })
            },
            onFailed = { e -> failed(Signal.WIPER, "capability", e); done() },
        )
    }

    private fun WiperSwitch.toLevel(): WiperLevel = when (this) {
        WiperSwitch.OFF -> WiperLevel.OFF
        WiperSwitch.AUTO -> WiperLevel.AUTO
        WiperSwitch.CONTINUOUS_LEVEL_1, WiperSwitch.CONTINUOUS_LEVEL_2,
        WiperSwitch.INTERMITTENT_LEVEL_1, WiperSwitch.INTERMITTENT_LEVEL_2,
        WiperSwitch.INTERMITTENT_LEVEL_3, WiperSwitch.MIST -> WiperLevel.LOW
        WiperSwitch.CONTINUOUS_LEVEL_3, WiperSwitch.CONTINUOUS_LEVEL_4, WiperSwitch.CONTINUOUS_LEVEL_5,
        WiperSwitch.INTERMITTENT_LEVEL_4, WiperSwitch.INTERMITTENT_LEVEL_5 -> WiperLevel.HIGH
        else -> WiperLevel.OTHER
    }

    // ───────────── 헤드램프 ─────────────

    private val lowBeamListener = object : LightStateListener {
        override fun onLightStateUpdated(state: LightState?) = setLowBeam(state)
        override fun onFailed(e: Exception) { Log.w(TAG, "헤드램프 리스너: ${e.message}") }
    }

    private fun setLowBeam(state: LightState?) =
        update("lowBeamOn", state) { copy(lowBeamOn = state?.let { it == LightState.ON }) }

    private fun startLowBeam() = call("low beam capability") { done ->
        vehicle.getLight().checkLowBeamLightStateCapabilities(
            onComplete = { _, _ ->
                done()
                onSupport(Signal.LOW_BEAM, SignalSupport.Supported)
                call("low beam get") { d ->
                    vehicle.getLight().getCurrentLowBeamLightState(
                        onComplete = { s -> setLowBeam(s); d() },
                        onFailed = { e -> Log.w(TAG, "헤드램프 조회: ${e.message}"); d() },
                    )
                }
                register(Signal.LOW_BEAM,
                    { vehicle.getLight().registerLowBeamLightState(lowBeamListener) },
                    { vehicle.getLight().unregisterLowBeamLightState(lowBeamListener) })
            },
            onFailed = { e -> failed(Signal.LOW_BEAM, "capability", e); done() },
        )
    }

    // ───────────── 창문 (0..10) ─────────────

    private val windowAreas = mapOf(
        SeatPos.FRONT_LEFT to WindowValue.ROW_1_LEFT,
        SeatPos.FRONT_RIGHT to WindowValue.ROW_1_RIGHT,
        SeatPos.REAR_LEFT to WindowValue.ROW_2_LEFT,
        SeatPos.REAR_RIGHT to WindowValue.ROW_2_RIGHT,
    )

    private fun setWindow(pos: SeatPos, v: Int?) =
        update("window.$pos", v) { copy(windows = if (v == null) windows - pos else windows + (pos to v)) }

    private fun startWindows() = call("window capability") { done ->
        vehicle.getWindow().checkWindowPositionCapabilities(
            onComplete = { _, bounds ->
                done()
                val present = windowAreas.filterValues { bounds.containsKey(WindowArea(it)) }
                if (present.isEmpty()) {
                    onSupport(Signal.WINDOW, SignalSupport.Unsupported("좌석 창문 area 없음"))
                    return@checkWindowPositionCapabilities
                }
                onSupport(Signal.WINDOW, SignalSupport.Supported)
                present.forEach { (pos, value) ->
                    val area = WindowArea(value)
                    call("window get $pos") { d ->
                        vehicle.getWindow().getCurrentWindowPosition(
                            area = area,
                            onComplete = { p -> setWindow(pos, p); d() },
                            onFailed = { e -> Log.w(TAG, "창문 $pos 조회: ${e.message}"); d() },
                        )
                    }
                    val listener = object : WindowPositionListener {
                        override fun onWindowPositionUpdated(position: Int?) = setWindow(pos, position)
                        override fun onFailed(e: Exception) { Log.w(TAG, "창문 $pos 리스너: ${e.message}") }
                    }
                    register(Signal.WINDOW,
                        { vehicle.getWindow().registerWindowPosition(area, listener) },
                        { vehicle.getWindow().unregisterWindowPosition(area, listener) })
                }
            },
            onFailed = { e -> failed(Signal.WINDOW, "capability", e); done() },
        )
    }

    // ───────────── 도어 (0/1) ─────────────

    private val doorAreas = mapOf(
        SeatPos.FRONT_LEFT to DoorValue.ROW_1_LEFT,
        SeatPos.FRONT_RIGHT to DoorValue.ROW_1_RIGHT,
        SeatPos.REAR_LEFT to DoorValue.ROW_2_LEFT,
        SeatPos.REAR_RIGHT to DoorValue.ROW_2_RIGHT,
    )

    private fun setDoor(pos: SeatPos, p: Int?) {
        val open = p?.let { it > 0 }
        update("door.$pos", open) { copy(doorsOpen = if (open == null) doorsOpen - pos else doorsOpen + (pos to open)) }
    }

    private fun startDoors() = call("door capability") { done ->
        vehicle.getDoor().checkDoorPositionCapabilities(
            onComplete = { _, bounds ->
                done()
                val present = doorAreas.filterValues { bounds.containsKey(DoorArea(it)) }
                if (present.isEmpty()) {
                    onSupport(Signal.DOOR, SignalSupport.Unsupported("좌석 도어 area 없음"))
                    return@checkDoorPositionCapabilities
                }
                onSupport(Signal.DOOR, SignalSupport.Supported)
                present.forEach { (pos, value) ->
                    val area = DoorArea(value)
                    call("door get $pos") { d ->
                        vehicle.getDoor().getCurrentDoorPosition(
                            area = area,
                            onComplete = { p -> setDoor(pos, p); d() },
                            onFailed = { e -> Log.w(TAG, "도어 $pos 조회: ${e.message}"); d() },
                        )
                    }
                    val listener = object : DoorPositionListener {
                        override fun onPositionUpdated(pos2: Int?) = setDoor(pos, pos2)
                        override fun onFailed(e: Exception) { Log.w(TAG, "도어 $pos 리스너: ${e.message}") }
                    }
                    register(Signal.DOOR,
                        { vehicle.getDoor().registerDoorPosition(area, listener) },
                        { vehicle.getDoor().unregisterDoorPosition(area, listener) })
                }
            },
            onFailed = { e -> failed(Signal.DOOR, "capability", e); done() },
        )
    }

    // ───────────── 조향각 ─────────────

    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) = setSteering(angle)
        override fun onFailed(e: Exception) { Log.w(TAG, "조향 리스너: ${e.message}") }
    }

    /** capability는 -470..500을 주지만 실제로 -500이 들어온다 → ±500 대칭 클램프 (13-A). */
    private fun setSteering(angle: Float?) {
        val v = angle?.coerceIn(-STEERING_LIMIT, STEERING_LIMIT)
        update("steeringDeg", v) { copy(steeringDeg = v) }
    }

    private fun startSteering() = call("steering capability") { done ->
        vehicle.getSteeringWheel().checkSteeringWheelAngleCapabilities(
            onComplete = { _, _ ->
                done()
                onSupport(Signal.STEERING, SignalSupport.Supported)
                call("steering get") { d ->
                    vehicle.getSteeringWheel().getCurrentSteeringWheelAngle(
                        onComplete = { a -> setSteering(a); d() },
                        onFailed = { e -> Log.w(TAG, "조향 조회: ${e.message}"); d() },
                    )
                }
                register(Signal.STEERING,
                    { vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener) },
                    { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) })
            },
            onFailed = { e -> failed(Signal.STEERING, "capability", e); done() },
        )
    }

    // ───────────── 브레이크 ─────────────

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) = setBrake(status)
        override fun onFailed(e: Exception) { Log.w(TAG, "브레이크 리스너: ${e.message}") }
    }

    private fun setBrake(b: Boolean?) = update("brakePressed", b) { copy(brakePressed = b) }

    private fun startBrake() = call("brake capability") { done ->
        vehicle.getBrake().checkBrakePedalStatusCapabilities(
            onComplete = { _ ->
                done()
                onSupport(Signal.BRAKE, SignalSupport.Supported)
                call("brake get") { d ->
                    vehicle.getBrake().getCurrentBrakePedalStatus(
                        onComplete = { b -> setBrake(b); d() },
                        onFailed = { e -> Log.w(TAG, "브레이크 조회: ${e.message}"); d() },
                    )
                }
                register(Signal.BRAKE,
                    { vehicle.getBrake().registerBrakePedalStatus(brakeListener) },
                    { vehicle.getBrake().unregisterBrakePedalStatus(brakeListener) })
            },
            onFailed = { e -> failed(Signal.BRAKE, "capability", e); done() },
        )
    }

    // ───────────── 앰비언트 (쓰기) ─────────────

    private fun startAmbient() = call("ambient capability") { done ->
        vehicle.getLight().checkAmbientLightBrightnessColorCapabilities(
            onComplete = { _, areas ->
                done()
                ambientArea = areas.firstOrNull()
                onSupport(
                    Signal.AMBIENT,
                    if (ambientArea != null) SignalSupport.Supported else SignalSupport.Unsupported("area 없음"),
                )
            },
            onFailed = { e -> failed(Signal.AMBIENT, "capability", e); done() },
        )
    }

    /**
     * 실내 앰비언트 색을 바꾼다. 임의 ARGB 동작 확인됨 (13번 4장).
     * 되읽기 알파가 0x00으로 오므로 확인은 RGB만 비교한다. 결과 콜백은 SDK 스레드에서 온다.
     */
    fun setAmbientColor(argb: Int, onResult: (Boolean) -> Unit = {}) {
        val area = ambientArea
        if (area == null) {
            onResult(false)
            return
        }
        call("ambient set") { done ->
            vehicle.getLight().setAmbientLightBrightnessColor(
                area = area,
                value = argb,
                onComplete = { _, applied -> done(); onResult((applied and 0xFFFFFF) == (argb and 0xFFFFFF)) },
                onFailed = { e -> Log.w(TAG, "앰비언트 쓰기: ${e.message}"); done(); onResult(false) },
            )
        }
    }

    /**
     * 실내 조명 스위치. true = ON·AUTO, false = OFF, null = 읽지 못함. 콜백은 SDK 스레드에서 온다.
     * ⚠️ 에뮬레이터에서는 Console 스위치와 연결돼 있지 않고 부팅 후 늘 OFF다 (13번 19장) — 판단에 쓰지 말고 기록만 한다
     */
    fun readAmbientSwitchOn(onResult: (Boolean?) -> Unit) {
        val area = ambientArea
        if (area == null) {
            onResult(null)
            return
        }
        call("ambient switch") { done ->
            vehicle.getLight().getAmbientLightSwitch(
                area = area,
                onComplete = { sw -> done(); onResult(sw?.let { it != LightSwitch.OFF }) },
                onFailed = { e -> Log.w(TAG, "앰비언트 스위치 읽기: ${e.message}"); done(); onResult(null) },
            )
        }
    }

    /** 현재 실내 조명 색. 되읽기 알파는 0x00으로 온다 (13번 4장). 콜백은 SDK 스레드에서 온다 */
    fun readAmbientColor(onResult: (Int?) -> Unit) {
        val area = ambientArea
        if (area == null) {
            onResult(null)
            return
        }
        call("ambient get") { done ->
            vehicle.getLight().getAmbientLightBrightnessColor(
                area = area,
                onComplete = { c -> done(); onResult(c) },
                onFailed = { e -> Log.w(TAG, "앰비언트 색 읽기: ${e.message}"); done(); onResult(null) },
            )
        }
    }

    // ───────────── 해제 ─────────────

    /** 리스너를 모두 해제하고, 남은 비동기 콜백이 끝난 뒤(최대 [RELEASE_WAIT_MS]) `release()`한다. */
    fun stop(onReleased: () -> Unit = {}) {
        if (!started) { onReleased(); return }
        started = false
        handler.removeCallbacks(wiperPoll)
        val list =synchronized(unregisters) { unregisters.toList().also { unregisters.clear() } }
        list.forEach { try { it() } catch (_: Throwable) {} }
        val deadline = SystemClock.uptimeMillis() + RELEASE_WAIT_MS
        val tryRelease = object : Runnable {
            override fun run() {
                val n = inFlight.get()
                if (n > 0 && SystemClock.uptimeMillis() < deadline) {
                    handler.postDelayed(this, 50)
                    return
                }
                if (n > 0) Log.w(TAG, "응답 대기 ${n}건 남은 채 시간 초과 — release 진행")
                try { vehicle.release() } catch (_: Throwable) {}
                Log.i(TAG, "Vehicle.release 완료")
                onReleased()
            }
        }
        handler.post(tryRelease)
    }

    private companion object {
        const val TAG = "MBVEH"
        const val STEERING_LIMIT = 500f
        const val RELEASE_WAIT_MS = 2_000L
        const val WIPER_POLL_MS = 2_000L
    }
}
