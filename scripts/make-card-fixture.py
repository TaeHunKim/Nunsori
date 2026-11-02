"""Generate a fictional business-card photo (test-fixtures/images/card_en.jpg). Run: uv run --with pillow scripts/make-card-fixture.py"""
from PIL import Image, ImageDraw, ImageFont
import os, sys

out = sys.argv[1] if len(sys.argv) > 1 else "test-fixtures/images/card_en.jpg"
W, H = 1400, 800
img = Image.new("RGB", (W, H), (236, 232, 222))
d = ImageDraw.Draw(img)
# card body with a soft shadow
d.rounded_rectangle((120, 110, 1290, 720), 28, fill=(150, 150, 150))
d.rounded_rectangle((110, 100, 1280, 700), 28, fill=(252, 252, 250))
d.rectangle((110, 100, 150, 700), fill=(45, 90, 160))

def font(size, bold=False):
    for p in ("/System/Library/Fonts/Supplemental/Arial Bold.ttf" if bold else "/System/Library/Fonts/Supplemental/Arial.ttf",
              "/System/Library/Fonts/Helvetica.ttc"):
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()

d.text((200, 150), "Harbor Light Studio", font=font(44, True), fill=(45, 90, 160))
d.text((200, 260), "Daniel Moreau", font=font(78, True), fill=(20, 20, 20))
d.text((200, 360), "Senior Designer", font=font(40), fill=(80, 80, 80))
d.line((200, 440, 1200, 440), fill=(200, 200, 200), width=3)
d.text((200, 480), "Phone  +1 415 555 0142", font=font(42), fill=(30, 30, 30))
d.text((200, 545), "Email  daniel.moreau@harborlight.example", font=font(42), fill=(30, 30, 30))
d.text((200, 610), "12 Pier Street, San Francisco", font=font(34), fill=(90, 90, 90))
img = img.rotate(-2, expand=False, fillcolor=(236, 232, 222))
os.makedirs(os.path.dirname(out), exist_ok=True)
img.save(out, quality=92)
print(out)
