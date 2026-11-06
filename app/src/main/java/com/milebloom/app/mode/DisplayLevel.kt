package com.milebloom.app.mode

/**
 * 표시 수준 (30번 6.3). 1차는 Lv1(기본)과 Lv3(최소)만 있다. Lv2(사용자 설정, 정적)는 2차 (D-081).
 *
 * 플랫폼 UX 제한이 주행 중 평소(`NO_VIDEO|NO_SETUP`)보다 강해지면 **즉시** Lv3, 평소로 돌아오면 Lv1 (안전 9번).
 * DO를 선언한 앱도 제한을 직접 적용해야 한다는 주최 측 안내를 따른다 (D-081). 안드로이드 의존 없음.
 */
enum class DisplayLevel {
    LV1, LV3;

    companion object {
        /** `CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO | UX_RESTRICTIONS_NO_SETUP` — 에뮬레이터에서 기어 D일 때의 값 (13번 5장) */
        const val USUAL_DRIVING = 0x10 or 0x40

        /** @param activeRestrictions `CarUxRestrictions.activeRestrictions`. 아직 모르면 null → Lv1 */
        fun of(activeRestrictions: Int?): DisplayLevel =
            if (activeRestrictions != null && activeRestrictions and USUAL_DRIVING.inv() != 0) LV3 else LV1
    }
}
