package com.milebloom.app.record

import java.util.Calendar
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Records] ↔ JSON (`files/records.json`). 안드로이드 Context 없이 돌아 단위 테스트할 수 있다.
 *
 * 버전 1(5-E): 주행·P 지점·음성 기록. 버전 2(D-087): + 풍경(`forest`), 주행의 성장량·신호 집계.
 * 버전 1 파일을 읽으면 끝난 주행의 거리로 숲을 다시 세운다 — 신호 집계가 없어 요소는 만들지 않는다.
 */
object RecordCodec {

    const val VERSION = 2

    /** 처음 설치 — 빈 기록, 시드만 새로 정한다 */
    fun fresh(): Records = Records(forest = Forest(seed = Random.nextLong()))

    fun monthKeyOf(epochMs: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = epochMs }
        return Forest.monthKey(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    fun encode(r: Records): String = JSONObject().apply {
        put("version", VERSION)
        put("nextId", r.nextId)
        put("journeys", JSONArray().apply {
            r.journeys.forEach { j ->
                put(JSONObject().apply {
                    put("id", j.id)
                    put("startedAt", j.startedAt)
                    j.endedAt?.let { put("endedAt", it) }
                    put("distKm", j.distKm.toDouble())
                    j.colorArgb?.let { put("colorArgb", it) }
                    j.greeting?.let { put("greeting", it) }
                    j.parkPointId?.let { put("parkPointId", it) }
                    if (j.growth > 0f) put("growth", j.growth.toDouble())
                    j.signs?.let { s ->
                        put("signs", JSONObject().apply {
                            put("rain", s.rain.toDouble()); put("rainLv", s.rainLv); put("night", s.night.toDouble())
                            put("wind", s.wind.toDouble()); put("guests", s.guests)
                        })
                    }
                    if (j.stops > 0) put("stops", j.stops)
                })
            }
        })
        put("parkPoints", JSONArray().apply {
            r.parkPoints.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id)
                    put("lat", p.lat)
                    put("lon", p.lon)
                    put("firstAt", p.firstAt)
                    put("lastAt", p.lastAt)
                    put("visitCount", p.visitCount)
                    p.label?.let { put("label", it) }
                })
            }
        })
        put("memos", JSONArray().apply {
            r.memos.forEach { m ->
                put(JSONObject().apply {
                    put("id", m.id)
                    put("journeyId", m.journeyId)
                    put("text", m.text)
                    put("recordedAt", m.recordedAt)
                    put("confirmed", m.confirmed)
                })
            }
        })
        put("forest", encodeForest(r.forest))
    }.toString()

    private fun encodeForest(f: Forest) = JSONObject().apply {
        put("seed", f.seed)
        put("totalDistKm", f.totalDistKm.toDouble())
        put("totalGrowth", f.totalGrowth.toDouble())
        put("journeyCount", f.journeyCount)
        f.meadowMonthKey?.let { put("meadowMonthKey", it) }
        put("meadowGrowth", f.meadowGrowth.toDouble())
        if (f.debugKm > 0f) put("debugKm", f.debugKm.toDouble())
        f.today?.let { t -> put("today", JSONObject().apply { put("h", t.hue.toDouble()); put("s", t.sat.toDouble()); put("v", t.value.toDouble()) }) }
        put("elements", JSONArray().apply {
            f.elements.forEach { e ->
                put(JSONObject().apply {
                    put("type", e.type.name)
                    put("count", e.count)
                    e.journeyId?.let { put("journeyId", it) }
                    e.monthKey?.let { put("monthKey", it) }
                    put("createdAt", e.createdAt)
                })
            }
        })
    }

    fun decode(s: String): Records {
        val o = JSONObject(s)
        val journeys = o.optJSONArray("journeys")?.objects().orEmpty().map {
            Records.Journey(
                id = it.getLong("id"),
                startedAt = it.getLong("startedAt"),
                endedAt = it.longOrNull("endedAt"),
                distKm = it.optDouble("distKm", 0.0).toFloat(),
                colorArgb = it.intOrNull("colorArgb"),
                greeting = it.strOrNull("greeting"),
                parkPointId = it.longOrNull("parkPointId"),
                growth = it.optDouble("growth", 0.0).toFloat(),
                signs = it.optJSONObject("signs")?.let { g ->
                    Forest.Signs(
                        rain = g.optDouble("rain", 0.0).toFloat(), rainLv = g.optInt("rainLv", 0),
                        night = g.optDouble("night", 0.0).toFloat(), wind = g.optDouble("wind", 0.0).toFloat(),
                        guests = g.optInt("guests", 0),
                    )
                },
                stops = it.optInt("stops", 0),
            )
        }
        val forest = o.optJSONObject("forest")?.let(::decodeForest)
            ?: Forest.rebuiltFrom(Random.nextLong(), journeys, ::monthKeyOf)
        return Records(
            journeys = journeys,
            parkPoints = o.optJSONArray("parkPoints")?.objects().orEmpty().map {
                Records.ParkPoint(
                    id = it.getLong("id"),
                    lat = it.getDouble("lat"),
                    lon = it.getDouble("lon"),
                    firstAt = it.getLong("firstAt"),
                    lastAt = it.getLong("lastAt"),
                    visitCount = it.getInt("visitCount"),
                    label = it.strOrNull("label"),
                )
            },
            memos = o.optJSONArray("memos")?.objects().orEmpty().map {
                Records.VoiceMemo(
                    id = it.getLong("id"),
                    journeyId = it.getLong("journeyId"),
                    text = it.getString("text"),
                    recordedAt = it.getLong("recordedAt"),
                    confirmed = it.optBoolean("confirmed", false),
                )
            },
            nextId = o.optLong("nextId", 1L),
            forest = forest,
        )
    }

    private fun decodeForest(o: JSONObject) = Forest(
        seed = o.getLong("seed"),
        totalDistKm = o.optDouble("totalDistKm", 0.0).toFloat(),
        totalGrowth = o.optDouble("totalGrowth", 0.0).toFloat(),
        journeyCount = o.optInt("journeyCount", 0),
        meadowMonthKey = o.strOrNull("meadowMonthKey"),
        meadowGrowth = o.optDouble("meadowGrowth", 0.0).toFloat(),
        debugKm = o.optDouble("debugKm", 0.0).toFloat(),
        today = o.optJSONObject("today")?.let { t ->
            Forest.Today(t.getDouble("h").toFloat(), t.getDouble("s").toFloat(), t.getDouble("v").toFloat())
        },
        elements = o.optJSONArray("elements")?.objects().orEmpty().mapNotNull { e ->
            // 모르는 종류(이후 버전)는 건너뛴다 — 읽기 실패로 기록 전체를 잃지 않게
            val type = Forest.Type.entries.find { it.name == e.getString("type") } ?: return@mapNotNull null
            Forest.Element(type, e.optInt("count", 1), e.longOrNull("journeyId"), e.strOrNull("monthKey"), e.optLong("createdAt", 0L))
        },
    )

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.longOrNull(k: String) = if (has(k)) getLong(k) else null
    private fun JSONObject.intOrNull(k: String) = if (has(k)) getInt(k) else null
    private fun JSONObject.strOrNull(k: String) = if (has(k)) getString(k) else null
}
