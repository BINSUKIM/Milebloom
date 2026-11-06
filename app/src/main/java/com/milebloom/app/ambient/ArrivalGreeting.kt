package com.milebloom.app.ambient

/**
 * P 체결 때의 도착 인사 한 줄 (12번 5장 3막 2번, 30번 6.4).
 *
 * 오늘 달린 길과 날씨를 말할 뿐 **운전을 평가하지 않는다** (D-039). 조향·제동은 이 한 줄로만 드러난다 (D-041).
 * 숫자·단계·축하 문구를 쓰지 않는다 (D-034). 안드로이드 의존 없음.
 */
object ArrivalGreeting {

    const val RAIN = "비 오는 길 수고하셨어요."
    const val STOPS = "막히는 길 수고하셨어요."
    const val TURNS = "오늘은 굽이진 길이 많았네요."
    const val NIGHT = "밤길 수고하셨어요."
    const val PLAIN = "잘 다녀오셨어요."

    /** 승차(1막) P 화면의 짧은 인사. 소리 내어 읽지 않는다 */
    const val BOARDING = "오늘도 잘 다녀오세요."

    /** 비 → 막힘 → 굽이 → 밤 → 기본. 앞의 것이 더 드문 날의 흔적이라 먼저 말한다 */
    fun of(t: JourneyTally.Result, distKm: Float): String = when {
        t.rain >= RAIN_MIN -> RAIN
        distKm >= MIN_KM && t.stops >= MIN_STOPS && t.stops / distKm >= STOPS_PER_KM -> STOPS
        t.straight <= CURVY_MAX -> TURNS
        t.night >= NIGHT_MIN -> NIGHT
        else -> PLAIN
    }

    private const val RAIN_MIN = 0.3f
    private const val NIGHT_MIN = 0.5f
    private const val CURVY_MAX = 0.5f
    private const val MIN_KM = 0.5f
    private const val MIN_STOPS = 3
    private const val STOPS_PER_KM = 1.0f
}
