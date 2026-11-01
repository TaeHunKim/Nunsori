"""PDF + Office fixtures with unique marker words. Output: test-fixtures/docs/ (not committed).
Run: uv run --with reportlab --with pillow --with python-docx --with python-pptx --with openpyxl scripts/make-office-fixtures.py"""
from pathlib import Path
out = Path(__file__).resolve().parent.parent / "test-fixtures" / "docs"
out.mkdir(parents=True, exist_ok=True)
KO_FONT = "/System/Library/Fonts/Supplemental/AppleGothic.ttf"

# ---- digital PDFs (reportlab)
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
pdfmetrics.registerFont(TTFont("KO", KO_FONT))

def pdf(name, pages, font="Helvetica"):
    c = canvas.Canvas(str(out / name))
    c.setTitle(name)
    for lines in pages:
        y = 780
        for i, line in enumerate(lines):
            c.setFont(font, 20 if i == 0 else 13); c.drawString(50, y, line); y -= 34 if i == 0 else 22
        c.showPage()
    c.save()

pdf("report_en.pdf", [
    ["Annual Report", "This report summarises the company performance for the year.", "Revenue and customer growth are described on the next pages."],
    ["Budget Forecast", "The marketing department requests additional funding for the spring campaign.", "The special marker for this page is tangerinequartz."],
    ["Appendix", "Detailed tables are available on request.", "Contact the finance team for more information."],
])
pdf("보고서_ko.pdf", [
    ["연간 매출 보고서", "올해 매출은 전년 대비 열두 퍼센트 증가했습니다.", "자세한 내용은 다음 쪽에서 설명합니다."],
    ["신제품 출시 계획", "신제품은 내년 봄에 출시될 예정이며 마케팅 예산이 필요합니다.", "이 쪽의 표식 단어는 청귤분기 입니다."],
], font="KO")

# ---- scanned PDF (images only, needs OCR)
from PIL import Image, ImageDraw, ImageFont
def page_img(lines):
    img = Image.new("RGB", (1240, 1754), "white"); d = ImageDraw.Draw(img)
    f = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 54); y = 200
    for l in lines: d.text((100, y), l, font=f, fill="black"); y += 90
    return img
a = page_img(["Scanned receipt", "Coffee beans 4500 won", "scanmarker77 thank you"])
b = page_img(["Second scanned page", "Delivery address Seoul", "scanmarker88"])
a.save(out / "scan.pdf", save_all=True, append_images=[b], resolution=150)

# ---- docx
from docx import Document
d = Document(); d.core_properties.title = "Launch Plan"
d.add_heading("Launch Plan", 1); d.add_paragraph("The product launch depends on design approval and server capacity.")
d.add_heading("Schedule", 2); d.add_paragraph("Marker word for this section: docxoriole.")
t = d.add_table(rows=2, cols=2); t.cell(0, 0).text = "Task"; t.cell(0, 1).text = "Owner"; t.cell(1, 0).text = "Design review"; t.cell(1, 1).text = "Hyeonwoo"
d.add_page_break(); d.add_heading("Risks", 2); d.add_paragraph("Capacity risk is the main concern.")
d.save(out / "plan.docx")

# ---- pptx
from pptx import Presentation
p = Presentation()
for title, body, note in [("Company Overview", "We build tools for photographers.", ""), ("Roadmap", "Q1 beta, Q2 launch, Q3 expansion.", "pptxnotewhale remember to mention pricing"), ("Thank you", "Questions?", "")]:
    s = p.slides.add_slide(p.slide_layouts[1]); s.shapes.title.text = title; s.placeholders[1].text = body
    if note: s.notes_slide.notes_text_frame.text = note
p.save(out / "deck.pptx")

# ---- xlsx
from openpyxl import Workbook
wb = Workbook(); ws = wb.active; ws.title = "Sales"
ws.append(["Region", "Product", "Units"])
for i in range(1, 80): ws.append([f"Region{i % 6}", f"Product{i}", i * 3])
ws.append(["Jeju", "Tangerine Jam", 999])
ws2 = wb.create_sheet("Staff"); ws2.append(["Name", "Team"]); ws2.append(["Quokka Kim", "Platform"]); ws2.append(["Jisoo Park", "Design"])
wb.save(out / "budget.xlsx")
print(sorted(p.name for p in out.iterdir()))
