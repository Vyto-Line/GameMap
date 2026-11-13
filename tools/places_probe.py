"""Is a place in the open datasets? Overture Maps places around Glyfada (and a coverage count)."""
import duckdb, json, sys, urllib.request
con = duckdb.connect()
con.execute("INSTALL httpfs; LOAD httpfs; INSTALL spatial; LOAD spatial; SET s3_region='us-west-2';")
cat = json.load(urllib.request.urlopen("https://stac.overturemaps.org/catalog.json"))
rel = sorted([l["href"] for l in cat["links"] if l.get("rel") == "child"])
print("releases:", rel[-3:])
latest = rel[-1].rstrip("/").split("/")[-2]
print("latest:", latest)
src = f"s3://overturemaps-us-west-2/release/{latest}/theme=places/type=place/*"
print(con.execute(f"DESCRIBE SELECT * FROM read_parquet('{src}') LIMIT 1").fetchall())
q = f"""
SELECT names.primary, categories.primary, confidence, addresses[1].freeform, ST_Y(geometry), ST_X(geometry), sources[1].dataset, operating_status
FROM read_parquet('{src}', hive_partitioning=1)
WHERE bbox.xmin BETWEEN 23.70 AND 23.82 AND bbox.ymin BETWEEN 37.84 AND 37.90
  AND (names.primary ILIKE '%crep%' OR names.primary ILIKE '%κρεπ%' OR names.primary ILIKE '%mikel%' OR names.primary ILIKE '%ζάχαρη%')
"""
for r in con.execute(q).fetchall(): print(r)
n = con.execute(f"SELECT count(*), count(*) FILTER (WHERE confidence >= 0.5) FROM read_parquet('{src}') WHERE bbox.xmin BETWEEN 23.70 AND 23.82 AND bbox.ymin BETWEEN 37.84 AND 37.90").fetchall()
print("all places in the Glyfada box / confidence>=0.5:", n)
print("categories:", con.execute(f"SELECT categories.primary, count(*) c FROM read_parquet('{src}') WHERE bbox.xmin BETWEEN 23.70 AND 23.82 AND bbox.ymin BETWEEN 37.84 AND 37.90 GROUP BY 1 ORDER BY c DESC LIMIT 40").fetchall())
g = con.execute(f"SELECT count(*) FROM read_parquet('{src}') WHERE bbox.xmin BETWEEN 19.3 AND 29.7 AND bbox.ymin BETWEEN 34.8 AND 41.8 AND confidence >= 0.5").fetchall()
print("Greece box, confidence>=0.5:", g)
