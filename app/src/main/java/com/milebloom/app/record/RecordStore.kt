package com.milebloom.app.record

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Records]를 앱 전용 저장소의 JSON 파일 하나에 둔다 (D-082). 외부로 보내지 않는다. 앱을 지우면 함께 지워진다.
 *
 * Room 대신 파일을 쓴 이유: 1차에 필요한 기록은 수백 행 이하이고, 빌드에 주석 처리기를 더하지 않는다.
 * 5-B가 숲 성장까지 저장할 때 Room으로 옮길 수 있게 모델([Records])과 저장 방식을 나눴다.
 *
 * 바꾸기는 메인 스레드에서 하고, 쓰기는 한 줄짜리 실행기에서 원자적으로(AtomicFile) 한다.
 */
class RecordStore(context: Context) {

    private val file = AtomicFile(File(context.filesDir, FILE_NAME))
    private val writer = Executors.newSingleThreadExecutor()

    private val _records = MutableStateFlow(load())
    val records: StateFlow<Records> = _records.asStateFlow()

    val current: Records get() = _records.value

    fun update(change: (Records) -> Records) {
        val next = change(_records.value)
        if (next == _records.value) return
        _records.value = next
        val json = encode(next)
        writer.execute { save(json) }
    }

    private fun load(): Records = try {
        if (!file.baseFile.exists()) Records() else decode(String(file.readFully(), Charsets.UTF_8))
    } catch (t: Throwable) {
        Log.e(TAG, "기록 읽기 실패 — 빈 기록으로 시작: ${t.message}")
        Records()
    }

    private fun save(json: String) {
        val out = try { file.startWrite() } catch (t: Throwable) { Log.e(TAG, "기록 쓰기 실패: ${t.message}"); return }
        try {
            out.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (t: Throwable) {
            file.failWrite(out)
            Log.e(TAG, "기록 쓰기 실패: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "MBREC"
        const val FILE_NAME = "records.json"
        const val VERSION = 1

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
        }.toString()

        fun decode(s: String): Records {
            val o = JSONObject(s)
            fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
            fun JSONObject.longOrNull(k: String) = if (has(k)) getLong(k) else null
            fun JSONObject.intOrNull(k: String) = if (has(k)) getInt(k) else null
            fun JSONObject.strOrNull(k: String) = if (has(k)) getString(k) else null
            return Records(
                journeys = o.optJSONArray("journeys")?.objects().orEmpty().map {
                    Records.Journey(
                        id = it.getLong("id"),
                        startedAt = it.getLong("startedAt"),
                        endedAt = it.longOrNull("endedAt"),
                        distKm = it.optDouble("distKm", 0.0).toFloat(),
                        colorArgb = it.intOrNull("colorArgb"),
                        greeting = it.strOrNull("greeting"),
                        parkPointId = it.longOrNull("parkPointId"),
                    )
                },
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
            )
        }
    }
}
