#!/usr/bin/env python3
"""화면 속 차 SVG 3종(assets/art/cars/C1_*.svg) → render/CarShapes.kt

사용: python3 -I tools/gen_car_shapes.py <cars 폴더> <출력 .kt>   (cars 폴더의 cars.json에서 바퀴 각속도를 읽는다)
SVG를 손으로 고친 뒤(4-D) 이 스크립트만 다시 돌리면 된다. 원점·축척·칸 이름(30번 4.5)은 SVG가 지킨다.
"""
import json
import re
import sys
from pathlib import Path

# SVG class → CarSlot (30번 4.5 칸 이름)
SLOTS = {
    "body": "BODY", "shade": "SHADE", "light": "LIGHT", "trim": "TRIM", "glass": "GLASS", "glassLight": "GLASS_LIGHT",
    "tire": "TIRE", "wheel": "WHEEL", "spoke": "SPOKE", "tail": "TAIL", "glow": "GLOW",
}

MODELS = [("GRANDEUR", "C1_GRANDEUR.svg"), ("AVANTE", "C1_AVANTE.svg"), ("TUCSON", "C1_TUCSON.svg")]


def attr(tag, name):
    m = re.search(r'\b' + name + r'="([^"]*)"', tag)
    return m.group(1) if m else None


def convert(svg: str, model: str, meta: dict) -> str:
    ell = re.search(r"<ellipse[^>]*>", svg).group(0)
    g = re.search(r'<g transform="scale\(([-\d.]+)\) translate\(([-\d.]+) ([-\d.]+)\)">', svg)
    s, tx, ty = (float(v) for v in g.groups())
    clip = re.search(r'<clipPath[^>]*>\s*<path d="([^"]*)"', svg).group(1)
    body = svg[g.end():]
    parts = []
    glass_i = 0
    wheels = {}
    for tag in re.findall(r"<path[^>]*>", body):
        slot = attr(tag, "class")
        if slot is None:  # clipPath 안의 경로
            continue
        d = attr(tag, "d")
        alpha = float(attr(tag, "opacity") or 1)
        clipped = "clip-path" in tag
        gi = -1
        if slot == "glass":
            gi = glass_i
            glass_i += 1
        elif attr(tag, "data-glass") is not None:  # 유리 반사(glassLight)는 그 유리 조각과 함께 내려간다
            gi = int(attr(tag, "data-glass"))
        wi = int(attr(tag, "data-wheel")) if attr(tag, "data-wheel") is not None else -1
        if wi >= 0 and attr(tag, "data-cx") is not None:
            wheels[wi] = (float(attr(tag, "data-cx")), float(attr(tag, "data-cy")))
        parts.append(
            f'            CarPart(CarSlot.{SLOTS[slot]}, "{d}", {alpha}f, clipToBody = {str(clipped).lower()}, glassIndex = {gi}, wheelIndex = {wi}),'
        )
    return (
        f"    val {model} = CarShape(\n"
        f"        model = CarModel.{model},\n"
        f"        scale = {s}f, tx = {tx}f, ty = {ty}f,\n"
        f"        shadowRx = {float(attr(ell, 'rx'))}f, shadowRy = {float(attr(ell, 'ry'))}f,\n"
        f'        bodyClip = "{clip}",\n'
        f"        glassCount = {glass_i},\n"
        f"        wheelDegPerSec = {meta['wheelDegPerSec']}f,\n"
        f"        wheelCenters = floatArrayOf({', '.join(f'{x}f, {y}f' for _, (x, y) in sorted(wheels.items()))}),\n"
        f"        parts = listOf(\n" + "\n".join(parts) + "\n        ),\n    )\n"
    )


def main():
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    meta = json.loads((src / "cars.json").read_text(encoding="utf-8"))
    blocks = [convert((src / f).read_text(encoding="utf-8"), m, meta[m]) for m, f in MODELS]
    out.write_text(
        "// 자동 생성: tools/gen_car_shapes.py ← assets/art/cars/C1_*.svg. 손으로 고치지 말 것\n"
        "package com.milebloom.app.render\n\n"
        "/** SVG 칸(class) 이름 — 색은 [CarPalette]가 덮어쓴다 (30번 4.5) */\n"
        "enum class CarSlot { BODY, SHADE, LIGHT, TRIM, GLASS, GLASS_LIGHT, TIRE, WHEEL, SPOKE, TAIL, GLOW }\n\n"
        "/**\n"
        " * @param glassIndex 유리 조각 순서(뒤→앞). 유리·유리 반사가 아니면 -1\n"
        " * @param wheelIndex 바퀴 묶음(휠 면·스포크·허브) 번호. 돌지 않는 부분이면 -1\n"
        " */\n"
        "class CarPart(val slot: CarSlot, val d: String, val alpha: Float, val clipToBody: Boolean, val glassIndex: Int, val wheelIndex: Int)\n\n"
        "/**\n"
        " * 차 한 대의 형태. 경로 좌표는 참고 사진 px이고, `scale·(p + t)`가 cm 좌표(원점 = 차 길이 중앙의 지면, 위가 −y)다.\n"
        " */\n"
        "class CarShape(\n"
        "    val model: CarModel,\n"
        "    val scale: Float, val tx: Float, val ty: Float,\n"
        "    val shadowRx: Float, val shadowRy: Float,\n"
        "    val bodyClip: String,\n"
        "    val glassCount: Int,\n"
        "    /** 바퀴 각속도(도/초, 시계 방향) — 근경 흐름과 맞물리는 값, cars.json (30번 6.2-A). 흐름 배율을 곱해 쓴다 */\n"
        "    val wheelDegPerSec: Float,\n"
        "    /** 바퀴 중심 (x, y)… — 사진 px. 회전 중심 */\n"
        "    val wheelCenters: FloatArray,\n"
        "    val parts: List<CarPart>,\n"
        ")\n\n"
        "object CarShapes {\n" + "\n".join(blocks) +
        "\n    fun of(model: CarModel): CarShape = when (model) {\n"
        "        CarModel.GRANDEUR -> GRANDEUR\n        CarModel.AVANTE -> AVANTE\n        CarModel.TUCSON -> TUCSON\n    }\n}\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
