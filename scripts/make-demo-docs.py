"""Demo documents for the README recordings. Run: uv run --with reportlab --with python-docx scripts/make-demo-docs.py OUTDIR"""
import sys, os
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas
from docx import Document

out = sys.argv[1]; os.makedirs(out, exist_ok=True)

c = canvas.Canvas(os.path.join(out, "Lisbon_trip_plan.pdf"), pagesize=A4)
y = 800
c.setFont("Helvetica-Bold", 20); c.drawString(60, y, "Lisbon trip plan - May 12 to 16"); y -= 40
c.setFont("Helvetica", 12)
for line in ["Flight TP 237 lands at 09:40, then take the metro to the hotel in Alfama.",
             "Day 1: walk through Alfama, visit the castle and listen to fado in the evening.",
             "Day 2: tram 28, pasteis de nata in Belem, sunset at the river.",
             "Day 3: day trip by train to Sintra to see the palaces.",
             "Day 4: seafood dinner and the flea market before the flight back.",
             "Hotel booking reference: LX-48213. Remember to buy a Viva Viagem transit card."]:
    c.drawString(60, y, line); y -= 24
c.save()

d = Document()
d.add_heading("Team onboarding guide", 1)
d.add_paragraph("Welcome to the design team. In your first week you will set up your laptop, get access to the shared drive and meet your mentor.")
d.add_heading("Expense reports", 2)
d.add_paragraph("Submit receipts within 30 days. Taxi rides and client lunches are reimbursed; alcohol is not.")
d.add_heading("Working hours", 2)
d.add_paragraph("Core hours are 10:00 to 16:00. Remote work is allowed on Wednesdays and Fridays.")
d.save(os.path.join(out, "Onboarding_guide.docx"))
