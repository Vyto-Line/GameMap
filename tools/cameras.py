"""Speed / red-light / average-speed cameras per country from OpenStreetMap (Overpass).

Writes out/cameras/<CC>.json as a compact list the app downloads once a week:
[{"lat":..,"lon":..,"k":"speed"|"redlight"|"average","s":50,"d":120}, ...]
Overpass is slow and often busy, so this runs in CI (with retries), not on phones.
"""
import json, os, sys, time, urllib.parse, urllib.request

ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]

KIND = {"maxspeed": "speed", "average_speed": "average", "traffic_signals": "redlight"}


def query(cc):
    q = f"""[out:json][timeout:240];
area["ISO3166-1"="{cc}"][admin_level=2]->.a;
(node["highway"="speed_camera"](area.a); node["enforcement"](area.a);)->.n;
relation["type"="enforcement"](area.a)->.r;
.n out;
.r out body;
node(r.r);
out;"""
    data = urllib.parse.urlencode({"data": q}).encode()
    for attempt in range(6):
        for ep in ENDPOINTS:
            try:
                req = urllib.request.Request(ep, data=data, headers={"User-Agent": "GameMap camera export (github.com/Vyto-Line/GameMap)"})
                with urllib.request.urlopen(req, timeout=300) as r:
                    body = r.read().decode()
                if body.lstrip().startswith("{"):
                    return json.loads(body)
            except Exception as e:
                print(cc, ep, "failed:", e, file=sys.stderr)
        time.sleep(30 * (attempt + 1))
    return None


def speed(v):
    if not v:
        return None
    d = "".join(ch for ch in str(v) if ch.isdigit())
    return int(d) if d else None


def direction(v):
    try:
        return int(float(str(v).split(";")[0]))
    except Exception:
        return None


def build(cc):
    j = query(cc)
    if j is None:
        return None
    nodes = {e["id"]: e for e in j["elements"] if e["type"] == "node"}
    out = {}

    def add(lat, lon, kind, s, d):
        key = (round(lat, 5), round(lon, 5))
        old = out.get(key)
        # a red-light or average-speed tag says more than a plain speed camera
        if old and old["k"] != "speed" and kind == "speed":
            return
        item = {"lat": round(lat, 6), "lon": round(lon, 6), "k": kind}
        if s: item["s"] = s
        if d is not None: item["d"] = d
        out[key] = item

    for e in nodes.values():
        t = e.get("tags", {})
        enf = t.get("enforcement")
        if t.get("highway") == "speed_camera":
            add(e["lat"], e["lon"], KIND.get(enf, "speed"), speed(t.get("maxspeed")), direction(t.get("direction")))
        elif enf in KIND:
            add(e["lat"], e["lon"], KIND[enf], speed(t.get("maxspeed")), direction(t.get("direction")))
    for r in (e for e in j["elements"] if e["type"] == "relation"):
        t = r.get("tags", {})
        kind = KIND.get(t.get("enforcement"))
        if not kind:
            continue
        members = [m for m in r.get("members", []) if m["type"] == "node" and m["ref"] in nodes]
        dev = [m for m in members if m.get("role") == "device"] or [m for m in members if m.get("role") in ("from", "")] or members
        if not dev:
            continue
        n = nodes[dev[0]["ref"]]
        add(n["lat"], n["lon"], kind, speed(t.get("maxspeed")) or speed(n.get("tags", {}).get("maxspeed")), direction(n.get("tags", {}).get("direction")))
    return sorted(out.values(), key=lambda x: (x["lat"], x["lon"]))


def main():
    countries = sys.argv[1:] or ["GR"]
    os.makedirs("out/cameras", exist_ok=True)
    index = {}
    if os.path.exists("out/cameras/index.json"):
        index = json.load(open("out/cameras/index.json"))
    for cc in countries:
        items = build(cc)
        if items is None:
            print(cc, "skipped (Overpass unavailable); keeping the previous file", file=sys.stderr)
            continue
        json.dump(items, open(f"out/cameras/{cc}.json", "w"), separators=(",", ":"))
        counts = {k: sum(1 for i in items if i["k"] == k) for k in ("speed", "redlight", "average")}
        index[cc] = {"updated": time.strftime("%Y-%m-%d"), **counts}
        print(cc, counts)
    json.dump(index, open("out/cameras/index.json", "w"), indent=1)


if __name__ == "__main__":
    main()
