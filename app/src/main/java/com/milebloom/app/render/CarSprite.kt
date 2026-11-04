package com.milebloom.app.render

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser

/**
 * 화면 속 차 한 대 (30번 4.5, D-058). 차종 SVG의 경로를 cm 좌표로 한 번만 바꿔 두고, 그릴 때는 축척만 곱한다.
 *
 * - **움직이지 않는다.** 들썩임·바퀴 회전 없음 — 휠 스포크는 정지 상태로 그린다 (D-052)
 * - 헤드램프: `glow` 칸이 켜지고 램프 앞에 불빛 부채꼴(1.6 m). 테일램프는 흰색 쪽으로 20% 밝아진다 (2.3-B)
 * - 창문: 유리 조각이 열린 비율만큼 아래로 내려가고, 비켜난 자리는 '열린 창'색 (`carGlass`:`skyLow` = 4:6).
 *   유리 반사(`glassLight`)는 제 유리 조각과 함께 내려간다
 * - 바퀴(시험 적용, D-063): 휠 면·스포크·허브 묶음이 바퀴 중심으로 돈다. 각도는 [LandscapeView]가 흐름 배율과 함께 넘긴다.
 *   타이어 원은 돌지 않는다. 대비는 팔레트 `carSpoke`가 낮춰 둔다 (30번 6.2-A)
 */
class CarSprite(val shape: CarShape) {

    private class Built(val part: CarPart, val path: Path, val bounds: RectF)

    private val built: List<Built>
    private val glassPaths = HashMap<Int, Built>()
    /** 바퀴 중심 cm (x0, y0, x1, y1…) */
    private val wheelCenters: FloatArray
    private val bodyClip: Path
    private val lampFront: RectF
    private val beam = Path()
    private val shadow = RectF(-shape.shadowRx, -shape.shadowRy, shape.shadowRx, shape.shadowRy)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var beamShaderColor = 0

    init {
        // SVG: <g transform="scale(s) translate(tx ty)"> → cm = s·(p + t)
        val m = Matrix().apply { setTranslate(shape.tx, shape.ty); postScale(shape.scale, shape.scale) }
        fun parse(d: String) = PathParser.createPathFromPathData(d).apply { transform(m) }
        bodyClip = parse(shape.bodyClip)
        built = shape.parts.map { p ->
            val path = parse(p.d)
            Built(p, path, RectF().also { path.computeBounds(it, true) })
        }
        built.filter { it.part.slot == CarSlot.GLASS }.forEach { glassPaths[it.part.glassIndex] = it }
        wheelCenters = shape.wheelCenters.copyOf().also { m.mapPoints(it) }
        lampFront = built.first { it.part.slot == CarSlot.GLOW }.bounds
        // 불빛 부채꼴: 램프 앞끝에서 1.6 m 앞으로, 아래로 퍼지며 지면에 닿는다
        val lx = lampFront.right - 2f
        val ly = lampFront.centerY()
        beam.apply {
            moveTo(lx, ly - 3f)
            lineTo(lx + 160f, ly + (0f - ly) * 0.25f - 6f)
            lineTo(lx + 160f, -1f)
            lineTo(lx, ly + 4f)
            close()
        }
    }

    /**
     * @param pxPerCm 화면 축척 — 1 m = 0.062H 이므로 `0.00062 × H`
     * @param lamp 헤드램프 0..1 (1초 보간된 값)
     * @param glassOpen 유리 조각별 열린 비율 0..1 (조각 순서 = [CarPart.glassIndex])
     * @param nightness 0 낮 ~ 1 밤 — 불빛 부채꼴 불투명도 0.22 → 0.55
     * @param wheelDeg 바퀴 회전각(도, 시계 방향). 돌리지 않으면 0
     */
    fun draw(
        canvas: Canvas, cx: Float, groundY: Float, pxPerCm: Float,
        car: CarColors, glow: Int, skyLow: Int,
        lamp: Float, glassOpen: FloatArray, nightness: Float, wheelDeg: Float = 0f,
    ) {
        canvas.save()
        canvas.translate(cx, groundY)
        canvas.scale(pxPerCm, pxPerCm)

        fill.color = car.shadow
        fill.alpha = (0.35f * 255).toInt()
        canvas.drawOval(shadow, fill)

        val tail = if (shape.model == CarModel.TUCSON) car.tailAmber else car.tailRed
        val openColor = ColorUtils.blendARGB(car.glass, skyLow, 0.6f)

        for (b in built) {
            val p = b.part
            val color = when (p.slot) {
                CarSlot.BODY -> car.body
                CarSlot.SHADE -> car.shade
                CarSlot.LIGHT -> car.highlight
                CarSlot.TRIM -> car.trim
                CarSlot.GLASS -> car.glass
                CarSlot.GLASS_LIGHT -> car.glassLight
                CarSlot.TIRE -> car.tire
                CarSlot.WHEEL -> car.wheel
                CarSlot.SPOKE -> car.spoke
                CarSlot.TAIL -> ColorUtils.blendARGB(tail, 0xFFFFFFFF.toInt(), 0.2f * lamp)
                CarSlot.GLOW -> ColorUtils.blendARGB(car.shade, glow, lamp)
            }
            fill.color = color
            fill.alpha = (p.alpha * 255).toInt()
            val open = if (p.glassIndex >= 0) glassOpen.getOrElse(p.glassIndex) { 0f } else 0f
            when {
                p.wheelIndex >= 0 && wheelDeg != 0f -> {
                    val i = p.wheelIndex * 2
                    canvas.save()
                    canvas.rotate(wheelDeg, wheelCenters[i], wheelCenters[i + 1])
                    canvas.drawPath(b.path, fill)
                    canvas.restore()
                }
                p.clipToBody -> {
                    canvas.save(); canvas.clipPath(bodyClip); canvas.drawPath(b.path, fill); canvas.restore()
                }
                open > 0.001f && p.slot == CarSlot.GLASS -> {
                    canvas.save()
                    canvas.clipPath(b.path)
                    fill.color = openColor
                    canvas.drawPath(b.path, fill)
                    canvas.translate(0f, b.bounds.height() * open)
                    fill.color = color
                    canvas.drawPath(b.path, fill)
                    canvas.restore()
                }
                open > 0.001f -> {
                    // 유리 반사: 제 유리 조각 안에서 같은 만큼 내려간다
                    val g = glassPaths[p.glassIndex] ?: continue
                    canvas.save()
                    canvas.clipPath(g.path)
                    canvas.translate(0f, g.bounds.height() * open)
                    canvas.drawPath(b.path, fill)
                    canvas.restore()
                }
                else -> canvas.drawPath(b.path, fill)
            }
        }

        if (lamp > 0.001f) {
            val a = (0.22f + (0.55f - 0.22f) * nightness) * lamp
            if (beamShaderColor != glow) {
                beamShaderColor = glow
                beamPaint.shader = LinearGradient(
                    lampFront.right, 0f, lampFront.right + 160f, 0f,
                    glow, ColorUtils.setAlphaComponent(glow, 0), Shader.TileMode.CLAMP,
                )
            }
            beamPaint.alpha = (a * 255).toInt()
            canvas.drawPath(beam, beamPaint)
        }
        canvas.restore()
    }

    companion object {
        /** 1 m = 0.062H (30번 3.3) */
        const val PX_PER_CM_PER_H = 0.00062f
    }
}

/** 톤 혼합이 끝난 차 색 한 벌 */
class CarColors {
    var body = 0; var shade = 0; var trim = 0; var glass = 0; var tire = 0
    var wheel = 0; var tailRed = 0; var tailAmber = 0; var shadow = 0
    var highlight = 0; var glassLight = 0; var spoke = 0
}
