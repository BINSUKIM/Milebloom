package com.milebloom.app.vehicle

/**
 * 앱이 쓰는 차량 신호 9종의 현재값 (docs/12-idea-v2.md 4.1 매핑표 8종 + UX 제약).
 *
 * 값이 아직 안 왔거나 차량이 지원하지 않으면 null이다. 지원 여부는 [SignalSupport]로 따로 본다.
 * 연비·EV 전비·좌석 착좌는 쓰지 않는다 (D-038) — 여기에 추가하지 말 것.
 */
data class VehicleSnapshot(
    /** AAOS GEAR_SELECTION (D-014). Pleos Gear 도메인은 @hide+@Deprecated라 쓰지 않는다. */
    val gear: Gear = Gear.UNKNOWN,
    /** CarUxRestrictions.isRequiresDistractionOptimization. 기어 D에서 true가 된다 (13번 5장). */
    val uxRequiresDo: Boolean? = null,
    /** CarUxRestrictions.activeRestrictions 비트. D에서 0x50 = NO_VIDEO|NO_SETUP. */
    val uxActiveRestrictions: Int? = null,
    /** 누적 주행거리(km). 트립 거리는 미지원이라 P→D→P 차분으로 계산한다. */
    val odometerKm: Float? = null,
    /** 와이퍼가 실제로 움직이는가 (WiperState). */
    val wiperOn: Boolean? = null,
    /** 와이퍼 스위치 위치. 비 집계는 수동(LOW·HIGH)만 센다 — AUTO 제외 (D-045). */
    val wiperSwitch: WiperLevel? = null,
    /** 하향등 점등. */
    val lowBeamOn: Boolean? = null,
    /** 창문 개도 0..10 (0..100 아님). */
    val windows: Map<SeatPos, Int> = emptyMap(),
    /** 도어 열림. 앞/뒷좌석 도어는 0/1 이진이다. */
    val doorsOpen: Map<SeatPos, Boolean> = emptyMap(),
    /**
     * 조향각(°). SDK 값 그대로 — **Virtual Console 입력과 부호가 반대**이고 범위는 ±500으로 클램프했다 (13-A).
     * 값이 그대로여도 약 3초마다 재전송되므로 이벤트 수를 '굽이'로 세면 안 된다.
     */
    val steeringDeg: Float? = null,
    /** 브레이크 페달. Boolean 뿐이다. */
    val brakePressed: Boolean? = null,
    /**
     * 정차 여부 — AAOS PERF_VEHICLE_SPEED를 [StopDetector]가 두 상태로 줄인 값 (D-061).
     * 속도 자체는 노출하지 않는다. null = 권한(CAR_SPEED)이 없거나 신호가 없음 → 풍경은 계속 흐른다
     */
    val stopped: Boolean? = null,
    val updatedAtMs: Long = 0L,
)

enum class Gear {
    PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN;

    companion object {
        // android.car.VehicleGear 값
        fun fromAaos(raw: Int): Gear = when (raw) {
            0x0004 -> PARK
            0x0002 -> REVERSE
            0x0001 -> NEUTRAL
            0x0008 -> DRIVE
            else -> UNKNOWN
        }
    }
}

/** 와이퍼 스위치. 에뮬레이터는 CONTINUOUS_LEVEL_1/3만 있어 실질 2단계다. */
enum class WiperLevel { OFF, LOW, HIGH, AUTO, OTHER }

/** 운전석 = FRONT_LEFT (좌핸들). 동승자 판별은 운전석 외 도어로 한다 (D-038). */
enum class SeatPos { FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT }

/** 신호 단위 지원 여부. capability 확인 결과다. */
enum class Signal { GEAR, UX_RESTRICTIONS, ODOMETER, WIPER, LOW_BEAM, WINDOW, DOOR, STEERING, BRAKE, SPEED, AMBIENT }

sealed interface SignalSupport {
    data object Checking : SignalSupport
    data object Supported : SignalSupport
    data class Unsupported(val reason: String) : SignalSupport
}
