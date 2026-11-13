"""Greek toll stations with their car price, for the route options and the "toll ahead" warning.

Positions come from OpenStreetMap (barrier=toll_booth); prices (2026, category 2: cars) from the
operators' published lists, matched to stations by place name. Writes out/tolls/GR.json:
[{"n": "Ελευσίνα", "p": 2.5, "lat": .., "lon": ..}, ...]   (unmatched booths are left out)
"""
import json, os, sys, time, unicodedata, urllib.parse, urllib.request

ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]

# place-name stem -> (main plaza price, side/ramp price); None = no such kind
PRICES = {
    # Νέα Οδός (A1 Athens-Lamia, A5 Ionia Odos)
    "αγγελοκαστρ": (4.20, None), "αφιδν": (3.90, None), "θηβ": (4.55, 1.80), "κλοκοβ": (3.55, None),
    "κουβαρ": (3.55, 1.95), "μενιδ": (3.55, None), "τεροβ": (3.70, None), "τραγαν": (4.55, 3.65),
    "αρτα": (None, 0.90), "αρτας": (None, 0.90), "γαβρολιμν": (None, 2.50), "γοργομυλ": (None, 1.20),
    "καπανδριτ": (None, 2.35), "μαλακασα": (None, 1.65), "μεσολογγ": (None, 1.50), "οινοφυτ": (None, 0.80),
    # Ολυμπία Οδός (A8)
    "ελαιων": (3.80, None), "αιγι": (3.80, None), "ελευσιν": (2.50, None), "ισθμ": (2.10, None),
    "καμιν": (3.60, None), "κιατ": (2.70, 1.00), "πυργ": (2.10, None), "ριου": (2.70, None), "ριο": (2.70, None),
    "θεοδωρ": (None, 0.60), "ακρατ": (None, 2.00), "αμαλιαδ": (None, 1.30), "δερβεν": (None, 1.60),
    "δρεπαν": (None, 2.00), "ζευγολατ": (None, 0.90), "καλαβρυτ": (None, 2.80), "αχαι": (None, 2.20),
    "περαμ": (None, 1.70), "παχη": (None, 1.10), "παχης": (None, 1.10),
    # Εγνατία Οδός (A2 and branches)
    "αναληψ": (2.90, None), "αρδανι": (2.00, None), "αλεξανδρουπ": (2.00, None), "ασπροβαλτ": (1.40, 0.45),
    "ευζων": (2.95, None), "ωραιοκαστρ": (0.65, None), "ιασμ": (2.25, 0.75), "ξανθ": (2.25, None),
    "ιεροπηγ": (2.30, None), "καβαλ": (2.30, None), "μαλακασι": (2.35, None), "μετσοβ": (2.35, None),
    "μαλγαρ": (1.15, None), "μεστ": (2.75, 1.25), "κομοτην": (2.75, 0.55), "μουσθεν": (2.65, 1.20),
    "παμβωτ": (1.95, None), "ιωαννιν": (1.95, None), "πολυμυλ": (2.35, None), "βεροι": (2.35, None),
    "προμαχων": (2.35, None), "σιατιστ": (2.40, 1.20), "κοζαν": (2.40, None), "στρυμον": (2.35, None),
    "τυρια": (3.35, None), "ηγουμενιτσ": (3.35, None), "ανδρε": (None, 0.55), "χωματ": (None, 0.50),
    "βαγιοχωρ": (None, 0.95), "βαφεικ": (None, 0.45), "γαληψ": (None, 0.45), "ορφαν": (None, 0.45),
    "ελευθερουπ": (None, 0.85), "καλαμια": (None, 0.60), "λαγκαδ": (None, 0.35), "πολυκαστρ": (None, 0.0),
    "προφητ": (None, 1.40),
    # Αυτοκινητόδρομος Αιγαίου (A1 Lamia–Thessaloniki)
    "κλειδ": (2.60, None), "αιγινι": (2.60, None), "λεπτοκαρ": (3.80, 2.10), "μακρυχωρ": (3.00, 1.30),
    "μοσχοχωρ": (4.70, None), "κιλελερ": (4.70, 2.80), "πελασγ": (4.10, None), "βελεστιν": (None, 1.40),
    "γλυφ": (None, 3.00), "γυρτων": (None, 0.80), "ευαγγελισμ": (None, 1.40), "μοναστηρ": (None, 2.20),
    "πλαταμων": (None, 0.80), "πυργετ": (None, 2.00),
    # Κεντρική Οδός (A1 Skarfeia–Raches, E65)
    "τριαδ": (2.10, None), "λιανοκλαδ": (2.05, None), "μαυρομαντηλ": (2.25, None), "σοφαδ": (3.70, None),
    "τρικαλ": (3.25, 0.90), "μαριν": (None, 1.40), "αναβρ": (None, 2.15), "καλαμπακ": (None, 0.95),
    "μπραλ": (None, 0.70), "θερμοπυλ": (None, 0.70), "μωλο": (None, 0.40), "προαστι": (None, 1.25),
    "στυλιδ": (None, 0.75), "καραβομυλ": (None, 0.75),
    # Μορέας (A7)
    "βελιγοστ": (1.45, None), "μαναρ": (2.30, None), "καλαματ": (2.25, None), "νεσταν": (2.80, None),
    "αργου": (2.80, None), "πετριν": (3.45, None), "τριπολ": (3.45, None), "σπαθοβουν": (2.95, None),
    "αρφαρ": (None, 0.90), "θουρι": (None, 0.55), "παραδεισ": (None, 0.90),
    # bridge and tunnel
    "αντιρρι": (15.90, None), "ακτιου": (3.00, None), "πρεβεζ": (3.00, None),
}
ATTIKI = 2.55  # Αττική Οδός: one price at every entry


def strip(s):
    s = unicodedata.normalize("NFD", s.lower())
    return "".join(c for c in s if unicodedata.category(c) != "Mn").replace("ς", "σ")


GR2LAT = [("ου", "ou"), ("αι", "e"), ("ει", "i"), ("οι", "i"), ("αυ", "av"), ("ευ", "ev"), ("γγ", "ng"), ("γκ", "g"),
          ("μπ", "b"), ("ντ", "d"), ("θ", "th"), ("χ", "ch"), ("ψ", "ps"), ("ξ", "x")]
ONE = dict(zip("αβγδεζηικλμνοπρστυφω", "avgdeziiklmnoprstifo"))


def latin(s):
    s = strip(s)
    for a, b in GR2LAT:
        s = s.replace(a, b)
    return "".join(ONE.get(c, c) for c in s).replace("y", "i").replace("h", "")


STEMS = [(latin(k), v) for k, v in sorted(PRICES.items(), key=lambda kv: -len(kv[0]))]


def query():
    q = """[out:json][timeout:240];
area["ISO3166-1"="GR"][admin_level=2]->.a;
(node["barrier"="toll_booth"](area.a);)->.n;
.n out;
way(bn.n)["highway"];
out tags qt;
(way["barrier"="toll_booth"](area.a);); out center tags;"""
    data = urllib.parse.urlencode({"data": q}).encode()
    for attempt in range(6):
        for ep in ENDPOINTS:
            try:
                req = urllib.request.Request(ep, data=data, headers={"User-Agent": "GameRoute toll export (github.com/Vyto-Line/GameRoute)"})
                with urllib.request.urlopen(req, timeout=300) as r:
                    body = r.read().decode()
                if body.lstrip().startswith("{"):
                    return json.loads(body)
            except Exception as e:
                print(ep, "failed:", e, file=sys.stderr)
        time.sleep(30 * (attempt + 1))
    return None


def main():
    d = query()
    if d is None:
        print("Overpass unavailable; keeping the old file"); return
    els = d["elements"]
    # which nodes sit on a ramp (motorway_link / trunk_link): those are side stations
    # (ways come after nodes; "out tags qt" has no node list, so ask once more cheaply below)
    booths = [e for e in els if e["type"] == "node"] + [dict(e, lat=e["center"]["lat"], lon=e["center"]["lon"]) for e in els if e["type"] == "way" and "center" in e]
    out, missed = [], []
    for b in booths:
        t = b.get("tags", {})
        names = " ".join(t.get(k, "") for k in ("name", "name:el", "name:en", "operator", "description"))
        ln = latin(names)
        ramp = any(w in ln for w in ("plevr", "lateral", "ramp", "side"))
        price, label = None, None
        if "attik" in ln and "odo" in ln:
            price, label = ATTIKI, "Αττική Οδός"
        else:
            for stem, (main_p, side_p) in STEMS:
                if stem and stem in ln:
                    price = (side_p if ramp and side_p is not None else main_p if main_p is not None else side_p)
                    label = t.get("name:el") or t.get("name") or stem
                    break
        if price is None:
            missed.append(names.strip() or f"(no name) {b['lat']:.4f},{b['lon']:.4f}")
            continue
        out.append({"n": label, "p": price, "lat": round(b["lat"], 6), "lon": round(b["lon"], 6)})
    os.makedirs("out/tolls", exist_ok=True)
    json.dump(out, open("out/tolls/GR.json", "w"), ensure_ascii=False, separators=(",", ":"))
    print(f"{len(booths)} booths, {len(out)} priced, {len(missed)} without a match")
    from collections import Counter
    for n, c in Counter(missed).most_common(80):
        print(f"  missed x{c}: {n}")


if __name__ == "__main__":
    main()
