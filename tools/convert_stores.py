#!/usr/bin/env python3
"""Convert the Alko export (stores.json in the repo root) into the bundled
app data: app/src/main/assets/alko_stores.json + closed_dates.json, and copy
both into web/.

Source: stores.json — {generatedAt, stores:[{id,name,address,postalCode,city,
lat,lon,hours[7],upcomingHours[{date,hours}]}]}.

Steps:
  1. Drop pickup points ("Noutopiste ..." / 6-digit ids) — only real stores stay.
  2. Drop stores that are closed every weekday (permanently/temporarily closed).
  3. Emit the weekly schedule + closed_dates.json (Finnish holidays).

Note: the source's per-date `upcomingHours` are intentionally ignored — they only
cover ~10 days and we can't regenerate the bundled data that often. Holidays are
handled by closed_dates.json instead.

Usage:
    python tools/convert_stores.py
"""
import datetime
import json
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SRC = os.path.join(ROOT, "stores.json")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
WEB = os.path.join(ROOT, "web")


def is_pickup(s):
    """Pickup points have 6-digit ids and a 'Noutopiste ' name prefix."""
    return s["name"].strip().lower().startswith("noutopiste") or len(str(s["id"])) != 4


def is_closed_forever(s):
    return all(h is None for h in (s.get("hours") or [None] * 7))


def norm_hours(h):
    """Normalize an hours entry to [open, close] or None."""
    if not h:
        return None
    return [h[0], h[1]]


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
            datetime.date(y, 1, 1),    # Uudenvuodenpaiva
            datetime.date(y, 1, 6),    # Loppiainen
            e - td(days=2),            # Pitkaperjantai
            e,                         # Paasiaispaiva
            e + td(days=1),            # 2. paasiaispaiva
            datetime.date(y, 5, 1),    # Vappu
            e + td(days=39),           # Helatorstai
            e + td(days=49),           # Helluntai
            datetime.date(y, 12, 6),   # Itsenaisyyspaiva
            datetime.date(y, 12, 24),  # Jouluaatto
            datetime.date(y, 12, 25),  # Joulupaiva
            datetime.date(y, 12, 26),  # Tapaninpaiva
        }
        for dd in range(20, 27):       # Juhannus: Sat 20-26 Jun, eve the Fri before
            d = datetime.date(y, 6, dd)
            if d.weekday() == 5:
                out |= {d, d - td(days=1)}
                break
        for off in range(7):           # Pyhainpaiva: Sat 31 Oct - 6 Nov
            d = datetime.date(y, 10, 31) + td(days=off)
            if d.weekday() == 5:
                out.add(d)
                break
    return sorted(d.isoformat() for d in out)


def main():
    src = json.load(open(SRC, encoding="utf-8"))
    raw = src["stores"]

    stores = []
    dropped_pickup = dropped_closed = 0
    for s in raw:
        if is_pickup(s):
            dropped_pickup += 1
            continue
        if is_closed_forever(s):
            dropped_closed += 1
            continue
        stores.append({
            "name": "Alko " + s["name"].strip(),
            "lat": s["lat"],
            "lon": s["lon"],
            "hours": [norm_hours(h) for h in (s.get("hours") or [None] * 7)],
            "hoursKnown": True,
        })

    stores.sort(key=lambda s: s["name"])

    years = [datetime.date.today().year, datetime.date.today().year + 1]
    closed = alko_closed_dates(years)

    for base in (ASSETS, WEB):
        os.makedirs(base, exist_ok=True)
        with open(os.path.join(base, "alko_stores.json"), "w", encoding="utf-8") as f:
            json.dump(stores, f, ensure_ascii=False, indent=2)
            f.write("\n")
        with open(os.path.join(base, "closed_dates.json"), "w", encoding="utf-8") as f:
            json.dump(closed, f, ensure_ascii=False, indent=2)
            f.write("\n")

    print(f"generatedAt: {src.get('generatedAt')}")
    print(f"dropped {dropped_pickup} pickup points, {dropped_closed} closed stores")
    print(f"wrote {len(stores)} stores + {len(closed)} closed dates")
    print(f"  -> {ASSETS}")
    print(f"  -> {WEB}")


if __name__ == "__main__":
    main()
