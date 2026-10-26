#!/usr/bin/env python3
"""Builds the README images in docs/screenshots/ from the Compose renders.

Usage:
  ./scripts/lib/screenshots.sh              # renders app/build/screenshots/*.png (no emulator)
  python3 scripts/lib/readme-screenshots.py # frames, crops and lays them out into docs/screenshots/

Needs Pillow (python3 -m pip install pillow). Images are saved as full-colour RGBA PNGs:
palette quantization bands the orb glows and breaks the transparent corners.

The README embeds every image with plain Markdown `![](...)`, not HTML `<img>`: IDE and other
local previews often skip relative paths in raw HTML. Markdown can't size or tile images, so each
row of phone screenshots is one ready-made picture that scales to the page width.
"""
import os
import re
import sys

try:
    from PIL import Image, ImageDraw, ImageFilter
except ImportError:
    sys.exit("Pillow is missing: python3 -m pip install pillow")

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app", "build", "screenshots")
OUT = os.path.join(ROOT, "docs", "screenshots")
ICON = os.path.join(ROOT, "resources", "icons", "icon.svg")

# Thin frame that stays visible on both GitHub light and dark backgrounds.
BORDER = (52, 58, 66, 255)

# docs/screenshots/<name>: renders shown side by side, as (render name, crop height of the source or None).
ROWS = {
    "hero.png": [
        ("Home_Auto_Connected_Night", None),
        ("Home_Server_Connected_Night", None),
        ("Home_Routes_Connected_Night", None),
    ],
    "gallery-1.png": [
        ("Servers_Connected_Night", None),
        ("Routes_Library_Day", None),
        ("Home_Auto_Testing_Night", None),
    ],
    "gallery-2.png": [
        ("Routes_AddPaste_Night", None),
        ("AppRouting_Selected_Night", None),
        ("Terminal_Active_Night", None),
    ],
    "gallery-3.png": [
        ("Activity_Server_Night", None),
        ("Settings_Idle_Phone_Night", None),
        ("Appearance_CustomSaved_Night", 1688),
    ],
}
PHONE_WIDTH = 520
PHONE_GAP = 48
SHADOW = 28

TABLET = ("tablet.png", "Home_Tablet_Server_Night", 1600)

# The launcher icon (512 px) at the size the README header shows it.
LOGO = ("logo.svg", 112)


def load(name, crop_height=None):
    path = os.path.join(SRC, name + ".png")
    if not os.path.exists(path):
        sys.exit(f"{path} is missing: run ./scripts/lib/screenshots.sh first")
    img = Image.open(path).convert("RGBA")
    if crop_height and img.height > crop_height:
        img = img.crop((0, 0, img.width, crop_height))
    return img


def resize_to_width(img, width):
    if img.width == width:
        return img
    return img.resize((width, round(img.height * width / img.width)), Image.LANCZOS)


def rounded_mask(size, radius, outline_width=0):
    """Anti-aliased rounded-rectangle mask, filled or as an outline."""
    w, h = size
    scale = 4
    mask = Image.new("L", (w * scale, h * scale), 0)
    box = (0, 0, w * scale - 1, h * scale - 1)
    if outline_width:
        ImageDraw.Draw(mask).rounded_rectangle(box, radius * scale, outline=255, width=outline_width * scale)
    else:
        ImageDraw.Draw(mask).rounded_rectangle(box, radius * scale, fill=255)
    return mask.resize(size, Image.LANCZOS)


def framed(img, radius, border=2):
    """Rounded corners (transparent outside) with a thin border."""
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), rounded_mask(img.size, radius))
    out.paste(Image.new("RGBA", img.size, BORDER), (0, 0), rounded_mask(img.size, radius, border))
    return out


def save(img, name):
    path = os.path.join(OUT, name)
    img.save(path, optimize=True)
    report(path, f"{img.width}x{img.height}")


def report(path, size):
    print(f"{os.path.relpath(path, ROOT):34s} {size} {os.path.getsize(path) // 1024} KiB")


def phone_row(renders):
    """Phones side by side on a transparent background, with a soft shadow."""
    phones = [framed(resize_to_width(load(name, crop), PHONE_WIDTH), 44) for name, crop in renders]
    pad = SHADOW * 2
    width = len(phones) * PHONE_WIDTH + (len(phones) - 1) * PHONE_GAP + 2 * pad
    row = Image.new("RGBA", (width, max(p.height for p in phones) + 2 * pad), (0, 0, 0, 0))
    shadow = Image.new("RGBA", row.size, (0, 0, 0, 0))
    for i, phone in enumerate(phones):
        x = pad + i * (PHONE_WIDTH + PHONE_GAP)
        shadow.paste(Image.new("RGBA", phone.size, (0, 0, 0, 60)), (x, pad + 10), phone.getchannel("A"))
    row = Image.alpha_composite(row, shadow.filter(ImageFilter.GaussianBlur(SHADOW // 2)))
    for i, phone in enumerate(phones):
        row.alpha_composite(phone, (pad + i * (PHONE_WIDTH + PHONE_GAP), pad))
    return row


def build_logo():
    name, size = LOGO
    with open(ICON, encoding="utf-8") as f:
        svg = f.read()
    root_tag = re.search(r"<svg\b[^>]*>", svg).group(0)
    sized = re.sub(r'\bwidth="[^"]*"', f'width="{size}"', root_tag, count=1)
    sized = re.sub(r'\bheight="[^"]*"', f'height="{size}"', sized, count=1)
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(svg.replace(root_tag, sized, 1))
    report(path, f"{size}x{size}")


def main():
    os.makedirs(OUT, exist_ok=True)
    build_logo()
    for name, renders in ROWS.items():
        save(phone_row(renders), name)
    name, render, width = TABLET
    save(framed(resize_to_width(load(render), width), 36), name)


if __name__ == "__main__":
    main()
