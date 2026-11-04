package com.milebloom.app.render

import java.util.Calendar

/**
 * 풍경을 그리는 데 필요한 '쌓인 결과' (docs/12-idea-v2.md 13.2·13.3을 렌더러가 쓰는 만큼만 줄인 것).
 *
 * 5-B가 저장소(`landscape_state`·`landscape_element`)에서 이 값을 만들어 넘긴다. 그 전까지는 [demo]를 쓴다.
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
        /** 5-B 전까지 쓰는 시연용 풍경: 5단계 숲 + 만개한 이번 달 들판 + 비·밤·창문·동승자 흔적 */
        fun demo(month: Int = Calendar.getInstance().get(Calendar.MONTH) + 1) = SceneState(
            seed = 20261115L,
            stage = 5,
            month = month,
            meadowGrowth = 25f,
            stream = true,
            puddles = 2,
            fireflies = 4,
            moon = true,
            seedFluff = 3,
            birds = 2,
        )
    }
}
