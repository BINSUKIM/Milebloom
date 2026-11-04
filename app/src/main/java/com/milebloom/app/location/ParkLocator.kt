package com.milebloom.app.location

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData

/**
 * P 체결 지점 좌표 1회 조회 (FusedLocation `getLastKnownLocation`).
 *
 * 경로를 연속 기록하지 않는다 — 연속 추적 API(`registerFusedLocationCallback`)는 쓰지 않는다 (D-005).
 * ★ 권한 없이 호출하면 onError가 아니라 SecurityException으로 앱이 죽는다 (13번 9장) → 호출 전에 확인한다.
 */
class ParkLocator(private val context: Context) {

    private var fused: FusedLocation? = null

    fun hasPermission(): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** 좌표를 돌려준다. 권한 없음·미지원·실패·시간 초과면 null (스키마상 endLat/endLon은 null 허용). */
    suspend fun locateOnce(timeoutMs: Long = 3_000): LocationData? {
        if (!hasPermission()) {
            Log.w(TAG, "FUSED_LOCATION 권한 없음 — 좌표 없이 진행")
            return null
        }
        val fl = fused ?: try {
            FusedLocation(context.applicationContext).also {
                if (!it.isSupported()) {
                    Log.w(TAG, "FusedLocation 미지원")
                    return null
                }
                it.initialize()
                fused = it
            }
        } catch (t: Throwable) {
            Log.e(TAG, "FusedLocation 초기화 실패: ${t.message}")
            return null
        }
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                try {
                    fl.getLastKnownLocation(object : FusedLocationListener {
                        override fun onChanged(location: LocationData?) {
                            if (cont.isActive) cont.resume(location)
                        }

                        override fun onError(exception: Exception) {
                            Log.w(TAG, "좌표 조회 실패: ${exception.message}")
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                } catch (t: Throwable) {
                    Log.e(TAG, "좌표 조회 예외: ${t.javaClass.simpleName}: ${t.message}")
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    fun release() {
        try { fused?.release() } catch (_: Throwable) {}
        fused = null
    }

    companion object {
        const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"
        private const val TAG = "MBLOC"
    }
}
