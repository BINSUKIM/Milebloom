package com.milebloom.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log

/**
 * 백그라운드 기록 검증 (open question #29, #30).
 *
 * 부팅·잠금해제 브로드캐스트 중 무엇이 user 10에 실제로 도착하는지 기록하고,
 * 도착하면 [RecordService]를 포그라운드로 띄운다.
 *
 * `tryLaunchActivity` 플래그가 켜져 있으면 수신기에서 MainActivity를 직접 띄워
 * 백그라운드 화면 시작(BAL)이 막히는지도 확인한다. 기본은 꺼져 있다.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: "null"
        val user = Process.myUserHandle().hashCode()
        Log.i(RecordService.TAG, "BOOT 수신 action=$action user=$user pid=${Process.myPid()}")

        when (action) {
            ACTION_SET_TRY_LAUNCH -> {
                val on = intent.getBooleanExtra("on", false)
                prefs(context).edit().putBoolean(KEY_TRY_LAUNCH, on).apply()
                Log.i(RecordService.TAG, "tryLaunchActivity=$on 로 설정")
                return
            }
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // 사용자 잠금 상태에서는 credential 저장소를 못 쓴다. 기록만 하고 서비스는 BOOT_COMPLETED에서 띄운다.
                return
            }
        }

        try {
            RecordService.start(context, "receiver:$action")
            Log.i(RecordService.TAG, "RecordService 시작 요청 성공 ($action)")
        } catch (t: Throwable) {
            Log.e(RecordService.TAG, "RecordService 시작 실패 ($action): ${t.javaClass.simpleName}: ${t.message}")
        }

        if (action == Intent.ACTION_BOOT_COMPLETED &&
            prefs(context).getBoolean(KEY_TRY_LAUNCH, false)
        ) {
            try {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                Log.i(RecordService.TAG, "BAL 시도: startActivity(MainActivity) 예외 없이 반환")
            } catch (t: Throwable) {
                Log.e(RecordService.TAG, "BAL 시도 예외: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    companion object {
        const val ACTION_SET_TRY_LAUNCH = "com.milebloom.app.SET_TRY_LAUNCH"
        private const val KEY_TRY_LAUNCH = "tryLaunchActivity"

        private fun prefs(context: Context) =
            context.getSharedPreferences("bootprobe", Context.MODE_PRIVATE)
    }
}
