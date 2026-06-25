#!/usr/bin/env python3
"""Fetch all Alko stores in Finland from OpenStreetMap via the Overpass API and
write app/src/main/assets/alko_stores.json.

No third-party dependencies (uses the Python standard library only).
Data: (c) OpenStreetMap contributors, licensed under the ODbL.

Steps:
  1. Query Overpass for shop=alcohol named "Alko" within a Finland bounding box.
  2. Keep only the genuine Alko brand (drops Estonian "CityAlko"/"SuperAlko" etc.).
  3. Build a display name from branch / addr:city.
  4. Merge hand-curated stores missing from OSM (tools/manual_stores.json).
  5. Reverse-geocode entries that are still just "Alko" or share a name with another
     store, adding "(city, suburb)" so every store is identifiable.

Usage:
    python tools/fetch_alko.py
"""
import json
import math
import os
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter

# Finland bounding box (south, west, north, east). South edge is 59.7 so the query
# does NOT reach across the Gulf of Finland into Estonia (Tallinn ~59.4).
QUERY = """[out:json][timeout:180];
(
  nwr["shop"="alcohol"]["name"~"Alko",i](59.7,19.0,70.5,31.8);
);
out center tags;"""

OVERPASS_ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.openstreetmap.ru/api/interpreter",
]
NOMINATIM = "https://nominatim.openstreetmap.org/reverse"
UA = "Gompashi/1.0 (Alko store fetcher; +https://github.com/Ville-Mattila/Gompashi)"


def base_name(tags: dict) -> str:
    """First segment of a possibly multi-valued OSM name, e.g. 'Alko;Apteekki' -> 'Alko'."""
    return tags.get("name", "Alko").split(";")[0].strip()


def is_alko(tags: dict) -> bool:
    """True only for the genuine Alko brand (not 'CityAlko', 'SuperAlko', ...)."""
    return base_name(tags).lower() == "alko"


def display_name(tags: dict) -> str:
    branch = tags.get("branch")
    if branch:
        return f"Alko {branch}"
    city = tags.get("addr:city")
    if city:
        return f"Alko ({city})"
    return "Alko"


def fetch() -> dict:
    body = urllib.parse.urlencode({"data": QUERY}).encode()
    headers = {"User-Agent": UA, "Accept": "application/json"}
    last_err = None
    for attempt in range(6):
        endpoint = OVERPASS_ENDPOINTS[attempt % len(OVERPASS_ENDPOINTS)]
        try:
            req = urllib.request.Request(endpoint, data=body, headers=headers)
            with urllib.request.urlopen(req, timeout=180) as resp:
                return json.load(resp)
        except Exception as err:  # noqa: BLE001 - try the next mirror
            last_err = err
            print(f"  ! {endpoint} failed: {err}", file=sys.stderr)
            time.sleep(15)
    raise SystemExit(f"All Overpass endpoints failed: {last_err}")


def reverse_place(lat: float, lon: float) -> str | None:
    """Reverse-geocode to 'city, suburb' (or 'city') via Nominatim. None on failure."""
    url = NOMINATIM + "?" + urllib.parse.urlencode(
        {"lat": lat, "lon": lon, "format": "json", "zoom": 16, "addressdetails": 1}
    )
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=60) as resp:
            a = json.load(resp).get("address", {})
    except Exception as err:  # noqa: BLE001
        print(f"  ! reverse-geocode failed for {lat},{lon}: {err}", file=sys.stderr)
        return None
    city = a.get("city") or a.get("town") or a.get("municipality") or a.get("village") or a.get("county")
    suburb = a.get("suburb") or a.get("city_district") or a.get("neighbourhood") or a.get("quarter")
    if suburb and city and suburb != city:
        return f"{city}, {suburb}"
    return suburb or city


def enrich_names(stores: list) -> list:
    """Add a place to entries that are still just 'Alko' or that share a name with
    another store, so every entry is identifiable."""
    counts = Counter(s["name"] for s in stores)
    for s in stores:
        if s["name"] == "Alko" or counts[s["name"]] > 1:
            place = reverse_place(s["lat"], s["lon"])
            if place:
                s["name"] = f"Alko ({place})"
                print(f"  ~ {s['lat']:.4f},{s['lon']:.4f} -> {s['name']}")
            time.sleep(1.1)  # be polite to Nominatim
    return stores


def merge_manual(stores: list) -> list:
    """Add hand-curated stores missing from OpenStreetMap (tools/manual_stores.json)."""
    path = os.path.join(os.path.dirname(__file__), "manual_stores.json")
    if not os.path.exists(path):
        return stores
    manual = json.load(open(path, encoding="utf-8"))
    names = {s["name"].lower() for s in stores}
    for m in manual:
        near = any(_meters(m["lat"], m["lon"], s["lat"], s["lon"]) < 150 for s in stores)
        if m["name"].lower() in names or near:
            continue
        stores.append({"name": m["name"], "lat": m["lat"], "lon": m["lon"]})
        print(f"  + manual: {m['name']}")
    return stores


def _meters(lat1, lon1, lat2, lon2):
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return r * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def main() -> None:
    out_path = os.path.abspath(
        os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "alko_stores.json")
    )
    os.makedirs(os.path.dirname(out_path), exist_ok=True)

    payload = fetch()
    if payload.get("remark"):
        print(f"  ! Overpass remark: {payload['remark']}", file=sys.stderr)

    stores = []
    for el in payload.get("elements", []):
        tags = el.get("tags", {})
        if not is_alko(tags):
            continue
        lat = el.get("lat")
        lon = el.get("lon")
        if lat is None or lon is None:
            center = el.get("center") or {}
            lat = center.get("lat")
            lon = center.get("lon")
        if lat is None or lon is None:
            continue
        stores.append({"name": display_name(tags), "lat": lat, "lon": lon})

    stores = merge_manual(stores)
    stores = enrich_names(stores)
    stores.sort(key=lambda s: s["name"])

    if not stores:
        raise SystemExit("No stores returned — aborting (check Overpass availability).")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(stores, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"Wrote {len(stores)} stores to {out_path}")


if __name__ == "__main__":
    main()
