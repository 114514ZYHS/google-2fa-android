#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 2FA 验证器的启动器图标 PNG。

设计（按用户指定的参照图）：
  - 纯黑底
  - 红色线条
  - 所有线条都是 45 度斜线，交叉成 X 形
  - 线条端点做方形收口，保留硬朗观感

实现：以 SS 倍超采样绘制后再降采样，保证斜线边缘平滑无锯齿。
"""

from PIL import Image, ImageDraw
import os

SS = 8  # 超采样倍数

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "app", "src", "main", "res")

# 配色：纯黑底 + 红色线条
BG_COLOR = (0, 0, 0, 255)
LINE_COLOR = (229, 30, 42, 255)

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def rounded_square_mask(s, radius_ratio=0.20):
    """圆角方形遮罩，用于方形图标背景。"""
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, s - 1, s - 1], radius=s * radius_ratio, fill=255)
    return mask


def circle_mask(s):
    """圆形遮罩。"""
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, s - 1, s - 1], fill=255)
    return mask


def thick_diagonal(draw, p1, p2, width, color):
    """
    画一条粗斜线。

    45 度斜线的方形端点在几何上不是「补个小方块」——
    那样会在两角凸出多余台阶。正确做法是把线段沿自身方向
    略微延长，让端面正好垂直于线身，从而得到干净的斜切收口。
    """
    w = int(round(width))
    (x1, y1), (x2, y2) = p1, p2
    dx, dy = x2 - x1, y2 - y1
    length = max(1e-6, (dx * dx + dy * dy) ** 0.5)
    ux, uy = dx / length, dy / length
    # 每端延长半个线宽，使端面垂直于线身
    ext = w / 2.0
    ex1, ey1 = x1 - ux * ext, y1 - uy * ext
    ex2, ey2 = x2 + ux * ext, y2 + uy * ext
    draw.line([(ex1, ey1), (ex2, ey2)], fill=color, width=w)


def draw_cross(draw, s, color):
    """在 s x s 画布上画两条 45 度交叉斜线。"""
    w = s * 0.135          # 线条粗细
    m = s * 0.22           # 边距，避免贴边被裁
    left, right = m, s - m
    top, bottom = m, s - m

    # 主对角线：左上 -> 右下
    thick_diagonal(draw, (left, top), (right, bottom), w, color)
    # 副对角线：右上 -> 左下
    thick_diagonal(draw, (right, top), (left, bottom), w, color)


def render_icon(size, round_icon=False):
    s = size * SS
    canvas = Image.new("RGBA", (s, s), (0, 0, 0, 0))

    # ---- 背景：纯黑 ----
    mask = circle_mask(s) if round_icon else rounded_square_mask(s, 0.20)
    canvas.paste(Image.new("RGBA", (s, s), BG_COLOR), (0, 0), mask)

    # ---- 红色斜线 ----
    line = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw_cross(ImageDraw.Draw(line), s, LINE_COLOR)

    # 圆形图标时，把溢出圆外的线条裁掉
    line_alpha = line.split()[3]
    clipped = Image.composite(line_alpha, Image.new("L", (s, s), 0), mask)
    line.putalpha(clipped)

    canvas = Image.alpha_composite(canvas, line)
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

    # ---- 自适应图标图层 ----
    # 背景层：纯黑铺满（系统会自行裁成圆/方/水滴等形状）
    os.makedirs(os.path.join(OUT_DIR, "drawable"), exist_ok=True)
    bg_out = os.path.join(OUT_DIR, "drawable", "ic_launcher_background.png")
    Image.new("RGBA", (432, 432), BG_COLOR).save(bg_out, "PNG", optimize=True)

    # 前景层：透明底 + 红色斜线，整体缩到安全区（中心 66/108）
    S = 432 * SS
    fg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw_cross(ImageDraw.Draw(fg), S, LINE_COLOR)

    scale = 66.0 / 108.0
    small = fg.resize((int(S * scale), int(S * scale)), Image.LANCZOS)
    safe = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    off = int(round((S - S * scale) / 2))
    safe.paste(small, (off, off), small)

    fg_out = os.path.join(OUT_DIR, "drawable", "ic_launcher_foreground.png")
    safe.resize((432, 432), Image.LANCZOS).save(fg_out, "PNG", optimize=True)

    print(f"\n自适应图标图层已更新：{bg_out}, {fg_out}")
    print("图标生成完成。")


if __name__ == "__main__":
    main()
