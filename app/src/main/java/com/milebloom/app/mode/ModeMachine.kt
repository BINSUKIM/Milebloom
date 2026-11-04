package com.milebloom.app.mode

import com.milebloom.app.vehicle.Gear

/**
 * 앱 화면 모드 — 3막 구조 (docs/12-idea-v2.md 5장).
 * 기어가 앱을 둘로 쪼갠다: 주행(D)은 [JOURNEY], 주차(P)는 [BOARDING]·[ARRIVAL].
 */
enum class AppMode {
    /** 1막 승차. 앱을 켰고 아직 이번 주행이 시작되지 않았다 (기어 P). */
    BOARDING,
    /** 2막 주행 (Journey Mode). 터치를 요구하지 않는다. */
    JOURNEY,
    /** 3막 하차 (Memory Mode). 주행을 마치고 P를 체결했다. 클라이맥스. */
    ARRIVAL,
}

/** 모드 전환이 만드는 사건. 5-B(기록)·5-D(앰비언트)가 이걸 받아 일을 한다. */
sealed interface ModeEvent {
    data class JourneyStarted(val atMs: Long, val odoStartKm: Float?) : ModeEvent
    data class JourneyEnded(
        val startedAtMs: Long,
        val endedAtMs: Long,
        val odoStartKm: Float?,
        val odoEndKm: Float?,
    ) : ModeEvent {
        /** 구간 거리. 트립 거리가 미지원이라 누적값 차분으로 낸다. 값이 없거나 역행하면 0. */
        val distKm: Float
            get() = if (odoStartKm == null || odoEndKm == null) 0f else (odoEndKm - odoStartKm).coerceAtLeast(0f)
    }
}

/**
 * 기어 → 모드 상태 머신. 안드로이드 의존 없음 (단위 테스트 가능).
 *
 * 규칙
 * - 한 번의 주행 = **D 진입 ~ 다음 P 체결** (스키마 13.1)
 * - R·N은 주행을 시작하지도 끝내지도 않는다. 주차장에서 R→P만 한 것은 주행이 아니고,
 *   주행 중 N(세차·신호대기)은 주행의 일부다
 * - UNKNOWN(아직 값이 안 옴)은 무시한다
 * - 주행 시작 시 주행거리가 아직 안 왔으면, 처음 들어온 값을 시작값으로 쓴다
 */
class ModeMachine(initial: AppMode = AppMode.BOARDING) {

    var mode: AppMode = initial
        private set

    private var journeyStartMs = 0L
    private var odoStart: Float? = null
    private var lastOdo: Float? = null

    val inJourney: Boolean get() = mode == AppMode.JOURNEY

    fun onOdometer(km: Float?) {
        if (km == null) return
        lastOdo = km
        if (inJourney && odoStart == null) odoStart = km
    }

    /** 기어 값을 넣으면 일어난 사건을 돌려준다 (없으면 null). */
    fun onGear(gear: Gear, nowMs: Long): ModeEvent? = when (gear) {
        Gear.DRIVE -> if (!inJourney) {
            mode = AppMode.JOURNEY
            journeyStartMs = nowMs
            odoStart = lastOdo
            ModeEvent.JourneyStarted(nowMs, odoStart)
        } else null

        Gear.PARK -> if (inJourney) {
            mode = AppMode.ARRIVAL
            ModeEvent.JourneyEnded(journeyStartMs, nowMs, odoStart, lastOdo)
        } else null

        Gear.REVERSE, Gear.NEUTRAL, Gear.UNKNOWN -> null
    }
}
