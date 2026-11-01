#!/usr/bin/env python3
"""Creates two photos with EXIF GPS positions (Dubai and Seoul) for the place-search E2E.

Run: uv run --with pillow --with piexif scripts/make-geo-fixtures.py
Output (not committed): test-fixtures/images/geo/IMG_7001.jpg (Dubai), IMG_7002.jpg (Seoul).
The file names are neutral on purpose so a match can only come from the place metadata.
"""
import os
import piexif
from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "test-fixtures", "images", "geo")
os.makedirs(OUT, exist_ok=True)


def dms(v):
    v = abs(v)
    d = int(v); m = int((v - d) * 60); s = round(((v - d) * 60 - m) * 60 * 100)
    return ((d, 1), (m, 1), (s, 100))


def photo(name, lat, lon, sky, ground, towers):
    img = Image.new("RGB", (640, 480), sky)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 360, 640, 480], fill=ground)
    for x, h in towers:
        d.rectangle([x, 360 - h, x + 40, 360], fill=(70, 80, 100))
    exif = piexif.dump({
        # Keep the EXIF block minimal: with other tags in this piexif layout, Android's ExifInterface drops the GPS ones.
        "0th": {},
        "Exif": {},
        "1st": {}, "thumbnail": None,
        "GPS": {
            piexif.GPSIFD.GPSVersionID: (2, 2, 0, 0),
            piexif.GPSIFD.GPSLatitudeRef: b"N" if lat >= 0 else b"S", piexif.GPSIFD.GPSLatitude: dms(lat),
            piexif.GPSIFD.GPSLongitudeRef: b"E" if lon >= 0 else b"W", piexif.GPSIFD.GPSLongitude: dms(lon),
        },
    })
    img.save(os.path.join(OUT, name), "JPEG", exif=exif)


photo("IMG_7001.jpg", 25.2048, 55.2708, (240, 170, 90), (200, 170, 110), [(60, 220), (140, 300), (220, 180), (300, 260), (380, 200)])
photo("IMG_7002.jpg", 37.5665, 126.9780, (150, 190, 230), (90, 120, 90), [(100, 120), (200, 90), (330, 140)])
print("wrote", OUT)
