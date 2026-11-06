package com.milebloom.app.record

import com.milebloom.app.ambient.ArrivalGreeting
import com.milebloom.app.ambient.TodayColor
import java.util.Calendar
import java.util.TimeZone

/**
 * **debug 시연용 장소 주입** (5-H). 에뮬레이터는 좌표가 한 점이라 지도에 원이 하나뿐이다.
 * 지도·장소 목록·방문 기록을 보여 주려고 **가짜 도착 기록**을 넣는다. 실측이 아니다 — README·캡션에 '주입'이라고 쓴다.
 *
 * - debug 빌드의 브로드캐스트(`--ez demoPlaces true`)에서만 부른다. release에는 이 경로를 부르는 곳이 없다
 * - 주입한 주행은 거리 0·성장 0이다. **풍경을 바꾸지 않는다** (D-039 '거리로만 자란다'와 어긋나지 않게)
 * - 이름은 한 곳에만 "단골 카페"를 넣는다 — 사용자가 직접 붙인 이름이 어떻게 보이는지 보이려는 예시다(32번 2.3).
 *   '집'·'직장'은 쓰지 않는다 (D-042, 자동 추정으로 읽힌다)
 * - 기존 장소에서 200 m 안이면 그 장소의 방문으로 더해진다 — 실제 [Records.parkAt]을 그대로 쓴다
 */
object DemoPlaces {

    /** 위도, 경도, 방문 수, 이름 — 수도권 4곳은 일부러 겹치게 둔다(근처 장소 목록 시연) */
    private data class Spot(val lat: Double, val lon: Double, val visits: Int, val label: String? = null)

    private val SPOTS = listOf(
        Spot(37.5665, 126.9780, 14),               // 서울 도심 — 10회 넘음(크기 같음)
        Spot(37.5170, 127.0473, 8),                // 서울 강남
        Spot(37.3595, 127.1052, 4, "단골 카페"),    // 성남 분당
        Spot(37.4563, 126.7052, 1),                // 인천
        Spot(37.7519, 128.8761, 2),                // 강릉
        Spot(36.3504, 127.3845, 3),                // 대전
        Spot(35.1796, 129.0756, 2),                // 부산
        Spot(34.7604, 127.6622, 1),                // 여수
        Spot(33.4996, 126.5312, 1),                // 제주
    )

    /** (장소 순번, 방문 순번) → 남긴 말. 받아쓴 글이 어떻게 보이는지 보이려는 예시 */
    private val MEMOS = mapOf(
        (1 to 0) to "다음 달에 타이어 점검 예약하기.",
        (1 to 3) to "아까 라디오에서 나온 노래, 제목 찾아보기.",
        (4 to 1) to "바다 보이는 쪽 주차장이 더 넓었다.",
        (2 to 0) to "원두 두 봉지 사기.",
    )

    private val GREETINGS = listOf(ArrivalGreeting.PLAIN, ArrivalGreeting.STOPS, ArrivalGreeting.RAIN, ArrivalGreeting.NIGHT, ArrivalGreeting.TURNS)

    /**
     * [now]부터 거슬러 올라가며 하루에 한 번씩 도착한 것처럼 넣는다. 마지막 도착(가장 최근)은 어제다 —
     * 오늘 도착한 원의 테두리 연출은 실제 P 체결에만 붙게 한다
     */
    fun inject(r: Records, now: Long, zone: TimeZone = TimeZone.getDefault()): Records {
        var next = r
        // 방문을 4일 간격으로 거슬러 배정한다. 장소 순서대로 이어 붙여 장소마다 마지막 도착의 달이 달라진다 —
        // 원 색(마지막 도착의 오늘의 색)이 한 가지로 몰리지 않게
        val visits = SPOTS.flatMapIndexed { i, s -> List(s.visits) { k -> i to k } }
        val cal = Calendar.getInstance(zone)
        // 오래된 도착부터 넣는다 — 실제 기록처럼 시간이 앞으로 흐르게
        visits.withIndex().reversed().forEach { (n, v) ->
            val (i, k) = v
            val s = SPOTS[i]
            cal.timeInMillis = now
            cal.add(Calendar.DAY_OF_YEAR, -(n * 4 + 1)) // 4일 간격 — 약 다섯 달에 걸쳐 오늘의 색이 달마다 달라진다
            cal.set(Calendar.HOUR_OF_DAY, 18 - (n % 4) * 2); cal.set(Calendar.MINUTE, 10 + n % 40)
            val end = cal.timeInMillis
            val month = cal.get(Calendar.MONTH) + 1
            val night = if (n % 5 == 3) 1f else 0f
            val rain = if (n % 5 == 2) 1f else 0f
            val color = TodayColor.of(month, rain, night, 0.5f).ambientArgb
            val (started, id) = next.startJourney(end - 35 * 60_000L)
            next = started.endJourney(id, end, 0f, color, GREETINGS[n % GREETINGS.size])
            next = next.parkAt(id, end, s.lat, s.lon)
            MEMOS[i to k]?.let { t -> next = next.addMemo(id, t, end - 10 * 60_000L).let { m -> m.confirmMemo(m.memos.last().id) } }
        }
        SPOTS.filter { it.label != null }.forEach { s ->
            val p = next.parkPoints.firstOrNull { Records.distanceM(it.lat, it.lon, s.lat, s.lon) <= Records.SAME_PLACE_M }
            if (p != null && p.label == null) next = next.renameParkPoint(p.id, s.label)
        }
        return next
    }

    val totalVisits: Int get() = SPOTS.sumOf { it.visits }
}
