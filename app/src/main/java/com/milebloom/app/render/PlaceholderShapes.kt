package com.milebloom.app.render

import android.graphics.Path
import android.graphics.RectF

/**
 * 에셋 색 칸 (30번 4.1·4.6). 색은 코드가 칸 이름으로 덮어쓴다.
 * - GROUND: 접지 그림자 (코드가 그리는 납작한 타원, `groundShade`)
 * - SHADE / BASE / LIGHT: 그늘(왼쪽 아래) · 기본 · 빛(오른쪽 위, 면적 15% 이하) — D-062
 * - ACCENT_SHADE / ACCENT / ACCENT_CENTER: 꽃 그늘 · 꽃 · 꽃 가운데 (들판 강조색 a1·a2)
 */
enum class Slot { GROUND, SHADE, BASE, LIGHT, ACCENT_SHADE, ACCENT, ACCENT_CENTER }

class ShapePart(val slot: Slot, val path: Path, val alpha: Float = 1f)

/**
 * 한 에셋 모양. **단위 좌표** — 원점 = 밑단 중앙(땅에 서는 점), 위가 −y. 화면에서는 `크기(px) × 단위`로 늘린다.
 * @param height 원점에서 꼭대기까지의 높이(단위) — 근경이 차를 가리지 않게 밑단을 정할 때 쓴다
 */
class Shape(val parts: List<ShapePart>, val height: Float = 1f)

/**
 * 코드 도형 자리표시. **지금 앱은 [LandscapeShapes](4-D 에셋, D-069)를 쓰고 여기서는 M3 `treeSlim`만 쓴다** (⬜ 에셋, #48). 나머지는 비교용으로 남겨 뒀다.
 *
 * 처음에는 30번 4.2 중 아직 없는 것 전부를 대신했다.
 *
 * 그리는 법은 4-B 시안 렌더러 `assets/art/screens/journey_scene.js`를 그대로 옮겼다 — 시안·무드보드·앱이 한 그림이다 (D-062).
 * **빛은 오른쪽 위 하나.** 그늘 덩어리 위에 같은 덩어리를 오른쪽 위로 비켜 기본색으로 얹어 왼쪽 아래에 그늘을 남기고,
 * 오른쪽 위 가장자리에 작은 빛 면, 밑단 왼쪽으로 눕힌 접지 그림자를 둔다. 음영은 정지 그림이다 (빛이 돌지 않는다).
 * 그래서 이 도형들은 **좌우 반전하지 않는다** (빛 방향이 뒤집힌다).
 *
 * 4-D가 SVG를 주면 같은 단위 좌표·같은 칸 이름으로 `PathParser` 경로를 만들어 바꿔 끼우면 된다.
 */
object PlaceholderShapes {

    // ── 단위 1의 화면 크기 (H 비율) ──
    const val TREE_ROUND_H = 0.22f   // 나무 높이
    const val TREE_CONE_H = 0.253f   // journey_scene.js: 0.22H × 1.15
    const val TREE_SLIM_H = 0.24f
    const val BUSH_K = 0.03f         // 덤불 기준 반지름 k
    const val CLOUD_K = 0.06f        // 구름 기준 k (원점 = 구름 중심)
    const val GRASS_K = 0.04f        // 풀 무더기 높이 k (꽃도 같은 k)
    const val SPROUT_K = 0.03f
    const val PUDDLE_K = 0.008f      // 물웅덩이 세로 반지름 (원점 = 중심)
    const val FLUFF_H = 0.012f
    const val BIRD_K = 0.02f

    /** M1 둥근 활엽수 — 수관 4원 (그늘 → 비켜 얹은 기본) + 빛 원 2 + 줄기 + 접지 그림자 */
    val treeRound: Shape = run {
        val r = 0.32f
        val cy = -0.64f
        fun crown(dx: Float, dy: Float, m: Float) = circles(
            floatArrayOf(dx, cy + dy, r * m),
            floatArrayOf(-r * 0.55f + dx, cy + r * 0.3f + dy, r * 0.68f * m),
            floatArrayOf(r * 0.55f + dx, cy + r * 0.28f + dy, r * 0.7f * m),
            floatArrayOf(r * 0.1f + dx, cy - r * 0.45f + dy, r * 0.62f * m),
        )
        val trunk = poly(-0.028f, 0f, -0.018f, cy, 0.018f, cy, 0.028f, 0f)
        Shape(
            listOf(
                ShapePart(Slot.GROUND, oval(-r * 0.45f, 0f, r * 1.1f, r * 0.16f)),
                ShapePart(Slot.SHADE, trunk),
                ShapePart(Slot.SHADE, crown(0f, 0f, 1f)),
                ShapePart(Slot.BASE, crown(r * 0.1f, -r * 0.12f, 0.9f)),
                ShapePart(Slot.LIGHT, circles(
                    floatArrayOf(r * 0.42f, cy - r * 0.42f, r * 0.32f),
                    floatArrayOf(r * 0.82f, cy + r * 0.08f, r * 0.2f),
                )),
            ),
            height = -(cy - r * 0.45f - r * 0.62f),
        )
    }

    /** M2 뾰족한 침엽수 — 두 단, 단마다 왼쪽 반 그늘 + 오른쪽 가장자리 빛 */
    val treeCone: Shape = run {
        val parts = ArrayList<ShapePart>()
        parts += ShapePart(Slot.GROUND, oval(-0.14f, 0f, 0.3f, 0.04f))
        parts += ShapePart(Slot.SHADE, rect(-0.025f, -0.2f, 0.025f, 0f))
        // [꼭대기, 밑단, 반폭] — 아래 단부터 그린다
        for (t in arrayOf(floatArrayOf(0.62f, 0.15f, 0.30f), floatArrayOf(1.0f, 0.45f, 0.22f))) {
            val yt = -t[0]; val yb = -t[1]; val hw = t[2]
            parts += ShapePart(Slot.BASE, poly(0f, yt, hw, yb, -hw, yb))
            parts += ShapePart(Slot.SHADE, poly(0f, yt, 0f, yb, -hw, yb))
            parts += ShapePart(Slot.LIGHT, poly(0f, yt, hw, yb, hw * 0.72f, yb, 0f, yt + (yb - yt) * 0.22f))
        }
        Shape(parts, height = 1f)
    }

    /** M3 가는 나무(자작나무형) — 1차 ⬜ 에셋. 같은 빛 규칙으로 그린 자리표시 */
    val treeSlim: Shape = run {
        val canopy = oval(0f, -0.69f, 0.2f, 0.31f)
        Shape(
            listOf(
                ShapePart(Slot.GROUND, oval(-0.1f, 0f, 0.26f, 0.035f)),
                ShapePart(Slot.ACCENT, rect(-0.025f, -0.6f, 0.025f, 0f)),
                ShapePart(Slot.SHADE, canopy),
                ShapePart(Slot.BASE, oval(0.025f, -0.71f, 0.175f, 0.28f)),
                ShapePart(Slot.LIGHT, oval(0.09f, -0.84f, 0.06f, 0.09f)),
            ),
            height = 1f,
        )
    }

    /** M4 덤불 — 그늘 덩어리 → 오른쪽 위로 비켜 얹은 밝은 덩어리 → 빛 점. 밑단 일부는 길 띠 뒤로 숨는다 */
    val bush: Shape = Shape(
        listOf(
            ShapePart(Slot.GROUND, oval(-0.4f, 0f, 1.5f, 0.18f)),
            ShapePart(Slot.SHADE, union(oval(0f, -0.4f, 1.4f, 0.8f), oval(0.7f, -0.3f, 0.8f, 0.6f))),
            ShapePart(Slot.BASE, union(oval(0.12f, -0.52f, 1.2f, 0.66f), oval(0.8f, -0.4f, 0.62f, 0.46f))),
            ShapePart(Slot.LIGHT, oval(0.55f, -0.85f, 0.42f, 0.22f)),
        ),
        height = 1.2f,
    )

    /** F2 구름 — 아랫면·왼쪽 그늘 위에 같은 덩어리를 오른쪽 위로 비켜 얹는다. 원점 = 구름 중심 */
    val cloud: Shape = run {
        fun lobes(dx: Float, dy: Float, m: Float) = union(
            oval(dx, dy, 1.6f * m, 0.55f * m),
            circles(floatArrayOf(-0.5f + dx, -0.25f + dy, 0.5f * m), floatArrayOf(0.35f + dx, -0.35f + dy, 0.62f * m)),
        )
        Shape(listOf(ShapePart(Slot.SHADE, lobes(0f, 0f, 1f)), ShapePart(Slot.BASE, lobes(0.07f, -0.1f, 0.94f))))
    }

    /** N1~N4 풀 무더기 — 뒤 잎 = 그늘, 앞 잎 = 기본, 오른쪽 잎 = 빛 + 밑동 그림자 */
    private val tuft: List<ShapePart> = listOf(
        ShapePart(Slot.GROUND, oval(-0.12f, 0f, 0.5f, 0.07f), alpha = 0.8f),
        ShapePart(Slot.SHADE, Path().apply { moveTo(-0.4f, 0f); quadTo(-0.3f, -0.7f, -0.05f, -1f); quadTo(0f, -0.5f, 0.1f, 0f); close() }),
        ShapePart(Slot.BASE, Path().apply { moveTo(-0.15f, 0f); quadTo(-0.05f, -0.55f, 0.2f, -0.72f); quadTo(0.12f, -0.35f, 0.15f, 0f); close() }),
        ShapePart(Slot.LIGHT, Path().apply { moveTo(0f, 0f); quadTo(0.2f, -0.8f, 0.45f, -0.85f); quadTo(0.3f, -0.4f, 0.35f, 0f); close() }),
    )
    val grass: Shape = Shape(tuft, height = 1f)

    /** 풀 무더기 + 낮은 꽃 (꽃 높이 0.7k) */
    val flowerLow: Shape = flower(headY = -0.7f, headR = 0.2f)

    /** 풀 무더기 + 키 큰 꽃 (꽃 높이 1.4k) */
    val flowerTall: Shape = flower(headY = -1.4f, headR = 0.24f)

    /** N5 싹 — 줄기(그늘색) + 잎 둘 */
    val sprout: Shape = Shape(
        listOf(
            ShapePart(Slot.SHADE, rect(-0.067f, -1f, 0.067f, 0f)),
            ShapePart(Slot.BASE, Path().apply {
                moveTo(0f, -0.7f); quadTo(-0.6f, -1.2f, -0.7f, -0.6f); quadTo(-0.3f, -0.5f, 0f, -0.7f); close()
                moveTo(0f, -1f); quadTo(0.6f, -1.5f, 0.75f, -0.9f); quadTo(0.3f, -0.8f, 0f, -1f); close()
            }),
        ),
        height = 1.3f,
    )

    /** P1 물웅덩이 — 그림자 타원 + 물 + 가로 빛 띠(반짝이지 않는다). 원점 = 중심 */
    val puddle: Shape = Shape(
        listOf(
            ShapePart(Slot.SHADE, oval(0f, 0.25f, 5.25f, 1.125f)),
            ShapePart(Slot.BASE, oval(0f, 0f, 5f, 1f)),
            ShapePart(Slot.LIGHT, Path().apply { addRoundRect(RectF(0.625f, -0.375f, 3.125f, -0.0625f), 0.125f, 0.125f, Path.Direction.CW) }),
        ),
        height = 1f,
    )

    /** P3 앉은 새 실루엣. 날개 없음 */
    val bird: Shape = Shape(
        listOf(ShapePart(Slot.BASE, Path().apply {
            moveTo(-1f, 0f); quadTo(-0.6f, -1.1f, 0.2f, -0.9f); quadTo(0.75f, -1.5f, 1f, -1.05f)
            lineTo(0.55f, -0.7f); quadTo(0.4f, 0f, -1f, 0f); close()
        })),
        height = 1.5f,
    )

    // ── 만드는 도구 ──

    private fun flower(headY: Float, headR: Float): Shape {
        val parts = ArrayList(tuft)
        parts += ShapePart(Slot.BASE, rect(-0.025f, headY, 0.025f, 0f))
        parts += ShapePart(Slot.ACCENT_SHADE, oval(0f, headY, headR, headR))
        parts += ShapePart(Slot.ACCENT, oval(headR * 0.12f, headY - headR * 0.12f, headR * 0.82f, headR * 0.82f))
        parts += ShapePart(Slot.ACCENT_CENTER, oval(headR * 0.1f, headY - headR * 0.1f, headR * 0.32f, headR * 0.32f))
        return Shape(parts, height = -headY + headR)
    }

    /** 원 여러 개를 합친 경로. 각 원 = (cx, cy, r) */
    private fun circles(vararg c: FloatArray): Path {
        val p = Path()
        c.forEach { p.op(Path().apply { addCircle(it[0], it[1], it[2], Path.Direction.CW) }, Path.Op.UNION) }
        return p
    }

    private fun oval(cx: Float, cy: Float, rx: Float, ry: Float) =
        Path().apply { addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW) }

    private fun rect(l: Float, t: Float, r: Float, b: Float) = Path().apply { addRect(l, t, r, b, Path.Direction.CW) }

    private fun poly(vararg xy: Float) = Path().apply {
        moveTo(xy[0], xy[1])
        var i = 2
        while (i < xy.size) { lineTo(xy[i], xy[i + 1]); i += 2 }
        close()
    }

    private fun union(a: Path, b: Path) = Path(a).apply { op(b, Path.Op.UNION) }
}
