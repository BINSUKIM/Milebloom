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
    /** 같은 시드에서 늘 같은 요소를 가리키는 번호. 도착 연출이 '새로 생긴 것'을 찾는 데 쓴다 */
    val key: Int,
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
class Drifter(val key: Int, val xBand: Float, val baseY: Float, val phase: Float, val radius: Float)

/**
 * 시드 고정 배치 (30번 4.4). 같은 [SceneState]와 같은 화면 크기면 늘 같은 풍경이다.
 * 화면 크기가 바뀌면 다시 만든다 (분할 화면 전환).
 *
 * **쌓이는 배치 (D-087)**: 종류마다 최대 개수만큼 늘 같은 순서로 만들어 두고 앞에서부터 보여 준다.
 * 그래서 단계·밀도가 올라도 이미 있던 나무·풀은 제자리에 있고, 새로 보이는 것만 늘어난다 — 도착 연출이 그것만 키운다.
 * 키([Placed.key])가 같으면 같은 요소다.
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
                    K_CLOUD + i, LandscapeShapes.clouds.random(r), Paint3.CLOUD,
                    xBand = (i + r.nextFloat() * 0.6f) / 5f,
                    baseY = H * (0.08f + r.nextFloat() * 0.2f),
                    size = H * LandscapeShapes.CLOUD_K * (0.7f + r.nextFloat() * 0.6f),
                )
            }
        }

        layoutMid(seed)
        streamX = if (state.stream) Random(seed xor 0x55).nextFloat() else -1f
        layoutNear(seed, reserveTopPx)

        Random(seed xor 0x77).let { r ->
            repeat(18) { stars += floatArrayOf(r.nextFloat() * w, H * (0.03f + r.nextFloat() * 0.42f)) }
        }
    }

    private fun layoutMid(seed: Long) {
        // 단계별 나무 수 (30번 2.5 밀도 표, 띠 3H 기준). 앞줄·뒷줄·셋째 줄
        val rows: IntArray = treesPerRow(state.stage)
        val coneShare = if (state.season == Season.WINTER) 0.6f else 0.35f
        // 뒷줄부터: 셋째 줄(가장 뒤) → 앞줄. 줄마다 0.025H 위로, 크기 −20%, far 쪽으로 35%씩 혼합 (journey_scene.js)
        for (row in MAX_TREES.indices.reversed()) {
            val nMax = MAX_TREES[row]
            val shown = rows.getOrElse(row) { 0 }
            val r = Random(seed xor (0xA2L + row * 0x101L))
            val colors = when (row) { 0 -> Paint3.TREE; 1 -> Paint3.TREE_BACK; else -> Paint3.TREE_BACK2 }
            val y = treeBaseY - H * 0.025f * row
            val depth = 1f - 0.2f * row
            // 칸마다 난수를 늘 같은 개수만큼 뽑는다 — 보이는 수가 달라도 각 칸의 나무는 같다
            val slots = Array(nMax) {
                val pick = r.nextFloat()
                val cone = LandscapeShapes.treeCones.random(r)
                val round = LandscapeShapes.treeRounds.random(r)
                Triple(pick, cone to round, r.nextFloat() to r.nextFloat())
            }
            val order = spreadOrder(nMax)
            for (k in 0 until shown) {
                val i = order[k]
                val (pick, shapes, rnd) = slots[i]
                val (shape, base) = when {
                    pick < coneShare -> shapes.first to LandscapeShapes.TREE_CONE_H
                    pick < coneShare + 0.1f -> LandscapeShapes.treeSlim to LandscapeShapes.TREE_SLIM_H
                    else -> shapes.second to LandscapeShapes.TREE_ROUND_H
                }
                // 3단계의 한 그루는 어린 나무(절반 크기). 4단계가 되면 같은 나무가 자란다
                val s = (0.6f + rnd.first * 0.6f) * depth * if (state.stage == 3) 0.5f else 1f
                mid += Placed(
                    K_TREE + row * 100 + i, shape, if (shape === LandscapeShapes.treeSlim && row == 0) Paint3.SLIM_TREE else colors,
                    xBand = (i + 0.15f + rnd.second * 0.7f) / nMax + row * 0.13f,
                    baseY = y, size = H * base * s,
                )
            }
        }
        // 덤불 — 2단계의 주인공, 그 뒤로도 숲 가장자리에 남는다
        val bushes = when (state.stage) { 1 -> 0; 2, 3 -> 3; else -> MAX_BUSHES }
        val rb = Random(seed xor 0xA3L)
        for (i in 0 until MAX_BUSHES) {
            val shape = LandscapeShapes.bushes.random(rb)
            val xb = (i + rb.nextFloat()) / MAX_BUSHES
            val size = H * LandscapeShapes.BUSH_K * (0.7f + rb.nextFloat() * 0.6f)
            if (i < bushes) mid += Placed(K_BUSH + i, shape, Paint3.BUSH, xBand = xb, baseY = treeBaseY, size = size)
        }
    }

    private fun layoutNear(seed: Long, reserveTopPx: Float) {
        val month = state.month
        // 같은 계절 세 달 변주 (30번 2.4): 꽃 크기 배율, 풀 몇 무더기마다 꽃 하나, 키 큰 꽃 비율
        val order = (month % 3)  // 3·6·9·12 → 0(첫 달), 4·7·10·1 → 1, 5·8·11·2 → 2
        val sizeMul = floatArrayOf(0.85f, 1f, 1.1f)[order]
        val flowerEvery = intArrayOf(4, 2, 3)[order]
        val tallShare = floatArrayOf(0.1f, 0.25f, 0.4f)[order]

        val density = meadowDensity(state.meadowGrowth)
        // journey_scene.js: 화면 폭 w에 w/H×60 무더기 → 띠 2H에 120
        val tuftN = (MAX_TUFTS * density).roundToInt()
        val sproutN = sproutsOf(state.stage, density)

        val meadow = LandscapeShapes.meadow(state.season)
        val top = roadBottom + H * 0.015f
        val bottom = H * 0.985f
        // 버튼 예약 영역(오른쪽 아래 200×200 dp)에 새·반딧불·키 큰 꽃이 들어가지 않게 — 띠가 흐르므로
        // x가 아니라 y로 막는다: 그 요소들은 예약 영역 윗변보다 위에만 둔다 (30번 3.3)
        val restrictedBottom = minOf(reserveTopPx - H * 0.005f, bottom)

        fun sway(r: Random) = 8f + r.nextFloat() * 5f to r.nextFloat() * 2f * PI.toFloat()
        // 근경은 차를 가리지 않는다 — 꼭대기가 차 바퀴 밑(0.72H)보다 위로 올라오지 않게 밑단을 내린다.
        // 그러면 길 띠(0.74H) 위로는 많아야 0.02H만 올라온다 (30번 3.2: 0.04H 이하)
        fun baseY(r: Random, extent: Float, hi: Float): Float {
            val lo = max(top, carGroundY + extent * 1.05f)
            return lo + r.nextFloat() * max(hi - lo, 0f)
        }

        // 종류마다 최대 개수만큼 늘 같은 난수 순서로 만들고, 앞에서부터 보이는 만큼만 넣는다 (쌓이는 배치)
        Random(seed xor 0xB4L).let { r ->
            for (i in 0 until MAX_TUFTS) {
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
                val xb = r.nextFloat()
                val by = baseY(r, size * shape.height, if (tall) restrictedBottom else bottom)
                if (i < tuftN) near += Placed(K_TUFT + i, shape, colors, xb, by, size, swayPeriod = p, swayPhase = ph)
            }
        }
        Random(seed xor 0xB5L).let { r ->
            for (i in 0 until MAX_SPROUTS) {
                val (p, ph) = sway(r)
                val size = H * LandscapeShapes.SPROUT_K
                val shape = LandscapeShapes.sprouts.random(r)
                val xb = r.nextFloat()
                val by = baseY(r, size * shape.height, bottom)
                if (i < sproutN) near += Placed(K_SPROUT + i, shape, Paint3.SPROUT, xb, by, size, swayPeriod = p, swayPhase = ph)
            }
        }
        Random(seed xor 0xB6L).let { r ->
            for (i in 0 until MAX_PUDDLES) {
                // 원점 = 웅덩이 밑단
                val shape = LandscapeShapes.puddles.random(r)
                val xb = r.nextFloat()
                val by = roadBottom + H * (0.04f + r.nextFloat() * 0.18f)
                val size = H * LandscapeShapes.PUDDLE_K * (0.8f + r.nextFloat() * 0.5f)
                if (i < state.puddles) near += Placed(K_PUDDLE + i, shape, Paint3.PUDDLE, xb, by, size)
            }
        }
        Random(seed xor 0xB7L).let { r ->
            for (i in 0 until MAX_BIRDS) {
                val size = H * LandscapeShapes.BIRD_K
                val shape = LandscapeShapes.birds.random(r)
                val xb = r.nextFloat()
                val by = baseY(r, size * shape.height, restrictedBottom)
                val flip = r.nextBoolean()
                if (i < state.birds) near += Placed(K_BIRD + i, shape, Paint3.BIRD, xb, by, size, flip = flip)
            }
        }
        near.sortBy { it.baseY }

        Random(seed xor 0xB8L).let { r ->
            for (i in 0 until MAX_FIREFLIES) {
                val d = Drifter(K_FIREFLY + i, r.nextFloat(), roadBottom + r.nextFloat() * max(restrictedBottom - roadBottom - H * 0.02f, 1f),
                    r.nextFloat() * 2f * PI.toFloat(), H * 0.02f)
                if (i < state.fireflies) fireflies += d
            }
        }
        // 홀씨: 공중, 차 위쪽 하늘 (journey_scene.js: 길 윗변보다 0.05~0.20H 위)
        Random(seed xor 0xB9L).let { r ->
            for (i in 0 until MAX_FLUFF) {
                val d = Drifter(K_FLUFF + i, r.nextFloat(), roadTop - H * (0.05f + r.nextFloat() * 0.15f), r.nextFloat() * 2f * PI.toFloat(), H * 0.01f)
                if (i < state.seedFluff) fluffs += d
            }
        }
    }

    companion object {
        /** 줄별 최대 나무 수 (7단계 값) */
        val MAX_TREES = intArrayOf(8, 7, 5)
        const val MAX_BUSHES = 4
        const val MAX_TUFTS = 120
        const val MAX_SPROUTS = 20
        const val MAX_PUDDLES = 4
        const val MAX_BIRDS = 3
        const val MAX_FIREFLIES = 6
        const val MAX_FLUFF = 5

        const val K_CLOUD = 100
        const val K_TREE = 1000
        const val K_BUSH = 2000
        const val K_TUFT = 3000
        const val K_SPROUT = 4000
        const val K_PUDDLE = 5000
        const val K_BIRD = 6000
        const val K_FIREFLY = 7000
        const val K_FLUFF = 8000

        /** 단계별 줄마다 나무 수 (30번 2.5) */
        fun treesPerRow(stage: Int): IntArray = when (stage) {
            1, 2 -> intArrayOf()
            3 -> intArrayOf(1)
            4 -> intArrayOf(4)
            5 -> intArrayOf(5, 3)
            6 -> intArrayOf(8, 6)
            else -> intArrayOf(8, 7, 5)
        }

        /** 들판 밀도 — 이번 달 growth 0..25, 하한 0.15 (새달 첫날에도 새싹이 돋아 있다, 12번 4.3) */
        fun meadowDensity(meadowGrowth: Float) = (meadowGrowth / 25f).coerceIn(0.15f, 1f)

        /** 싹 수 — 어린 숲에 많고, 들판이 차면 풀에 자리를 내준다 */
        fun sproutsOf(stage: Int, density: Float) =
            (when (stage) { 1 -> 1; 2, 3 -> 10; else -> 0 }) + (10 * (1f - density)).roundToInt()

        /**
         * 칸 0..n−1을 고르게 퍼지는 순서로 (0, n/2, n/4, 3n/4 …). 몇 그루만 보여도 띠 전체에 흩어진다.
         * 앞쪽 k개가 늘 같으므로 수가 늘어도 이미 있던 칸은 그대로다.
         */
        fun spreadOrder(n: Int): IntArray {
            if (n <= 0) return IntArray(0)
            val out = ArrayList<Int>(n)
            val used = BooleanArray(n)
            var step = 1
            while (out.size < n) {
                for (j in 0 until step) {
                    val i = ((2 * j) * n / (2 * step)).coerceIn(0, n - 1)
                    if (!used[i]) { used[i] = true; out += i }
                }
                step *= 2
                if (step > 2 * n) for (i in 0 until n) if (!used[i]) { used[i] = true; out += i }
            }
            return out.toIntArray()
        }
    }
}
