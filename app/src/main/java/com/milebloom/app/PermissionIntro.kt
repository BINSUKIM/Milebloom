package com.milebloom.app

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.milebloom.app.location.ParkLocator
import com.milebloom.app.vehicle.Gear
import com.milebloom.app.vehicle.VehicleSnapshot

/**
 * 첫 실행 권한 안내 — **기어 P에서, 한 번만** (D-061, 13번 17-1).
 *
 * 런타임 권한 2개(P 지점 좌표 `FUSED_LOCATION`, 정차 판별 `CAR_SPEED`)를 한 흐름으로 묻는다.
 * 대화상자 문구가 용도와 다르게 나오므로('위치 정보'·'알 수 없는 작업', 5-C 실측) 이유를 한 줄 먼저 보여 준다.
 * 주행 중(DO 요구)에는 띄우지 않고, P로 돌아오면 다시 보인다. 거부해도 앱은 동작한다 — 다시 묻지 않는다.
 *
 * 화면 디자인은 자리표시다. 주차 화면(4-C·5-E)이 정식 온보딩을 만들면 교체한다.
 */
class PermissionIntro(private val activity: Activity, root: FrameLayout) {

    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val view: View

    init {
        val dp = activity.resources.displayMetrics.density
        val reason = TextView(activity).apply {
            // 에뮬레이터 실측: CAR_SPEED는 '위치 정보에 액세스', Pleos FUSED_LOCATION은 '알 수 없는 작업 실행'으로 표시된다
            text = "권한 창이 두 번 뜹니다. 주차한 곳을 남기고, 차가 서면 풍경도 쉬어 가게 하는 데 씁니다."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(0xFF2F3A35.toInt()) // uiText MD (30번 2.7)
            gravity = Gravity.CENTER
        }
        val ok = Button(activity).apply {
            text = "계속"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            minHeight = (88 * dp).toInt()
            minWidth = (200 * dp).toInt()
            setOnClickListener { request() }
        }
        view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((48 * dp).toInt(), (120 * dp).toInt(), (48 * dp).toInt(), 0)
            addView(reason)
            addView(ok, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (32 * dp).toInt() })
            visibility = View.GONE
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
    }

    private fun missing(): List<String> = PERMISSIONS.filter {
        activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    /** 신호가 바뀔 때마다 부른다. P이고 DO 요구가 없을 때만 보인다 */
    fun onSnapshot(s: VehicleSnapshot) {
        if (prefs.getBoolean(KEY_DONE, false)) { view.visibility = View.GONE; return }
        if (missing().isEmpty()) { markDone(); view.visibility = View.GONE; return }
        val parked = s.gear == Gear.PARK && s.uxRequiresDo != true
        view.visibility = if (parked) View.VISIBLE else View.GONE
    }

    private fun request() {
        val m = missing()
        if (m.isEmpty()) { markDone(); view.visibility = View.GONE; return }
        activity.requestPermissions(m.toTypedArray(), REQ)
    }

    /** [Activity.onRequestPermissionsResult]에서 부른다. 허용·거부와 관계없이 한 번 물었으면 끝 */
    fun onResult(requestCode: Int): Boolean {
        if (requestCode != REQ) return false
        markDone()
        view.visibility = View.GONE
        return true
    }

    private fun markDone() = prefs.edit().putBoolean(KEY_DONE, true).apply()

    companion object {
        const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
        val PERMISSIONS = listOf(ParkLocator.PERMISSION, SPEED_PERMISSION)
        private const val PREFS = "milebloom"
        private const val KEY_DONE = "permission_intro_done"
        private const val REQ = 1
    }
}
