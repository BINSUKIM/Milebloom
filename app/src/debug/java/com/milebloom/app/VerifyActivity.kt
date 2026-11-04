package com.milebloom.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessColorListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryEfficiencyListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData

/**
 * Milebloom 실동작 검증 하니스.
 *
 * docs/07-sdk-reference.md 5장 체크리스트를 순차적으로 확인한다.
 * 각 단계는 단발성 `done` 콜백과 감시 타이머를 갖는다. IPC가 응답하지 않으면
 * 멈추는 대신 TIMEOUT으로 기록하고 다음 단계로 넘어간다 — 응답 없음도 결과다.
 *
 * 결과는 Logcat 태그 `MBVERIFY`와 외부 파일 `mbverify-report.txt`에 동시에 남는다.
 */
class VerifyActivity : Activity() {

    private companion object {
        const val TAG = "MBVERIFY"
        const val STEP_TIMEOUT_MS = 6000L

        // AAOS android.car.VehicleGear 표준 값
        const val AAOS_GEAR_NEUTRAL = 0x0001
        const val AAOS_GEAR_REVERSE = 0x0002
        const val AAOS_GEAR_PARK = 0x0004
        const val AAOS_GEAR_DRIVE = 0x0008
    }

    private val handler = Handler(Looper.getMainLooper())
    private val report = StringBuilder()

    private lateinit var out: TextView
    private lateinit var vehicle: Vehicle
    private var fusedLocation: FusedLocation? = null

    private var vehicleInitialized = false
    private var pendingCallbacks = 0

    /** 큐에 쌓인 검증 단계. 각 항목은 자신이 끝날 때 전달받은 람다를 호출한다. */
    private val steps = ArrayDeque<Pair<String, (() -> Unit) -> Unit>>()
    private var currentStep: String? = null
    private var stepSettled = false

    // ────────────────────────── 로그 ──────────────────────────

    private fun log(line: String) {
        Log.i(TAG, line)
        report.append(line).append('\n')
        runOnUiThread { out.text = report.toString() }
    }

    private fun section(title: String) {
        log("")
        log("═══ $title ═══")
    }

    /** 검증 결과 한 줄. 판정은 OK / NO / COND 세 가지만 쓴다. */
    private fun result(verdict: String, item: String, detail: String) {
        log("[$verdict] $item — $detail")
    }

    // ────────────────────── 단계 실행기 ──────────────────────

    private fun step(name: String, body: (done: () -> Unit) -> Unit) {
        steps.addLast(name to body)
    }

    private fun runNext() {
        val next = steps.removeFirstOrNull()
        if (next == null) {
            finishSweep()
            return
        }
        val (name, body) = next
        currentStep = name
        stepSettled = false

        val watchdog = Runnable {
            if (!stepSettled) {
                stepSettled = true
                result("NO", name, "TIMEOUT — ${STEP_TIMEOUT_MS}ms 안에 콜백이 오지 않음")
                runNext()
            }
        }
        handler.postDelayed(watchdog, STEP_TIMEOUT_MS)

        val done = {
            if (!stepSettled) {
                stepSettled = true
                handler.removeCallbacks(watchdog)
                handler.post { runNext() }
            }
        }

        try {
            body(done)
        } catch (t: Throwable) {
            if (!stepSettled) {
                stepSettled = true
                handler.removeCallbacks(watchdog)
                result("NO", name, "예외 ${t.javaClass.simpleName}: ${t.message}")
                handler.post { runNext() }
            }
        }
    }

    private fun finishSweep() {
        section("검증 종료")
        log("남은 리스너 콜백 대기: $pendingCallbacks")
        writeReportFile()
        log("리포트 파일: ${reportFile()?.absolutePath}")
    }

    private fun reportFile(): File? =
        getExternalFilesDir(null)?.let { File(it, "mbverify-report.txt") }

    private fun writeReportFile() {
        try {
            reportFile()?.writeText(report.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "리포트 파일 기록 실패", t)
        }
    }

    // ────────────────────────── UI ──────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(250, 250, 252))
        }

        val title = TextView(this).apply {
            text = "Milebloom SDK 검증"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(25, 35, 55))
        }

        val sweepButton = Button(this).apply {
            text = "전체 검증 실행"
            setOnClickListener {
                isEnabled = false
                report.setLength(0)
                buildSweep()
                runNext()
            }
        }

        val ambientRed = Button(this).apply {
            text = "앰비언트: 빨강"
            setOnClickListener { setAmbient(Color.rgb(255, 0, 0), "빨강") }
        }

        val ambientGreen = Button(this).apply {
            text = "앰비언트: 초록"
            setOnClickListener { setAmbient(Color.rgb(0, 200, 80), "초록") }
        }

        out = TextView(this).apply {
            text = "‘전체 검증 실행’을 누르세요."
            textSize = 12f
            setTextColor(Color.rgb(20, 20, 20))
            setPadding(0, 16, 0, 16)
            typeface = android.graphics.Typeface.MONOSPACE
        }

        layout.addView(title)
        layout.addView(sweepButton)
        layout.addView(ambientRed)
        layout.addView(ambientGreen)
        layout.addView(out)

        setContentView(ScrollView(this).apply { addView(layout) })

        vehicle = Vehicle(this)
        requestRuntimePermissions()

        // am start ... --es only carinfo → 차종 판별 프로브만 실행
        if (intent?.getStringExtra("only") == "carinfo") {
            sweepButton.isEnabled = false
            step("Vehicle.initialize") { done ->
                vehicle.initialize()
                vehicleInitialized = true
                handler.postDelayed({ done() }, 1500)
            }
            addCarInfoSteps()
            runNext()
        }
        // am start ... --es only speed → 속도·정차 판별 프로브만 실행
        if (intent?.getStringExtra("only") == "speed") {
            sweepButton.isEnabled = false
            step("Vehicle.initialize") { done ->
                vehicle.initialize()
                vehicleInitialized = true
                handler.postDelayed({ done() }, 1500)
            }
            addSpeedSteps()
            runNext()
        }
    }

    /**
     * pleos.car.permission.FUSED_LOCATION 만 런타임 권한이다.
     * 나머지 pleos.car.permission.* 은 설치 시 자동 부여된다 (검증으로 확인).
     */
    private fun requestRuntimePermissions() {
        val needed = arrayOf("pleos.car.permission.FUSED_LOCATION", "android.car.permission.CAR_SPEED")
            .filter {
                checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1001)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissions.forEachIndexed { i, perm ->
            val granted = grantResults.getOrNull(i) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "런타임 권한 $perm granted=$granted")
        }
    }

    // ────────────────── 검증 단계 구성 ──────────────────

    private fun buildSweep() {
        step("Vehicle.initialize") { done ->
            section("0. SDK 초기화")
            vehicle.initialize()
            vehicleInitialized = true
            result("OK", "Vehicle.initialize", "예외 없이 반환 (연결은 비동기)")
            // 서비스 바인딩에 시간을 준다
            handler.postDelayed({ done() }, 1500)
        }

        // ── 2번: 기어 상태 ──
        step("기어: AAOS GEAR_SELECTION") { done ->
            section("체크리스트 2. 기어(P/D) 상태 취득")
            checkAaosGear()
            done()
        }

        step("기어: Pleos Gear 도메인") { done ->
            checkPleosGear(done)
        }

        // ── 5번: CarUxRestrictions ──
        step("CarUxRestrictions") { done ->
            section("체크리스트 5. CarUxRestrictions 구독")
            checkUxRestrictions()
            done()
        }

        // ── 1번: FusedLocation ──
        step("FusedLocation 단발 조회") { done ->
            section("체크리스트 1. FusedLocation 좌표 단발 조회")
            checkFusedLocation(done)
        }

        // ── 4번: Vehicle 도메인 capability 전수 ──
        step("CarInfo.getCapabilityInfo 전수") { done ->
            section("체크리스트 4. Vehicle 도메인 capability")
            vehicle.getCarInfo().getCapabilityInfo(
                onComplete = { map ->
                    if (map == null) {
                        result("NO", "getCapabilityInfo", "null 반환")
                    } else {
                        result("OK", "getCapabilityInfo", "${map.size}개 프로퍼티 반환")
                        map.keys.sorted().forEach { key ->
                            log("    · $key = ${map[key]}")
                        }
                    }
                    done()
                },
                onFailed = { e ->
                    result("NO", "getCapabilityInfo", "${e.javaClass.simpleName}: ${e.message}")
                    done()
                }
            )
        }

        addWiperSteps()
        addWindowSteps()
        addDoorSteps()
        addOdometerSteps()
        addBrakeSteps()
        addFuelSteps()
        addLightSteps()
        addAmbientSteps()
        addSteeringSteps()
        addEvBatterySteps()
        addSeatOccupancySteps()

        // ── 리스너 실제 수신 확인 ──
        step("리스너 등록 (와이퍼 / 주행거리 / 앰비언트색)") { done ->
            section("리스너 등록 — 이후 Virtual Console 조작으로 수신 확인")
            registerProbes()
            registerExtraProbes()
            done()
        }
    }

    // ───────────────────── 기어 ─────────────────────

    private fun gearName(v: Int): String = when (v) {
        AAOS_GEAR_PARK -> "P (PARK)"
        AAOS_GEAR_DRIVE -> "D (DRIVE)"
        AAOS_GEAR_NEUTRAL -> "N (NEUTRAL)"
        AAOS_GEAR_REVERSE -> "R (REVERSE)"
        else -> "기타($v)"
    }

    private fun checkAaosGear() {
        try {
            val car = android.car.Car.createCar(this)
            if (car == null) {
                result("NO", "Car.createCar", "null 반환 — car service 연결 실패")
                return
            }
            result("OK", "Car.createCar", "AAOS car service 연결됨")

            val cpm = car.getCarManager(android.car.Car.PROPERTY_SERVICE)
                    as? android.car.hardware.property.CarPropertyManager
            if (cpm == null) {
                result("NO", "CarPropertyManager", "매니저 획득 실패")
                return
            }

            val propId = android.car.VehiclePropertyIds.GEAR_SELECTION
            val available = cpm.isPropertyAvailable(propId, 0)
            result(
                if (available) "OK" else "COND",
                "GEAR_SELECTION isPropertyAvailable",
                "$available"
            )

            val value = cpm.getIntProperty(propId, 0)
            result("OK", "GEAR_SELECTION 단발 조회", "${gearName(value)} (raw=$value)")

            val cb = object : android.car.hardware.property.CarPropertyManager
            .CarPropertyEventCallback {
                override fun onChangeEvent(
                    v: android.car.hardware.CarPropertyValue<*>
                ) {
                    val iv = v.value as? Int ?: -1
                    log("    ▶ GEAR_SELECTION 변화 수신: ${gearName(iv)}")
                }

                override fun onErrorEvent(propertyId: Int, zone: Int) {
                    log("    ▶ GEAR_SELECTION 오류 이벤트: prop=$propertyId zone=$zone")
                }
            }
            val registered = cpm.registerCallback(
                cb,
                propId,
                android.car.hardware.property.CarPropertyManager.SENSOR_RATE_ONCHANGE
            )
            result(
                if (registered) "OK" else "NO",
                "GEAR_SELECTION 리스너 등록",
                "registerCallback=$registered"
            )
            pendingCallbacks++
        } catch (t: Throwable) {
            result("NO", "AAOS 기어 경로", "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun checkPleosGear(done: () -> Unit) {
        try {
            vehicle.getGear().getCurrentGearSelectionState(
                onComplete = { gear ->
                    result(
                        "COND",
                        "Pleos Gear.getCurrentGearSelectionState",
                        "값=$gear — 단, 이 API는 @Deprecated + @hide 다"
                    )
                    done()
                },
                onFailed = { e ->
                    result(
                        "NO",
                        "Pleos Gear.getCurrentGearSelectionState",
                        "${e.javaClass.simpleName}: ${e.message}"
                    )
                    done()
                }
            )
        } catch (t: Throwable) {
            result("NO", "Pleos Gear 도메인", "${t.javaClass.simpleName}: ${t.message}")
            done()
        }
    }

    // ───────────────── UX 제약 ─────────────────

    private fun checkUxRestrictions() {
        try {
            val car = android.car.Car.createCar(this)
            val uxm = car?.getCarManager(android.car.Car.CAR_UX_RESTRICTION_SERVICE)
                    as? android.car.drivingstate.CarUxRestrictionsManager
            if (uxm == null) {
                result("NO", "CarUxRestrictionsManager", "매니저 획득 실패")
                return
            }
            val current = uxm.currentCarUxRestrictions
            result(
                "OK",
                "CarUxRestrictions 현재값",
                "requiresDistractionOptimization=${current?.isRequiresDistractionOptimization} " +
                    "activeRestrictions=${current?.activeRestrictions}"
            )
            uxm.registerListener { r ->
                log(
                    "    ▶ UxRestrictions 변화: DO=${r.isRequiresDistractionOptimization} " +
                        "active=${r.activeRestrictions}"
                )
            }
            result("OK", "CarUxRestrictions 리스너 등록", "registerListener 성공")
            pendingCallbacks++
        } catch (t: Throwable) {
            result("NO", "CarUxRestrictions", "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ───────────────── FusedLocation ─────────────────

    private fun checkFusedLocation(done: () -> Unit) {
        try {
            val fl = FusedLocation(this)
            fusedLocation = fl
            val supported = fl.isSupported()
            result(
                if (supported) "OK" else "NO",
                "FusedLocation.isSupported",
                "$supported (서비스 패키지 설치 여부)"
            )
            fl.initialize()

            handler.postDelayed({
              try {
                fl.getLastKnownLocation(object : FusedLocationListener {
                    override fun onChanged(location: LocationData?) {
                        if (location == null) {
                            result("COND", "getLastKnownLocation", "콜백은 왔지만 좌표가 null")
                        } else {
                            result(
                                "OK",
                                "getLastKnownLocation",
                                "lat=${location.latitude} lon=${location.longitude} " +
                                    "alt=${location.altitude} heading=${location.heading} " +
                                    "confidence=${location.confidence} ts=${location.timestampMillis}"
                            )
                        }
                        done()
                    }

                    override fun onError(exception: Exception) {
                        result(
                            "NO",
                            "getLastKnownLocation",
                            "${exception.javaClass.simpleName}: ${exception.message}"
                        )
                        done()
                    }
                })
              } catch (t: Throwable) {
                // FUSED_LOCATION 미허용 시 SDK가 onError가 아니라 예외를 그대로 던진다
                result(
                    "NO", "getLastKnownLocation",
                    "동기 예외 ${t.javaClass.simpleName}: ${t.message}"
                )
                done()
              }
            }, 1200)
        } catch (t: Throwable) {
            result("NO", "FusedLocation", "${t.javaClass.simpleName}: ${t.message}")
            done()
        }
    }

    // ───────────── 도메인별 capability + 현재값 ─────────────

    private fun addWiperSteps() {
        val area = WindowArea(WindowValue.FRONT_WINDSHIELD)

        step("Wiper state capability") { done ->
            section("Wiper")
            vehicle.getWiper().checkWiperStateCapabilities(
                onComplete = { config: CapabilityConfig, map: Map<WindowArea, List<WiperState>> ->
                    result(
                        "OK", "checkWiperStateCapabilities",
                        "access=${config.accessType} areas=${map.size} 지원값=${map.values.firstOrNull()}"
                    )
                    done()
                },
                onFailed = { e ->
                    result("NO", "checkWiperStateCapabilities", "${e.javaClass.simpleName}: ${e.message}")
                    done()
                }
            )
        }

        step("Wiper switch capability") { done ->
            vehicle.getWiper().checkWiperSwitchCapabilities(
                onComplete = { config: CapabilityConfig, map: Map<WindowArea, List<WiperSwitch>> ->
                    result(
                        "OK", "checkWiperSwitchCapabilities",
                        "access=${config.accessType} 지원단계=${map.values.firstOrNull()}"
                    )
                    done()
                },
                onFailed = { e ->
                    result("NO", "checkWiperSwitchCapabilities", "${e.javaClass.simpleName}: ${e.message}")
                    done()
                }
            )
        }

        step("Wiper 현재 state") { done ->
            vehicle.getWiper().getCurrentWiperState(
                area = area,
                onComplete = { s -> result("OK", "getCurrentWiperState", "$s"); done() },
                onFailed = { e -> result("NO", "getCurrentWiperState", "${e.message}"); done() }
            )
        }

        step("Wiper 현재 switch") { done ->
            vehicle.getWiper().getCurrentWiperSwitch(
                area = area,
                onComplete = { s ->
                    result("OK", "getCurrentWiperSwitch", "$s  ← 비의 세기는 여기서만 나온다")
                    done()
                },
                onFailed = { e -> result("NO", "getCurrentWiperSwitch", "${e.message}"); done() }
            )
        }
    }

    private fun addWindowSteps() {
        step("Window position capability") { done ->
            section("Window")
            vehicle.getWindow().checkWindowPositionCapabilities(
                onComplete = { config: CapabilityConfig, map: Map<WindowArea, AreaBounds<Int>> ->
                    result("OK", "checkWindowPositionCapabilities", "access=${config.accessType}")
                    map.forEach { (a, b) ->
                        log("    · ${a.values.joinToString()} : ${b.minValue}..${b.maxValue}")
                    }
                    done()
                },
                onFailed = { e ->
                    result("NO", "checkWindowPositionCapabilities", "${e.message}")
                    done()
                }
            )
        }

        step("Window 현재 position (운전석)") { done ->
            vehicle.getWindow().getCurrentWindowPosition(
                area = WindowArea(WindowValue.ROW_1_LEFT),
                onComplete = { p -> result("OK", "getCurrentWindowPosition", "$p"); done() },
                onFailed = { e -> result("NO", "getCurrentWindowPosition", "${e.message}"); done() }
            )
        }
    }

    private fun addDoorSteps() {
        step("Door position capability") { done ->
            section("Door")
            vehicle.getDoor().checkDoorPositionCapabilities(
                onComplete = { config: CapabilityConfig, map: Map<DoorArea, AreaBounds<Int>> ->
                    result("OK", "checkDoorPositionCapabilities", "access=${config.accessType}")
                    map.forEach { (a, b) ->
                        log("    · ${a.values.joinToString()} : ${b.minValue}..${b.maxValue}")
                    }
                    done()
                },
                onFailed = { e -> result("NO", "checkDoorPositionCapabilities", "${e.message}"); done() }
            )
        }

        step("Door 현재 position (운전석)") { done ->
            vehicle.getDoor().getCurrentDoorPosition(
                area = DoorArea(DoorValue.ROW_1_LEFT),
                onComplete = { p -> result("OK", "getCurrentDoorPosition", "$p"); done() },
                onFailed = { e -> result("NO", "getCurrentDoorPosition", "${e.message}"); done() }
            )
        }
    }

    private fun addOdometerSteps() {
        step("Odometer capability") { done ->
            section("Odometer")
            vehicle.getOdometer().checkCurrentDistanceDrivenCapabilities(
                onComplete = { config ->
                    result("OK", "checkCurrentDistanceDrivenCapabilities", "access=${config.accessType}")
                    done()
                },
                onFailed = { e -> result("NO", "checkCurrentDistanceDrivenCapabilities", "${e.message}"); done() }
            )
        }

        step("Odometer 누적 주행거리") { done ->
            vehicle.getOdometer().getCurrentDistanceDriven(
                onComplete = { d -> result("OK", "getCurrentDistanceDriven", "$d km"); done() },
                onFailed = { e -> result("NO", "getCurrentDistanceDriven", "${e.message}"); done() }
            )
        }

        step("Trip 현재 주행거리") { done ->
            vehicle.getOdometer().getTripCurrentDrivingDistance(
                onComplete = { d -> result("OK", "getTripCurrentDrivingDistance", "$d"); done() },
                onFailed = { e -> result("NO", "getTripCurrentDrivingDistance", "${e.message}"); done() }
            )
        }

        step("Trip 현재 주행시간") { done ->
            vehicle.getOdometer().getTripCurrentDrivingTime(
                onComplete = { t -> result("OK", "getTripCurrentDrivingTime", "$t"); done() },
                onFailed = { e -> result("NO", "getTripCurrentDrivingTime", "${e.message}"); done() }
            )
        }
    }

    private fun addBrakeSteps() {
        step("Brake capability") { done ->
            section("Brake — 주의: 페달 Boolean 뿐, '부드러움'은 직접 신호가 없다")
            vehicle.getBrake().checkBrakePedalStatusCapabilities(
                onComplete = { config ->
                    result("OK", "checkBrakePedalStatusCapabilities", "access=${config.accessType}")
                    done()
                },
                onFailed = { e -> result("NO", "checkBrakePedalStatusCapabilities", "${e.message}"); done() }
            )
        }

        step("Brake 현재 페달 상태") { done ->
            vehicle.getBrake().getCurrentBrakePedalStatus(
                onComplete = { b -> result("OK", "getCurrentBrakePedalStatus", "$b (Boolean)"); done() },
                onFailed = { e -> result("NO", "getCurrentBrakePedalStatus", "${e.message}"); done() }
            )
        }
    }

    private fun addFuelSteps() {
        step("Fuel economy capability") { done ->
            section("Fuel")
            vehicle.getFuel().checkCurrentFuelEconomyCapabilities(
                onComplete = { config ->
                    result("OK", "checkCurrentFuelEconomyCapabilities", "access=${config.accessType}")
                    done()
                },
                onFailed = { e -> result("NO", "checkCurrentFuelEconomyCapabilities", "${e.message}"); done() }
            )
        }

        step("Fuel 현재 연비") { done ->
            vehicle.getFuel().getCurrentFuelEconomy(
                onComplete = { v -> result("OK", "getCurrentFuelEconomy", "$v"); done() },
                onFailed = { e -> result("NO", "getCurrentFuelEconomy", "${e.message}"); done() }
            )
        }

        step("Fuel 잔량 %") { done ->
            vehicle.getFuel().getFuelPercent(
                onComplete = { v -> result("OK", "getFuelPercent", "$v"); done() },
                onFailed = { e -> result("NO", "getFuelPercent", "${e.message}"); done() }
            )
        }
    }

    private fun addLightSteps() {
        step("LowBeam state capability") { done ->
            section("Light — 헤드램프")
            vehicle.getLight().checkLowBeamLightStateCapabilities(
                onComplete = { config: CapabilityConfig, states: List<LightState> ->
                    result(
                        "OK", "checkLowBeamLightStateCapabilities",
                        "access=${config.accessType} 지원값=$states"
                    )
                    done()
                },
                onFailed = { e -> result("NO", "checkLowBeamLightStateCapabilities", "${e.message}"); done() }
            )
        }

        step("LowBeam 현재 state") { done ->
            vehicle.getLight().getCurrentLowBeamLightState(
                onComplete = { s: LightState? ->
                    result("OK", "getCurrentLowBeamLightState", "$s  ← 주야 판별 대체 신호")
                    done()
                },
                onFailed = { e -> result("NO", "getCurrentLowBeamLightState", "${e.message}"); done() }
            )
        }

        step("LowBeam 현재 switch") { done ->
            vehicle.getLight().getLowBeamLightSwitch(
                onComplete = { s: LightSwitch? -> result("OK", "getLowBeamLightSwitch", "$s"); done() },
                onFailed = { e -> result("NO", "getLowBeamLightSwitch", "${e.message}"); done() }
            )
        }
    }

    /** 앰비언트 프로퍼티가 실제로 지원하는 SeatArea. capability 응답에서 받아 채운다. */
    private var ambientArea: SeatArea? = null

    private fun addAmbientSteps() {

        step("Ambient color capability") { done ->
            section("Light — 앰비언트 (클라이맥스 연출의 핵심)")
            vehicle.getLight().checkAmbientLightBrightnessColorCapabilities(
                onComplete = { config: CapabilityConfig, areas: List<SeatArea> ->
                    ambientArea = areas.firstOrNull()
                    val names = areas.joinToString { a -> a.values.joinToString() }
                    result(
                        "OK", "checkAmbientLightBrightnessColorCapabilities",
                        "access=${config.accessType} 지원 area=[$names]" +
                            "  ← 이 area만 쓸 수 있다"
                    )
                    done()
                },
                onFailed = { e ->
                    result("NO", "checkAmbientLightBrightnessColorCapabilities", "${e.message}")
                    done()
                }
            )
        }

        step("Ambient switch / state 현재값") { done ->
            val seat = ambientArea
            if (seat == null) {
                result("NO", "getAmbientLightSwitch", "지원 area를 못 받아 건너뜀")
                done(); return@step
            }
            vehicle.getLight().getAmbientLightSwitch(
                area = seat,
                onComplete = { s -> result("OK", "getAmbientLightSwitch", "$s"); done() },
                onFailed = { e -> result("NO", "getAmbientLightSwitch", "${e.message}"); done() }
            )
        }

        step("Ambient 현재 색") { done ->
            val seat = ambientArea
            if (seat == null) {
                result("NO", "getAmbientLightBrightnessColor", "지원 area를 못 받아 건너뜀")
                done(); return@step
            }
            vehicle.getLight().getAmbientLightBrightnessColor(
                area = seat,
                onComplete = { argb ->
                    result(
                        "OK", "getAmbientLightBrightnessColor",
                        if (argb == null) "null" else String.format("#%08X", argb)
                    )
                    done()
                },
                onFailed = { e -> result("NO", "getAmbientLightBrightnessColor", "${e.message}"); done() }
            )
        }

        step("Ambient auto-by-drive-mode 상태") { done ->
            vehicle.getLight().getAmbientColorAutoByDriveModeEnabled(
                onComplete = { b ->
                    result(
                        "OK", "getAmbientColorAutoByDriveModeEnabled",
                        "$b  ← true면 차가 색을 덮어쓴다. 우리 색을 쓰려면 false로"
                    )
                    done()
                },
                onFailed = { e ->
                    result("NO", "getAmbientColorAutoByDriveModeEnabled", "${e.message}")
                    done()
                }
            )
        }

        step("Ambient 색 쓰기 → 되읽기") { done ->
            val seat = ambientArea
            if (seat == null) {
                result("NO", "setAmbientLightBrightnessColor", "지원 area를 못 받아 건너뜀")
                done(); return@step
            }
            val target = Color.rgb(120, 200, 140) // Milebloom 연둣빛
            vehicle.getLight().setAmbientLightBrightnessColor(
                area = seat,
                value = target,
                onComplete = { _, applied ->
                    result(
                        "OK", "setAmbientLightBrightnessColor",
                        "요청=${String.format("#%08X", target)} 적용=${String.format("#%08X", applied)}"
                    )
                    vehicle.getLight().getAmbientLightBrightnessColor(
                        area = seat,
                        onComplete = { back ->
                            result(
                                if (back == target) "OK" else "COND",
                                "쓰기 후 되읽기",
                                if (back == null) "null" else String.format("#%08X", back)
                            )
                            done()
                        },
                        onFailed = { e -> result("NO", "쓰기 후 되읽기", "${e.message}"); done() }
                    )
                },
                onFailed = { e ->
                    result(
                        "NO", "setAmbientLightBrightnessColor",
                        "${e.javaClass.simpleName}: ${e.message}"
                    )
                    done()
                }
            )
        }
    }

    // ───────────── 리스너 실제 수신 프로브 ─────────────

    private fun registerProbes() {
        try {
            vehicle.getWiper().registerWiperState(
                area = WindowArea(WindowValue.FRONT_WINDSHIELD),
                listener = object : WiperStateListener {
                    override fun onWiperStateUpdated(state: WiperState?) {
                        log("    ▶ WiperState 수신: $state")
                    }

                    override fun onFailed(e: Exception) {
                        log("    ▶ WiperState 오류: ${e.message}")
                    }
                }
            )
            result("OK", "registerWiperState", "등록 완료 — Virtual Console에서 와이퍼 조작해 볼 것")
            pendingCallbacks++
        } catch (t: Throwable) {
            result("NO", "registerWiperState", "${t.javaClass.simpleName}: ${t.message}")
        }

        try {
            vehicle.getOdometer().registerDistanceDriven(object : DistanceDrivenListener {
                override fun onDistanceDrivenUpdated(distance: Float?) {
                    log("    ▶ DistanceDriven 수신: $distance")
                }

                override fun onFailed(e: Exception) {
                    log("    ▶ DistanceDriven 오류: ${e.message}")
                }
            })
            result("OK", "registerDistanceDriven", "등록 완료")
            pendingCallbacks++
        } catch (t: Throwable) {
            result("NO", "registerDistanceDriven", "${t.javaClass.simpleName}: ${t.message}")
        }

        try {
            vehicle.getLight().registerAmbientLightBrightnessColorListener(
                area = ambientArea ?: SeatArea(SeatValue.ROW_1_LEFT),
                listener = object : AmbientLightBrightnessColorListener {
                    override fun onBrightnessColorUpdated(argbColor: Int?) {
                        log(
                            "    ▶ AmbientColor 수신: " +
                                if (argbColor == null) "null" else String.format("#%08X", argbColor)
                        )
                    }

                    override fun onFailed(e: Exception) {
                        log("    ▶ AmbientColor 오류: ${e.message}")
                    }
                }
            )
            result("OK", "registerAmbientLightBrightnessColorListener", "등록 완료")
            pendingCallbacks++
        } catch (t: Throwable) {
            result(
                "NO", "registerAmbientLightBrightnessColorListener",
                "${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    // ───────── 세션 3-B 추가 검증: 조향 / EV / 좌석 착좌 ─────────

    /** 좌석 착좌 프로퍼티가 실제로 지원하는 area 목록. capability 응답에서 채운다. */
    private var seatAreas: List<SeatArea> = emptyList()

    /** 리스너 수신 간격 측정용. 키 = 리스너 이름 */
    private val lastEventAt = HashMap<String, Long>()
    private val eventCount = HashMap<String, Int>()

    /** 리스너 수신을 경과시간(ms)과 함께 기록한다. 이름별 40건까지만 남긴다. */
    private fun probe(name: String, value: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val n = (eventCount[name] ?: 0) + 1
        eventCount[name] = n
        val dt = lastEventAt[name]?.let { now - it }
        lastEventAt[name] = now
        val line = "    ▶ $name #$n: $value" + (dt?.let { "  (+${it}ms)" } ?: "")
        if (n <= 40) log(line) else Log.i(TAG, line)  // 40건 넘으면 logcat에만
    }

    private fun addSteeringSteps() {
        step("Steering angle capability") { done ->
            section("추가검증 1. 조향각")
            vehicle.getSteeringWheel().checkSteeringWheelAngleCapabilities(
                onComplete = { config: CapabilityConfig, bounds: AreaBounds<Float> ->
                    result(
                        "OK", "checkSteeringWheelAngleCapabilities",
                        "access=${config.accessType} 범위=${bounds.minValue}..${bounds.maxValue}"
                    )
                    done()
                },
                onFailed = { e -> result("NO", "checkSteeringWheelAngleCapabilities", "${e.message}"); done() }
            )
        }
        step("Steering 현재 각도") { done ->
            vehicle.getSteeringWheel().getCurrentSteeringWheelAngle(
                onComplete = { a -> result("OK", "getCurrentSteeringWheelAngle", "$a"); done() },
                onFailed = { e -> result("NO", "getCurrentSteeringWheelAngle", "${e.message}"); done() }
            )
        }
    }

    private fun addEvBatterySteps() {
        step("EV SOC capability") { done ->
            section("추가검증 2. EV 배터리")
            vehicle.getEvBattery().checkEvBatteryStateOfChargeCapabilities(
                onComplete = { config -> result("OK", "checkEvBatteryStateOfChargeCapabilities", "access=${config.accessType}"); done() },
                onFailed = { e -> result("NO", "checkEvBatteryStateOfChargeCapabilities", "${e.message}"); done() }
            )
        }
        step("EV SOC 현재값") { done ->
            vehicle.getEvBattery().getEvBatteryStateOfCharge(
                onComplete = { v -> result("OK", "getEvBatteryStateOfCharge", "$v"); done() },
                onFailed = { e -> result("NO", "getEvBatteryStateOfCharge", "${e.message}"); done() }
            )
        }
        step("EV 전비 capability") { done ->
            vehicle.getEvBattery().checkCurrentEvEfficiencyCapabilities(
                onComplete = { config -> result("OK", "checkCurrentEvEfficiencyCapabilities", "access=${config.accessType}"); done() },
                onFailed = { e -> result("NO", "checkCurrentEvEfficiencyCapabilities", "${e.message}"); done() }
            )
        }
        step("EV 전비 현재값") { done ->
            vehicle.getEvBattery().getCurrentEvEfficiency(
                onComplete = { v -> result("OK", "getCurrentEvEfficiency", "$v"); done() },
                onFailed = { e -> result("NO", "getCurrentEvEfficiency", "${e.message}"); done() }
            )
        }
        step("EV 순간 전비") { done ->
            vehicle.getEvBattery().getInstantaneousEvEfficiency(
                onComplete = { v -> result("OK", "getInstantaneousEvEfficiency", "$v"); done() },
                onFailed = { e -> result("NO", "getInstantaneousEvEfficiency", "${e.message}"); done() }
            )
        }
        step("EV 배터리 레벨") { done ->
            vehicle.getEvBattery().getEvBatteryLevel(
                onComplete = { v -> result("OK", "getEvBatteryLevel", "$v"); done() },
                onFailed = { e -> result("NO", "getEvBatteryLevel", "${e.message}"); done() }
            )
        }
    }

    private fun addSeatOccupancySteps() {
        step("Seat occupancy capability") { done ->
            section("추가검증 3. 좌석 착좌")
            vehicle.getSeat().checkSeatOccupancyCapabilities(
                onComplete = { config: CapabilityConfig, areas: List<SeatArea> ->
                    seatAreas = areas
                    result(
                        "OK", "checkSeatOccupancyCapabilities",
                        "access=${config.accessType} area ${areas.size}개 = " +
                            areas.joinToString(" / ") { a -> a.values.joinToString("+") }
                    )
                    done()
                },
                onFailed = { e -> result("NO", "checkSeatOccupancyCapabilities", "${e.message}"); done() }
            )
        }
        step("Seat occupancy 현재값 (capability area)") { done ->
            if (seatAreas.isEmpty()) {
                result("NO", "getCurrentSeatOccupancy", "지원 area 없음"); done(); return@step
            }
            var remaining = seatAreas.size
            seatAreas.forEach { a ->
                vehicle.getSeat().getCurrentSeatOccupancy(
                    area = a,
                    onComplete = { v ->
                        result("OK", "getCurrentSeatOccupancy[${a.values.joinToString("+")}]", "$v")
                        if (--remaining == 0) done()
                    },
                    onFailed = { e ->
                        result("NO", "getCurrentSeatOccupancy[${a.values.joinToString("+")}]", "${e.message}")
                        if (--remaining == 0) done()
                    }
                )
            }
        }
        step("Seat occupancy 직접 만든 SeatArea(ROW_1_RIGHT)") { done ->
            // 앰비언트처럼 직접 만든 area가 거부되는지 확인
            vehicle.getSeat().getCurrentSeatOccupancy(
                area = SeatArea(SeatValue.ROW_1_RIGHT),
                onComplete = { v -> result("OK", "getCurrentSeatOccupancy[직접 ROW_1_RIGHT]", "$v"); done() },
                onFailed = { e -> result("NO", "getCurrentSeatOccupancy[직접 ROW_1_RIGHT]", "${e.javaClass.simpleName}: ${e.message}"); done() }
            )
        }
    }

    /** 세션 3-B 요청: 조향·EV·착좌 + 단발만 확인됐던 리스너 4종. */
    private fun registerExtraProbes() {
        fun tryReg(name: String, body: () -> Unit) {
            try {
                body()
                result("OK", name, "등록 완료")
                pendingCallbacks++
            } catch (t: Throwable) {
                result("NO", name, "${t.javaClass.simpleName}: ${t.message}")
            }
        }

        tryReg("registerSteeringWheelAngle") {
            vehicle.getSteeringWheel().registerSteeringWheelAngle(object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(angle: Float?) = probe("SteeringAngle", "$angle")
                override fun onFailed(e: Exception) = log("    ▶ SteeringAngle 오류: ${e.message}")
            })
        }
        tryReg("registerEvBatteryStateOfCharge") {
            vehicle.getEvBattery().registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(value: Int?) = probe("EvSOC", "$value")
                override fun onFailed(e: Exception) = log("    ▶ EvSOC 오류: ${e.message}")
            })
        }
        tryReg("registerCurrentEvEfficiency") {
            vehicle.getEvBattery().registerCurrentEvEfficiency(object : EvBatteryEfficiencyListener {
                override fun onEvBatteryEfficiencyUpdated(efficiency: Float?) = probe("EvEfficiency", "$efficiency")
                override fun onFailed(e: Exception) = log("    ▶ EvEfficiency 오류: ${e.message}")
            })
        }
        seatAreas.forEach { a ->
            val tag = a.values.joinToString("+")
            tryReg("registerSeatOccupancy[$tag]") {
                vehicle.getSeat().registerSeatOccupancy(a, object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(occupancy: Boolean?) = probe("SeatOccupancy[$tag]", "$occupancy")
                    override fun onFailed(e: Exception) = log("    ▶ SeatOccupancy[$tag] 오류: ${e.message}")
                })
            }
        }
        tryReg("registerLowBeamLightState") {
            vehicle.getLight().registerLowBeamLightState(object : LightStateListener {
                override fun onLightStateUpdated(state: LightState?) = probe("LowBeam", "$state")
                override fun onFailed(e: Exception) = log("    ▶ LowBeam 오류: ${e.message}")
            })
        }
        tryReg("registerWindowPosition[ROW_1_LEFT]") {
            vehicle.getWindow().registerWindowPosition(
                WindowArea(WindowValue.ROW_1_LEFT),
                object : WindowPositionListener {
                    override fun onWindowPositionUpdated(position: Int?) = probe("WindowPos", "$position")
                    override fun onFailed(e: Exception) = log("    ▶ WindowPos 오류: ${e.message}")
                }
            )
        }
        tryReg("registerDoorPosition[ROW_1_LEFT]") {
            vehicle.getDoor().registerDoorPosition(
                DoorArea(DoorValue.ROW_1_LEFT),
                object : DoorPositionListener {
                    override fun onPositionUpdated(pos: Int?) = probe("DoorPos", "$pos")
                    override fun onFailed(e: Exception) = log("    ▶ DoorPos 오류: ${e.message}")
                }
            )
        }
        tryReg("registerBrakePedalStatus") {
            vehicle.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(status: Boolean?) = probe("BrakePedal", "$status")
                override fun onFailed(e: Exception) = log("    ▶ BrakePedal 오류: ${e.message}")
            })
        }
    }

    // ───────────── 수동 앰비언트 테스트 ─────────────

    // ───────────────────── 속도 · 정차 판별 ─────────────────────

    private fun addSpeedSteps() {
        step("AAOS 속도 프로퍼티") { done ->
            section("정차 판별 1. AAOS PERF_VEHICLE_SPEED / _DISPLAY / WHEEL_TICK")
            val granted = checkSelfPermission("android.car.permission.CAR_SPEED") ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            result(if (granted) "OK" else "COND", "CAR_SPEED 권한", "granted=$granted")
            try {
                val car = android.car.Car.createCar(this)
                val cpm = car?.getCarManager(android.car.Car.PROPERTY_SERVICE)
                        as? android.car.hardware.property.CarPropertyManager
                if (cpm == null) {
                    result("NO", "CarPropertyManager", "획득 실패")
                } else {
                    val ids = android.car.VehiclePropertyIds::class.java
                    for (name in listOf("PERF_VEHICLE_SPEED", "PERF_VEHICLE_SPEED_DISPLAY", "WHEEL_TICK")) {
                        try {
                            val id = ids.getField(name).getInt(null)
                            val cfg = cpm.getCarPropertyConfig(id)
                            if (cfg == null) {
                                result("NO", name, "config 없음 (미지원 또는 권한 없음)")
                                continue
                            }
                            result("OK", "$name config", "change=${cfg.changeMode} rate=${cfg.minSampleRate}..${cfg.maxSampleRate}")
                            val v = cpm.getProperty<Any>(id, 0)?.value
                            result("OK", "$name 단발", if (v is Array<*>) v.joinToString(",", "[", "]") else "$v")
                            val cb = object : android.car.hardware.property.CarPropertyManager
                            .CarPropertyEventCallback {
                                override fun onChangeEvent(e: android.car.hardware.CarPropertyValue<*>) {
                                    val ev = e.value
                                    probe(name, if (ev is Array<*>) ev.joinToString(",", "[", "]") else "$ev")
                                }
                                override fun onErrorEvent(propertyId: Int, zone: Int) {
                                    log("    ▶ $name 오류 이벤트 zone=$zone")
                                }
                            }
                            val rate = if (cfg.maxSampleRate > 0f) minOf(cfg.maxSampleRate, 10f)
                                       else android.car.hardware.property.CarPropertyManager.SENSOR_RATE_ONCHANGE
                            val ok = cpm.registerCallback(cb, id, rate)
                            result(if (ok) "OK" else "NO", "$name 리스너", "rate=$rate registered=$ok")
                        } catch (t: Throwable) {
                            result("NO", name, "${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                }
            } catch (t: Throwable) {
                result("NO", "AAOS 속도 경로", "${t.javaClass.simpleName}: ${t.message}")
            }
            done()
        }
        step("대안: 주행거리 · 브레이크 리스너") { done ->
            section("정차 판별 2. 대안 — DistanceDriven / BrakePedal")
            try {
                vehicle.getOdometer().registerDistanceDriven(object : DistanceDrivenListener {
                    override fun onDistanceDrivenUpdated(distance: Float?) { probe("DistanceDriven", "$distance") }
                    override fun onFailed(e: Exception) { log("    ▶ DistanceDriven 오류: ${e.message}") }
                })
                result("OK", "registerDistanceDriven", "등록")
            } catch (t: Throwable) {
                result("NO", "registerDistanceDriven", "${t.message}")
            }
            try {
                vehicle.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
                    override fun onBrakePedalStatusUpdated(status: Boolean?) { probe("BrakePedal", "$status") }
                    override fun onFailed(e: Exception) { log("    ▶ BrakePedal 오류: ${e.message}") }
                })
                result("OK", "registerBrakePedalStatus", "등록")
            } catch (t: Throwable) {
                result("NO", "registerBrakePedalStatus", "${t.message}")
            }
            done()
        }
    }

    // ───────────────────── 차종 판별 ─────────────────────

    private fun addCarInfoSteps() {
        step("AAOS INFO_*") { done ->
            section("차종 판별 1. AAOS INFO_MAKE / INFO_MODEL / INFO_MODEL_YEAR")
            try {
                val car = android.car.Car.createCar(this)
                val cpm = car?.getCarManager(android.car.Car.PROPERTY_SERVICE)
                        as? android.car.hardware.property.CarPropertyManager
                if (cpm == null) {
                    result("NO", "CarPropertyManager", "획득 실패")
                } else {
                    val ids = android.car.VehiclePropertyIds::class.java
                    for (name in listOf("INFO_MAKE", "INFO_MODEL", "INFO_MODEL_YEAR",
                            "INFO_FUEL_TYPE", "INFO_EV_BATTERY_CAPACITY", "INFO_EXTERIOR_DIMENSIONS")) {
                        try {
                            val id = ids.getField(name).getInt(null)
                            val cfg = cpm.getCarPropertyConfig(id)
                            if (cfg == null) {
                                result("NO", name, "config 없음 (미지원)")
                                continue
                            }
                            val v = cpm.getProperty<Any>(id, 0)?.value
                            val shown = when (v) {
                                is IntArray -> v.joinToString(",", "[", "]")
                                is Array<*> -> v.joinToString(",", "[", "]")
                                else -> "\"$v\""
                            }
                            result("OK", name, "값=$shown (type=${cfg.propertyType.simpleName})")
                        } catch (t: Throwable) {
                            result("NO", name, "${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                }
            } catch (t: Throwable) {
                result("NO", "AAOS INFO 경로", "${t.javaClass.simpleName}: ${t.message}")
            }
            done()
        }
        // VIN·CRN은 값은 읽지 않고 capability만 본다 (개인정보·요청 범위)
        step("Pleos CarInfo VIN capability") { done ->
            section("차종 판별 2. Pleos CarInfo (VIN / CRN capability만)")
            vehicle.getCarInfo().checkVINInfoCapabilities(
                { c -> result("OK", "checkVINInfoCapabilities", "access=${c.accessType}"); done() },
                { e -> result("NO", "checkVINInfoCapabilities", "${e.message}"); done() }
            )
        }
        step("Pleos CarInfo CRN capability") { done ->
            vehicle.getCarInfo().checkCRNInfoCapabilities(
                { c -> result("OK", "checkCRNInfoCapabilities", "access=${c.accessType}"); done() },
                { e -> result("NO", "checkCRNInfoCapabilities", "${e.message}"); done() }
            )
        }
    }

    private fun setAmbient(argb: Int, name: String) {
        if (!vehicleInitialized) {
            vehicle.initialize()
            vehicleInitialized = true
        }
        vehicle.getLight().setAmbientLightBrightnessColor(
            area = ambientArea ?: SeatArea(SeatValue.ROW_1_LEFT),
            value = argb,
            onComplete = { _, applied ->
                log("수동 앰비언트 $name → 적용 ${String.format("#%08X", applied)}")
            },
            onFailed = { e -> log("수동 앰비언트 $name 실패: ${e.message}") }
        )
    }

    override fun onDestroy() {
        writeReportFile()
        try {
            fusedLocation?.release()
        } catch (_: Exception) {
        }
        // Vehicle.release()는 콜백이 모두 끝난 뒤에만 호출한다 (SDK 규약)
        handler.postDelayed({
            if (vehicleInitialized) {
                try {
                    vehicle.release()
                } catch (_: Exception) {
                }
            }
        }, 500)
        super.onDestroy()
    }
}
