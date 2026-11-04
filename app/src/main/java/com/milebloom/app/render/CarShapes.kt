// 자동 생성: tools/gen_car_shapes.py ← assets/art/cars/C1_*.svg. 손으로 고치지 말 것
package com.milebloom.app.render

/** SVG 칸(class) 이름 — 색은 [CarPalette]가 덮어쓴다 (30번 4.5) */
enum class CarSlot { BODY, SHADE, LIGHT, TRIM, GLASS, GLASS_LIGHT, TIRE, WHEEL, SPOKE, TAIL, GLOW }

/**
 * @param glassIndex 유리 조각 순서(뒤→앞). 유리·유리 반사가 아니면 -1
 * @param wheelIndex 바퀴 묶음(휠 면·스포크·허브) 번호. 돌지 않는 부분이면 -1
 */
class CarPart(val slot: CarSlot, val d: String, val alpha: Float, val clipToBody: Boolean, val glassIndex: Int, val wheelIndex: Int)

/**
 * 차 한 대의 형태. 경로 좌표는 참고 사진 px이고, `scale·(p + t)`가 cm 좌표(원점 = 차 길이 중앙의 지면, 위가 −y)다.
 */
class CarShape(
    val model: CarModel,
    val scale: Float, val tx: Float, val ty: Float,
    val shadowRx: Float, val shadowRy: Float,
    val bodyClip: String,
    val glassCount: Int,
    /** 바퀴 각속도(도/초, 시계 방향) — 근경 흐름과 맞물리는 값, cars.json (30번 6.2-A). 흐름 배율을 곱해 쓴다 */
    val wheelDegPerSec: Float,
    /** 바퀴 중심 (x, y)… — 사진 px. 회전 중심 */
    val wheelCenters: FloatArray,
    val parts: List<CarPart>,
)

object CarShapes {
    val GRANDEUR = CarShape(
        model = CarModel.GRANDEUR,
        scale = 0.6383f, tx = -469.0f, ty = -381.0f,
        shadowRx = 241.0f, shadowRy = 5.0f,
        bodyClip = "M80 336 L76 300 L78 270 L77 245 Q78 222 88 214 L100 212 Q120 209 130.0 206.0 Q140 203 150.0 199.0 Q160 195 170.0 191.5 Q180 188 190.0 184.0 Q200 180 210.0 176.0 Q220 172 230.0 169.0 Q240 166 250.0 162.5 Q260 159 270.0 156.0 Q280 153 290.0 151.5 Q300 150 310.0 148.5 Q320 147 330.0 146.5 Q340 146 350.0 145.0 Q360 144 380.0 143.5 Q400 143 420.0 142.5 Q440 142 460.0 143.0 Q480 144 490.0 145.5 Q500 147 510.0 149.0 Q520 151 530.0 155.0 Q540 159 550.0 163.0 Q560 167 570.0 172.0 Q580 177 590.0 182.0 Q600 187 610.0 192.0 Q620 197 630.0 202.0 L640 207 L640 207 Q680 208 700.0 209.5 Q720 211 740.0 214.0 Q760 217 780.0 221.0 Q800 225 810.0 228.0 Q820 231 830.0 236.0 Q840 241 848.0 247.5 Q856 254 859.5 264.5 L863 275 L863 322 Q861 338 845 341 L782 345 L778.0 345 A65 65 0 1 0 658.0 345 L316 345 L311.4 345 A65 65 0 1 0 190.6 345 L120 342 Q88 340 80 336 Z",
        glassCount = 3,
        wheelDegPerSec = 72.4f,
        wheelCenters = floatArrayOf(251.0f, 321.0f, 718.0f, 320.0f),
        parts = listOf(
            CarPart(CarSlot.TRIM, "M190.6 345 A65 65 0 1 1 311.4 345Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M658.0 345 A65 65 0 1 1 778.0 345Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.BODY, "M80 336 L76 300 L78 270 L77 245 Q78 222 88 214 L100 212 Q120 209 130.0 206.0 Q140 203 150.0 199.0 Q160 195 170.0 191.5 Q180 188 190.0 184.0 Q200 180 210.0 176.0 Q220 172 230.0 169.0 Q240 166 250.0 162.5 Q260 159 270.0 156.0 Q280 153 290.0 151.5 Q300 150 310.0 148.5 Q320 147 330.0 146.5 Q340 146 350.0 145.0 Q360 144 380.0 143.5 Q400 143 420.0 142.5 Q440 142 460.0 143.0 Q480 144 490.0 145.5 Q500 147 510.0 149.0 Q520 151 530.0 155.0 Q540 159 550.0 163.0 Q560 167 570.0 172.0 Q580 177 590.0 182.0 Q600 187 610.0 192.0 Q620 197 630.0 202.0 L640 207 L640 207 Q680 208 700.0 209.5 Q720 211 740.0 214.0 Q760 217 780.0 221.0 Q800 225 810.0 228.0 Q820 231 830.0 236.0 Q840 241 848.0 247.5 Q856 254 859.5 264.5 L863 275 L863 322 Q861 338 845 341 L782 345 L778.0 345 A65 65 0 1 0 658.0 345 L316 345 L311.4 345 A65 65 0 1 0 190.6 345 L120 342 Q88 340 80 336 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M70 285 L135 293 L190 301 L192 342 L100 321 L70 303 Z", 1.0f, clipToBody = true, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M318 320 L652 331 L652 343 L318 343 Z", 1.0f, clipToBody = true, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M264.2 216 L261.2 235 L265.2 250 L266.8 250 L262.8 235 L265.8 216Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M441.2 213 L448.2 245 L451.2 312 L452.8 312 L449.8 245 L442.8 213Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M624.2 220 L629.2 260 L629.2 318 L630.8 318 L630.8 260 L625.8 220Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M204 213 Q196 213 201 209 Q232 187 262 171 Q282 163 299 161 Q303 161 301 164 L252 210 Q250 213 245 213 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M275.0 212.0 Q272 212 274.0 209.8 L316.0 162.2 Q318 160 321.0 159.6 L342.1 157.1 Q360 155 378.0 154.1 L382.0 153.9 Q400 153 418.0 152.7 L442.0 152.3 Q460 152 478.0 153.3 L488.6 154.1 Q500 155 511.2 157.2 L513.8 157.8 Q525 160 535.3 165.1 L537.6 166.3 Q548 171.5 557.5 178.2 L560.1 180.0 Q570 187 579.4 194.6 L601.7 212.6 Q604 214.5 601.0 214.5 L513.8 213.4 Q440 212.5 366.2 212.3 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.GLASS, "M213 209 Q209 209 212 206.5 Q238 190 264 175.5 Q280 168.5 291 166 Q294 165.5 292 168 L248 207.5 Q247 209 244 209 Z", 1.0f, clipToBody = false, glassIndex = 0, wheelIndex = -1),
            CarPart(CarSlot.GLASS, "M434.0 205.0 Q434 208.0 431.0 208.0 L285.0 207.5 Q282.0 207.5 284.0 205.3 L318.2 166.5 Q320.2 164.3 323.2 163.9 L342.6 161.6 Q360.4 159.5 378.3 158.6 L384.9 158.3 Q400.1 157.5 415.4 157.2 L431.0 157.0 Q434 156.9 434.0 159.9 Z", 1.0f, clipToBody = false, glassIndex = 1, wheelIndex = -1),
            CarPart(CarSlot.GLASS_LIGHT, "M348.0 172.2 L360.1 172.2 L345.8 201.9 L333.7 201.9Z", 1.0f, clipToBody = false, glassIndex = 1, wheelIndex = -1),
            CarPart(CarSlot.GLASS, "M448.0 159.7 Q448 156.7 451.0 156.6 L454.5 156.6 Q459.9 156.5 465.2 156.9 L488.4 158.7 Q499.4 159.5 510.2 161.7 L512.7 162.1 Q523.5 164.3 533.4 169.2 L535.7 170.4 Q545.7 175.4 554.8 181.8 L557.6 183.8 Q567.3 190.6 576.6 198.1 L588.8 207.9 Q591.1 209.8 588.1 209.8 L451.0 208.1 Q448 208.1 448.0 205.1 Z", 1.0f, clipToBody = false, glassIndex = 2, wheelIndex = -1),
            CarPart(CarSlot.GLASS_LIGHT, "M511.6 172.5 L523.0 172.5 L508.1 203.4 L496.7 203.4Z", 1.0f, clipToBody = false, glassIndex = 2, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M79 282 L135 290 L188 299 L188 303 L135 295 L79 287 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M77 308 L100 320 L190 341 L190 345 L120 342 L84 330 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M318 310 L450 318 L652 324 L652 330 L450 325 L318 318 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M318 343 L655 343 L655 346 L318 346 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M784 324 L815 325 L805 331 L784 331 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M805 311 L822 278 L862 272 L863 302 L856 322 L830 315 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M630 245 L672 247 L672 250 L630 248 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M813.4 304.4 L817.2 308.2 L817.2 309.4 L813.4 305.6Z M815.8 299.8 L826.2 310.2 L826.2 311.4 L815.8 301.0Z M818.2 295.2 L834.5 311.5 L834.5 312.7 L818.2 296.4Z M820.6 290.6 L842.3 312.3 L842.3 313.5 L820.6 291.8Z M823.0 286.0 L850.1 313.1 L850.1 314.3 L823.0 287.2Z M825.9 281.9 L855.0 311.0 L855.0 312.2 L825.9 283.1Z M831.9 280.9 L857.0 306.0 L857.0 307.2 L831.9 282.1Z M838.0 280.0 L859.0 301.0 L859.0 302.2 L838.0 281.2Z M844.1 279.1 L858.7 293.7 L858.7 294.9 L844.1 280.3Z M850.2 278.2 L858.4 286.4 L858.4 287.6 L850.2 279.4Z M856.3 277.3 L858.1 279.1 L858.1 280.3 L856.3 278.5Z M819.6 292.4 L830.9 281.1 L830.9 282.3 L819.6 293.6Z M812.1 306.9 L839.1 279.9 L839.1 281.1 L812.1 308.1Z M817.7 308.3 L847.4 278.6 L847.4 279.8 L817.7 309.5Z M823.4 309.6 L855.6 277.4 L855.6 278.6 L823.4 310.8Z M829.2 310.8 L858.2 281.8 L858.2 283.0 L829.2 312.0Z M835.4 311.6 L858.5 288.5 L858.5 289.7 L835.4 312.8Z M841.8 312.2 L858.8 295.2 L858.8 296.4 L841.8 313.4Z M848.1 312.9 L858.3 302.7 L858.3 303.9 L848.1 314.1Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.LIGHT, "M270 214 L600 217 L640 222 L640 225 L600 221 L270 218 Z", 1.0f, clipToBody = true, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.BODY, "M556 207 Q560 192 572 192 Q586 193 596 208 L585 214 L566 212 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M289 241 h36 v3.5 h-36Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M461 241 h36 v3.5 h-36Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TAIL, "M79 236 L125 238 L140 245 L125 241 L79 240 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.GLOW, "M795 248 L851 249 L851 252 L800 252 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TIRE, "M191 321 a60 60 0 1 0 120 0 a60 60 0 1 0 -120 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.WHEEL, "M206 321 a45 45 0 1 0 90 0 a45 45 0 1 0 -90 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 0),
            CarPart(CarSlot.SPOKE, "M261.0 320.0 Q276.2 314.5 290.8 334.3 L284.3 346.6 Q276.9 323.0 261.0 322.0Z M260.6 323.9 Q276.7 324.6 282.7 348.6 L272.0 357.4 Q274.2 332.8 259.8 325.7Z M258.7 327.4 Q273.4 334.2 269.7 358.6 L256.5 362.6 Q267.9 340.8 257.4 328.7Z M255.7 329.8 Q266.6 341.8 253.9 362.9 L240.1 361.6 Q259.0 345.7 253.9 330.6Z M252.0 331.0 Q257.5 346.2 237.7 360.8 L225.4 354.3 Q249.0 346.9 250.0 331.0Z M248.1 330.6 Q247.4 346.7 223.4 352.7 L214.6 342.0 Q239.2 344.2 246.3 329.8Z M244.6 328.7 Q237.8 343.4 213.4 339.7 L209.4 326.5 Q231.2 337.9 243.3 327.4Z M242.2 325.7 Q230.2 336.6 209.1 323.9 L210.4 310.1 Q226.3 329.0 241.4 323.9Z M241.0 322.0 Q225.8 327.5 211.2 307.7 L217.7 295.4 Q225.1 319.0 241.0 320.0Z M241.4 318.1 Q225.3 317.4 219.3 293.4 L230.0 284.6 Q227.8 309.2 242.2 316.3Z M243.3 314.6 Q228.6 307.8 232.3 283.4 L245.5 279.4 Q234.1 301.2 244.6 313.3Z M246.3 312.2 Q235.4 300.2 248.1 279.1 L261.9 280.4 Q243.0 296.3 248.1 311.4Z M250.0 311.0 Q244.5 295.8 264.3 281.2 L276.6 287.7 Q253.0 295.1 252.0 311.0Z M253.9 311.4 Q254.6 295.3 278.6 289.3 L287.4 300.0 Q262.8 297.8 255.7 312.2Z M257.4 313.3 Q264.2 298.6 288.6 302.3 L292.6 315.5 Q270.8 304.1 258.7 314.6Z M259.8 316.3 Q271.8 305.4 292.9 318.1 L291.6 331.9 Q275.7 313.0 260.6 318.1Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 0),
            CarPart(CarSlot.WHEEL, "M245 321 a6 6 0 1 0 12 0 a6 6 0 1 0 -12 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 0),
            CarPart(CarSlot.TRIM, "M241 321 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 0),
            CarPart(CarSlot.TIRE, "M658 320 a60 60 0 1 0 120 0 a60 60 0 1 0 -120 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.WHEEL, "M673 320 a45 45 0 1 0 90 0 a45 45 0 1 0 -90 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 1),
            CarPart(CarSlot.SPOKE, "M728.0 319.0 Q743.2 313.5 757.8 333.3 L751.3 345.6 Q743.9 322.0 728.0 321.0Z M727.6 322.9 Q743.7 323.6 749.7 347.6 L739.0 356.4 Q741.2 331.8 726.8 324.7Z M725.7 326.4 Q740.4 333.2 736.7 357.6 L723.5 361.6 Q734.9 339.8 724.4 327.7Z M722.7 328.8 Q733.6 340.8 720.9 361.9 L707.1 360.6 Q726.0 344.7 720.9 329.6Z M719.0 330.0 Q724.5 345.2 704.7 359.8 L692.4 353.3 Q716.0 345.9 717.0 330.0Z M715.1 329.6 Q714.4 345.7 690.4 351.7 L681.6 341.0 Q706.2 343.2 713.3 328.8Z M711.6 327.7 Q704.8 342.4 680.4 338.7 L676.4 325.5 Q698.2 336.9 710.3 326.4Z M709.2 324.7 Q697.2 335.6 676.1 322.9 L677.4 309.1 Q693.3 328.0 708.4 322.9Z M708.0 321.0 Q692.8 326.5 678.2 306.7 L684.7 294.4 Q692.1 318.0 708.0 319.0Z M708.4 317.1 Q692.3 316.4 686.3 292.4 L697.0 283.6 Q694.8 308.2 709.2 315.3Z M710.3 313.6 Q695.6 306.8 699.3 282.4 L712.5 278.4 Q701.1 300.2 711.6 312.3Z M713.3 311.2 Q702.4 299.2 715.1 278.1 L728.9 279.4 Q710.0 295.3 715.1 310.4Z M717.0 310.0 Q711.5 294.8 731.3 280.2 L743.6 286.7 Q720.0 294.1 719.0 310.0Z M720.9 310.4 Q721.6 294.3 745.6 288.3 L754.4 299.0 Q729.8 296.8 722.7 311.2Z M724.4 312.3 Q731.2 297.6 755.6 301.3 L759.6 314.5 Q737.8 303.1 725.7 313.6Z M726.8 315.3 Q738.8 304.4 759.9 317.1 L758.6 330.9 Q742.7 312.0 727.6 317.1Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 1),
            CarPart(CarSlot.WHEEL, "M712 320 a6 6 0 1 0 12 0 a6 6 0 1 0 -12 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 1),
            CarPart(CarSlot.TRIM, "M708 320 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 1),
        ),
    )

    val AVANTE = CarShape(
        model = CarModel.AVANTE,
        scale = 0.5866f, tx = -466.5f, ty = -427.0f,
        shadowRx = 226.0f, shadowRy = 5.0f,
        bodyClip = "M72 362 L66 332 L72 310 L87 272 L80 251 L84 250 Q100 248 110.0 246.5 Q120 245 130.0 242.5 Q140 240 150.0 236.5 Q160 233 170.0 229.0 Q180 225 190.0 221.5 Q200 218 210.0 214.5 Q220 211 230.0 207.5 Q240 204 250.0 200.5 Q260 197 270.0 194.5 Q280 192 290.0 189.5 Q300 187 310.0 186.0 Q320 185 330.0 184.0 Q340 183 350.0 182.5 Q360 182 380.0 182.0 Q400 182 420.0 182.5 Q440 183 460.0 184.0 Q480 185 490.0 186.0 Q500 187 510.0 189.0 Q520 191 530.0 194.5 Q540 198 550.0 202.0 Q560 206 570.0 211.0 Q580 216 590.0 221.0 Q600 226 610.0 231.5 Q620 237 630.0 242.5 Q640 248 647.5 250.5 L655 253 L655 253 Q680 256 700.0 258.5 Q720 261 740.0 264.0 Q760 267 780.0 271.5 Q800 276 810.0 279.0 Q820 282 830.0 287.0 Q840 292 845.0 296.0 Q850 300 857.5 305.5 L865 311 L864 328 L869 328 L868 349 L864 350 L864 373 L866 380 L861 385 L845 388.5 L825 390.5 L800 393 L778 396 L772.2 396 A62 62 0 1 0 657.8 396 L300 397 L292.2 396 A62 62 0 1 0 177.8 396 L180 392 L100 377 Q82 372 72 362 Z",
        glassCount = 3,
        wheelDegPerSec = 85.9f,
        wheelCenters = floatArrayOf(235.0f, 372.0f, 715.0f, 372.0f),
        parts = listOf(
            CarPart(CarSlot.TRIM, "M177.8 396 A62 62 0 1 1 292.2 396Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M657.8 396 A62 62 0 1 1 772.2 396Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.BODY, "M72 362 L66 332 L72 310 L87 272 L80 251 L84 250 Q100 248 110.0 246.5 Q120 245 130.0 242.5 Q140 240 150.0 236.5 Q160 233 170.0 229.0 Q180 225 190.0 221.5 Q200 218 210.0 214.5 Q220 211 230.0 207.5 Q240 204 250.0 200.5 Q260 197 270.0 194.5 Q280 192 290.0 189.5 Q300 187 310.0 186.0 Q320 185 330.0 184.0 Q340 183 350.0 182.5 Q360 182 380.0 182.0 Q400 182 420.0 182.5 Q440 183 460.0 184.0 Q480 185 490.0 186.0 Q500 187 510.0 189.0 Q520 191 530.0 194.5 Q540 198 550.0 202.0 Q560 206 570.0 211.0 Q580 216 590.0 221.0 Q600 226 610.0 231.5 Q620 237 630.0 242.5 Q640 248 647.5 250.5 L655 253 L655 253 Q680 256 700.0 258.5 Q720 261 740.0 264.0 Q760 267 780.0 271.5 Q800 276 810.0 279.0 Q820 282 830.0 287.0 Q840 292 845.0 296.0 Q850 300 857.5 305.5 L865 311 L864 328 L869 328 L868 349 L864 350 L864 373 L866 380 L861 385 L845 388.5 L825 390.5 L800 393 L778 396 L772.2 396 A62 62 0 1 0 657.8 396 L300 397 L292.2 396 A62 62 0 1 0 177.8 396 L180 392 L100 377 Q82 372 72 362 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M60 317 L76 317 L90 348 L100 377 Q82 372 72 362 L60 340 Z", 1.0f, clipToBody = true, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M265.2 254 L256.2 275 L264.2 300 L284.2 322 L295.2 350 L296.8 350 L285.8 322 L265.8 300 L257.8 275 L266.8 254Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M431.2 261 L437.2 300 L440.2 362 L441.8 362 L438.8 300 L432.8 261Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M625.2 262 L636.2 300 L635.2 366 L636.8 366 L637.8 300 L626.8 262Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M177 249 Q169 249 175 245 L250 214 Q275 204 300 202 Q316 200.5 330 201 L330 204 L302 206.5 L289 209 L260 218 L235 246 Q233 249 229 249 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M271.0 251.5 Q268 251 269.3 248.3 L287.7 211.7 Q289 209 291.7 207.7 L296.1 205.7 Q302 203 308.4 202.3 L328.4 200.2 Q350 198 371.7 197.1 L377.5 196.9 Q400 196 422.5 196.0 L442.0 196.0 Q460 196 478.0 197.1 L482.0 197.4 Q500 198.5 517.0 204.5 L528.0 208.3 Q550 216 570.2 227.5 L576.5 231.0 Q595 241.5 613.5 252.1 L633.4 263.5 Q636 265 633.0 264.9 L314.6 256.4 Q300 256 285.6 253.8 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.GLASS, "M191.0 245.0 Q186 245 190.6 243.1 L252.2 217.6 Q255 216.5 253.1 218.8 L232.9 242.7 Q231 245 228.0 245.0 Z", 1.0f, clipToBody = false, glassIndex = 0, wheelIndex = -1),
            CarPart(CarSlot.GLASS, "M277.8 248.0 Q274.8 247.5 276.1 244.8 L291.0 215.1 Q292.3 212.4 295.0 211.1 L298.3 209.7 Q303.2 207.4 308.6 206.8 L329.1 204.7 Q350.3 202.5 371.6 201.6 L391.2 200.9 Q400.1 200.5 409.1 200.5 L417.0 200.5 Q420 200.5 420.0 203.5 L420.0 251.7 Q420 254.7 417.0 254.6 L312.1 251.8 Q300.4 251.5 288.9 249.7 Z", 1.0f, clipToBody = false, glassIndex = 1, wheelIndex = -1),
            CarPart(CarSlot.GLASS_LIGHT, "M339.3 216.8 L351.0 216.8 L335.8 248.2 L324.2 248.2Z", 1.0f, clipToBody = false, glassIndex = 1, wheelIndex = -1),
            CarPart(CarSlot.GLASS, "M442.0 203.5 Q442 200.5 445.0 200.5 L451.8 200.5 Q459.9 200.5 467.9 201.0 L481.5 201.9 Q499.1 203.0 515.8 208.8 L526.3 212.5 Q548.1 220.1 568.2 231.5 L581.3 238.9 Q592.8 245.4 604.3 252.0 L615.7 258.5 Q618.3 260.0 615.3 259.9 L445.0 255.4 Q442 255.3 442.0 252.3 Z", 1.0f, clipToBody = false, glassIndex = 2, wheelIndex = -1),
            CarPart(CarSlot.GLASS_LIGHT, "M518.6 218.3 L532.7 218.3 L516.0 252.9 L501.9 252.9Z", 1.0f, clipToBody = false, glassIndex = 2, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M76 317 L100 320 L110 320 L145 330 L184 341 L184 393 L100 377 L90 348 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M298 368 L340 359 L600 364 L648 388 L648 397 L298 397 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M825 315 L856 315 L850 333 L860 332 L860 328 L869 328 L868 349 L864 350 L864 373 L858 375 L839 357 L797 357.5 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M772 357 L797 357.5 L814 383 L863 382.5 L861 385 L845 388.5 L825 390.5 L800 393 L778 397 L770 397 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M798 295 L846 298.5 L846 300 L803 297.5 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M266 198 L266 186 Q270 183 279 183 L295 187.5 L295 191 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TIRE, "M825 315 L856 315 L850 333 L813.5 333 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M822.6 320 a1.4 1.4 0 1 0 2.8 0 a1.4 1.4 0 1 0 -2.8 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M821.6 327 a1.4 1.4 0 1 0 2.8 0 a1.4 1.4 0 1 0 -2.8 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.LIGHT, "M302 258.5 L640 268 L640 271 L302 262 Z", 1.0f, clipToBody = true, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.BODY, "M566 247 Q570 240 582 239 Q598 240 612 257 L596 260 L578 257 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M275 288 h37 v3.5 h-37Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M450 291 h37 v3.5 h-37Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TAIL, "M116 273.5 L124 273.5 L109.5 315 L110 320 L102 319.5 L100.5 310 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TAIL, "M84 281 L108 281.5 L107 284.5 L85 283.5 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.GLOW, "M803 297 L851 300 L851 302 L812 302 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.GLOW, "M773 291 L778 291 L819 309 L812 309 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TIRE, "M180 372 a55 55 0 1 0 110 0 a55 55 0 1 0 -110 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.WHEEL, "M192 372 a43 43 0 1 0 86 0 a43 43 0 1 0 -86 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 0),
            CarPart(CarSlot.SPOKE, "M195 372 a40 40 0 1 0 80 0 a40 40 0 1 0 -80 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 0),
            CarPart(CarSlot.WHEEL, "M242.9 370.5 L275.3 376.2 L272.6 387.2 L242.9 373.5Z M238.9 379.0 L243.4 411.6 L232.2 412.4 L236.0 379.9Z M229.5 377.9 L199.9 392.2 L195.7 381.8 L227.7 375.4Z M227.7 368.6 L204.9 344.9 L213.5 337.7 L229.5 366.1Z M236.0 364.1 L251.5 335.0 L261.0 341.0 L238.9 365.0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 0),
            CarPart(CarSlot.TRIM, "M245.0 371.5 L271.9 381.2 L270.7 385.0 L245.0 372.5Z M238.6 381.3 L237.7 409.9 L233.7 410.0 L237.6 381.7Z M227.2 378.3 L199.8 386.2 L198.5 382.5 L226.6 377.4Z M226.6 366.6 L210.6 342.9 L213.8 340.5 L227.2 365.7Z M237.6 362.3 L255.1 339.8 L258.4 342.1 L238.6 362.7Z M227 372 a8 8 0 1 0 16 0 a8 8 0 1 0 -16 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 0),
            CarPart(CarSlot.TIRE, "M660 372 a55 55 0 1 0 110 0 a55 55 0 1 0 -110 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.WHEEL, "M672 372 a43 43 0 1 0 86 0 a43 43 0 1 0 -86 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 1),
            CarPart(CarSlot.SPOKE, "M675 372 a40 40 0 1 0 80 0 a40 40 0 1 0 -80 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 1),
            CarPart(CarSlot.WHEEL, "M722.9 370.5 L755.3 376.2 L752.6 387.2 L722.9 373.5Z M718.9 379.0 L723.4 411.6 L712.2 412.4 L716.0 379.9Z M709.5 377.9 L679.9 392.2 L675.7 381.8 L707.7 375.4Z M707.7 368.6 L684.9 344.9 L693.5 337.7 L709.5 366.1Z M716.0 364.1 L731.5 335.0 L741.0 341.0 L718.9 365.0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 1),
            CarPart(CarSlot.TRIM, "M725.0 371.5 L751.9 381.2 L750.7 385.0 L725.0 372.5Z M718.6 381.3 L717.7 409.9 L713.7 410.0 L717.6 381.7Z M707.2 378.3 L679.8 386.2 L678.5 382.5 L706.6 377.4Z M706.6 366.6 L690.6 342.9 L693.8 340.5 L707.2 365.7Z M717.6 362.3 L735.1 339.8 L738.4 342.1 L718.6 362.7Z M707 372 a8 8 0 1 0 16 0 a8 8 0 1 0 -16 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 1),
        ),
    )

    val TUCSON = CarShape(
        model = CarModel.TUCSON,
        scale = 0.6068f, tx = -468.5f, ty = -422.0f,
        shadowRx = 222.0f, shadowRy = 5.0f,
        bodyClip = "M95 335 L87 315 L95 282 L92 250 L97 215 L103 209 L125 192 L138 180 L145 167 L142 161 L131 159 L127 153 L140 148 Q160 145 170.0 143.5 Q180 142 190.0 140.5 Q200 139 210.0 137.5 Q220 136 250.0 134.5 Q280 133 310.0 132.5 Q340 132 390.0 132.0 Q440 132 460.0 133.0 Q480 134 490.0 136.5 Q500 139 510.0 142.0 Q520 145 530.0 150.0 Q540 155 550.0 159.5 Q560 164 570.0 169.5 Q580 175 590.0 180.5 Q600 186 610.0 192.0 Q620 198 630.0 204.0 L640 210 L640 210 Q660 216 680.0 217.5 Q700 219 720.0 220.0 Q740 221 760.0 223.0 Q780 225 790.0 226.5 Q800 228 810.0 231.0 Q820 234 827.5 239.5 Q835 245 839.0 253.5 L843 262 L845 300 L850 305 L850 325 L845 327 L843 352 L847 355 L846 358 L830 364 L800 372 L782 366 L780.2 366 A73 73 0 1 0 635.8 366 L320 366 L317.2 366 A73 73 0 1 0 172.8 366 L152 358 L115 350 Q102 346 95 335 Z",
        glassCount = 3,
        wheelDegPerSec = 68.2f,
        wheelCenters = floatArrayOf(245.0f, 355.0f, 708.0f, 355.0f),
        parts = listOf(
            CarPart(CarSlot.TRIM, "M172.8 366 A73 73 0 1 1 317.2 366Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M635.8 366 A73 73 0 1 1 780.2 366Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.BODY, "M95 335 L87 315 L95 282 L92 250 L97 215 L103 209 L125 192 L138 180 L145 167 L142 161 L131 159 L127 153 L140 148 Q160 145 170.0 143.5 Q180 142 190.0 140.5 Q200 139 210.0 137.5 Q220 136 250.0 134.5 Q280 133 310.0 132.5 Q340 132 390.0 132.0 Q440 132 460.0 133.0 Q480 134 490.0 136.5 Q500 139 510.0 142.0 Q520 145 530.0 150.0 Q540 155 550.0 159.5 Q560 164 570.0 169.5 Q580 175 590.0 180.5 Q600 186 610.0 192.0 Q620 198 630.0 204.0 L640 210 L640 210 Q660 216 680.0 217.5 Q700 219 720.0 220.0 Q740 221 760.0 223.0 Q780 225 790.0 226.5 Q800 228 810.0 231.0 Q820 234 827.5 239.5 Q835 245 839.0 253.5 L843 262 L845 300 L850 305 L850 325 L845 327 L843 352 L847 355 L846 358 L830 364 L800 372 L782 366 L780.2 366 A73 73 0 1 0 635.8 366 L320 366 L317.2 366 A73 73 0 1 0 172.8 366 L152 358 L115 350 Q102 346 95 335 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M340 328 L617 334 L617 366 L332 366 Z", 1.0f, clipToBody = true, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M80 290 L96 296 L152 320 L152 353 L92 336 L80 316 Z", 1.0f, clipToBody = true, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M259.2 216 L257.2 250 L258.8 250 L260.8 216Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M434.2 220 L441.2 260 L444.2 312 L445.8 312 L442.8 260 L435.8 220Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M442.2 330 L442.2 364 L443.8 364 L443.8 330Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M626.2 240 L626.2 295 L627.8 295 L627.8 240Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.GLASS_LIGHT, "M140 190 L192 190 L232 156 L172 160 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M127 153 L150 155 L172 160 L140 190 L120 198 L104 209 L125 192 L138 180 L145 167 L142 161 L131 159 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M194.9 191.7 Q192 191 194.3 189.0 L229.7 158.0 Q232 156 235.0 155.9 L291.4 154.3 Q340 153 388.6 152.7 L488.7 152.1 Q500 152 511.2 153.3 L513.8 153.7 Q525 155 534.9 160.5 L582.0 186.9 Q600 197 617.1 208.5 L635.5 220.8 Q638 222.5 635.0 222.4 L294.0 213.4 Q280 213 266.5 209.4 L263.5 208.6 Q250 205 236.4 201.7 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.GLASS, "M211.9 187.7 Q209 187 211.2 185.0 L232.8 166.0 Q235 164 238.0 163.9 L289.0 162.5 Q292 162.4 291.4 165.3 L284.6 202.2 Q284 205.1 281.1 204.4 L266.4 200.8 Q252 197.2 237.6 193.8 Z", 1.0f, clipToBody = false, glassIndex = 0, wheelIndex = -1),
            CarPart(CarSlot.GLASS, "M327.8 160.6 Q328 157.6 331.0 157.6 L404.0 157.0 Q407 157 407.6 159.9 L417.4 209.3 Q418 212.2 415.0 212.1 L328.0 209.8 Q325 209.7 325.2 206.7 Z", 1.0f, clipToBody = false, glassIndex = 1, wheelIndex = -1),
            CarPart(CarSlot.GLASS_LIGHT, "M372.1 173.6 L379.5 173.6 L364.1 205.6 L356.6 205.6Z", 1.0f, clipToBody = false, glassIndex = 1, wheelIndex = -1),
            CarPart(CarSlot.GLASS, "M443.7 159.7 Q443 156.8 446.0 156.8 L477.1 156.7 Q505 156.6 528.1 172.3 L591.5 215.3 Q594 217 591.0 216.9 L459.5 213.1 Q456.5 213 455.8 210.1 Z", 1.0f, clipToBody = false, glassIndex = 2, wheelIndex = -1),
            CarPart(CarSlot.GLASS_LIGHT, "M511.3 174.7 L523.3 174.7 L506.4 209.8 L494.3 209.8Z", 1.0f, clipToBody = false, glassIndex = 2, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M205 140 L214 134 L222 130 L236 133 L340 130 L440 130 L480 133 L506 144 L480 141 L340 138 L205 142 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M152 358 L150 302 L185 257 L297 257 L340 312 L620 316 L652 262 L755 265 L800 317 L810 316 L814 291 L841 291 L845 304 L850 305 L850 325 L845 327 L845 310 L840 303 L815 329 L806 332 L805.5 338 L800 372 L782 368 L617 366 L312 366 L312 342 L176 342 L176 366 L152 358 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M95 283 L110 277 L152 300 L152 320 L96 296 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M92 335 L152 352 L152 358 L115 350 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M340 327 L617 333 L617 364 L334 364 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M806 332 L815 329 L840 303 L845 310 L845 327 L843 352 L847 355 L846 358 L830 364 L800 372 L805.5 338 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TRIM, "M800 367.5 L830 359.5 L847 353.5 L846 358 L830 364 L800 372 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.LIGHT, "M285 215.5 L630 225 L630 228 L285 219 Z", 1.0f, clipToBody = true, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.BODY, "M556 198 Q566 194 578 197 Q588 202 591 216 L568 217 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M278 236 h36 v3.5 h-36Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.SHADE, "M452 240 h36 v3.5 h-36Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TAIL, "M120 230.5 L137.5 230.5 L116.5 280.5 L110.5 277.5 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TAIL, "M96 234 L114 235.5 L114 237.5 L104 237 L99 236 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.GLOW, "M813 254 L840 253 L838 257 L815 258 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.GLOW, "M786 249 L792 249 L821 292.5 L815 292.5 Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.TIRE, "M178 355 a67 67 0 1 0 134 0 a67 67 0 1 0 -134 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.WHEEL, "M199 355 a46 46 0 1 0 92 0 a46 46 0 1 0 -92 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 0),
            CarPart(CarSlot.SPOKE, "M201.5 355 a43.5 43.5 0 1 0 87.0 0 a43.5 43.5 0 1 0 -87.0 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 0),
            CarPart(CarSlot.WHEEL, "M260.0 354.2 L283.0 370.4 L273.5 384.5 L260.0 355.8Z M250.4 369.0 L242.1 395.9 L225.8 391.2 L248.9 369.5Z M233.3 364.4 L205.2 364.9 L204.6 347.9 L232.4 363.2Z M232.4 346.8 L223.3 320.2 L239.3 314.4 L233.3 345.6Z M248.9 340.5 L271.4 323.6 L281.9 337.0 L250.4 341.0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 0),
            CarPart(CarSlot.TRIM, "M235 355 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0Z M254.9 353.4 L263.9 353.0 L263.9 357.0 L254.9 356.6Z M249.5 363.9 L252.7 372.4 L249.0 373.6 L246.6 364.9Z M237.9 362.1 L230.9 367.7 L228.5 364.5 L236.1 359.5Z M236.1 350.5 L228.5 345.5 L230.9 342.3 L237.9 347.9Z M246.6 345.1 L249.0 336.4 L252.7 337.6 L249.5 346.1Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 0),
            CarPart(CarSlot.TIRE, "M641 355 a67 67 0 1 0 134 0 a67 67 0 1 0 -134 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = -1),
            CarPart(CarSlot.WHEEL, "M662 355 a46 46 0 1 0 92 0 a46 46 0 1 0 -92 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 1),
            CarPart(CarSlot.SPOKE, "M664.5 355 a43.5 43.5 0 1 0 87.0 0 a43.5 43.5 0 1 0 -87.0 0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 1),
            CarPart(CarSlot.WHEEL, "M723.0 354.2 L746.0 370.4 L736.5 384.5 L723.0 355.8Z M713.4 369.0 L705.1 395.9 L688.8 391.2 L711.9 369.5Z M696.3 364.4 L668.2 364.9 L667.6 347.9 L695.4 363.2Z M695.4 346.8 L686.3 320.2 L702.3 314.4 L696.3 345.6Z M711.9 340.5 L734.4 323.6 L744.9 337.0 L713.4 341.0Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 1),
            CarPart(CarSlot.TRIM, "M698 355 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0Z M717.9 353.4 L726.9 353.0 L726.9 357.0 L717.9 356.6Z M712.5 363.9 L715.7 372.4 L712.0 373.6 L709.6 364.9Z M700.9 362.1 L693.9 367.7 L691.5 364.5 L699.1 359.5Z M699.1 350.5 L691.5 345.5 L693.9 342.3 L700.9 347.9Z M709.6 345.1 L712.0 336.4 L715.7 337.6 L712.5 346.1Z", 1.0f, clipToBody = false, glassIndex = -1, wheelIndex = 1),
        ),
    )

    fun of(model: CarModel): CarShape = when (model) {
        CarModel.GRANDEUR -> GRANDEUR
        CarModel.AVANTE -> AVANTE
        CarModel.TUCSON -> TUCSON
    }
}
