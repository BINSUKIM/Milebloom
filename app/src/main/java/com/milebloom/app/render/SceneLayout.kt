package com.milebloom.app.render

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/** 칠할 색의 종류. 실제 색은 그릴 때 팔레트에서 고른다 */
enum class Paint3 { TREE, TREE_BACK, TREE_BACK2, SLIM_TREE, BUSH, GRASS, FLOWER_A1, FLOWER_A2, SPROUT, PUDDLE, BIRD, CLOUD }

/**
 * 띠 위에 놓인 요소 하나.
 * @param xBand 띠 안 위치 0..1 (12번 13.3과 같은 의미)
 * @param baseY 원점의 화면 y (px) — 대개 밑단
 * @param size 단위 좌표 1의 화면 크기(px)
 * @param flip 좌우 반전. 음영이 있는 도형은 빛 방향이 뒤집히므로 쓰지 않는다 (D-062)
 * @param swayPeriod 흔들림 주기(초). 0이면 흔들리지 않는다
 */
class Placed(
    val shape: Shape,
    val colors: Paint3,
    val xBand: Float,
    val baseY: Float,
    val size: Float,
    val flip: Boolean = false,
    val swayPeriod: Float = 0f,
    val swayPhase: Float = 0f,
)

/** 움직이는 작은 점 (반딧불·홀씨). 위치만 천천히 바뀐다 */
class Drifter(val xBand: Float, val baseY: Float, val phase: Float, val radius: Float)

/**
 * 시드 고정 배치 (30번 4.4). 같은 [SceneState]와 같은 화면 크기면 늘 같은 풍경이다.
 * 화면 크기가 바뀌면 다시 만든다 (분할 화면 전환).
 * 배치 수치는 4-B 시안 렌더러 `assets/art/screens/journey_scene.js`와 맞췄다 (띠 길이 기준으로 환산).
 */
class SceneLayout(val state: SceneState, val w: Int, val h: Int, reserveTopPx: Float) {

    val H = h.toFloat()

    /** 세로형(분할 화면)에서는 능선을 0.04H 내려 숲이 차와 붙어 보이게 (30번 3.3) */
    val horizonY = H * if (w < h) 0.60f else 0.56f
    val treeBaseY = H * 0.64f
    val roadTop = H * 0.64f
    val roadBottom = H * 0.74f
    val carGroundY = H * 0.72f

    // 띠 길이 (30번 3.2)
    val farLen = 4f * H
    val midLen = 3f * H
    val nearLen = 2f * H
    val cloudLen = 4f * H

    val clouds = ArrayList<Placed>()
    val mid = ArrayList<Placed>()          // 뒤 → 앞 순서로 정렬됨
    val streamX: Float                     // 개울 시작 (띠 비율), 없으면 -1
    val streamLen = 0.9f * H
    val near = ArrayList<Placed>()         // y 작은 것(뒤) → 큰 것(앞)
    val fireflies = ArrayList<Drifter>()
    val fluffs = ArrayList<Drifter>()
    val stars = ArrayList<FloatArray>()    // x, y (px)

    init {
        val seed = state.seed
        Random(seed xor 0xC1).let { r ->
            repeat(5) { i ->
                clouds += Placed(
                    LandscapeShapes.clouds.random(r), Paint3.CLOUD,
                    xBand = (i + r.nextFloat() * 0.6f) / 5f,
                    baseY = H * (0.08f + r.nextFloat() * 0.2f),
                    size = H * LandscapeShapes.CLOUD_K * (0.7f + r.nextFloat() * 0.6f),
                )
            }
        }

        layoutMid(Random(seed xor 0xA2))
        streamX = if (state.stream) Random(seed xor 0x55).nextFloat() else -1f
        layoutNear(Random(seed xor 0xB4), reserveTopPx)

        Random(seed xor 0x77).let { r ->
            repeat(18) { stars += floatArrayOf(r.nextFloat() * w, H * (0.03f + r.nextFloat() * 0.42f)) }
        }
    }

    private fun layoutMid(r: Random) {
        // 단계별 나무 수 (30번 2.5 밀도 표, 띠 3H 기준). 앞줄·뒷줄·셋째 줄
        val rows: IntArray = when (state.stage) {
            1, 2 -> intArrayOf()
            3 -> intArrayOf(1)
            4 -> intArrayOf(4)
            5 -> intArrayOf(5, 3)
            6 -> intArrayOf(8, 6)
            else -> intArrayOf(8, 7, 5)
        }
        val coneShare = if (state.season == Season.WINTER) 0.6f else 0.35f
        // 뒷줄부터: 셋째 줄(가장 뒤) → 앞줄. 줄마다 0.025H 위로, 크기 −20%, far 쪽으로 35%씩 혼합 (journey_scene.js)
        for (row in rows.indices.reversed()) {
            val n = rows[row]
            val colors = when (row) { 0 -> Paint3.TREE; 1 -> Paint3.TREE_BACK; else -> Paint3.TREE_BACK2 }
            val y = treeBaseY - H * 0.025f * row
            val depth = 1f - 0.2f * row
            for (i in 0 until n) {
                val pick = r.nextFloat()
                val (shape, base) = when {
                    pick < coneShare -> LandscapeShapes.treeCones.random(r) to LandscapeShapes.TREE_CONE_H
                    pick < coneShare + 0.1f -> LandscapeShapes.treeSlim to LandscapeShapes.TREE_SLIM_H
                    else -> LandscapeShapes.treeRounds.random(r) to LandscapeShapes.TREE_ROUND_H
                }
                val s = (0.6f + r.nextFloat() * 0.6f) * depth * if (state.stage == 3) 0.5f else 1f
                mid += Placed(
                    shape, if (shape === LandscapeShapes.treeSlim && row == 0) Paint3.SLIM_TREE else colors,
                    xBand = (i + 0.15f + r.nextFloat() * 0.7f) / n + row * 0.13f,
                    baseY = y, size = H * base * s,
                )
            }
        }
        // 덤불 — 2단계의 주인공, 그 뒤로도 숲 가장자리에 남는다
        val bushes = when (state.stage) { 1 -> 0; 2, 3 -> 3; else -> 4 }
        for (i in 0 until bushes) {
            mid += Placed(
                LandscapeShapes.bushes.random(r), Paint3.BUSH,
                xBand = (i + r.nextFloat()) / max(bushes, 1), baseY = treeBaseY,
                size = H * LandscapeShapes.BUSH_K * (0.7f + r.nextFloat() * 0.6f),
            )
        }
    }

    private fun layoutNear(r: Random, reserveTopPx: Float) {
        val month = state.month
        // 같은 계절 세 달 변주 (30번 2.4): 꽃 크기 배율, 풀 몇 무더기마다 꽃 하나, 키 큰 꽃 비율
        val order = (month % 3)  // 3·6·9·12 → 0(첫 달), 4·7·10·1 → 1, 5·8·11·2 → 2
        val sizeMul = floatArrayOf(0.85f, 1f, 1.1f)[order]
        val flowerEvery = intArrayOf(4, 2, 3)[order]
        val tallShare = floatArrayOf(0.1f, 0.25f, 0.4f)[order]

        val density = (state.meadowGrowth / 25f).coerceIn(0.15f, 1f)
        // journey_scene.js: 화면 폭 w에 w/H×60 무더기 → 띠 2H에 120
        val tuftN = (120 * density).roundToInt()
        val sproutN = (when (state.stage) { 1 -> 1; 2, 3 -> 10; else -> 0 }) + (10 * (1f - density)).roundToInt()

        val meadow = LandscapeShapes.meadow(state.season)
        val top = roadBottom + H * 0.015f
        val bottom = H * 0.985f
        // 버튼 예약 영역(오른쪽 아래 200×200 dp)에 새·반딧불·키 큰 꽃이 들어가지 않게 — 띠가 흐르므로
        // x가 아니라 y로 막는다: 그 요소들은 예약 영역 윗변보다 위에만 둔다 (30번 3.3)
        val restrictedBottom = minOf(reserveTopPx - H * 0.005f, bottom)

        fun sway(r: Random) = 8f + r.nextFloat() * 5f to r.nextFloat() * 2f * PI.toFloat()
        // 근경은 차를 가리지 않는다 — 꼭대기가 차 바퀴 밑(0.72H)보다 위로 올라오지 않게 밑단을 내린다.
        // 그러면 길 띠(0.74H) 위로는 많아야 0.02H만 올라온다 (30번 3.2: 0.04H 이하)
        fun baseY(extent: Float, hi: Float): Float {
            val lo = max(top, carGroundY + extent * 1.05f)
            return lo + r.nextFloat() * max(hi - lo, 0f)
        }

        repeat(tuftN) { i ->
            val (p, ph) = sway(r)
            val flower = i % flowerEvery == 0
            val tall = flower && r.nextFloat() < tallShare
            val size = H * LandscapeShapes.GRASS_K * (0.6f + r.nextFloat() * 0.6f) * (if (flower) sizeMul else 1f)
            val shape = when {
                !flower -> meadow.grass
                tall -> meadow.flowerTall
                else -> meadow.flowerLow
            }
            val colors = when {
                !flower -> Paint3.GRASS
                r.nextFloat() < 0.7f -> Paint3.FLOWER_A1
                else -> Paint3.FLOWER_A2
            }
            near += Placed(shape, colors, r.nextFloat(), baseY(size * shape.height, if (tall) restrictedBottom else bottom), size,
                swayPeriod = p, swayPhase = ph)
        }
        repeat(sproutN) {
            val (p, ph) = sway(r)
            val size = H * LandscapeShapes.SPROUT_K
            val shape = LandscapeShapes.sprouts.random(r)
            near += Placed(shape, Paint3.SPROUT, r.nextFloat(), baseY(size * shape.height, bottom), size,
                swayPeriod = p, swayPhase = ph)
        }
        repeat(state.puddles) {
            // 원점 = 웅덩이 밑단
            near += Placed(LandscapeShapes.puddles.random(r), Paint3.PUDDLE, r.nextFloat(), roadBottom + H * (0.04f + r.nextFloat() * 0.18f),
                H * LandscapeShapes.PUDDLE_K * (0.8f + r.nextFloat() * 0.5f))
        }
        repeat(state.birds.coerceAtMost(3)) {
            val size = H * LandscapeShapes.BIRD_K
            val shape = LandscapeShapes.birds.random(r)
            near += Placed(shape, Paint3.BIRD, r.nextFloat(), baseY(size * shape.height, restrictedBottom), size, flip = r.nextBoolean())
        }
        near.sortBy { it.baseY }

        repeat(state.fireflies.coerceAtMost(6)) {
            fireflies += Drifter(r.nextFloat(), roadBottom + r.nextFloat() * max(restrictedBottom - roadBottom - H * 0.02f, 1f),
                r.nextFloat() * 2f * PI.toFloat(), H * 0.02f)
        }
        // 홀씨: 공중, 차 위쪽 하늘 (journey_scene.js: 길 윗변보다 0.05~0.20H 위)
        repeat(state.seedFluff.coerceAtMost(5)) {
            fluffs += Drifter(r.nextFloat(), roadTop - H * (0.05f + r.nextFloat() * 0.15f), r.nextFloat() * 2f * PI.toFloat(), H * 0.01f)
        }
    }
}
