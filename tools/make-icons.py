#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 2FA 验证器的启动器图标 PNG。

设计：蓝色渐变底 + 白色盾牌 + 盾内钥匙孔。

实现方式（关键改进）：
  盾牌不再靠手工排点拼多边形——那种做法很容易左右不对称。
  这里改用「图形布尔运算」：
      盾牌 = (上部长方形 ∪ 下部半椭圆) ∩ 圆角矩形
  形状用高分辨率蒙版绘制，天然左右对称，最后整体降采样获得平滑边缘。
"""

from PIL import Image, ImageDraw, ImageFilter
import os

SS = 8  # 超采样倍数

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "app", "src", "main", "res")

BLUE_TOP = (32, 124, 236)
BLUE_BOTTOM = (11, 87, 208)
WHITE = (255, 255, 255, 255)

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def vertical_gradient(size, top_color, bottom_color):
    """垂直线性渐变（比对角渐变更适合图标，观感更稳）。"""
    grad = Image.new("RGBA", (1, size))
    px = grad.load()
    for y in range(size):
        t = y / float(size - 1) if size > 1 else 0.0
        px[0, y] = (
            int(top_color[0] + (bottom_color[0] - top_color[0]) * t),
            int(top_color[1] + (bottom_color[1] - top_color[1]) * t),
            int(top_color[2] + (bottom_color[2] - top_color[2]) * t),
            255,
        )
    return grad.resize((size, size), Image.NEAREST)


def build_shield_mask(s):
    """
    构造盾牌形状的蒙版（L 模式）。

    几何构成：
      - 上部：宽度 full_w，高度 rect_h 的矩形
      - 下部：与上部等宽的半椭圆，纵向半径 ellipse_h
      - 整体再与一个圆角矩形求交，让肩部自然变圆
    """
    mask = Image.new("L", (s, s), 0)
    draw = ImageDraw.Draw(mask)

    cx = s / 2.0
    full_w = s * 0.50          # 盾牌最大宽度
    total_h = s * 0.60         # 盾牌总高度
    top = (s - total_h) / 2.0
    bottom = top + total_h

    half_w = full_w / 2.0
    rect_h = total_h * 0.52        # 上部矩形高度
    ellipse_h = total_h - rect_h   # 下部椭圆高度
    rect_bottom = top + rect_h

    # 上部矩形
    draw.rectangle([cx - half_w, top, cx + half_w, rect_bottom], fill=255)

    # 下部半椭圆（只保留下半部分）
    ellipse = Image.new("L", (s, s), 0)
    ImageDraw.Draw(ellipse).ellipse(
        [cx - half_w, rect_bottom - ellipse_h, cx + half_w, rect_bottom + ellipse_h],
        fill=255)
    # 只取 rect_bottom 以下的半椭圆区域
    cut = Image.new("L", (s, s), 0)
    ImageDraw.Draw(cut).rectangle([0, rect_bottom, s, s], fill=255)
    ellipse = Image.composite(ellipse, Image.new("L", (s, s), 0), cut)

    mask = Image.composite(Image.new("L", (s, s), 255), mask, ellipse)

    # 与圆角矩形求交，使肩部圆润
    rounded = Image.new("L", (s, s), 0)
    radius = full_w * 0.16
    ImageDraw.Draw(rounded).rounded_rectangle(
        [cx - half_w, top, cx + half_w, bottom], radius=radius, fill=255)

    final = Image.new("L", (s, s), 0)
    fpx = final.load()
    mpx = mask.load()
    rpx = rounded.load()
    for y in range(s):
        for x in range(s):
            if mpx[x, y] and rpx[x, y]:
                fpx[x, y] = 255
    return final


def build_keyhole_mask(s, sh_w, sh_top, sh_h):
    """钥匙孔蒙版：圆头 + 下方梯形柱。"""
    mask = Image.new("L", (s, s), 0)
    draw = ImageDraw.Draw(mask)
    cx = s / 2.0

    head_r = sh_w * 0.150
    head_cy = sh_top + sh_h * 0.345
    draw.ellipse([cx - head_r, head_cy - head_r, cx + head_r, head_cy + head_r],
                 fill=255)

    stem_top = head_cy + head_r * 0.10
    stem_bot = sh_top + sh_h * 0.60
    half_top = head_r * 0.44
    half_bot = head_r * 0.88
    draw.polygon([
        (cx - half_top, stem_top),
        (cx + half_top, stem_top),
        (cx + half_bot, stem_bot),
        (cx - half_bot, stem_bot),
    ], fill=255)
    return mask


def render_icon(size, round_icon=False):
    s = size * SS

    # ---- 背景 ----
    bg = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    grad = vertical_gradient(s, BLUE_TOP, BLUE_BOTTOM)
    if round_icon:
        circle = Image.new("L", (s, s), 0)
        ImageDraw.Draw(circle).ellipse([0, 0, s - 1, s - 1], fill=255)
        bg.paste(grad, (0, 0), circle)
    else:
        bg.paste(grad, (0, 0))

    # ---- 盾牌几何参数 ----
    sh_w = s * 0.50
    sh_h = s * 0.60
    sh_top = (s - sh_h) / 2.0

    shield_mask = build_shield_mask(s)

    # ---- 盾牌投影 ----
    shadow_mask = shield_mask.filter(ImageFilter.GaussianBlur(SS * 1.6))
    shadow_layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    shadow_layer.paste((0, 0, 0, 95), (0, int(s * 0.012)), shadow_mask)
    canvas = Image.alpha_composite(bg, shadow_layer)

    # ---- 盾牌本体（白） ----
    shield_layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    shield_layer.paste(WHITE, (0, 0), shield_mask)

    # ---- 挖出钥匙孔 ----
    keyhole = build_keyhole_mask(s, sh_w, sh_top, sh_h)
    shield_alpha = shield_layer.split()[3]
    carved = Image.composite(Image.new("L", (s, s), 0), shield_alpha, keyhole)
    shield_layer.putalpha(carved)

    canvas = Image.alpha_composite(canvas, shield_layer)

    return canvas.resize((size, size), Image.LANCZOS)


def main():
    for density, size in DENSITIES.items():
        d = os.path.join(OUT_DIR, f"mipmap-{density}")
        os.makedirs(d, exist_ok=True)

        icon = render_icon(size, round_icon=False)
        p1 = os.path.join(d, "ic_launcher.png")
        icon.save(p1, "PNG", optimize=True)

        rnd = render_icon(size, round_icon=True)
        p2 = os.path.join(d, "ic_launcher_round.png")
        rnd.save(p2, "PNG", optimize=True)

        print(f"{density:8s} {size}x{size}  launcher={os.path.getsize(p1)}B  "
              f"round={os.path.getsize(p2)}B")

    print("\n图标生成完成。")


if __name__ == "__main__":
    main()
