package com.milebloom.app.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/**
 * 주차 화면의 상호작용 움직임 (D-088). 차량용이라 **짧고 차분하게** — 150~300 ms, 튕김·과장·반복 없음.
 *
 * - 움직임은 주차(P) 화면에만 있다. 주행(D) 중에는 새 움직임을 만들지 않는다(음성 버튼은 상태 표시만)
 * - 기어가 P에서 벗어나면 기다리지 않고 [snap]으로 끊고 숨긴다 (절대 규칙 2·3, D-081)
 * - [allowed]가 false(Lv3)이거나 시스템 '애니메이션 삭제'(animator 길이 배율 0)면 움직이지 않고 바로 끝 상태로 둔다
 */
object Motion {
    const val PRESS_MS = 100L
    const val RELEASE_MS = 160L
    const val ENTER_MS = 240L
    const val EXIT_MS = 160L
    const val PAGE_MS = 200L
    const val ITEM_MS = 220L
    const val STAGGER_MS = 30L
    /** 이보다 뒤의 항목은 함께 나타난다 — 긴 목록에서 마지막 항목이 늦게 뜨지 않게 */
    const val STAGGER_MAX = 6
    const val PRESS_SCALE = 0.97f

    /** 감속만 있는 곡선(material standard decelerate) — 넘치지 않는다 */
    val EASE_OUT = PathInterpolator(0f, 0f, 0.2f, 1f)
    val EASE_IN = PathInterpolator(0.4f, 0f, 1f, 1f)

    /** 화면이 정한다: 표시 수준 Lv1일 때만 true (Lv3에서는 움직임 없음) */
    @Volatile var allowed: Boolean = true

    /** 지금 움직여도 되는가 — Lv1이고 시스템 설정이 애니메이션을 끄지 않았다 */
    val on: Boolean get() = allowed && ValueAnimator.areAnimatorsEnabled()

    /** 진행 중인 움직임을 끊고 끝 상태(완전히 보임, 제자리)로 되돌린다. 하위 뷰까지 */
    fun snap(v: View) {
        v.animate().cancel()
        v.alpha = 1f; v.translationX = 0f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f
        if (v is ViewGroup) for (i in 0 until v.childCount) snap(v.getChildAt(i))
    }

    /** 나타나기: 투명 → 보임, [dy]·[dx]만큼 비켜 있다가 제자리로 */
    fun enter(v: View, dx: Float = 0f, dy: Float = 0f, delay: Long = 0L, duration: Long = ENTER_MS) {
        v.animate().cancel()
        if (!on) { v.alpha = 1f; v.translationX = 0f; v.translationY = 0f; return }
        v.alpha = 0f; v.translationX = dx; v.translationY = dy
        v.animate().alpha(1f).translationX(0f).translationY(0f)
            .setStartDelay(delay).setDuration(duration).setInterpolator(EASE_OUT).start()
    }

    /** 사라지기. 끝나면 [then] (움직이지 않을 때는 바로). 기어 전환에는 쓰지 않는다 — 그때는 [snap] + GONE */
    fun exit(v: View, dx: Float = 0f, dy: Float = 0f, then: () -> Unit) {
        v.animate().cancel()
        if (!on) { then(); return }
        v.animate().alpha(0f).translationX(dx).translationY(dy)
            .setStartDelay(0L).setDuration(EXIT_MS).setInterpolator(EASE_IN)
            .withEndAction { v.alpha = 1f; v.translationX = 0f; v.translationY = 0f; then() }.start()
    }

    /** [group]의 자식을 위에서부터 차례로 띄운다 (목록·카드 등장) */
    fun stagger(group: ViewGroup, dy: Float, from: Int = 0) {
        for (i in from until group.childCount) {
            val k = minOf(i - from, STAGGER_MAX)
            enter(group.getChildAt(i), dy = dy, delay = k * STAGGER_MS, duration = ITEM_MS)
        }
    }

    /**
     * 누름 반응: 눌린 동안 [PRESS_SCALE]로 살짝 작아졌다 돌아온다. 리플(색)과 함께 쓴다.
     * StateListAnimator는 시스템 animator 배율을 따르므로 '애니메이션 삭제'에서는 즉시 바뀐다
     */
    fun pressable(v: View) {
        fun to(scale: Float, ms: Long) = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(v, View.SCALE_X, scale),
                ObjectAnimator.ofFloat(v, View.SCALE_Y, scale),
            )
            duration = ms
            interpolator = EASE_OUT
        }
        v.stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled), to(PRESS_SCALE, PRESS_MS))
            addState(intArrayOf(), to(1f, RELEASE_MS))
        }
    }
}
