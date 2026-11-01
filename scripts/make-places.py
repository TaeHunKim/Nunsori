#!/usr/bin/env python3
"""Builds app/src/main/assets/places.bin from GeoNames dumps (CC BY 4.0, https://www.geonames.org).

Rows: lat, lon, population, city_en, city_ko, country_en, country_ko, aliases (tab separated, gzip).
Aliases are extra searchable words for the country: abbreviations, continent and region names in English and Korean.
Downloads about 210 MB into the work directory (default build/places-src, not committed); only run when refreshing the data.
Usage: python3 scripts/make-places.py [workdir]
"""
import csv, gzip, io, os, subprocess, sys, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORK = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "build", "places-src")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "places.bin")
BASE = "https://download.geonames.org/export/dump/"
os.makedirs(WORK, exist_ok=True)

def fetch(name):
    path = os.path.join(WORK, name)
    if not os.path.exists(path):
        print("downloading", name, flush=True)
        subprocess.run(["curl", "-fL", "-o", path + ".part", BASE + name], check=True)
        os.replace(path + ".part", path)
    return path

cities = []  # (geonameid, name, lat, lon, cc, pop)
with zipfile.ZipFile(fetch("cities15000.zip")) as z, z.open("cities15000.txt") as f:
    for line in io.TextIOWrapper(f, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        cities.append((p[0], p[1], float(p[4]), float(p[5]), p[8], int(p[14] or 0)))

CONTINENTS = {
    "AS": "Asia 아시아", "EU": "Europe 유럽", "AF": "Africa 아프리카", "NA": "North America 북미 북아메리카",
    "SA": "South America 남미 남아메리카", "OC": "Oceania 오세아니아", "AN": "Antarctica 남극",
}
# Regions people actually say ("Middle East trip"); ISO 3166 country codes.
REGIONS = {
    "Middle East 중동": "AE SA QA KW BH OM YE IQ IR IL JO LB SY TR PS",
    "East Asia 동아시아": "KR JP CN TW HK MO MN KP",
    "Southeast Asia 동남아시아 동남아": "TH VN SG MY ID PH KH LA MM BN TL",
    "South Asia 남아시아": "IN PK BD LK NP BT MV",
    "Central Asia 중앙아시아": "KZ UZ TM KG TJ",
    "Nordic Scandinavia 북유럽 스칸디나비아": "NO SE DK FI IS",
    "Western Europe 서유럽": "FR DE NL BE LU AT CH GB IE",
    "Southern Europe 남유럽": "ES PT IT GR MT HR",
    "Eastern Europe 동유럽": "PL CZ SK HU RO BG UA RS SI",
    "Caribbean 카리브해": "CU JM BS DO HT BB TT",
}
# Short names for single countries. ISO codes are not used: "IN", "ARE", "IT" are ordinary English words.
ABBREVIATIONS = {
    "AE": "UAE Emirates", "US": "USA America", "GB": "UK Britain England", "KR": "Korea 한국",
    "NZ": "Kiwi", "CZ": "Czechia",
}

countries = {}  # cc -> (geonameid, english name, continent code)
with open(fetch("countryInfo.txt"), encoding="utf-8") as f:
    for line in f:
        if line.startswith("#") or not line.strip():
            continue
        p = line.rstrip("\n").split("\t")
        countries[p[0]] = (p[16], p[4], p[8])

wanted = {c[0] for c in cities} | {v[0] for v in countries.values()}
ko = {}  # geonameid -> (preferred?, name)
with zipfile.ZipFile(fetch("alternateNamesV2.zip")) as z, z.open("alternateNamesV2.txt") as f:
    for line in io.TextIOWrapper(f, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) < 5 or p[2] != "ko" or p[1] not in wanted:
            continue
        if len(p) > 7 and p[7] == "1":  # historic name
            continue
        pref = p[4] == "1"
        if p[1] not in ko or (pref and not ko[p[1]][0]):
            ko[p[1]] = (pref, p[3])

rows = []
for gid, name, lat, lon, cc, pop in cities:
    cgid, cname, cont = countries.get(cc, ("", cc, ""))
    words = [CONTINENTS.get(cont, ""), ABBREVIATIONS.get(cc, "")] + [r for r, codes in REGIONS.items() if cc in codes.split()]
    aliases = " ".join(w for w in words if w)
    rows.append((lat, lon, pop, name, ko.get(gid, (0, ""))[1], cname, ko.get(cgid, (0, ""))[1], aliases))
rows.sort()
with gzip.open(OUT, "wt", encoding="utf-8", newline="\n", compresslevel=9) as out:
    w = csv.writer(out, delimiter="\t", lineterminator="\n", quoting=csv.QUOTE_NONE, escapechar="\\")
    for r in rows:
        w.writerow(r)
print(len(rows), "places ->", OUT, os.path.getsize(OUT), "bytes")
