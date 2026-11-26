#!/usr/bin/env python3
"""Build a Windows .ico from the app's PNG icon."""
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "web" / "assets" / "icon.png"
DST = ROOT / "build" / "daytally.ico"


def main():
    DST.parent.mkdir(parents=True, exist_ok=True)
    image = Image.open(SRC).convert("RGBA")
    image.save(DST, format="ICO", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print(DST)


if __name__ == "__main__":
    main()
