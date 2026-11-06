package com.milebloom.app

import android.app.Application
import android.content.Context
import android.util.Log
import android.os.SystemClock
import com.milebloom.app.ambient.AmbientDirector
import com.milebloom.app.ambient.ArrivalGreeting
import com.milebloom.app.ambient.JourneyTally
import com.milebloom.app.record.Forest
import com.milebloom.app.record.RecordCodec
import com.milebloom.app.record.RecordStore
import com.milebloom.app.render.SceneState
import com.milebloom.app.ambient.TodayColor
import com.milebloom.app.location.ParkLocator
import com.milebloom.app.mode.ModeController
import com.milebloom.app.mode.ModeEvent
import com.milebloom.app.render.SunClock
import com.milebloom.app.vehicle.VehicleSignals
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * 앱 전역 객체를 한 곳에 둔다. DI 프레임워크 없이 수동으로 연결한다.
 *
 * 신호 수집의 시작·종료는 화면([MainActivity])이 정한다 — 앱을 켠 동안만 기록한다 (D-046).
 */
class MilebloomApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var signals: VehicleSignals
        private set
    lateinit var modes: ModeController
        private set
    lateinit var parkLocator: ParkLocator
        private set
    lateinit var ambient: AmbientDirector
        private set
    lateinit var records: RecordStore
        private set

    /** 지금 달리고 있는 주행의 기록 id. 음성 기록이 여기에 묶인다. 주차 중이면 null */
    var currentJourneyId: Long? = null
        private set

    /** P 체결의 도착 인사. 화면이 연출에 쓴다. [atElapsedMs]로 '방금'인지 판단한다 */
    data class Arrival(val journeyId: Long?, val greeting: String, val atElapsedMs: Long)

    private val _arrival = MutableStateFlow<Arrival?>(null)
    val arrival: StateFlow<Arrival?> = _arrival.asStateFlow()

    /**
     * 화면이 그릴 쌓인 풍경. [growAtElapsedMs]가 있으면 그 P 체결에서 자란 것이다 — 화면이 '오늘 생긴 요소' 연출을 한다.
     * 주행 중에 바뀐 값은 렌더러가 다음 P까지 미룬다
     */
    data class SceneInput(val state: SceneState, val growAtElapsedMs: Long?)

    private val _scene = MutableStateFlow(SceneInput(SceneState.empty(), null))
    val scene: StateFlow<SceneInput> = _scene.asStateFlow()

    /** debug 시연용: 들판의 달을 바꿔 보여 준다. 저장된 풍경은 그대로다 */
    var monthOverride: Int? = null
        set(v) { field = v; ambient.monthOverride = v; publishScene(null) }

    private val prefs by lazy { getSharedPreferences("milebloom", Context.MODE_PRIVATE) }

    /** 설정 '주차한 곳 남기기' (D-030 위치 기록 중단). 끄면 P 지점을 조회하지 않는다 */
    var keepParkPoints: Boolean
        get() = prefs.getBoolean(KEY_KEEP_PARK, true)
        set(v) = prefs.edit().putBoolean(KEY_KEEP_PARK, v).apply()

    /** 시연용(debug): null = 해 계산(서울), true = 해 짐, false = 낮. 화면과 오늘의 색이 같이 따른다 */
    @Volatile var sunDownOverride: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        signals = VehicleSignals(this)
        modes = ModeController(signals.snapshot, appScope)
        parkLocator = ParkLocator(this)
        records = RecordStore(this)

        ambient = AmbientDirector(
            port = object : AmbientDirector.Port {
                override fun readSwitchOn(onResult: (Boolean?) -> Unit) = signals.readAmbientSwitchOn { r -> mainThread { onResult(r) } }
                override fun readColor(onResult: (Int?) -> Unit) = signals.readAmbientColor { r -> mainThread { onResult(r) } }
                override fun setColor(argb: Int, onResult: (Boolean) -> Unit) = signals.setAmbientColor(argb) { r -> mainThread { onResult(r) } }
            },
            store = PrefsAmbientStore(this),
            isSunDown = { ms -> sunDownOverride ?: SunClock.isSunDown(ms) },
            currentMonth = { Calendar.getInstance().get(Calendar.MONTH) + 1 },
        )
        signals.snapshot.onEach { s -> ambient.onSnapshot(System.currentTimeMillis(), s) }.launchIn(appScope)

        // 새달 첫 승차면 들판을 새로 시작한다 (12번 13.6). 마지막 오늘의 색을 되살린다 (D-087)
        records.update { it.copy(forest = it.forest.rollMonth(monthKeyNow())) }
        records.current.forest.today?.let { t -> ambient.restoreToday(TodayColor(t.hue, t.sat, t.value)) }
        publishScene(null)
        records.current.forest.let { f ->
            Log.i(TAG_FOREST, "풍경 읽음: 누적 ${f.totalDistKm} km · growth ${f.totalGrowth} · 이번 달 ${f.meadowGrowth} · 요소 ${f.elements.size}" +
                if (f.debugKm > 0f) " · debug 주입 ${f.debugKm} km" else "")
        }

        // 주행 시작·끝을 기록하고, P 체결 때 도착 인사를 정하고 풍경을 키우고 P 지점 한 점을 남긴다 (12번 13.6, D-082·D-087).
        modes.events.onEach { e ->
            val now = System.currentTimeMillis()
            ambient.onEvent(now, e)
            when (e) {
                is ModeEvent.JourneyStarted -> {
                    var id = 0L
                    records.update { r -> r.startJourney(now).also { id = it.second }.first }
                    currentJourneyId = id
                    Log.i(TAG, "주행 기록 시작 id=$id")
                }
                is ModeEvent.JourneyEnded -> {
                    val id = currentJourneyId
                    currentJourneyId = null
                    val tally = ambient.lastTally ?: JourneyTally.Result(0f, 0f, 1f)
                    val line = ArrivalGreeting.of(tally, e.distKm)
                    Log.i(TAG, "도착 인사 \"$line\" (stops=${tally.stops} dist=${e.distKm})")
                    val c = ambient.today.value
                    val before = records.current.forest
                    records.update {
                        it.arrive(
                            id, now, monthKeyNow(), e.distKm,
                            Forest.Signs(tally.rain, tally.rainLv, tally.night, tally.wind, tally.guests), tally.stops,
                            c?.let { t -> Forest.Today(t.hue, t.sat, t.ambientValue) }, c?.ambientArgb, line,
                        )
                    }
                    val after = records.current.forest
                    Log.i(TAG_FOREST, "P 체결: 거리 ${e.distKm} km → growth +${after.totalGrowth - before.totalGrowth}, " +
                        "누적 ${after.totalDistKm} km, 단계 ${before.stage} → ${after.stage}, 새 요소 " +
                        after.elements.filter { it.journeyId == id && id != null }.joinToString { "${it.type}×${it.count}" }.ifEmpty { "없음" } +
                        " (rain=${tally.rain} rainLv=${tally.rainLv} night=${tally.night} wind=${tally.wind} guests=${tally.guests})")
                    val at = SystemClock.elapsedRealtime()
                    publishScene(at)
                    _arrival.value = Arrival(id, line, at)
                    if (!keepParkPoints) {
                        Log.i(TAG, "주차한 곳 남기기 꺼짐 — 좌표를 조회하지 않음")
                    } else appScope.launch {
                        val loc = parkLocator.locateOnce()
                        Log.i(TAG, "P 지점 좌표 = ${loc?.let { "${it.latitude}, ${it.longitude}" } ?: "null"}")
                        if (id != null && loc != null) records.update { it.parkAt(id, now, loc.latitude, loc.longitude) }
                    }
                }
            }
        }.launchIn(appScope)
    }

    private fun monthKeyNow() = RecordCodec.monthKeyOf(System.currentTimeMillis())

    /** 저장된 풍경 → 화면 입력. @param growAt 도착 연출을 할 P 체결 시각(elapsedRealtime), 아니면 null */
    fun publishScene(growAt: Long?) {
        val f = records.current.forest
        val month = monthOverride ?: (Calendar.getInstance().get(Calendar.MONTH) + 1)
        _scene.value = SceneInput(SceneState.of(f.seed, f.view(monthKeyNow()), month), growAt)
    }

    /** 기록 모두 지우기 (D-030). 풍경은 남는다 (D-087) */
    fun clearAllRecords() {
        records.update { it.clearAll(currentJourneyId) }
        publishScene(null)
    }

    /** debug 시연용: 주행 없이 누적 거리를 더한다 (시작 상태 만들기). 실측과 구분해 [Forest.debugKm]에 남는다 */
    fun debugAddKm(km: Float) {
        records.update { it.copy(forest = it.forest.debugAddKm(km, monthKeyNow())) }
        Log.i(TAG_FOREST, "debug 거리 주입 +$km km → 누적 ${records.current.forest.totalDistKm} km (debug ${records.current.forest.debugKm} km)")
        publishScene(null)
    }

    /** debug 시연용: 풍경을 처음 설치한 상태로 (시드는 유지). 주행·장소·음성 기록은 그대로 */
    fun debugResetForest() {
        records.update { it.copy(forest = Forest(seed = it.forest.seed, meadowMonthKey = monthKeyNow())) }
        Log.i(TAG_FOREST, "debug 풍경 초기화")
        publishScene(null)
    }

    /** 음성 기록 한 줄을 지금 주행에 묶는다. 주행 중이 아니면 마지막 주행에 (debug 주입용) */
    fun addVoiceMemo(text: String): Boolean {
        val id = currentJourneyId ?: records.current.journeys.lastOrNull()?.id ?: return false
        val before = records.current.memos.size
        records.update { it.addMemo(id, text, System.currentTimeMillis()) }
        return records.current.memos.size > before
    }

    private fun mainThread(body: () -> Unit) {
        appScope.launch { body() }
    }

    /** 사용자 원래 색·연동 설정. 앱을 다시 켜도 처음 읽은 원래 색을 덮어쓰지 않도록 저장한다 */
    private class PrefsAmbientStore(context: Context) : AmbientDirector.Store {
        private val prefs = context.getSharedPreferences("milebloom", Context.MODE_PRIVATE)
        override var originalColor: Int?
            get() = if (prefs.contains(KEY_ORIGINAL)) prefs.getInt(KEY_ORIGINAL, 0) else null
            set(v) = prefs.edit().apply { if (v == null) remove(KEY_ORIGINAL) else putInt(KEY_ORIGINAL, v) }.apply()
        override var linkEnabled: Boolean
            get() = prefs.getBoolean(KEY_LINK, true)
            set(v) = prefs.edit().putBoolean(KEY_LINK, v).apply()

        private companion object {
            const val KEY_ORIGINAL = "ambient_original_color"
            const val KEY_LINK = "ambient_link_enabled"
        }
    }

    private companion object {
        const val TAG = "MBMODE"
        const val TAG_FOREST = "MBFOREST"
        const val KEY_KEEP_PARK = "keep_park_points"
    }
}
