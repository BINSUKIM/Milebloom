package com.milebloom.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.milebloom.app.ambient.AmbientDirector
import com.milebloom.app.location.ParkLocator
import com.milebloom.app.mode.ModeController
import com.milebloom.app.mode.ModeEvent
import com.milebloom.app.render.SunClock
import com.milebloom.app.vehicle.VehicleSignals
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    /** 시연용(debug): null = 해 계산(서울), true = 해 짐, false = 낮. 화면과 오늘의 색이 같이 따른다 */
    @Volatile var sunDownOverride: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        signals = VehicleSignals(this)
        modes = ModeController(signals.snapshot, appScope)
        parkLocator = ParkLocator(this)

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

        // 5-B(기록)가 들어오기 전까지 P 지점 좌표는 로그로만 확인한다.
        modes.events.onEach { e ->
            ambient.onEvent(System.currentTimeMillis(), e)
            if (e is ModeEvent.JourneyEnded) {
                appScope.launch {
                    val loc = parkLocator.locateOnce()
                    Log.i(TAG, "P 지점 좌표 = ${loc?.let { "${it.latitude}, ${it.longitude}" } ?: "null"}")
                }
            }
        }.launchIn(appScope)
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
    }
}
