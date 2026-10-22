#!/usr/bin/env python3
"""SSH Server MC logo generator.

Mark: a Minecraft grass block whose face is a terminal showing `>_`.
Everything except the rounded background is drawn on a coarse pixel grid, so
the mark stays crisp at every size.

    python assets/logo/generate.py

writes logo.svg (512x512, 32x32 cells) and logo-16.svg (favicon-sized, redrawn
by hand so it survives 16 device pixels). Rasterise with:

    magick -background none logo.svg    -depth 8 logo-512.png
    magick logo-512.png -filter point -resize 128x128 logo-128.png
    magick logo-512.png -filter point -resize  32x32  logo-32.png
    magick -background none logo-16.svg -filter point -resize 16x16 logo-16.png
    magick logo-16.png logo-32.png favicon.ico
"""

import os
import random

OUT = os.path.dirname(os.path.abspath(__file__))

PALETTE = {
    "b": "#14161A",  # background
    "S": "#0B0D12",  # drop shadow
    "G": "#74CE4A",  # grass highlight
    "g": "#5EBB2B",  # grass
    "d": "#4A9422",  # grass edge
    "T": "#866043",  # dirt
    "D": "#9C7A52",  # dirt light
    "t": "#6B4C35",  # dirt dark
    "F": "#2E3542",  # screen bezel
    "K": "#0A0C10",  # screen
    "P": "#3BE38A",  # prompt
}


def make_grid(size, block, screen, bezel, prompt, shadow, grass_rows, seed):
    """Build one pixel grid.

    block:  (x0, y0, x1, y1) inclusive bounds of the grass block.
    screen: (x0, y0, x1, y1) inclusive bounds of the terminal screen.
    bezel:  whether the screen has a 1-cell frame.
    prompt: (chevron cells, cursor cells) as sets of (x, y).
    shadow: drop-shadow offset in cells, 0 disables.
    """
    g = [["." for _ in range(size)] for _ in range(size)]

    def rect(x0, y0, x1, y1, ch):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                g[y][x] = ch

    bx0, by0, bx1, by1 = block
    if shadow:
        rect(bx0 + shadow, by0 + shadow, bx1 + shadow, by1 + shadow, "S")

    rect(bx0, by0, bx1, by1, "T")
    rect(bx0, by0, bx1, by0, "G")
    rect(bx0, by0 + 1, bx1, by0 + grass_rows - 2, "g")
    for i, x in enumerate(range(bx0, bx1 + 1)):
        g[by0 + grass_rows - 1][x] = "g" if i % 2 == 0 else "d"

    sx0, sy0, sx1, sy1 = screen
    rnd = random.Random(seed)
    for _ in range((bx1 - bx0) * 2):
        x = rnd.randint(bx0, bx1)
        y = rnd.randint(by0 + grass_rows + 1, by1)
        if sx0 - 1 <= x <= sx1 + 1 and sy0 - 1 <= y <= sy1 + 1:
            continue
        g[y][x] = "t" if rnd.random() < 0.65 else "D"

    rect(sx0, sy0, sx1, sy1, "F" if bezel else "K")
    if bezel:
        rect(sx0 + 1, sy0 + 1, sx1 - 1, sy1 - 1, "K")
    for x, y in prompt[0] | prompt[1]:
        g[y][x] = "P"

    return g


def to_svg(g, cell):
    size = len(g)
    px = size * cell
    runs = {}
    for y, row in enumerate(g):
        x = 0
        while x < size:
            ch = row[x]
            start = x
            while x < size and row[x] == ch:
                x += 1
            if PALETTE.get(ch):
                w = (x - start) * cell
                runs.setdefault(ch, []).append(
                    f"M{start * cell} {y * cell}h{w}v{cell}h-{w}z"
                )

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{px}" height="{px}" '
        f'viewBox="0 0 {px} {px}">',
        "  <title>SSH Server MC</title>",
        f'  <rect width="{px}" height="{px}" rx="{round(px * 0.22)}" fill="{PALETTE["b"]}"/>',
        '  <g shape-rendering="crispEdges">',
    ]
    for ch, d in runs.items():
        parts.append(f'    <path fill="{PALETTE[ch]}" d="{"".join(d)}"/>')
    parts += ["  </g>", "</svg>"]
    return "\n".join(parts) + "\n"


def chevron(rows, x0, thickness):
    """Right-pointing '>' made of `thickness`-wide diagonal runs."""
    cells = set()
    for i, y in enumerate(rows):
        x = x0 + min(i, len(rows) - 1 - i)
        for t in range(thickness):
            cells.add((x + t, y))
    return cells


def build_512():
    # 32x32 grid, 16px cells: 20-cell block, 14x11 framed screen.
    prompt = (
        chevron(range(15, 22), 11, 1),
        {(x, 21) for x in range(17, 21)},
    )
    grid = make_grid(
        size=32,
        block=(6, 6, 25, 25),
        screen=(9, 13, 22, 23),
        bezel=True,
        prompt=prompt,
        shadow=2,
        grass_rows=5,
        seed=7,
    )
    return to_svg(grid, 16)


def build_16():
    # 16x16 favicon: same idea, redrawn with a flat screen and a hairline
    # chevron so it still reads at 16 device pixels.
    prompt = (
        chevron(range(7, 12), 5, 1),
        {(x, 11) for x in range(9, 11)},
    )
    grid = make_grid(
        size=16,
        block=(2, 2, 13, 13),
        screen=(4, 7, 11, 12),
        bezel=False,
        prompt=prompt,
        shadow=0,
        grass_rows=4,
        seed=3,
    )
    return to_svg(grid, 16)


def main():
    for name, svg in (("logo.svg", build_512()), ("logo-16.svg", build_16())):
        with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
            f.write(svg)


if __name__ == "__main__":
    main()
