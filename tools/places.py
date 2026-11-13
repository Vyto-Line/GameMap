"""
Places for search: every business and place Overture Maps has in Greece and Cyprus (Meta and
Foursquare data, refreshed monthly), cut into 0.1° squares so the phone downloads only the
squares around it.

out/places/index.json            {"release": "...", "cells": {"379_237": 812, ...}}
out/places/<lat10>_<lon10>.tsv.gz  name \t alt name \t lat \t lon \t category \t address \t confidence
"""
import duckdb, gzip, json, os, urllib.request
from collections import defaultdict

con = duckdb.connect()
con.execute("INSTALL httpfs; LOAD httpfs; INSTALL spatial; LOAD spatial; SET s3_region='us-west-2';")
cat = json.load(urllib.request.urlopen("https://stac.overturemaps.org/catalog.json"))
rel = sorted(l["href"] for l in cat["links"] if l.get("rel") == "child")[-1].rstrip("/").split("/")[-2]
src = f"s3://overturemaps-us-west-2/release/{rel}/theme=places/type=place/*"
print("release", rel)

def clean(s):
    return (s or "").replace("\t", " ").replace("\n", " ").strip()

rows = con.execute(f"""
SELECT names.primary,
       coalesce(names.common['en'], names.common['el'], ''),
       ST_Y(geometry), ST_X(geometry),
       coalesce(basic_category, taxonomy.primary, ''),
       coalesce(addresses[1].freeform, ''), coalesce(addresses[1].locality, ''),
       confidence
FROM read_parquet('{src}', hive_partitioning=1)
WHERE bbox.xmin BETWEEN 19.0 AND 34.7 AND bbox.ymin BETWEEN 34.5 AND 42.0
  AND addresses[1].country IN ('GR', 'CY')
  AND confidence >= 0.4
  AND names.primary IS NOT NULL
  AND coalesce(operating_status, 'open') NOT IN ('permanently_closed', 'closed', 'temporarily_closed')
""").fetchall()
print("places", len(rows))

cells = defaultdict(list)
for name, alt, lat, lon, category, addr, loc, conf in rows:
    name = clean(name)
    if not name: continue
    alt = clean(alt)
    if alt == name: alt = ""
    a = clean(addr)
    l = clean(loc)
    if l and l.lower() not in a.lower(): a = f"{a}, {l}" if a else l
    key = f"{int(lat * 10 // 1)}_{int(lon * 10 // 1)}"
    cells[key].append(f"{name}\t{alt}\t{lat:.6f}\t{lon:.6f}\t{category}\t{a}\t{conf:.2f}")

os.makedirs("out/places", exist_ok=True)
for f in os.listdir("out/places"): os.remove(os.path.join("out/places", f))
total = 0
for key, lines in cells.items():
    lines.sort(key=lambda s: -float(s.rsplit("\t", 1)[1]))
    data = ("\n".join(lines) + "\n").encode()
    with gzip.open(f"out/places/{key}.tsv.gz", "wb", compresslevel=9) as g: g.write(data)
    total += os.path.getsize(f"out/places/{key}.tsv.gz")
json.dump({"release": rel, "cells": {k: len(v) for k, v in cells.items()}}, open("out/places/index.json", "w"), separators=(",", ":"))
big = sorted(cells.items(), key=lambda kv: -len(kv[1]))[:5]
print("cells", len(cells), "total MB", round(total / 1e6, 1), "biggest", [(k, len(v)) for k, v in big])
for k in ("378_237", "379_237"):
    for line in cells.get(k, []):
        if "crep" in line.lower(): print(k, line)
