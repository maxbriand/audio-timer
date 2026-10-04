#!/usr/bin/env python3
"""Generate the source images @capacitor/assets expands into Android launcher icons.

Reuses the drawing code from make-icons.py so the APK's launcher icon is the same mark as
the PWA's, just at the sizes and in the split (foreground / background) that Android's
adaptive icons need. Requires Pillow.
"""

import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

BG = (11, 13, 16)
ACC = (232, 176, 70)
DIM = (60, 50, 30)

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'assets')


def artwork(size, scale, transparent):
    """The ring, play triangle and four ticks, centred on `size`.

    `scale` is the ring radius as a fraction of the canvas. Adaptive-icon foregrounds are
    drawn small because Android crops the outer ~28% of the layer to whatever mask the
    launcher applies.
    """
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0) if transparent else BG + (255,))
    d = ImageDraw.Draw(img)
    c = size / 2
    r = size * scale
    w = max(2, int(size * 0.045 * (scale / 0.38)))
    d.ellipse([c - r, c - r, c + r, c + r], outline=ACC, width=w)
    t = r * 0.46
    d.polygon([(c - t * 0.55, c - t), (c - t * 0.55, c + t), (c + t * 0.95, c)], fill=ACC)
    for dx, dy in ((0, -1), (1, 0), (0, 1), (-1, 0)):
        x, y = c + dx * (r + w * 1.9), c + dy * (r + w * 1.9)
        m = w * 0.75
        d.ellipse([x - m, y - m, x + m, y + m], fill=DIM)
    return img


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)

    # Legacy square icon and the splash both carry the full-bleed mark.
    artwork(1024, 0.38, transparent=False).save(os.path.join(OUT, 'icon.png'))

    # Adaptive icon: the mark alone on transparency, kept well inside the safe zone, over a
    # flat background layer in the app's own near-black.
    artwork(1024, 0.26, transparent=True).save(os.path.join(OUT, 'icon-foreground.png'))
    Image.new('RGBA', (1024, 1024), BG + (255,)).save(os.path.join(OUT, 'icon-background.png'))

    # Splash screens are plain: the mark small and centred on the app background.
    for name, size in (('splash.png', (2732, 2732)), ('splash-dark.png', (2732, 2732))):
        sp = Image.new('RGBA', size, BG + (255,))
        mark = artwork(600, 0.38, transparent=True)
        sp.paste(mark, ((size[0] - 600) // 2, (size[1] - 600) // 2), mark)
        sp.save(os.path.join(OUT, name))

    print('android icon sources written to assets/')
