#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成应用图标 PNG（纯标准库，无第三方依赖）。

设计：青绿渐变圆角方块 + 三条逐渐变短的白色横条。
含义就是产品本身——候选栏的英文标签越打越淡，最后消失。
"""
import os
import struct
import zlib

OUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "res",
)
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

C1 = (0x17, 0xB9, 0x8A)
C2 = (0x0B, 0x85, 0x63)
# (x0, x1, y_center, height, alpha)
BARS = [
    (0.215, 0.785, 0.345, 0.062, 0.97),
    (0.215, 0.660, 0.485, 0.062, 0.74),
    (0.215, 0.555, 0.625, 0.062, 0.50),
]
CORNER = 0.235
SS = 4  # 超采样倍数


def in_rrect(x, y, x0, y0, x1, y1, r):
    if x < x0 or x > x1 or y < y0 or y > y1:
        return False
    cx = min(max(x, x0 + r), x1 - r)
    cy = min(max(y, y0 + r), y1 - r)
    dx, dy = x - cx, y - cy
    return dx * dx + dy * dy <= r * r


def sample(nx, ny):
    """归一化坐标 -> (r,g,b,a)"""
    if not in_rrect(nx, ny, 0.0, 0.0, 1.0, 1.0, CORNER):
        return (0, 0, 0, 0)
    t = (nx + ny) * 0.5
    r = int(C1[0] + (C2[0] - C1[0]) * t)
    g = int(C1[1] + (C2[1] - C1[1]) * t)
    b = int(C1[2] + (C2[2] - C1[2]) * t)
    a = 255
    for x0, x1, yc, h, alpha in BARS:
        y0, y1 = yc - h / 2, yc + h / 2
        if in_rrect(nx, ny, x0, y0, x1, y1, h / 2):
            aa = alpha
            r = int(r * (1 - aa) + 255 * aa)
            g = int(g * (1 - aa) + 255 * aa)
            b = int(b * (1 - aa) + 255 * aa)
    return (r, g, b, a)


def render(size):
    n = size * SS
    px = bytearray(size * size * 4)
    # 先算超采样网格
    grid = []
    for j in range(n):
        row = []
        ny = (j + 0.5) / n
        for i in range(n):
            row.append(sample((i + 0.5) / n, ny))
        grid.append(row)
    inv = 1.0 / (SS * SS)
    for y in range(size):
        for x in range(size):
            sr = sg = sb = sa = 0
            for j in range(y * SS, y * SS + SS):
                row = grid[j]
                for i in range(x * SS, x * SS + SS):
                    r, g, b, a = row[i]
                    if a:
                        sr += r * a
                        sg += g * a
                        sb += b * a
                        sa += a
            o = (y * size + x) * 4
            if sa == 0:
                px[o:o + 4] = b"\x00\x00\x00\x00"
            else:
                px[o + 0] = min(255, int(sr / sa + 0.5))
                px[o + 1] = min(255, int(sg / sa + 0.5))
                px[o + 2] = min(255, int(sb / sa + 0.5))
                px[o + 3] = min(255, int(sa * inv + 0.5))
    return px


def write_png(path, size, rgba):
    raw = bytearray()
    stride = size * 4
    for y in range(size):
        raw.append(0)
        raw += rgba[y * stride:(y + 1) * stride]

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data +
                struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    blob = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) +
            chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(blob)


def main():
    for bucket, size in SIZES.items():
        d = os.path.join(OUT, "mipmap-" + bucket)
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, "ic_launcher.png")
        write_png(p, size, render(size))
        print("wrote", p, size)


if __name__ == "__main__":
    main()
