package com.milebloom.app.record

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [Records]를 앱 전용 저장소의 JSON 파일 하나에 둔다 (D-082). 외부로 보내지 않는다. 앱을 지우면 함께 지워진다.
 *
 * Room 대신 파일을 쓴 이유: 1차에 필요한 기록은 수백 행 이하이고, 빌드에 주석 처리기를 더하지 않는다.
 * 쌓인 풍경([Forest])도 같은 파일에 둔다 (D-087). 형식은 [RecordCodec].
 *
 * 바꾸기는 메인 스레드에서 하고, 쓰기는 한 줄짜리 실행기에서 원자적으로(AtomicFile) 한다.
 */
class RecordStore(context: Context) {

    private val file = AtomicFile(File(context.filesDir, FILE_NAME))
    private val writer = Executors.newSingleThreadExecutor()

    private val _records = MutableStateFlow(load())
    val records: StateFlow<Records> = _records.asStateFlow()

    val current: Records get() = _records.value

    init {
        // 처음 설치면 시드를, 버전 1 파일이면 다시 세운 숲을 바로 남긴다 — 저장 전에 앱이 꺼져도 다음 실행의 풍경이 같게
        val json = RecordCodec.encode(current)
        writer.execute { save(json) }
    }

    fun update(change: (Records) -> Records) {
        val next = change(_records.value)
        if (next == _records.value) return
        _records.value = next
        val json = RecordCodec.encode(next)
        writer.execute { save(json) }
    }

    private fun load(): Records = try {
        if (!file.baseFile.exists()) RecordCodec.fresh()
        else RecordCodec.decode(String(file.readFully(), Charsets.UTF_8)).also {
            Log.i(TAG, "기록 읽음: 주행 ${it.journeys.size} · 누적 ${it.forest.totalDistKm} km · 요소 ${it.forest.elements.size}" +
                if (it.forest.debugKm > 0f) " (debug 주입 ${it.forest.debugKm} km 포함)" else "")
        }
    } catch (t: Throwable) {
        Log.e(TAG, "기록 읽기 실패 — 빈 기록으로 시작: ${t.message}")
        RecordCodec.fresh()
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
    }
}
