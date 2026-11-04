package com.milebloom.app.mode

import android.os.SystemClock
import android.util.Log
import com.milebloom.app.vehicle.VehicleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 차량 신호 흐름을 받아 [ModeMachine]을 돌리고, 모드와 사건을 흐름으로 내보낸다.
 * 모드 전환은 Logcat 태그 `MBMODE`.
 */
class ModeController(signals: StateFlow<VehicleSnapshot>, scope: CoroutineScope) {

    private val machine = ModeMachine()

    private val _mode = MutableStateFlow(machine.mode)
    val mode: StateFlow<AppMode> = _mode.asStateFlow()

    private val _events = MutableSharedFlow<ModeEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ModeEvent> = _events.asSharedFlow()

    init {
        // 주행거리를 기어보다 먼저 반영해야 P 체결 순간의 누적값이 끝값이 된다.
        signals
            .onEach { s ->
                machine.onOdometer(s.odometerKm)
                val event = machine.onGear(s.gear, SystemClock.elapsedRealtime())
                if (event != null) {
                    Log.i(TAG, "사건 $event" + if (event is ModeEvent.JourneyEnded) " distKm=${event.distKm}" else "")
                    _events.tryEmit(event)
                }
                if (_mode.value != machine.mode) {
                    Log.i(TAG, "모드 ${_mode.value} → ${machine.mode} (gear=${s.gear})")
                    _mode.value = machine.mode
                }
            }
            .launchIn(scope)
    }

    private companion object {
        const val TAG = "MBMODE"
    }
}
