package com.milebloom.app.record

import kotlin.math.floor
import kotlin.math.min

/**
 * 쌓인 풍경 (docs/12-idea-v2.md 13.2 `landscape_state` + 13.3 `landscape_element`, D-087).
 *
 * - 숲은 **누적 growth**로만 자란다. growth는 주행 거리로만 계산한다 (D-039). 줄어들 경로가 없다
 * - 들판은 **이번 달** growth로 채워지고, 달이 바뀌면 새로 싹이 튼다. 지난달 요소는 지우지 않는다 (D-036)
 * - 요소는 '무엇이 몇 개, 어느 주행에서, 어느 달에'만 남긴다. 화면 위치는 [seed]와 순서로 정해진다
 *   — 같은 기록이면 늘 같은 풍경이다 (30번 4.4). 그래서 x·y·크기를 따로 저장하지 않는다
 * - [today]는 마지막 P 체결의 오늘의 색이다. 앱을 다시 켜도 하늘 물들임이 이어진다 (D-074에서 남긴 일)
 *
 * 단계 숫자는 화면에 내보내지 않는다 (30번 6.4, D-034). [stage]는 렌더러의 나무 수를 정하는 데만 쓴다.
 * 불변 값. 안드로이드 의존 없음 (단위 테스트 가능).
 */
data class Forest(
    /** 배치 난수 시드. 처음 만들 때 한 번 정하고 바꾸지 않는다 */
    val seed: Long,
    val totalDistKm: Float = 0f,
    val totalGrowth: Float = 0f,
    val journeyCount: Int = 0,
    /** 지금 들판의 달 `"2026-11"`. 아직 없으면 null */
    val meadowMonthKey: String? = null,
    /** 이번 달 누적 growth. 0..25에서 들판이 채워진다 */
    val meadowGrowth: Float = 0f,
    val elements: List<Element> = emptyList(),
    val today: Today? = null,
    /** debug 빌드에서 시연용으로 더한 거리(km). 실측과 섞이지 않게 따로 센다. release에서는 늘 0 */
    val debugKm: Float = 0f,
) {
    enum class Type(val layer: Layer) {
        STREAM(Layer.FOREST),
        PUDDLE(Layer.MEADOW), FIREFLY(Layer.MEADOW), MOON(Layer.MEADOW), SEED_FLUFF(Layer.MEADOW), BIRD(Layer.MEADOW),
    }

    enum class Layer { FOREST, MEADOW }

    /**
     * 한 주행이 만든 요소 묶음. 나무·풀·꽃은 요소로 남기지 않는다 — 누적 growth(단계)와 이번 달 growth(밀도)가 정한다.
     * @param journeyId 만든 주행. 기록을 모두 지우면 null이 된다 (풍경은 남는다, D-087)
     * @param monthKey 들판 요소의 달. 숲 요소는 null
     */
    data class Element(val type: Type, val count: Int, val journeyId: Long?, val monthKey: String?, val createdAt: Long)

    /** 오늘의 색 (H·S·실내 조명 V). `TodayColor`와 같은 값 — record 패키지를 ambient에 묶지 않으려고 따로 둔다 */
    data class Today(val hue: Float, val sat: Float, val value: Float)

    /** 지금 숲 단계 1..7. 화면에 숫자로 내보내지 않는다 */
    val stage: Int get() = stageOf(totalGrowth)

    /** 달이 바뀌었으면 들판을 새로 시작한다. 지난달 요소는 그대로 둔다 (12번 13.6 '새달 첫 승차') */
    fun rollMonth(monthKey: String): Forest =
        if (meadowMonthKey == monthKey) this else copy(meadowMonthKey = monthKey, meadowGrowth = 0f)

    /**
     * P 체결 한 번을 더한다 (12번 13.6). 움직이지 않은 주행(거리 0)은 풍경을 바꾸지 않는다 — 오늘의 색만 남긴다.
     * @return 새 풍경. 이 주행이 만든 요소는 [Element.journeyId]로 찾는다
     */
    fun grow(journeyId: Long?, at: Long, monthKey: String, distKm: Float, signs: Signs, today: Today?): Forest {
        val rolled = rollMonth(monthKey).let { if (today != null) it.copy(today = today) else it }
        if (distKm <= 0f) return rolled
        val g = growthOf(distKm)
        return rolled.copy(
            totalDistKm = rolled.totalDistKm + distKm,
            totalGrowth = rolled.totalGrowth + g,
            journeyCount = rolled.journeyCount + 1,
            meadowGrowth = rolled.meadowGrowth + g,
            elements = rolled.elements + elementsOf(signs).map { (type, n) ->
                Element(type, n, journeyId, if (type.layer == Layer.MEADOW) monthKey else null, at)
            },
        )
    }

    /** debug 시연용: 주행 없이 거리를 더한다. 요소는 만들지 않는다 */
    fun debugAddKm(km: Float, monthKey: String): Forest {
        if (km <= 0f) return this
        val g = growthOfTotal(km)
        val r = rollMonth(monthKey)
        return r.copy(totalDistKm = r.totalDistKm + km, totalGrowth = r.totalGrowth + g,
            meadowGrowth = r.meadowGrowth + g, debugKm = r.debugKm + km)
    }

    /** 기록 모두 지우기 (D-030). 풍경은 남기고, 어느 주행·언제 생겼는지만 끊는다 (D-087) */
    fun forgetJourneys(): Forest = copy(elements = elements.map { it.copy(journeyId = null, createdAt = 0L) })

    /** 이번 달 들판 요소 개수 */
    fun meadowCount(type: Type, monthKey: String) = elements.filter { it.type == type && it.monthKey == monthKey }.sumOf { it.count }

    fun has(type: Type) = elements.any { it.type == type }

    /** 렌더러에 넘길 값. 개수 상한은 30번 3장 밀도표와 버튼 예약 영역을 지키는 범위 */
    fun view(monthKey: String): View {
        val g = if (meadowMonthKey == monthKey) meadowGrowth else 0f
        return View(
            stage = stage,
            meadowGrowth = g,
            stream = has(Type.STREAM),
            puddles = min(meadowCount(Type.PUDDLE, monthKey), MAX_PUDDLES),
            fireflies = min(meadowCount(Type.FIREFLY, monthKey), MAX_FIREFLIES),
            moon = meadowCount(Type.MOON, monthKey) > 0,
            seedFluff = min(meadowCount(Type.SEED_FLUFF, monthKey), MAX_FLUFF),
            birds = min(meadowCount(Type.BIRD, monthKey), MAX_BIRDS),
        )
    }

    data class View(
        val stage: Int, val meadowGrowth: Float, val stream: Boolean, val puddles: Int,
        val fireflies: Int, val moon: Boolean, val seedFluff: Int, val birds: Int,
    )

    /** 한 주행의 신호 집계 중 요소를 정하는 값 (12번 4.3) */
    data class Signs(val rain: Float = 0f, val rainLv: Int = 0, val night: Float = 0f, val wind: Float = 0f, val guests: Int = 0)

    companion object {
        /** 숲 단계 문턱 (12번 4.4, D-039) — 3/8/18/35/60/100 */
        val STAGE_AT = floatArrayOf(3f, 8f, 18f, 35f, 60f, 100f)

        const val MAX_PUDDLES = 4
        const val MAX_FIREFLIES = 6
        const val MAX_FLUFF = 5
        const val MAX_BIRDS = 3

        /** 한 주행의 성장량 — 20 km에 1.0, 상한 1.5 (D-039) */
        fun growthOf(distKm: Float): Float = min(distKm.coerceAtLeast(0f) / 20f, 1.5f)

        /** debug 주입용: 여러 날로 나눠 달린 것처럼 하루 30.8 km(12번 4.4 일평균) 단위로 나눠 더한다 */
        fun growthOfTotal(km: Float): Float {
            val day = 30.8f
            val full = floor(km / day)
            return full * growthOf(day) + growthOf(km - full * day)
        }

        fun stageOf(totalGrowth: Float): Int = 1 + STAGE_AT.count { totalGrowth >= it }

        /** 요소 배정 (12번 4.3 '비주얼 요소 배정'). 나무·들판 밀도는 growth가 맡으므로 여기 없다 */
        fun elementsOf(s: Signs): List<Pair<Type, Int>> = buildList {
            if (s.rain >= 0.6f) add(Type.STREAM to 1)
            if (s.rain >= 0.3f) add(Type.PUDDLE to 1 + floor(s.rain * s.rainLv).toInt())
            if (s.night >= 0.4f) floor(s.night * 6f).toInt().takeIf { it > 0 }?.let { add(Type.FIREFLY to it) }
            if (s.night >= 0.7f) add(Type.MOON to 1)
            if (s.wind >= 0.2f) floor(s.wind * 5f).toInt().takeIf { it > 0 }?.let { add(Type.SEED_FLUFF to it) }
            if (s.guests >= 1) add(Type.BIRD to s.guests.coerceAtMost(3))
        }

        fun monthKey(year: Int, month: Int) = String.format(java.util.Locale.ROOT, "%04d-%02d", year, month)

        /** 지난 기록에서 다시 세운다 — 5-E(버전 1) 파일에는 풍경이 없었다. 신호 집계가 없어 요소는 만들 수 없다 */
        fun rebuiltFrom(seed: Long, journeys: List<Records.Journey>, monthKeyOf: (Long) -> String): Forest =
            journeys.filter { it.endedAt != null && it.distKm > 0f }.sortedBy { it.endedAt }
                .fold(Forest(seed)) { f, j -> f.grow(null, j.endedAt!!, monthKeyOf(j.endedAt), j.distKm, Signs(), null) }
    }
}
