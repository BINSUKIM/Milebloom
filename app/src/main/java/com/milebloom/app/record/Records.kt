package com.milebloom.app.record

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 주차 화면이 쓰는 기록 (docs/12-idea-v2.md 13.1·13.4·13.5를 1차에 필요한 만큼만 줄인 것, D-082).
 *
 * - 경로는 저장하지 않는다. 위치는 **P 체결 지점 하나**뿐이다 (D-005)
 * - 음성 기록은 **텍스트만** 저장하고, 녹음 위치를 두지 않는다. 그 주행의 도착지에 묶인다 (D-042)
 * - 쌓인 풍경([Forest] = `landscape_state`·`landscape_element`)도 같은 파일에 둔다 (D-087)
 *
 * 불변 값이다. 바꾸는 함수는 새 [Records]를 돌려준다. 안드로이드 의존 없음 (단위 테스트 가능).
 */
data class Records(
    val journeys: List<Journey> = emptyList(),
    val parkPoints: List<ParkPoint> = emptyList(),
    val memos: List<VoiceMemo> = emptyList(),
    val nextId: Long = 1L,
    val forest: Forest = Forest(seed = 0L),
) {
    data class Journey(
        val id: Long,
        val startedAt: Long,
        /** 아직 P를 체결하지 않았으면 null (주행 중이거나, 주행 중에 앱이 꺼졌다) */
        val endedAt: Long? = null,
        val distKm: Float = 0f,
        /** 그날의 '오늘의 색'(실내 조명 값). 목록의 점 색으로 쓴다 */
        val colorArgb: Int? = null,
        /** 그날의 도착 인사 한 줄 */
        val greeting: String? = null,
        val parkPointId: Long? = null,
        /** 이 주행이 더한 성장량 (12번 13.1 growthDelta) */
        val growth: Float = 0f,
        /** P 체결 때 한 번 저장한 신호 집계 (12번 13.1). 주행 중에는 메모리에만 있다 */
        val signs: Forest.Signs? = null,
        val stops: Int = 0,
    )

    data class ParkPoint(
        val id: Long,
        val lat: Double,
        val lon: Double,
        val firstAt: Long,
        val lastAt: Long,
        /** 반경 [SAME_PLACE_M] 안에 다시 세우면 새 점을 만들지 않고 이 값만 올린다 */
        val visitCount: Int,
        /** 사용자가 직접 붙인 이름. 자동으로 추정하지 않는다 (D-042) */
        val label: String? = null,
    )

    data class VoiceMemo(
        val id: Long,
        val journeyId: Long,
        val text: String,
        val recordedAt: Long,
        /** 주행 중에는 false. 주차 중에 사용자가 확인하거나 고치면 true */
        val confirmed: Boolean = false,
    )

    fun journey(id: Long?) = id?.let { i -> journeys.find { it.id == i } }
    fun parkPoint(id: Long?) = id?.let { i -> parkPoints.find { it.id == i } }

    /** 시간순 전체 목록 — 최근 것이 위 (시안 P12) */
    fun memosNewestFirst(): List<VoiceMemo> = memos.sortedByDescending { it.recordedAt }

    fun memosOf(journeyId: Long): List<VoiceMemo> = memos.filter { it.journeyId == journeyId }.sortedBy { it.recordedAt }

    val unconfirmedCount: Int get() = memos.count { !it.confirmed }

    /** D 진입. 새 주행의 id와 함께 돌려준다 */
    fun startJourney(at: Long): Pair<Records, Long> {
        val id = nextId
        return copy(journeys = journeys + Journey(id = id, startedAt = at), nextId = id + 1) to id
    }

    /** P 체결. 주행 기록이 없으면(앱이 주행 중에 다시 켜졌다) 아무것도 하지 않는다 */
    fun endJourney(journeyId: Long, at: Long, distKm: Float, colorArgb: Int?, greeting: String?): Records {
        val j = journey(journeyId) ?: return this
        val done = j.copy(endedAt = at, distKm = distKm, colorArgb = colorArgb, greeting = greeting)
        return copy(journeys = journeys.map { if (it.id == journeyId) done else it })
    }

    /**
     * P 체결 — 주행을 닫고 풍경을 키운다 (12번 13.6). 이미 닫힌 주행이면 풍경을 두 번 키우지 않는다.
     * 주행 기록이 없으면(주행 중에 앱이 켜져 시작을 못 봤다) 풍경도 키우지 않는다 — 그 주행의 시작 거리를 모른다.
     * 오늘의 색은 그래도 남긴다.
     */
    fun arrive(
        journeyId: Long?, at: Long, monthKey: String, distKm: Float, signs: Forest.Signs, stops: Int,
        today: Forest.Today?, colorArgb: Int?, greeting: String?,
    ): Records {
        val j = journey(journeyId)
        if (j == null || j.endedAt != null) return copy(forest = forest.rollMonth(monthKey).let { if (today != null) it.copy(today = today) else it })
        val grown = forest.grow(j.id, at, monthKey, distKm, signs, today)
        val done = j.copy(endedAt = at, distKm = distKm, colorArgb = colorArgb, greeting = greeting,
            growth = grown.totalGrowth - forest.totalGrowth, signs = signs, stops = stops)
        return copy(journeys = journeys.map { if (it.id == j.id) done else it }, forest = grown)
    }

    /**
     * P 지점을 그 주행의 도착지로 남긴다 — 반경 200 m 안의 기존 점이면 새 점을 만들지 않고 방문 횟수만 올린다.
     * 이미 도착지가 있는 주행이면 바꾸지 않는다.
     */
    fun parkAt(journeyId: Long, at: Long, lat: Double, lon: Double): Records {
        val j = journey(journeyId) ?: return this
        if (j.parkPointId != null) return this
        val near = parkPoints.minByOrNull { distanceM(it.lat, it.lon, lat, lon) }
            ?.takeIf { distanceM(it.lat, it.lon, lat, lon) <= SAME_PLACE_M }
        val pointId: Long
        var next = this
        if (near != null) {
            pointId = near.id
            next = next.copy(parkPoints = parkPoints.map {
                if (it.id == near.id) it.copy(visitCount = it.visitCount + 1, lastAt = maxOf(it.lastAt, at), firstAt = minOf(it.firstAt, at)) else it
            })
        } else {
            pointId = nextId
            next = next.copy(
                parkPoints = parkPoints + ParkPoint(pointId, lat, lon, firstAt = at, lastAt = at, visitCount = 1),
                nextId = nextId + 1,
            )
        }
        return next.copy(journeys = next.journeys.map { if (it.id == journeyId) it.copy(parkPointId = pointId) else it })
    }

    fun addMemo(journeyId: Long, text: String, at: Long): Records {
        val t = text.trim()
        if (t.isEmpty() || journey(journeyId) == null) return this
        return copy(memos = memos + VoiceMemo(nextId, journeyId, t, at), nextId = nextId + 1)
    }

    fun confirmMemo(id: Long) = copy(memos = memos.map { if (it.id == id) it.copy(confirmed = true) else it })

    /** 고친 글은 사용자가 확인한 것으로 본다. 빈 글로 고치면 바꾸지 않는다 */
    fun editMemo(id: Long, text: String): Records {
        val t = text.trim()
        if (t.isEmpty()) return this
        return copy(memos = memos.map { if (it.id == id) it.copy(text = t, confirmed = true) else it })
    }

    fun deleteMemo(id: Long) = copy(memos = memos.filterNot { it.id == id })

    // ── 장소 (전국 지도, D-042·D-030) ──

    /** 그 장소에 도착한 주행 — 최근 것이 위. 도착(P 체결)한 것만 */
    fun journeysAt(pointId: Long): List<Journey> =
        journeys.filter { it.parkPointId == pointId && it.endedAt != null }.sortedByDescending { it.endedAt }

    /** 그 장소의 대표 색 = **마지막 도착의 오늘의 색** (30번 6.5·7.4). 색이 남은 도착이 없으면 null */
    fun colorOf(pointId: Long): Int? = journeysAt(pointId).firstOrNull { it.colorArgb != null }?.colorArgb

    /**
     * 사용자가 직접 붙인 이름 (D-042 — 앱이 추정하지 않는다). 앞뒤 공백을 지우고 [LABEL_MAX]자에서 자른다.
     * 빈 글이면 이름을 없앤다(다시 "방문 n회"가 제목이 된다)
     */
    fun renameParkPoint(id: Long, label: String?): Records {
        val t = label?.trim()?.take(LABEL_MAX)?.takeIf { it.isNotEmpty() }
        if (parkPoint(id) == null) return this
        return copy(parkPoints = parkPoints.map { if (it.id == id) it.copy(label = t) else it })
    }

    /**
     * 장소 하나 지우기 (D-030). 그 점과 이름을 지우고, 그 장소에 도착한 주행의 도착지를 끊는다.
     * 주행·음성 기록·풍경은 남는다 — 음성 기록은 목록에서 "위치 없음"으로 보인다. 다음에 같은 곳에 세우면 새 점이 생긴다
     */
    fun deleteParkPoint(id: Long): Records {
        if (parkPoint(id) == null) return this
        return copy(
            parkPoints = parkPoints.filterNot { it.id == id },
            journeys = journeys.map { if (it.parkPointId == id) it.copy(parkPointId = null) else it },
        )
    }

    /**
     * 전체 초기화 (D-030). 진행 중인 주행 하나는 남긴다 — 그 주행에 남길 말이 갈 곳이 있어야 한다.
     * 풍경은 남긴다 — 장소·글·시각이 없는 그림이다. 요소가 어느 주행에서 언제 생겼는지만 끊는다 (D-087)
     */
    fun clearAll(keepJourneyId: Long?): Records = Records(
        journeys = journeys.filter { it.id == keepJourneyId }.map { it.copy(parkPointId = null) },
        nextId = nextId,
        forest = forest.forgetJourneys(),
    )

    companion object {
        /** 같은 장소로 보는 반경 (12번 13.4, D-042) */
        const val SAME_PLACE_M = 200.0
        /** 장소 이름 최대 글자 수 — 목록 한 줄에 들어가는 길이 */
        const val LABEL_MAX = 16

        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}
