#!/usr/bin/env python3
"""Generate the PWA launcher icons. Requires Pillow."""

from PIL import Image, ImageDraw

BG = (11, 13, 16)
ACC = (232, 176, 70)
DIM = (60, 50, 30)


def icon(size, maskable=False):
    img = Image.new('RGBA', (size, size), BG + (255,))
    d = ImageDraw.Draw(img)
    c = size / 2
    # maskable icons must keep their artwork inside the middle 80%
    r = size * (0.30 if maskable else 0.38)
    w = max(2, int(size * 0.045))
    d.ellipse([c - r, c - r, c + r, c + r], outline=ACC, width=w)
    t = r * 0.46
    d.polygon([(c - t * 0.55, c - t), (c - t * 0.55, c + t), (c + t * 0.95, c)], fill=ACC)
    for dx, dy in ((0, -1), (1, 0), (0, 1), (-1, 0)):
        x, y = c + dx * (r + w * 1.9), c + dy * (r + w * 1.9)
        m = w * 0.75
        d.ellipse([x - m, y - m, x + m, y + m], fill=DIM)
    return img


if __name__ == '__main__':
    icon(192).save('icon-192.png')
    icon(512).save('icon-512.png')
    icon(512, maskable=True).save('icon-maskable-512.png')
    print('icons written')
