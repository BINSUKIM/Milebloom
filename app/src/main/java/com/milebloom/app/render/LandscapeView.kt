package com.milebloom.app.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import androidx.core.graphics.ColorUtils
import com.milebloom.app.ambient.Oklch
import com.milebloom.app.ambient.TodayColor
import com.milebloom.app.mode.AppMode
import com.milebloom.app.mode.DisplayLevel
import com.milebloom.app.vehicle.SeatPos
import com.milebloom.app.vehicle.VehicleSnapshot
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 풍경 렌더링 엔진 (세션 5-C). 기준: `docs/30-art-direction.md` 3장(구도)·4장(에셋)·6.2(움직임 수치).
 *
 * 층 L0 하늘 → L1 원경(해·달·별·구름·능선) → L2 중경(나무·덤불·개울) → L3 길 + 화면 속 차 → L4 근경(들판·웅덩이·새·반딧불·홀씨) → L5 화면 효과.
 *
 * 주행 중(JOURNEY) 지키는 것 (30번 6.1·6.2, D-052):
 * - 풍경은 **실제 차 속도와 무관한 등속**으로 오른쪽 → 왼쪽으로 흐른다. 근경 0.03H/초가 가장 빠르다
 * - 차선·점선·속도선·바퀴 회전·날갯짓·반딧불 점멸을 **그리지 않는다.** 차는 움직이지 않는다
 * - 새 요소·계절·성장 단계 변경은 주행 중에 적용하지 않는다 — [state]·[carModel]은 기어 P에서 반영된다
 * - 도착(P) 때 [growInto]로 받은 풍경은 '오늘 생긴 요소'만 하나씩 0.8초에 걸쳐 밑단에서 자라난다 (30번 6.4, D-087).
 *   D로 바꾸면 남은 연출 없이 바로 다 자란 모습이다
 * - 낮↔밤 20초 교차 혼합, 헤드램프·창문 1초, D 진입 즉시 주행 톤 / P 진입 1.5초
 * - D에서 정차하면 흐름이 [FLOW_STOP_SEC]초에 걸쳐 서서히 멈추고, 출발하면 [FLOW_RESUME_SEC]초에 걸쳐 다시 흐른다
 *   (D-061·D-064). 흐름/멈춤 두 상태뿐 — 속도에 비례하지 않는다. 정차 신호가 없으면(권한 거부 등) 계속 흐른다.
 *   멈추는 것은 흐름(4층·홀씨 가로 이동)뿐이고 흔들림·반딧불·홀씨 떠 있음은 남는다
 * - 바퀴는 [wheelSpin]이 켜져 있으면 흐름과 같은 배율로 돈다 (시험 적용, D-063). 끄면 정지 그림
 * - 30 fps 상한
 */
class LandscapeView(context: Context) : View(context), Choreographer.FrameCallback {

    // ───────────── 입력 ─────────────

    var mode: AppMode = AppMode.BOARDING

    var snapshot: VehicleSnapshot = VehicleSnapshot()

    /** 쌓인 풍경. 주행 중에 바뀌면 다음 P에서 적용한다 (앱을 켠 뒤 첫 값만은 바로 적용). 연출 없이 바뀐다 */
    var state: SceneState
        get() = target
        set(value) {
            if (value == target) return
            target = value
            pendingState = true
            growAt = null
        }
    private var target: SceneState = SceneState.empty()

    /**
     * 도착 연출과 함께 풍경을 바꾼다 (30번 6.4 '약 4초 ~'). 지금 보이는 풍경에 없던 요소만 [startElapsedMs]부터
     * 0.15초 간격으로 하나씩 0.8초에 걸쳐 자란다. 사라지는 싹은 같은 시간에 줄어든다.
     * 계획이 서면 [onGrowthPlanned]로 끝 시각을 알린다.
     * @param startElapsedMs `SystemClock.elapsedRealtime()` 기준
     */
    fun growInto(value: SceneState, startElapsedMs: Long) {
        if (value == appliedState && !pendingState) {
            // 이미 연출 없이 바뀌었다(값이 먼저 들어온 경우) — 바로 전 풍경을 기준으로 다시 계획한다
            val prev = previousState ?: return onGrowthPlanned(startElapsedMs)
            if (SystemClock.elapsedRealtime() - appliedAtMs > 2_000) return onGrowthPlanned(startElapsedMs)
            appliedState = prev
            rebuildLayout(width, height)
        }
        target = value
        pendingState = true
        growAt = startElapsedMs
    }

    /** 도착 연출 계획의 끝 시각(elapsedRealtime). 자랄 것이 없으면 시작 시각 그대로 */
    var onGrowthPlanned: (endElapsedMs: Long) -> Unit = {}

    /** 차종 (D-058, 고르기 전 기본 아반떼). 주행 중에는 바뀌지 않는다 */
    var carModel: CarModel = CarModel.AVANTE
        set(value) { field = value; pendingCar = true }

    /** 바퀴 회전 (시험 적용, D-063). 기본값은 [WHEEL_SPIN]. 끄면 바퀴가 그 자리에서 서지 않고 정지 그림(0°)으로 돌아간다 */
    var wheelSpin: Boolean = WHEEL_SPIN

    /** 이번 P 체결의 오늘의 색. 주차 톤으로 넘어가는 만큼 하늘 위쪽이 30%까지 물든다 (30번 6.4·7.2, D-071) */
    var today: TodayColor? = null

    /**
     * 표시 수준 (30번 6.3, D-081). Lv3이면 세로 그라데이션 한 장만 그린다 — 전환은 연출 없이 즉시.
     * 차·풍경·바퀴를 그리지 않고, 낮밤 20초 전환만 남는다. 음성 버튼은 화면이 따로 얹는다(5-F)
     */
    var level: DisplayLevel = DisplayLevel.LV1
        set(value) { if (field != value) { field = value; invalidate() } }

    /** 디버그·시연용: null = 해 계산(서울), true = 해 짐, false = 낮 */
    var sunDownOverride: Boolean? = null

    /** 화면이 밤 쪽으로 반 넘게 넘어갔는가. 위에 얹는 글자·버튼이 낮/밤 색을 고르는 데 쓴다 */
    var isNight: Boolean = false
        private set

    /**
     * 주차 화면 글자·버튼 자리 (뷰 좌표). 주차 톤에서 이 안의 근경 요소(풀·꽃·새)를 서서히 지운다 — 시안처럼 글자 밑을 비운다 (32번 2.1).
     * 배치·난수는 그대로라 나머지는 움직이지 않는다. null이면 지우지 않는다
     */
    var clearZone: android.graphics.RectF? = null

    /** [isNight]가 바뀔 때 부른다 (메인 스레드) */
    var onNightChanged: (Boolean) -> Unit = {}

    // ───────────── 현재 적용 중인 것 ─────────────

    private var appliedState: SceneState = target
    private var pendingState = false
    /** 앱을 켠 뒤 저장된 풍경을 한 번이라도 적용했나. 첫 값은 주행 중이어도 바로 쓴다 — 아니면 빈 숲으로 달린다 */
    private var firstApplied = false
    private var previousState: SceneState? = null
    private var appliedAtMs = 0L
    private var growAt: Long? = null

    /** 도착 연출 중인 요소: 키 → (시작 시각, 시작 배율, 사라지는 중인가) */
    private class Grow(val startMs: Long, val from: Float, val leave: Boolean)
    private val growing = HashMap<Int, Grow>()
    private var growEndMs = 0L
    private var pendingCar = false
    private var car = CarSprite(CarShapes.of(carModel))
    private var layout: SceneLayout? = null

    // 보간되는 값 (0..1)
    private var memoryW = 1f      // 0 주행 톤 ~ 1 주차 톤
    private var darkW = 0f        // 0 낮 ~ 1 밤
    private var flowP = 0f        // 흐름 진행도 (선형 0..1)
    private var flowW = 0f        // 흐름 배율 f = ease(flowP) (30번 6.2-B)
    private var wheelDeg = 0f
    private var lampW = 0f
    private var clearW = 0f
    private var glassW = FloatArray(4)
    private var snapped = false

    private var growNow = 0L      // 이번 프레임의 elapsedRealtime (도착 연출용)
    private var flowTime = 0f     // 흐름이 진행된 '등속 초'
    private var clock = 0f        // 흔들림·떠다님용 시계(초)
    private var lastStepNs = 0L
    private var lastDrawNs = 0L
    private var sunDown = false
    private var sunCheckedMs = 0L

    // ───────────── 그리기 도구 ─────────────

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val skyPaint = Paint()
    private val fogPaint = Paint()
    private val minimalPaint = Paint()
    private var minimalKey = 0L
    private var minimalH = 0
    private var skyKey = 0L
    private var fogKey = 0
    private val moon = Path()
    private val rays = Path()
    private val tmpRect = RectF()
    private val carColors = CarColors()

    /** [Paint3] × [Slot] 색표. 매 프레임 톤 혼합 후 채운다 */
    private val colorTable = IntArray(Paint3.entries.size * SLOTS)

    private var cSkyTop = 0; private var cSkyLow = 0; private var cCloud = 0; private var cFar = 0
    private var cStream = 0; private var cGround = 0; private var cRoad = 0; private var cGlow = 0; private var cCelestial = 0
    // D-062 음영 토큰
    private var cFarShade = 0; private var cStreamLight = 0; private var cGroundShade = 0; private var cRoadShade = 0
    private var cMidBand = 0

    // ───────────── 성능 기록 (#35) ─────────────

    private var statFrames = 0
    private var statDrawNs = 0L
    private var statMaxNs = 0L
    private var statSinceMs = 0L

    // ───────────── 프레임 루프 ─────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastStepNs = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        // 30 fps 상한 (30번 6.2) — vsync 60Hz에서 두 번에 한 번만 그린다.
        // Lv3은 움직임이 없어 낮밤 전환만 따라가면 된다 — 1초에 한 번 (30번 6.2 '변화가 있을 때만')
        val interval = if (level == DisplayLevel.LV3) MINIMAL_FRAME_NS else FRAME_NS
        if (frameTimeNanos - lastDrawNs >= interval - VSYNC_SLACK_NS) {
            lastDrawNs = frameTimeNanos
            invalidate()
        }
        if (isAttachedToWindow) Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuildLayout(w, h)
    }

    private fun rebuildLayout(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val reserveTop = h - 200f * resources.displayMetrics.density
        layout = SceneLayout(appliedState, w, h, reserveTop)
        val H = h.toFloat()
        val r = H * 0.035f
        val sx = sunX(w, H)
        val sy = H * 0.15f
        moon.reset()
        moon.addCircle(sx, sy, r, Path.Direction.CW)
        moon.op(Path().apply { addCircle(sx + r * 0.45f, sy - r * 0.2f, r * 0.85f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        rays.reset()
        for (i in 0..2) {
            val x0 = w * (0.52f + 0.13f * i)
            rays.moveTo(x0, 0f)
            rays.lineTo(x0 + H * 0.035f, 0f)
            rays.lineTo(x0 - H * 0.2f + H * 0.11f, H * 0.62f)
            rays.lineTo(x0 - H * 0.2f, H * 0.62f)
            rays.close()
        }
    }

    private fun sunX(w: Int, H: Float) = w - max(H * 0.16f, w * 0.12f)

    // ───────────── 상태 진행 ─────────────

    private fun step(dt: Float) {
        val journey = mode == AppMode.JOURNEY

        if (pendingState && journey && !firstApplied && width > 0) {
            // 주행 중에 앱을 켰다 — 저장된 풍경을 바로 쓴다. 새로 생긴 것이 아니라 이미 있던 것이다
            applyState(null)
        }
        if (journey && growing.isNotEmpty()) finishGrowth()
        if (!journey && (pendingState || pendingCar)) {
            if (pendingState && width > 0) applyState(growAt)
            if (pendingCar) {
                car = CarSprite(CarShapes.of(carModel))
                glassW = FloatArray(4)
                pendingCar = false
            }
        }

        val nowMs = System.currentTimeMillis()
        if (nowMs - sunCheckedMs > 30_000 || sunCheckedMs == 0L) {
            sunCheckedMs = nowMs
            sunDown = SunClock.isSunDown(nowMs)
        }

        val s = snapshot
        val lowBeam = s.lowBeamOn == true
        // dark = 헤드램프 ON 또는 해가 진 시간 (D-043)
        val darkTarget = if (lowBeam || (sunDownOverride ?: sunDown)) 1f else 0f
        val lampTarget = if (lowBeam) 1f else 0f
        val gc = car.shape.glassCount
        fun open(a: SeatPos, b: SeatPos) = (max(s.windows[a] ?: 0, s.windows[b] ?: 0) / 10f).coerceIn(0f, 1f)
        val glassTarget = FloatArray(4).also {
            // 유리 조각은 뒤→앞 순서: 맨 앞 = 앞문, 그 뒤 = 뒷문, 나머지는 고정창 (30번 4.5)
            if (gc >= 1) it[gc - 1] = open(SeatPos.FRONT_LEFT, SeatPos.FRONT_RIGHT)
            if (gc >= 2) it[gc - 2] = open(SeatPos.REAR_LEFT, SeatPos.REAR_RIGHT)
        }

        if (!snapped) {
            snapped = true
            memoryW = if (journey) 0f else 1f
            flowP = if (journey && s.stopped != true) 1f else 0f
            darkW = darkTarget
            lampW = lampTarget
            glassTarget.copyInto(glassW)
        } else {
            // D 진입 → 즉시 주행 톤. P 진입 → 1.5초에 걸쳐 주차 톤·흐름 멈춤 (30번 2.6·6.4)
            if (journey) {
                memoryW = 0f
                when (s.stopped) {
                    // 정차 신호 없음(권한 거부·미지원) → 예전처럼 D 진입 즉시 흐른다
                    null -> flowP = 1f
                    // 정차 ↔ 출발: 서서히 (D-061·D-064). 도중에 뒤집혀도 지금 위치에서 이어 간다
                    true -> flowP = approach(flowP, 0f, dt / FLOW_STOP_SEC)
                    false -> flowP = approach(flowP, 1f, dt / FLOW_RESUME_SEC)
                }
            } else {
                memoryW = approach(memoryW, 1f, dt / 1.5f)
                flowP = approach(flowP, 0f, dt / 1.5f)
            }
            darkW = approach(darkW, darkTarget, dt / 20f)
            lampW = approach(lampW, lampTarget, dt / 1f)
            for (i in 0 until 4) glassW[i] = approach(glassW[i], glassTarget[i], dt / 1f)
        }

        // 사인 이징 인-아웃 (1 − cos πt)/2: 시작·끝 기울기가 0 — 갑작스러운 정지·재개 없음 (30번 6.2-B)
        flowW = (1f - cos(PI.toFloat() * flowP)) / 2f
        flowTime += dt * flowW
        // 바퀴: 근경 흐름과 맞물린 각속도 × 같은 배율. 속도에 비례하지 않는다 (30번 6.2-A)
        wheelDeg = if (wheelSpin) (wheelDeg + dt * flowW * car.shape.wheelDegPerSec) % 360f else 0f
        clock += dt
        clearW = approach(clearW, if (clearZone != null && !journey) 1f else 0f, dt / 0.8f)
        val night = darkW >= 0.5f
        if (night != isNight) { isNight = night; onNightChanged(night) }
    }

    /** [target]을 적용한다. [startMs]가 있으면 바뀐 요소에 도착 연출을 건다 */
    private fun applyState(startMs: Long?) {
        val old = layout
        previousState = appliedState
        appliedState = target
        appliedAtMs = SystemClock.elapsedRealtime()
        pendingState = false
        growAt = null
        finishGrowth()
        rebuildLayout(width, height)
        val now = layout
        firstApplied = true
        if (startMs != null) {
            val end = if (old != null && now != null) planGrowth(old, now, startMs) else startMs
            Log.i(TAG, "도착 연출: 새 요소 ${growing.count { !it.value.leave }}개, 줄어드는 것 ${growing.count { it.value.leave }}개, " +
                "${end - startMs} ms (stage ${previousState?.stage} → ${appliedState.stage})")
            onGrowthPlanned(end)
        }
    }

    /**
     * 새로 보이는 것(키가 없던 것)과 커진 것(어린 나무 → 나무)에 시작 시각을 매긴다. 숲 → 들판 → 생물 순서.
     * 간격은 0.15초, 다만 들판이 한 번에 많이 차면 전체가 2.4초를 넘지 않게 좁힌다 (주차 중 연출이 길어지지 않게).
     */
    private fun planGrowth(old: SceneLayout, now: SceneLayout, startMs: Long): Long {
        val oldPlaced = HashMap<Int, Placed>()
        (old.mid + old.near).forEach { oldPlaced[it.key] = it }
        val oldDrift = (old.fireflies + old.fluffs).map { it.key }.toHashSet()
        val fresh = ArrayList<Pair<Int, Float>>()   // 키, 시작 배율
        for (p in now.mid) {
            val o = oldPlaced[p.key]
            if (o == null) fresh += p.key to 0f else if (p.size > o.size * 1.05f) fresh += p.key to o.size / p.size
        }
        if (now.streamX >= 0f && old.streamX < 0f) fresh += K_STREAM to 0f
        for (p in now.near) if (p.key !in oldPlaced) fresh += p.key to 0f
        for (d in now.fireflies + now.fluffs) if (d.key !in oldDrift) fresh += d.key to 0f
        if (appliedState.moon && previousState?.moon != true) fresh += K_MOON to 0f
        // 줄어드는 것 — 새 배치에 없는 근경 요소(싹이 풀에 자리를 내줌). 다 줄면 목록에서 뺀다
        val nowKeys = (now.mid + now.near).map { it.key }.toHashSet()
        val leaving = (old.mid + old.near).filter { it.key !in nowKeys }
        for (p in leaving) {
            if (p.key in old.mid.map { it.key }) now.mid += p else now.near += p
            growing[p.key] = Grow(startMs, 1f, leave = true)
        }
        now.near.sortBy { it.baseY }
        if (fresh.isEmpty()) {
            growEndMs = if (leaving.isEmpty()) startMs else startMs + GROW_MS
            return growEndMs
        }
        val gap = if (fresh.size <= 1) 0L else min(GROW_GAP_MS, GROW_SPREAD_MS / (fresh.size - 1))
        fresh.forEachIndexed { i, (k, from) -> growing[k] = Grow(startMs + i * gap, from, leave = false) }
        growEndMs = startMs + (fresh.size - 1) * gap + GROW_MS
        return growEndMs
    }

    /** 연출을 끝낸다 — 자라던 것은 다 자란 모습, 줄던 것은 뺀다 */
    private fun finishGrowth() {
        if (growing.isEmpty()) return
        val gone = growing.filterValues { it.leave }.keys
        layout?.let { l -> l.mid.removeAll { it.key in gone }; l.near.removeAll { it.key in gone } }
        growing.clear()
    }

    /** 도착 연출 진행도. 1 = 다 자람(또는 연출 없음), 0 = 아직 안 보임 */
    private fun grown(key: Int, nowMs: Long): Float {
        val g = growing[key] ?: return 1f
        val t = ((nowMs - g.startMs).toFloat() / GROW_MS).coerceIn(0f, 1f)
        val e = (1f - cos(PI.toFloat() * t)) / 2f
        return if (g.leave) 1f - e else g.from + (1f - g.from) * e
    }

    private fun approach(v: Float, target: Float, maxStep: Float) =
        if (v < target) min(v + maxStep, target) else max(v - maxStep, target)

    // ───────────── 팔레트 ─────────────

    private fun resolvePalette(st: SceneState) {
        val sp = MbPalette.scene.getValue(st.season)
        val jd = sp.getValue(Tone.JOURNEY_DAY); val jn = sp.getValue(Tone.JOURNEY_NIGHT)
        val md = sp.getValue(Tone.MEMORY_DAY); val mn = sp.getValue(Tone.MEMORY_NIGHT)
        fun b(sel: (ScenePalette) -> Int) = blend4(sel(jd), sel(jn), sel(md), sel(mn))
        cSkyTop = washSkyTop(b { it.skyTop }); cSkyLow = b { it.skyLow }; cCloud = b { it.cloud }; cFar = b { it.far }
        cStream = b { it.stream }; cGround = b { it.ground }; cRoad = b { it.road }
        cGlow = b { it.glow }; cCelestial = b { it.celestial }
        cFarShade = b { it.farShade }; cStreamLight = b { it.streamLight }; cGroundShade = b { it.groundShade }; cRoadShade = b { it.roadShade }
        val cloudShade = b { it.cloudShade }

        // 숲색은 장면 팔레트의 mid 대신 단계별 값 (30번 2.5)
        val si = (st.stage - 1).coerceIn(0, 6)
        val fm = MbPalette.forestMidByStage.getValue(st.season)
        val fs = MbPalette.forestShadeByStage.getValue(st.season)
        val fl = MbPalette.forestLightByStage.getValue(st.season)
        fun bt(m: Map<Tone, IntArray>, i: Int) =
            blend4(m.getValue(Tone.JOURNEY_DAY)[i], m.getValue(Tone.JOURNEY_NIGHT)[i], m.getValue(Tone.MEMORY_DAY)[i], m.getValue(Tone.MEMORY_NIGHT)[i])
        val mid = bt(fm, si)
        val midShade = bt(fs, si)
        val midLight = bt(fl, si)
        val mi = (st.month - 1).coerceIn(0, 11)
        val a1 = bt(MbPalette.meadowA1, mi)
        val a2 = bt(MbPalette.meadowA2, mi)

        // 칸별 색 (journey_scene.js와 같은 혼합). 빛 = 오른쪽 위, 그늘 = 왼쪽 아래 (D-062)
        cMidBand = mix(cGround, cFar, 0.35f)                       // 중경 평지 — 대기 원근
        val midBandShade = mix(cGroundShade, cFar, 0.35f)          // 중경 접지 그림자
        fun set(p: Paint3, ground: Int, shade: Int, base: Int, light: Int, accent: Int = base, accentShade: Int = base, center: Int = base) {
            val o = p.ordinal * SLOTS
            colorTable[o + Slot.GROUND.ordinal] = ground
            colorTable[o + Slot.SHADE.ordinal] = shade
            colorTable[o + Slot.BASE.ordinal] = base
            colorTable[o + Slot.LIGHT.ordinal] = light
            colorTable[o + Slot.ACCENT_SHADE.ordinal] = accentShade
            colorTable[o + Slot.ACCENT.ordinal] = accent
            colorTable[o + Slot.ACCENT_CENTER.ordinal] = center
        }
        // 뒷줄은 far 쪽으로 35%씩 혼합 — 대기 원근 (30번 4.2 M1). 그림자는 중경 평지 쪽으로 같은 만큼
        fun tree(p: Paint3, fade: Float) = set(p, mix(midBandShade, cMidBand, fade), mix(midShade, cFar, fade), mix(mid, cFar, fade), mix(midLight, cFar, fade),
            accent = mix(midShade, cFar, fade))
        tree(Paint3.TREE, 0f)
        tree(Paint3.TREE_BACK, 0.35f)
        tree(Paint3.TREE_BACK2, 0.7f)
        set(Paint3.SLIM_TREE, midBandShade, midShade, mid, midLight, accent = mix(cCloud, cFar, 0.25f))
        set(Paint3.BUSH, midBandShade, midShade, mid, midLight)
        val g = mix(cGround, midShade, 0.45f)
        val gb = mix(cGroundShade, midShade, 0.55f)
        val gl = mix(cGround, midLight, 0.35f)
        set(Paint3.GRASS, cGroundShade, gb, g, gl)
        set(Paint3.FLOWER_A1, cGroundShade, gb, g, gl, accent = a1, accentShade = mix(a1, cGroundShade, 0.25f), center = a2)
        set(Paint3.FLOWER_A2, cGroundShade, gb, g, gl, accent = a2, accentShade = mix(a2, cGroundShade, 0.25f), center = mix(a1, 0xFFFFFFFF.toInt(), 0.35f))
        set(Paint3.SPROUT, cGroundShade, midShade, mid, midLight)
        set(Paint3.PUDDLE, cGroundShade, cGroundShade, cStream, cStreamLight)
        set(Paint3.BIRD, cGroundShade, midShade, mix(midShade, 0xFF000000.toInt(), 0.25f), midShade)
        set(Paint3.CLOUD, cloudShade, cloudShade, cCloud, cCloud)

        val cp = MbPalette.car
        val cjd = cp.getValue(Tone.JOURNEY_DAY); val cjn = cp.getValue(Tone.JOURNEY_NIGHT)
        val cmd = cp.getValue(Tone.MEMORY_DAY); val cmn = cp.getValue(Tone.MEMORY_NIGHT)
        fun c(sel: (CarPalette) -> Int) = blend4(sel(cjd), sel(cjn), sel(cmd), sel(cmn))
        carColors.body = c { it.carBody }; carColors.shade = c { it.carShade }; carColors.trim = c { it.carTrim }
        carColors.glass = c { it.carGlass }; carColors.tire = c { it.carTire }; carColors.wheel = c { it.carWheel }
        carColors.tailRed = c { it.carTailRed }; carColors.tailAmber = c { it.carTailAmber }; carColors.shadow = c { it.carShadow }
        carColors.highlight = c { it.carHighlight }; carColors.glassLight = c { it.carGlassLight }; carColors.spoke = c { it.carSpoke }
    }

    /**
     * 하늘 위쪽 끝색에 오늘의 색을 OKLCH 색상각 경로로 섞는다 (D-071). 그라데이션이 아래로 갈수록 빠지므로 위쪽만 물든다.
     * D에서는 memoryW = 0이라 즉시 사라진다. 결과는 오늘의 색 그대로가 아니다 — 11월 하늘은 연보라가 된다
     */
    private fun washSkyTop(skyTop: Int): Int {
        val c = today ?: return skyTop
        return Oklch.mixHue(skyTop, mix(c.memoryDayArgb, c.memoryNightArgb, darkW), TODAY_WASH * memoryW)
    }

    /** 두 팔레트를 토큰별 RGB 선형 보간 (30번 8장 5-C) — 주행/주차 × 낮/밤 */
    private fun blend4(jd: Int, jn: Int, md: Int, mn: Int): Int =
        mix(mix(jd, md, memoryW), mix(jn, mn, memoryW), darkW)

    private fun mix(a: Int, b: Int, t: Float) = if (t <= 0f) a else if (t >= 1f) b else ColorUtils.blendARGB(a, b, t)

    private fun color(p: Paint3, s: Slot) = colorTable[p.ordinal * SLOTS + s.ordinal]

    // ───────────── 그리기 ─────────────

    override fun onDraw(canvas: Canvas) {
        val t0 = System.nanoTime()
        val now = SystemClock.uptimeMillis() * 1_000_000L
        // 한 프레임에 진행할 시간 상한. Lv3은 1초에 한 번 그리므로 상한도 넓힌다 — 아니면 낮밤 20초 전환이 수 분으로 늘어난다
        val maxDt = if (level == DisplayLevel.LV3) 1.5f else 0.1f
        val dt = if (lastStepNs == 0L) 0f else ((now - lastStepNs) / 1e9f).coerceIn(0f, maxDt)
        lastStepNs = now
        step(dt)

        val lay = layout ?: return
        val st = appliedState
        growNow = SystemClock.elapsedRealtime()
        if (growing.isNotEmpty() && growNow > growEndMs) finishGrowth()
        resolvePalette(st)
        val w = width.toFloat()
        val H = lay.H

        if (level == DisplayLevel.LV3) {
            drawMinimal(canvas, st)
            recordStats(System.nanoTime() - t0)
            return
        }

        // L0 하늘
        val key = cSkyTop.toLong() shl 32 or (cSkyLow.toLong() and 0xFFFFFFFFL)
        if (key != skyKey) {
            skyKey = key
            skyPaint.shader = LinearGradient(0f, 0f, 0f, lay.horizonY, cSkyTop, cSkyLow, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w, lay.horizonY + 2f, skyPaint)

        // L1 원경 — 별(밤, 고정·반짝임 없음), 해/달(고정), 구름, 능선
        if (darkW > 0.01f) {
            fill.color = cCelestial
            fill.alpha = (0.5f * darkW * 255).toInt()
            val r = 1.5f * resources.displayMetrics.density
            for (p in lay.stars) if (p[1] < lay.horizonY - H * 0.12f) canvas.drawCircle(p[0], p[1], r, fill)
        }
        val sx = sunX(width, H)
        if (darkW < 0.99f) {
            // 해 + 둘레 빛무리 (반지름 1.8배, 불투명도 0.28, 고정 — 맥동 없음, 30번 4.6)
            fill.color = cCelestial
            fill.alpha = (0.28f * (1f - darkW) * 255).toInt()
            canvas.drawCircle(sx, H * 0.15f, H * 0.035f * 1.8f, fill)
            fill.alpha = ((1f - darkW) * 255).toInt()
            canvas.drawCircle(sx, H * 0.15f, H * 0.035f, fill)
        }
        if (st.moon && darkW > 0.01f) {
            // 달 + 둘레 (1.7배, 0.08). 오늘 생긴 달은 천천히 떠오르듯 나타난다
            val g = grown(K_MOON, growNow)
            fill.color = cCelestial
            fill.alpha = (0.08f * darkW * g * 255).toInt()
            canvas.drawCircle(sx, H * 0.15f, H * 0.035f * 1.7f, fill)
            fill.alpha = (darkW * g * 255).toInt()
            canvas.drawPath(moon, fill)
        }
        drawBand(canvas, lay.clouds, lay.cloudLen, flowTime * 0.002f * H, sway = false)
        // 능선 2장 — 모양이 다른 조각 6장을 해시 순서로 잇는다. 두 장의 순서·길이·속도가 다르다 (D-085, 30번 4.5-B)
        drawRidgeSeq(canvas, lay, LandscapeShapes.FAR_RIDGE_BACK_SEQ, RIDGE_BACK_H, RIDGE_BACK_START_UNITS,
            flowTime * 0.004f * H * 0.8f, mix(cFar, cSkyLow, 0.5f), mix(cFarShade, cSkyLow, 0.5f))
        drawRidgeSeq(canvas, lay, LandscapeShapes.FAR_RIDGE_FRONT_SEQ, RIDGE_FRONT_H, RIDGE_FRONT_START_UNITS,
            flowTime * 0.004f * H, cFar, cFarShade)

        // 중경 평지 (ground를 far 쪽으로 35% — 대기 원근). 무늬 없음
        fill.color = cMidBand
        canvas.drawRect(0f, lay.horizonY, w, lay.roadTop + 2f, fill)

        // 7단계 빛내림 (낮 톤만, 고정)
        if (st.stage >= 7 && darkW < 0.99f) {
            fill.color = cCelestial
            fill.alpha = (0.12f * (1f - darkW) * 255).toInt()
            canvas.drawPath(rays, fill)
        }

        // L2 중경 — 개울 → 나무·덤불
        val midOff = flowTime * 0.012f * H
        if (lay.streamX >= 0f) drawStream(canvas, lay, midOff)
        drawBand(canvas, lay.mid, lay.midLen, midOff, sway = false)

        // 7단계 안개 띠
        if (st.stage >= 7) {
            if (fogKey != cCloud) {
                fogKey = cCloud
                val c = ColorUtils.setAlphaComponent(cCloud, (0.25f * 255).toInt())
                fogPaint.shader = LinearGradient(0f, lay.treeBaseY - H * 0.05f, 0f, lay.treeBaseY + H * 0.004f,
                    intArrayOf(ColorUtils.setAlphaComponent(cCloud, 0), c, c), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, lay.treeBaseY - H * 0.05f, w, lay.treeBaseY + H * 0.004f, fogPaint)
        }

        // L3 길 — 사각 띠 하나. 차선·점선 없음 (D-052). 윗변 0.008H 길가 그늘 띠 — 경계선이지 무늬가 아니다 (D-062)
        fill.color = cRoad
        canvas.drawRect(0f, lay.roadTop, w, lay.roadBottom, fill)
        fill.color = cRoadShade
        canvas.drawRect(0f, lay.roadTop, w, lay.roadTop + H * 0.008f, fill)
        car.draw(
            canvas, w / 2f, lay.carGroundY, CarSprite.PX_PER_CM_PER_H * H,
            carColors, cGlow, cSkyLow, lampW, glassW, darkW, wheelDeg,
        )

        // L4 근경 — 들판 바탕 + 윗변 0.006H 그늘 띠(불투명도 0.7)
        fill.color = cGround
        canvas.drawRect(0f, lay.roadBottom, w, H, fill)
        fill.color = cGroundShade
        fill.alpha = (0.7f * 255).toInt()
        canvas.drawRect(0f, lay.roadBottom, w, lay.roadBottom + H * 0.006f, fill)
        val nearOff = flowTime * 0.03f * H
        drawBand(canvas, lay.near, lay.nearLen, nearOff, sway = true, clear = if (clearW > 0.001f) clearZone else null)
        drawDrifters(canvas, lay, nearOff, H)

        // L5 야간 감광 — 주행 밤 톤에 검정 15% (30번 6.3)
        val dim = 0.15f * darkW * (1f - memoryW)
        if (dim > 0.001f) canvas.drawColor((dim * 255).toInt() shl 24)

        recordStats(System.nanoTime() - t0)
    }

    /**
     * Lv3 최소 모드 (30번 6.3·7.2, 시안 J06~J08): 위 = 하늘색 7 : 최근 오늘의 색 3, 아래 = ground 7 : 같은 색 3.
     * 오늘의 색이 아직 없으면(앱을 켠 뒤 첫 P 전) 그달의 기본 색. 주행 밤 감광 15%는 그대로
     */
    private fun drawMinimal(canvas: Canvas, st: SceneState) {
        val c = today ?: TodayColor.of(st.month, 0f, 0f, 1f)
        val tint = mix(c.minimalDayArgb, c.minimalNightArgb, darkW)
        val top = mix(cSkyTop, tint, 0.3f)
        val bottom = mix(cGround, tint, 0.3f)
        val key = top.toLong() shl 32 or (bottom.toLong() and 0xFFFFFFFFL)
        if (key != minimalKey || minimalH != height) {
            minimalKey = key
            minimalH = height
            minimalPaint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), minimalPaint)
        val dim = 0.15f * darkW * (1f - memoryW)
        if (dim > 0.001f) canvas.drawColor((dim * 255).toInt() shl 24)
    }

    /** 띠 위 요소를 화면에 보이는 반복 위치마다 그린다. 흐름은 오른쪽 → 왼쪽 */
    private fun drawBand(canvas: Canvas, list: List<Placed>, len: Float, offset: Float, sway: Boolean, clear: android.graphics.RectF? = null) {
        val w = width.toFloat()
        for (p in list) {
            val half = p.size * 4.5f
            var x = mod(p.xBand * len - offset, len)
            x -= len * floor((x + half) / len)
            while (x - half < w) {
                // 글자 자리와 겹치는 요소는 주차 톤으로 넘어가는 만큼 옅어진다
                var keep = 1f
                if (clear != null && x + p.size * 0.6f > clear.left && x - p.size * 0.6f < clear.right &&
                    p.baseY > clear.top && p.baseY - p.size < clear.bottom) keep = 1f - clearW
                if (keep <= 0.01f) { x += len; continue }
                // 도착 연출: 밑단(원점)을 중심으로 0 → 1 배율로 자란다
                val g = if (growing.isEmpty()) 1f else grown(p.key, growNow)
                if (g <= 0.01f) { x += len; continue }
                canvas.save()
                canvas.translate(x, p.baseY)
                if (sway && p.swayPeriod > 0f) {
                    // 기울기 ±2° 이하, 주기 8초 이상, 이웃끼리 위상 다르게 (30번 6.2)
                    canvas.rotate(2f * sin(2f * PI.toFloat() * clock / p.swayPeriod + p.swayPhase))
                }
                canvas.scale(if (p.flip) -p.size * g else p.size * g, p.size * g)
                for (part in p.shape.parts) {
                    fill.color = color(p.colors, part.slot)
                    if (part.alpha < 1f || keep < 1f) fill.alpha = (part.alpha * keep * 255).toInt()
                    canvas.drawPath(part.path, fill)
                }
                canvas.restore()
                x += len
            }
        }
    }

    /** 능선 한 장 (D-085). 조각 폭 단위 u = H/10(이전 타일과 같은 x 축척), 높이 1 = heightRatio·H.
     *  @param seq 조각 순서(한 바퀴) · @param startUnits 시작 어긋남(단위) · @param offset 흐른 거리(px, 클수록 왼쪽) */
    private fun drawRidgeSeq(canvas: Canvas, lay: SceneLayout, seq: IntArray, heightRatio: Float, startUnits: Float,
                             offset: Float, c: Int, sh: Int) {
        val u = lay.H / 10f
        val cycle = (if (seq === LandscapeShapes.FAR_RIDGE_BACK_SEQ) backCycleUnits else frontCycleUnits) * u
        var x = -mod(offset + startUnits * u, cycle)
        var n = 0
        val segs = LandscapeShapes.farRidges
        while (x < width) {
            val seg = segs[seq[n % seq.size]]
            val wPx = seg.width * u
            if (x + wPx > 0f) {
                canvas.save()
                canvas.translate(x, lay.horizonY)
                canvas.scale(u, heightRatio * lay.H)
                fill.color = c; canvas.drawPath(seg.base, fill)
                fill.color = sh; canvas.drawPath(seg.shade, fill)
                canvas.restore()
            }
            x += wPx; n++
        }
    }
    private val backCycleUnits = LandscapeShapes.ridgeCycleUnits(LandscapeShapes.FAR_RIDGE_BACK_SEQ)
    private val frontCycleUnits = LandscapeShapes.ridgeCycleUnits(LandscapeShapes.FAR_RIDGE_FRONT_SEQ)

    private fun drawStream(canvas: Canvas, lay: SceneLayout, offset: Float) {
        val H = lay.H
        var x = mod(lay.streamX * lay.midLen - offset, lay.midLen)
        x -= lay.midLen * floor((x + lay.streamLen) / lay.midLen)
        val y0 = lay.treeBaseY - H * 0.016f
        val y1 = lay.treeBaseY - H * 0.002f
        val g = grown(K_STREAM, growNow)
        if (g <= 0.01f) return
        val fade = (g * 255).toInt()
        while (x < width) {
            fill.color = mix(cStream, cGround, 0.5f)
            fill.alpha = fade
            tmpRect.set(x - H * 0.01f, y0 - H * 0.003f, x + lay.streamLen + H * 0.01f, y1 + H * 0.002f)
            canvas.drawRoundRect(tmpRect, H * 0.012f, H * 0.012f, fill)
            fill.color = cStream
            fill.alpha = fade
            tmpRect.set(x, y0, x + lay.streamLen, y1)
            canvas.drawRoundRect(tmpRect, H * 0.008f, H * 0.008f, fill)
            // 물 위 가로 빛 띠 3개 — 반짝이지 않는다 (D-062)
            fill.color = cStreamLight
            fill.alpha = fade
            for (f in floatArrayOf(0.18f, 0.47f, 0.78f)) {
                val bx = x + lay.streamLen * f
                tmpRect.set(bx, y0 + H * 0.004f, bx + H * 0.05f, y0 + H * 0.0075f)
                canvas.drawRoundRect(tmpRect, H * 0.002f, H * 0.002f, fill)
            }
            x += lay.midLen
        }
    }

    /** 반딧불(밤에만 보임, 밝기 변화 없음)·민들레 홀씨 */
    private fun drawDrifters(canvas: Canvas, lay: SceneLayout, nearOff: Float, H: Float) {
        val w = width.toFloat()
        val dp = resources.displayMetrics.density
        if (darkW > 0.01f) {
            // 위치만 천천히 이동 — 반지름 0.02H 원을 0.005H/초로 (30번 6.2)
            val omega = 0.005f / 0.02f
            for (f in lay.fireflies) {
                var x = mod(f.xBand * lay.nearLen - nearOff, lay.nearLen) + f.radius * cos(omega * clock + f.phase)
                val y = f.baseY + f.radius * 0.5f * sin(omega * clock + f.phase)
                x -= lay.nearLen * floor((x + 20f * dp) / lay.nearLen)
                while (x - 20f * dp < w) {
                    fill.color = cGlow
                    val k = keepOutside(x, y, 12f * dp) * grown(f.key, growNow)
                    fill.alpha = (0.25f * darkW * k * 255).toInt()
                    canvas.drawCircle(x, y, 12f * dp, fill)
                    fill.alpha = (darkW * k * 255).toInt()
                    canvas.drawCircle(x, y, 3f * dp, fill)
                    x += lay.nearLen
                }
            }
        }
        // 홀씨: 오른쪽→왼쪽 0.02H/초 + 위아래 ±0.01H 완만한 곡선
        val fluffOff = flowTime * 0.02f * H
        val size = H * LandscapeShapes.FLUFF_H
        for (i in lay.fluffs.indices) {
            val f = lay.fluffs[i]
            val shape = LandscapeShapes.fluffs[i % LandscapeShapes.fluffs.size]
            var x = mod(f.xBand * lay.nearLen - fluffOff, lay.nearLen)
            x -= lay.nearLen * floor((x + size) / lay.nearLen)
            val y = f.baseY + f.radius * sin(2f * PI.toFloat() * clock / 10f + f.phase)
            while (x - size < w) {
                canvas.save()
                canvas.translate(x, y)
                canvas.scale(size, size)
                for (part in shape.parts) {
                    fill.color = cCloud
                    fill.alpha = (0.9f * part.alpha * keepOutside(x, y, size) * grown(f.key, growNow) * 255).toInt()
                    canvas.drawPath(part.path, fill)
                }
                canvas.restore()
                x += lay.nearLen
            }
        }
    }

    /** 주차 화면 글자 자리([clearZone]) 안이면 주차 톤으로 넘어가는 만큼 옅어진다. 1 = 그대로 */
    private fun keepOutside(x: Float, y: Float, r: Float): Float {
        val z = clearZone ?: return 1f
        if (clearW <= 0.001f) return 1f
        return if (x + r > z.left && x - r < z.right && y + r > z.top && y - r < z.bottom) 1f - clearW else 1f
    }

    private fun mod(a: Float, m: Float): Float { val r = a % m; return if (r < 0f) r + m else r }

    private fun recordStats(ns: Long) {
        statFrames++
        statDrawNs += ns
        if (ns > statMaxNs) statMaxNs = ns
        val nowMs = SystemClock.uptimeMillis()
        if (statSinceMs == 0L) statSinceMs = nowMs
        val span = nowMs - statSinceMs
        if (span >= 10_000) {
            Log.i(TAG, "fps=%.1f onDraw avg=%.2fms max=%.2fms mode=%s stopped=%s flow=%.2f dark=%.2f mem=%.2f stage=%d near=%d mid=%d".format(
                statFrames * 1000f / span, statDrawNs / 1e6f / statFrames, statMaxNs / 1e6f, mode, snapshot.stopped, flowW, darkW, memoryW,
                appliedState.stage, layout?.near?.size ?: 0, layout?.mid?.size ?: 0))
            statFrames = 0; statDrawNs = 0; statMaxNs = 0; statSinceMs = nowMs
        }
    }

    private companion object {
        const val TAG = "MBREN"

        /** 도착 연출 (30번 6.4): 요소 하나가 자라는 시간, 다음 요소까지 간격, 간격을 좁힐 때 전체 상한 */
        const val GROW_MS = 800L
        const val GROW_GAP_MS = 150L
        const val GROW_SPREAD_MS = 2_400L
        const val K_STREAM = 9_000
        const val K_MOON = 9_001
        val SLOTS = Slot.entries.size

        /** 정차 판정 후 흐름이 멈추는 시간(초) — 30번 6.2-B (D-064). 곡선은 사인 이징 인-아웃 */
        const val TODAY_WASH = 0.30f
        const val MINIMAL_FRAME_NS = 1_000_000_000L
        const val RIDGE_BACK_H = 0.10f
        const val RIDGE_BACK_START_UNITS = 14.8f   // 이전 타일 어긋남 0.37 × 40단위
        const val RIDGE_FRONT_H = 0.07f
        const val RIDGE_FRONT_START_UNITS = 3.6f   // 0.09 × 40
        const val FLOW_STOP_SEC = 6f

        /** 출발 판정 후 흐름이 다시 붙는 시간(초) — 30번 6.2-B (D-064) */
        const val FLOW_RESUME_SEC = 5f

        /**
         * 바퀴 회전 — D-063 시험 적용 후 사용자가 **유지**로 확정(D-066 시점, 4-A 전달). 끄면 정지 그림.
         * 각속도는 차종별 [CarShape.wheelDegPerSec] (cars.json)
         */
        const val WHEEL_SPIN = true
        const val FRAME_NS = 33_333_333L
        const val VSYNC_SLACK_NS = 4_000_000L
    }
}
