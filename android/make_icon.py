#!/usr/bin/env python3
"""Draw the launcher icon: a yellow ring with a blue core on a dark card.

Every pixel is supersampled before it is written, so the circles come out
smooth instead of stair-stepped. Pure Python on purpose: the build must not
depend on Pillow.
"""
import os
import struct
import zlib

SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

BG = (14, 17, 22, 255)      # #0E1116
RING = (255, 209, 102, 255)  # #FFD166
CORE = (122, 196, 255, 255)  # #7AC4FF

OUTER = 0.458  # measured from the shipped icon (66/144)
INNER = 0.299
DOT = 0.118
SS = 6         # sub-samples per axis


def png(width, height, pixels):
    raw = b"".join(b"\x00" + bytes(row) for row in pixels)

    def chunk(tag, data):
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def build(size):
    """Average SS*SS sub-pixels per output pixel so edges fade instead of jag."""
    cx = cy = size / 2.0
    outer = size * OUTER
    inner = size * INNER
    dot = size * DOT
    step = 1.0 / SS
    offset = step / 2.0
    rows = []
    for y in range(size):
        row = bytearray()
        for x in range(size):
            r = g = b = a = 0
            for sy in range(SS):
                py = y + offset + sy * step - cy
                for sx in range(SS):
                    px = x + offset + sx * step - cx
                    dist2 = px * px + py * py
                    if dist2 <= dot * dot:
                        color = CORE
                    elif inner * inner <= dist2 <= outer * outer:
                        color = RING
                    else:
                        color = BG
                    r += color[0]
                    g += color[1]
                    b += color[2]
                    a += color[3]
            n = SS * SS
            row.extend((r // n, g // n, b // n, a // n))
        rows.append(row)
    return png(size, size, rows)


here = os.path.dirname(os.path.abspath(__file__))
for folder, size in SIZES.items():
    target = os.path.join(here, "res", folder)
    os.makedirs(target, exist_ok=True)
    with open(os.path.join(target, "ic_launcher.png"), "wb") as handle:
        handle.write(build(size))
    print(f"{folder}/ic_launcher.png {size}px")
