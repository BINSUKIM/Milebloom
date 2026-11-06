package com.milebloom.app.render

import java.util.Calendar

/**
 * 풍경을 그리는 데 필요한 '쌓인 결과' (docs/12-idea-v2.md 13.2·13.3을 렌더러가 쓰는 만큼만 줄인 것).
 *
 * 저장된 풍경(`record/Forest`)에서 [of]로 만든다 (D-087). 시연용 고정값은 없다.
 * 렌더러는 이 값을 **기어 P에서만** 바꿔 적용한다 — 주행 중에는 '사건'이 없다 (30번 2.6·6.1-3).
 */
data class SceneState(
    /** 배치 난수 시드. 같은 값이면 늘 같은 풍경 (30번 4.4) */
    val seed: Long,
    /** 숲 성장 단계 1..7 (12번 4.4) */
    val stage: Int,
    /** 들판의 달 1..12 — 계절·강조색을 정한다 */
    val month: Int,
    /** 이번 달 누적 growth. 0..25에서 들판이 채워진다 (12번 4.3 들판 규칙) */
    val meadowGrowth: Float,
    val stream: Boolean,
    val puddles: Int,
    val fireflies: Int,
    val moon: Boolean,
    val seedFluff: Int,
    val birds: Int,
) {
    val season: Season
        get() = when (month) {
            3, 4, 5 -> Season.SPRING
            6, 7, 8 -> Season.SUMMER
            9, 10, 11 -> Season.AUTUMN
            else -> Season.WINTER
        }

    companion object {
        /** 저장된 풍경 → 렌더러 입력. @param month 들판의 달 1..12 */
        fun of(seed: Long, v: com.milebloom.app.record.Forest.View, month: Int) = SceneState(
            seed = seed, stage = v.stage, month = month, meadowGrowth = v.meadowGrowth, stream = v.stream,
            puddles = v.puddles, fireflies = v.fireflies, moon = v.moon, seedFluff = v.seedFluff, birds = v.birds,
        )

        /** 기록을 읽기 전 첫 프레임용. 처음 설치한 풍경과 같은 모습(1단계, 새싹 들판) */
        fun empty(month: Int = Calendar.getInstance().get(Calendar.MONTH) + 1) = SceneState(
            seed = 0L, stage = 1, month = month, meadowGrowth = 0f, stream = false,
            puddles = 0, fireflies = 0, moon = false, seedFluff = 0, birds = 0,
        )
    }
}
