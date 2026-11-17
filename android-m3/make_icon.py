#!/usr/bin/env python3
"""Generate the launcher icon (a small PNG, no image library needed)."""
import os
import struct
import zlib

SIZES = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96, "mipmap-xxhdpi": 144}

BG = (14, 17, 22, 255)
RING = (255, 209, 102, 255)
CORE = (122, 196, 255, 255)


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
    cx = cy = (size - 1) / 2.0
    outer = size * 0.46
    inner = size * 0.30
    dot = size * 0.12
    rows = []
    for y in range(size):
        row = []
        for x in range(size):
            dx, dy = x - cx, y - cy
            dist = (dx * dx + dy * dy) ** 0.5
            if dist <= dot:
                color = CORE
            elif inner <= dist <= outer:
                color = RING
            else:
                color = BG
            row.extend(color)
        rows.append(row)
    return png(size, size, rows)


here = os.path.dirname(os.path.abspath(__file__))
for folder, size in SIZES.items():
    target = os.path.join(here, "res", folder)
    os.makedirs(target, exist_ok=True)
    with open(os.path.join(target, "ic_launcher.png"), "wb") as handle:
        handle.write(build(size))
print("icons written")
