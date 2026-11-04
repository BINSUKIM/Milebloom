package com.milebloom.app.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

/**
 * AAOS 표준 경로로 읽는 신호: 기어(GEAR_SELECTION)·UX 제약(CarUxRestrictions)·차 속도(PERF_VEHICLE_SPEED).
 *
 * 기어는 Pleos Gear 도메인이 아니라 여기서 읽는다 (D-014, 13번 2장).
 * Virtual Console의 VENDOR_GEAR_SELECTION이 표준 GEAR_SELECTION으로 전파되는 것을 실측했다.
 *
 * 속도는 정차 판별에만 쓴다 (D-061). 런타임 권한 `CAR_SPEED`가 없으면 config가 null로 와서
 * 속성이 없는 것처럼 보인다 (13번 17-1) → 지원 안 함으로 알리고 끝. 권한을 받은 뒤 [startSpeed]를 다시 부른다.
 */
class CarStateSource(
    private val context: Context,
    private val onGear: (Gear) -> Unit,
    private val onUx: (requiresDo: Boolean, active: Int) -> Unit,
    private val onSupport: (Signal, SignalSupport) -> Unit,
    private val onSpeed: (mps: Float) -> Unit,
) {
    private var car: Car? = null
    private var cpm: CarPropertyManager? = null
    private var uxm: CarUxRestrictionsManager? = null

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val raw = value.value as? Int ?: return
            onGear(Gear.fromAaos(raw))
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.w(TAG, "GEAR_SELECTION 오류 이벤트 zone=$zone")
        }
    }

    private var speedRegistered = false

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val v = value.value as? Float ?: return
            onSpeed(v)
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.w(TAG, "PERF_VEHICLE_SPEED 오류 이벤트 zone=$zone")
        }
    }

    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r ->
        deliverUx(r)
    }

    fun start() {
        val c = try {
            Car.createCar(context)
        } catch (t: Throwable) {
            Log.e(TAG, "Car.createCar 실패: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (c == null) {
            onSupport(Signal.GEAR, SignalSupport.Unsupported("car service 연결 실패"))
            onSupport(Signal.UX_RESTRICTIONS, SignalSupport.Unsupported("car service 연결 실패"))
            return
        }
        car = c
        startGear(c)
        startUx(c)
        startSpeed()
    }

    /** 속도 구독. 권한을 나중에 받으면 다시 부른다 — 이미 등록됐으면 아무것도 하지 않는다 */
    fun startSpeed() {
        val m = cpm ?: return
        if (speedRegistered) return
        try {
            val id = VehiclePropertyIds.PERF_VEHICLE_SPEED
            if (m.getCarPropertyConfig(id) == null) {
                onSupport(Signal.SPEED, SignalSupport.Unsupported("config null (CAR_SPEED 권한 없음?)"))
                return
            }
            // 등록 가능한 최소 주기가 10 Hz다. 솎아 내기는 StopDetector가 1초 평균으로 한다
            speedRegistered = m.registerCallback(speedCallback, id, 10f)
            onSupport(Signal.SPEED, if (speedRegistered) SignalSupport.Supported else SignalSupport.Unsupported("registerCallback=false"))
        } catch (t: Throwable) {
            Log.e(TAG, "속도 경로 실패: ${t.javaClass.simpleName}: ${t.message}")
            onSupport(Signal.SPEED, SignalSupport.Unsupported("${t.javaClass.simpleName}"))
        }
    }

    private fun startGear(c: Car) {
        try {
            val m = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            cpm = m
            val id = VehiclePropertyIds.GEAR_SELECTION
            if (!m.isPropertyAvailable(id, 0)) {
                onSupport(Signal.GEAR, SignalSupport.Unsupported("GEAR_SELECTION unavailable"))
                return
            }
            onGear(Gear.fromAaos(m.getIntProperty(id, 0)))
            val ok = m.registerCallback(gearCallback, id, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            onSupport(Signal.GEAR, if (ok) SignalSupport.Supported else SignalSupport.Unsupported("registerCallback=false"))
        } catch (t: Throwable) {
            Log.e(TAG, "기어 경로 실패: ${t.javaClass.simpleName}: ${t.message}")
            onSupport(Signal.GEAR, SignalSupport.Unsupported("${t.javaClass.simpleName}"))
        }
    }

    private fun startUx(c: Car) {
        try {
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            uxm = m
            m.currentCarUxRestrictions?.let(::deliverUx)
            m.registerListener(uxListener)
            onSupport(Signal.UX_RESTRICTIONS, SignalSupport.Supported)
        } catch (t: Throwable) {
            Log.e(TAG, "UX 제약 경로 실패: ${t.javaClass.simpleName}: ${t.message}")
            onSupport(Signal.UX_RESTRICTIONS, SignalSupport.Unsupported("${t.javaClass.simpleName}"))
        }
    }

    private fun deliverUx(r: CarUxRestrictions) {
        onUx(r.isRequiresDistractionOptimization, r.activeRestrictions)
    }

    fun stop() {
        try { cpm?.unregisterCallback(gearCallback) } catch (_: Throwable) {}
        try { if (speedRegistered) cpm?.unregisterCallback(speedCallback) } catch (_: Throwable) {}
        speedRegistered = false
        try { uxm?.unregisterListener() } catch (_: Throwable) {}
        try { car?.disconnect() } catch (_: Throwable) {}
        cpm = null
        uxm = null
        car = null
    }

    private companion object {
        const val TAG = "MBCAR"
    }
}
