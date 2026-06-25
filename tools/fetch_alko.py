#!/usr/bin/env python3
"""Fetch all Alko stores in Finland from OpenStreetMap via the Overpass API and
write app/src/main/assets/alko_stores.json + closed_dates.json.

No third-party dependencies (uses the Python standard library only).
Data: (c) OpenStreetMap contributors, licensed under the ODbL.

Steps:
  1. Query Overpass for shop=alcohol named "Alko" within a Finland bounding box.
  2. Keep only the genuine Alko brand (drops Estonian "CityAlko"/"SuperAlko" etc.).
  3. Build a display name from branch / addr:city.
  4. Parse OSM opening_hours into a simple 7-day schedule (Mon..Sun). Stores without a
     parseable value fall back to Alko's typical hours and are marked hoursKnown=false.
  5. Merge hand-curated stores missing from OSM (tools/manual_stores.json).
  6. Reverse-geocode entries that are still just "Alko" or share a name with another store.
  7. Write the Alko public-holiday closed dates (next 2 years) for the countdown.

Usage:
    python tools/fetch_alko.py
"""
import datetime
import json
import math
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter

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

# Alko's typical hours, used when OSM has no parseable opening_hours.
DEFAULT_HOURS = [["09:00", "21:00"]] * 5 + [["09:00", "18:00"], None]  # Mon..Fri, Sat, Sun
WD = {"mo": 0, "tu": 1, "we": 2, "th": 3, "fr": 4, "sa": 5, "su": 6}


def base_name(tags):
    return tags.get("name", "Alko").split(";")[0].strip()


def is_alko(tags):
    return base_name(tags).lower() == "alko"


def display_name(tags):
    branch = tags.get("branch")
    if branch:
        return f"Alko {branch}"
    city = tags.get("addr:city")
    if city:
        return f"Alko ({city})"
    return "Alko"


def _expand_days(spec):
    days = set()
    for part in spec.split(","):
        part = part.strip().lower()
        if "-" in part:
            a, b = part.split("-", 1)
            a, b = WD.get(a[:2]), WD.get(b[:2])
            if a is None or b is None:
                continue
            i = a
            for _ in range(7):
                days.add(i)
                if i == b:
                    break
                i = (i + 1) % 7
        else:
            d = WD.get(part[:2])
            if d is not None:
                days.add(d)
    return days


def parse_opening_hours(s):
    """Parse common OSM opening_hours into 7 entries (Mon..Sun); each is [open, close]
    or None (closed). Returns None if nothing parseable (caller uses defaults).
    Public-holiday (PH) rules are ignored here — holidays are handled separately."""
    if not s or "24/7" in s:
        return None
    hours = [None] * 7
    matched = False
    for rule in s.split(";"):
        rule = rule.strip()
        if not rule or rule.lower().startswith("ph"):
            continue
        m = re.match(r"^([A-Za-z][A-Za-z,\-\s]*?)\s+(.*)$", rule)
        dauspec, rest = (m.group(1), m.group(2)) if m else (None, rule)
        days = _expand_days(dauspec) if dauspec else set(range(7))
        tm = re.search(r"(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})", rest)
        if not days:
            continue
        if tm:
            o = f"{int(tm.group(1)):02d}:{tm.group(2)}"
            c = f"{int(tm.group(3)):02d}:{tm.group(4)}"
            for d in days:
                hours[d] = [o, c]
            matched = True
        elif re.search(r"\b(off|closed)\b", rest.lower()):
            for d in days:
                hours[d] = None
            matched = True
    return hours if matched else None


def store_hours(tags):
    parsed = parse_opening_hours(tags.get("opening_hours"))
    if parsed:
        return parsed, True
    return [list(h) if h else None for h in DEFAULT_HOURS], False


def _easter(y):
    a = y % 19
    b, c = y // 100, y % 100
    d, e = b // 4, b % 4
    f = (b + 8) // 25
    g = (b - f + 1) // 3
    h = (19 * a + b - d - g + 15) % 30
    i, k = c // 4, c % 4
    l = (32 + 2 * e + 2 * i - h - k) % 7
    mth = (a + 11 * h + 22 * l) // 451
    month = (h + l - 7 * mth + 114) // 31
    day = ((h + l - 7 * mth + 114) % 31) + 1
    return datetime.date(y, month, day)


def alko_closed_dates(years):
    """Dates Alko is closed (Finnish public holidays + the eves it stays shut)."""
    out = set()
    td = datetime.timedelta
    for y in years:
        e = _easter(y)
        out |= {
            datetime.date(y, 1, 1),    # Uudenvuodenpäivä
            datetime.date(y, 1, 6),    # Loppiainen
            e - td(days=2),            # Pitkäperjantai
            e,                         # Pääsiäispäivä
            e + td(days=1),            # 2. pääsiäispäivä
            datetime.date(y, 5, 1),    # Vappu
            e + td(days=39),           # Helatorstai
            e + td(days=49),           # Helluntai
            datetime.date(y, 12, 6),   # Itsenäisyyspäivä
            datetime.date(y, 12, 24),  # Jouluaatto
            datetime.date(y, 12, 25),  # Joulupäivä
            datetime.date(y, 12, 26),  # Tapaninpäivä
        }
        for dd in range(20, 27):       # Juhannus: Sat 20–26 Jun, eve the Fri before
            d = datetime.date(y, 6, dd)
            if d.weekday() == 5:
                out |= {d, d - td(days=1)}
                break
        for off in range(7):           # Pyhäinpäivä: Sat 31 Oct – 6 Nov
            d = datetime.date(y, 10, 31) + td(days=off)
            if d.weekday() == 5:
                out.add(d)
                break
    return sorted(d.isoformat() for d in out)


def reverse_place(lat, lon):
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


def enrich_names(stores):
    counts = Counter(s["name"] for s in stores)
    for s in stores:
        if s["name"] == "Alko" or counts[s["name"]] > 1:
            place = reverse_place(s["lat"], s["lon"])
            if place:
                s["name"] = f"Alko ({place})"
                print(f"  ~ {s['lat']:.4f},{s['lon']:.4f} -> {s['name']}")
            time.sleep(1.1)
    return stores


def merge_manual(stores):
    path = os.path.join(os.path.dirname(__file__), "manual_stores.json")
    if not os.path.exists(path):
        return stores
    manual = json.load(open(path, encoding="utf-8"))
    names = {s["name"].lower() for s in stores}
    for m in manual:
        near = any(_meters(m["lat"], m["lon"], s["lat"], s["lon"]) < 150 for s in stores)
        if m["name"].lower() in names or near:
            continue
        stores.append({
            "name": m["name"], "lat": m["lat"], "lon": m["lon"],
            "hours": [list(h) if h else None for h in DEFAULT_HOURS], "hoursKnown": False,
        })
        print(f"  + manual: {m['name']}")
    return stores


def _meters(lat1, lon1, lat2, lon2):
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return r * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def fetch():
    body = urllib.parse.urlencode({"data": QUERY}).encode()
    headers = {"User-Agent": UA, "Accept": "application/json"}
    last_err = None
    for attempt in range(6):
        endpoint = OVERPASS_ENDPOINTS[attempt % len(OVERPASS_ENDPOINTS)]
        try:
            req = urllib.request.Request(endpoint, data=body, headers=headers)
            with urllib.request.urlopen(req, timeout=180) as resp:
                return json.load(resp)
        except Exception as err:  # noqa: BLE001
            last_err = err
            print(f"  ! {endpoint} failed: {err}", file=sys.stderr)
            time.sleep(15)
    raise SystemExit(f"All Overpass endpoints failed: {last_err}")


def main():
    assets = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets"))
    os.makedirs(assets, exist_ok=True)

    payload = fetch()
    if payload.get("remark"):
        print(f"  ! Overpass remark: {payload['remark']}", file=sys.stderr)

    stores = []
    for el in payload.get("elements", []):
        tags = el.get("tags", {})
        if not is_alko(tags):
            continue
        lat, lon = el.get("lat"), el.get("lon")
        if lat is None or lon is None:
            center = el.get("center") or {}
            lat, lon = center.get("lat"), center.get("lon")
        if lat is None or lon is None:
            continue
        hours, known = store_hours(tags)
        stores.append({"name": display_name(tags), "lat": lat, "lon": lon, "hours": hours, "hoursKnown": known})

    stores = merge_manual(stores)
    stores = enrich_names(stores)
    stores.sort(key=lambda s: s["name"])
    if not stores:
        raise SystemExit("No stores returned — aborting (check Overpass availability).")

    with open(os.path.join(assets, "alko_stores.json"), "w", encoding="utf-8") as f:
        json.dump(stores, f, ensure_ascii=False, indent=2)
        f.write("\n")

    years = [datetime.date.today().year, datetime.date.today().year + 1]
    with open(os.path.join(assets, "closed_dates.json"), "w", encoding="utf-8") as f:
        json.dump(alko_closed_dates(years), f, ensure_ascii=False, indent=2)
        f.write("\n")

    known = sum(1 for s in stores if s["hoursKnown"])
    print(f"Wrote {len(stores)} stores ({known} with OSM hours) + closed_dates.json to {assets}")


if __name__ == "__main__":
    main()
