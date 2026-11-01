"""Generate text-bearing fixture images (Korean/English). Run: uv run --with pillow scripts/make-text-fixtures.py"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

out = Path(__file__).resolve().parent.parent / "test-fixtures" / "images"
out.mkdir(parents=True, exist_ok=True)
KO = "/System/Library/Fonts/AppleSDGothicNeo.ttc"

def font(size, ko=True):
    try:
        return ImageFont.truetype(KO if ko else "/System/Library/Fonts/Helvetica.ttc", size)
    except OSError:
        return ImageFont.load_default()

def card(name, lines, bg=(255, 255, 255), fg=(20, 20, 20), size=(1080, 1400)):
    img = Image.new("RGB", size, bg)
    d = ImageDraw.Draw(img)
    y = 120
    for text, sz in lines:
        d.text((80, y), text, font=font(sz), fill=fg)
        y += int(sz * 1.9)
    img.save(out / name, quality=92)

card("receipt_ko.jpg", [("영수증", 90), ("스타벅스 강남점", 60), ("아메리카노  2잔   9,000원", 52),
                        ("치즈케이크  1개   6,500원", 52), ("합계  15,500원", 60), ("2026-10-31", 48)])
card("meeting_ko.jpg", [("회의록 2026", 90), ("프로젝트 일정 논의", 60), ("1. 출시일 12월 15일 확정", 50),
                        ("2. 디자인 리뷰 다음 주 화요일", 50), ("3. 담당자: 김민수", 50)], bg=(245, 248, 255))
card("invoice_en.jpg", [("INVOICE #20481", 90), ("Acme Corporation", 60), ("Wireless keyboard   $49.00", 50),
                        ("USB-C cable         $12.50", 50), ("Total due: $61.50", 60), ("Due date: 2026-11-30", 48)])
card("sign_en.jpg", [("WELCOME TO", 100), ("SEOUL STATION", 110), ("Platform 3  ->  Incheon Airport", 56)],
     bg=(20, 60, 140), fg=(255, 255, 255))
print("ok", sorted(p.name for p in out.iterdir()))
