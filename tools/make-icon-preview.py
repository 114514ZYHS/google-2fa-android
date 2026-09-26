#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把已生成的图标 PNG 拼成一张预览图，方便肉眼核对。

输出：/workspace/2FA-图标预览.png
  - 第一行：五个密度的方形图标
  - 第二行：五个密度的圆形图标
  - 右侧：自适应图标的前景层 / 背景层 / 合成效果（模拟启动器裁切）
"""

import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
OUT = "/workspace/2FA-图标预览.png"

DENSITIES = [
    ("mdpi", "48px", 48),
    ("hdpi", "72px", 72),
    ("xhdpi", "96px", 96),
    ("xxhdpi", "144px", 144),
    ("xxxhdpi", "192px", 192),
]

BG = (245, 246, 248, 255)
INK = (32, 36, 44, 255)
MUTED = (120, 126, 138, 255)

CJK_FONT = "/usr/share/fonts/opentype/noto/NotoSerifCJK-Bold.ttc"


def font(size):
    for path in (CJK_FONT,
                 "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                continue
    return ImageFont.load_default()


def label(draw, xy, text, f, color=MUTED, anchor="mm"):
    draw.text(xy, text, font=f, fill=color, anchor=anchor)


def main():
    cell = 192
    pad = 28
    gap = 32
    f_small = font(20)
    f_tiny = font(17)
    f_head = font(26)

    cols = len(DENSITIES)
    row_h = cell + 46

    W = pad * 2 + cols * cell + (cols - 1) * gap + 40 + 3 * 160 + 2 * gap + pad
    H = pad * 2 + 52 + row_h * 2 + 40
    canvas = Image.new("RGBA", (W, H), BG)
    d = ImageDraw.Draw(canvas)

    label(d, (pad, pad + 14), "启动器图标（方形 / 圆形）与自适应图层",
          f_head, INK, anchor="lm")

    y0 = pad + 52
    for row, variant in enumerate(("ic_launcher.png", "ic_launcher_round.png")):
        cy = y0 + row * row_h + cell // 2
        label(d, (pad - 12, cy), "方" if row == 0 else "圆",
              f_small, MUTED, anchor="mm")
        for i, (den, name, size) in enumerate(DENSITIES):
            x = pad + 24 + i * (cell + gap)
            p = os.path.join(RES, f"mipmap-{den}", variant)
            im = Image.open(p).convert("RGBA")
            # 无放大缩放到 cell（保持原始像素观感用 NEAREST）
            im = im.resize((cell, cell), Image.NEAREST)
            canvas.alpha_composite(im, (x, cy - cell // 2))
            label(d, (x + cell // 2, cy + cell // 2 + 24), name, f_tiny)

    # ---- 自适应三态 ----
    bx = pad + 24 + cols * (cell + gap) + 40
    cy = y0 + row_h // 2
    label(d, (bx, y0 + 6), "自适应图标", f_head, INK, anchor="lm")

    fg = Image.open(os.path.join(RES, "drawable",
                                 "ic_launcher_foreground.png")).convert("RGBA")
    bg = Image.open(os.path.join(RES, "drawable",
                                 "ic_launcher_background.png")).convert("RGBA")
    comp = bg.copy()
    comp.alpha_composite(fg)

    # 圆角裁切模拟启动器遮罩
    mask = Image.new("L", comp.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, comp.size[0] - 1, comp.size[1] - 1],
        radius=comp.size[0] * 0.22, fill=255)
    cropped = comp.copy()
    cropped.putalpha(mask)
    # 启动器只保留中间 ~72% 区域
    inset = int(comp.size[0] * 0.14)
    cropped = cropped.crop((inset, inset,
                            comp.size[0] - inset, comp.size[1] - inset))

    items = [("前景层", fg), ("背景层", bg), ("合成效果", cropped)]
    ssz = 150
    for i, (name, im) in enumerate(items):
        x = bx + i * (ssz + 12)
        yy = cy - ssz // 2 + 10
        thumb = Image.new("RGBA", (ssz, ssz), (255, 255, 255, 0))
        # 棋盘格底，方便看透明
        for gy in range(0, ssz, 15):
            for gx in range(0, ssz, 15):
                c = (225, 228, 232, 255) if (gx // 15 + gy // 15) % 2 == 0 \
                    else (250, 250, 251, 255)
                ImageDraw.Draw(thumb).rectangle(
                    [gx, gy, gx + 14, gy + 14], fill=c)
        r = im.resize((ssz, ssz), Image.LANCZOS)
        thumb.alpha_composite(r)
        canvas.alpha_composite(thumb, (x, yy))
        label(d, (x + ssz // 2, yy + ssz + 18), name, f_tiny)

    canvas.convert("RGB").save(OUT, "PNG", optimize=True)
    print("已输出预览：", OUT, canvas.size)


if __name__ == "__main__":
    main()
