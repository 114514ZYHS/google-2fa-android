#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 2FA 验证器的启动器图标 PNG。

设计（按用户指定）：
  - 一条从右上角到左下角的 45 度斜线，把图标分成两块
  - 斜线左侧（左上三角）红色
  - 斜线右侧（右下三角）黑色
  - 正中间一个五角星，颜色与所在背景相反
      · 落在红色半边的部分 -> 黑色
      · 落在黑色半边的部分 -> 红色
    星压在分界线上，天然一半一半，边界严丝合缝。

实现：以 SS 倍超采样绘制后再降采样，保证斜边与星形边缘平滑无锯齿。
所有形状判断走 numpy 向量化，避免逐像素循环。
"""

from PIL import Image, ImageDraw
import math
import os
import numpy as np

SS = 8  # 超采样倍数

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "app", "src", "main", "res")

RED = (229, 30, 42, 255)
BLACK = (0, 0, 0, 255)

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


def build_upper_left_mask(s):
    """
    「左上三角」遮罩（L 模式）。

    分界线是右上角 -> 左下角的 45 度直线，方程等价于 x + y = s。
    满足 x + y < s 的点位于分界线左上方，即红色区域。
    用 numpy 一次性算完，避免逐像素循环。
    """
    yy, xx = np.mgrid[0:s, 0:s]
    inside = (xx + yy) < s
    return Image.fromarray(np.where(inside, 255, 0).astype(np.uint8), mode="L")


def nmask_to_img(arr):
    """numpy 布尔数组 -> L 模式 Image。"""
    return Image.fromarray(np.where(arr, 255, 0).astype(np.uint8), mode="L")


def star_mask_array(s, outer_r_ratio=0.30, cx_ratio=0.5, cy_ratio=0.5):
    """返回五角星覆盖区域的布尔数组。"""
    mask = Image.new("L", (s, s), 0)
    cx, cy = s * cx_ratio, s * cy_ratio
    outer_r = s * outer_r_ratio
    points = []
    for i in range(10):
        radius = outer_r if i % 2 == 0 else outer_r * 0.382  # 内/外半径比
        angle = -math.pi / 2 + i * math.pi / 5
        points.append((cx + radius * math.cos(angle),
                       cy + radius * math.sin(angle)))
    ImageDraw.Draw(mask).polygon(points, fill=255)
    return np.array(mask) > 127


def render_icon(size, round_icon=False):
    s = size * SS

    # ---- 分界掩码与形状掩码 ----
    upper_left = np.array(build_upper_left_mask(s)) > 127          # True = 红区
    if round_icon:
        shape = np.array(circle_mask(s)) > 127
    else:
        shape = np.array(rounded_square_mask(s, 0.20)) > 127

    # ---- 底色：红区红、黑区黑 ----
    rgb = np.zeros((s, s, 3), dtype=np.uint8)
    rgb[upper_left] = RED[:3]
    rgb[~upper_left] = BLACK[:3]

    # ---- 五角星：与背景相反 ----
    star = star_mask_array(s, 0.30)
    # 星落在红区的部分填黑
    rgb[star & upper_left] = BLACK[:3]
    # 星落在黑区的部分填红
    rgb[star & (~upper_left)] = RED[:3]

    # ---- 裁进图标形状 ----
    alpha = np.where(shape, 255, 0).astype(np.uint8)
    out = np.dstack([rgb, alpha])

    return Image.fromarray(out, mode="RGBA").resize((size, size), Image.LANCZOS)


def render_background(size):
    """自适应图标背景层：红黑斜分，铺满整张画布。"""
    s = size * SS
    upper_left = np.array(build_upper_left_mask(s)) > 127
    rgb = np.zeros((s, s, 3), dtype=np.uint8)
    rgb[upper_left] = RED[:3]
    rgb[~upper_left] = BLACK[:3]
    alpha = np.full((s, s), 255, dtype=np.uint8)
    out = np.dstack([rgb, alpha])
    return Image.fromarray(out, mode="RGBA").resize((size, size), Image.LANCZOS)


def render_foreground(size):
    """自适应图标前景层：透明底 + 五角星（颜色与背景相反），缩到安全区。"""
    s = size * SS
    upper_left = np.array(build_upper_left_mask(s)) > 127
    star = star_mask_array(s, 0.30)

    rgb = np.zeros((s, s, 3), dtype=np.uint8)
    alpha = np.zeros((s, s), dtype=np.uint8)

    # 星在红区 -> 黑；星在黑区 -> 红
    red_zone_star = star & upper_left
    black_zone_star = star & (~upper_left)
    rgb[red_zone_star] = BLACK[:3]
    rgb[black_zone_star] = RED[:3]
    alpha[red_zone_star | black_zone_star] = 255

    out = np.dstack([rgb, alpha])
    img = Image.fromarray(out, mode="RGBA")

    # 缩进安全区（中心 66/108）
    scale = 66.0 / 108.0
    small = img.resize((int(s * scale), int(s * scale)), Image.LANCZOS)
    safe = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    off = int(round((s - s * scale) / 2))
    safe.paste(small, (off, off), small)
    return safe.resize((size, size), Image.LANCZOS)


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
    os.makedirs(os.path.join(OUT_DIR, "drawable"), exist_ok=True)
    bg_out = os.path.join(OUT_DIR, "drawable", "ic_launcher_background.png")
    render_background(432).save(bg_out, "PNG", optimize=True)

    fg_out = os.path.join(OUT_DIR, "drawable", "ic_launcher_foreground.png")
    render_foreground(432).save(fg_out, "PNG", optimize=True)

    print(f"\n自适应图标图层已更新：\n  {bg_out}\n  {fg_out}")
    print("图标生成完成。")


if __name__ == "__main__":
    main()
