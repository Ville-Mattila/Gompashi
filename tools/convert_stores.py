#!/usr/bin/env python3
"""Build the bundled multi-country store data for Gompashi.

Outputs (to app/src/main/assets/ and web/):
  - alko_stores.json : list of {name, lat, lon, hours[7], hoursKnown, country}
  - closed_dates.json: {"FI":[...], "SE":[...], "NO":[...]} of holiday closures

Sources (raw inputs kept under tools/raw/ for reproducibility):
  - FI  Alko          : stores.json (repo root) - Alko's own export.
  - SE  Systembolaget : tools/raw/systembolaget_mirror.json - the official Site V2
        store data, fetched via the community mirror
        (github.com/AlexGustafsson/systembolaget-api-data). Data (c) Systembolaget;
        their API terms permit copying and publishing it within an app.
  - NO  Vinmonopolet  : tools/raw/vinmonopolet_osm.json - OpenStreetMap via Overpass
        (Vinmonopolet's own API forbids redistribution). Data (c) OpenStreetMap
        contributors, ODbL.

Each source's hours are normalized to a weekly Mon..Sun schedule; per-country public
holidays are emitted separately into closed_dates.json (the upcoming-days/date-specific
hours are intentionally not bundled - they can't be refreshed often enough).

Usage:
    python tools/convert_stores.py
    python tools/convert_stores.py --check
"""
import argparse
import collections
import datetime
import filecmp
import json
import os
import re
import tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
RAW = os.path.join(ROOT, "tools", "raw")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
WEB = os.path.join(ROOT, "web")
OUTPUT_FILES = ("alko_stores.json", "closed_dates.json")
# closed_dates.json is regenerated from the current date (this year + next), so a fresh run
# legitimately differs once the year rolls over. --check only compares the date-independent files.
CHECK_FILES = ("alko_stores.json",)

WD = {"mo": 0, "tu": 1, "we": 2, "th": 3, "fr": 4, "sa": 5, "su": 6}


def norm_hours(h):
    """Normalize an [open, close] pair to ["HH:MM","HH:MM"] or None."""
    if not h:
        return None
    return [h[0][:5], h[1][:5]]


def validate_hours(hours, context):
    if hours is None:
        return
    if not isinstance(hours, list) or len(hours) != 2:
        raise ValueError(f"{context}: hours must be [open, close] or null")
    for value in hours:
        if not isinstance(value, str):
            raise ValueError(f"{context}: hour values must be strings")


def validate_output(stores):
    seen = {}
    for index, store in enumerate(stores):
        context = f"alko_stores.json stores[{index}]"
        key = (store["name"], round(float(store["lat"]), 7), round(float(store["lon"]), 7))
        if key in seen:
            raise ValueError(f"alko_stores.json: duplicate store {store['name']!r} at {store['lat']},{store['lon']}")
        seen[key] = index

        weekly = store.get("hours")
        if not isinstance(weekly, list) or len(weekly) != 7:
            raise ValueError(f"{context}: hours must contain 7 weekday entries")
        for weekday, hours in enumerate(weekly):
            validate_hours(hours, f"{context}.hours[{weekday}]")


# ---------------------------------------------------------------- Finland (Alko)
def load_finland():
    src = json.load(open(os.path.join(ROOT, "stores.json"), encoding="utf-8"))
    out = []
    for s in src["stores"]:
        # Drop pickup points (6-digit id / "Noutopiste ...") and closed-forever stores.
        if str(s["id"]).strip() and (len(str(s["id"])) != 4 or s["name"].strip().lower().startswith("noutopiste")):
            continue
        weekly = [norm_hours(h) for h in (s.get("hours") or [None] * 7)]
        if all(h is None for h in weekly):
            continue
        out.append({
            "name": "Alko " + s["name"].strip(),
            "lat": s["lat"], "lon": s["lon"],
            "hours": weekly, "hoursKnown": True, "country": "FI",
        })
    return out


# -------------------------------------------------------- Sweden (Systembolaget)
def _se_weekly(opening_hours):
    """Collapse Systembolaget's dated rows into a weekly Mon..Sun pattern.
    Named-holiday rows (reason is a holiday name) are ignored - those map to
    closed_dates instead. '' and '-' are ordinary days; 00:00-00:00 means closed."""
    buckets = collections.defaultdict(list)
    for e in opening_hours:
        reason = (e.get("reason") or "").strip()
        if reason not in ("", "-"):
            continue
        wd = datetime.date.fromisoformat(e["date"][:10]).weekday()
        f, t = e["openFrom"][:5], e["openTo"][:5]
        buckets[wd].append(None if f == t else (f, t))
    weekly = [None] * 7
    for wd in range(7):
        if buckets[wd]:
            mode = collections.Counter(buckets[wd]).most_common(1)[0][0]
            weekly[wd] = list(mode) if mode else None
    return weekly


def load_sweden():
    src = json.load(open(os.path.join(RAW, "systembolaget_mirror.json"), encoding="utf-8"))
    out = []
    for s in src:
        if s.get("isAgent") or s.get("isBlocked"):
            continue
        pos = s.get("position") or {}
        lat, lon = pos.get("latitude"), pos.get("longitude")
        if lat is None or lon is None:
            continue
        label = (s.get("displayName") or s.get("alias") or (s.get("city") or "").title()).strip()
        weekly = _se_weekly(s.get("openingHours") or [])
        if all(h is None for h in weekly):
            continue
        out.append({
            "name": "Systembolaget " + label,
            "lat": lat, "lon": lon,
            "hours": weekly, "hoursKnown": True, "country": "SE",
        })
    return out


# -------------------------------------------------------- Norway (Vinmonopolet)
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


def _parse_osm_hours(s):
    """Parse OSM opening_hours (e.g. 'Mo-Fr 10:00-18:00; Sa 10:00-16:00; Su closed')
    into 7 entries Mon..Sun. Returns None if nothing parseable."""
    if not s or "24/7" in s:
        return None
    hours = [None] * 7
    matched = False
    for rule in s.split(";"):
        rule = rule.strip()
        if not rule or rule.lower().startswith("ph"):
            continue
        m = re.match(r"^([A-Za-z][A-Za-z,\-\s]*?)\s+(.*)$", rule)
        dayspec, rest = (m.group(1), m.group(2)) if m else (None, rule)
        days = _expand_days(dayspec) if dayspec else set(range(7))
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


def load_norway():
    src = json.load(open(os.path.join(RAW, "vinmonopolet_osm.json"), encoding="utf-8"))
    out = []
    for e in src.get("elements", []):
        t = e.get("tags", {})
        lat, lon = e.get("lat"), e.get("lon")
        if lat is None or lon is None:
            c = e.get("center") or {}
            lat, lon = c.get("lat"), c.get("lon")
        if lat is None or lon is None:
            continue
        name = t.get("name") or ("Vinmonopolet " + (t.get("branch") or "")).strip()
        weekly = _parse_osm_hours(t.get("opening_hours"))
        if not weekly or all(h is None for h in weekly):
            continue
        out.append({
            "name": name, "lat": lat, "lon": lon,
            "hours": weekly, "hoursKnown": True, "country": "NO",
        })
    return out


# ------------------------------------------------------------------- Holidays
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


def _weekday_in(year, m1, d1, m2, d2, weekday):
    """First date with the given weekday (Mon=0) in [m1/d1 .. m2/d2] of `year`."""
    cur = datetime.date(year, m1, d1)
    end = datetime.date(year, m2, d2)
    while cur <= end:
        if cur.weekday() == weekday:
            return cur
        cur += datetime.timedelta(days=1)
    return None


def closed_dates_fi(years):
    out = set()
    td = datetime.timedelta
    for y in years:
        e = _easter(y)
        out |= {
            datetime.date(y, 1, 1), datetime.date(y, 1, 6), e - td(days=2), e, e + td(days=1),
            datetime.date(y, 5, 1), e + td(days=39), e + td(days=49),
            datetime.date(y, 12, 6), datetime.date(y, 12, 24), datetime.date(y, 12, 25), datetime.date(y, 12, 26),
        }
        mid = _weekday_in(y, 6, 20, 6, 26, 5)  # Juhannus (Sat) + eve
        if mid:
            out |= {mid, mid - td(days=1)}
        alls = _weekday_in(y, 10, 31, 11, 6, 5)  # Pyhainpaiva (Sat)
        if alls:
            out.add(alls)
    return sorted(d.isoformat() for d in out)


def closed_dates_se(years):
    """Systembolaget closed days (Swedish public holidays + eves it shuts)."""
    out = set()
    td = datetime.timedelta
    for y in years:
        e = _easter(y)
        out |= {
            datetime.date(y, 1, 1), datetime.date(y, 1, 6),       # nyarsdagen, trettondedag
            e - td(days=2), e - td(days=1), e, e + td(days=1),    # langfredag, paskafton, pask, annandag
            datetime.date(y, 5, 1), e + td(days=39), e + td(days=49),  # forsta maj, Kristi himmelsfard, pingst
            datetime.date(y, 6, 6),                               # nationaldagen
            datetime.date(y, 12, 24), datetime.date(y, 12, 25), datetime.date(y, 12, 26), datetime.date(y, 12, 31),
        }
        mid = _weekday_in(y, 6, 20, 6, 26, 5)  # midsommardagen (Sat)
        if mid:
            out |= {mid, mid - td(days=1)}     # + midsommarafton (Fri)
        alls = _weekday_in(y, 10, 31, 11, 6, 5)  # alla helgons dag (Sat)
        if alls:
            out.add(alls)
    return sorted(d.isoformat() for d in out)


def closed_dates_no(years):
    """Vinmonopolet closed days (Norwegian public holidays + eves it shuts)."""
    out = set()
    td = datetime.timedelta
    for y in years:
        e = _easter(y)
        out |= {
            datetime.date(y, 1, 1),                               # nyttarsdag
            e - td(days=3), e - td(days=2), e - td(days=1), e, e + td(days=1),  # skjaer, lang, paskeaften, pask, 2.pask
            datetime.date(y, 5, 1), datetime.date(y, 5, 17),      # arbeidernes dag, grunnlovsdag
            e + td(days=39), e + td(days=49), e + td(days=50),    # Kristi himmelfart, pinse, 2. pinse
            datetime.date(y, 12, 24), datetime.date(y, 12, 25), datetime.date(y, 12, 26), datetime.date(y, 12, 31),
        }
    return sorted(d.isoformat() for d in out)


def write_outputs(stores, closed, bases):
    for base in bases:
        os.makedirs(base, exist_ok=True)
        with open(os.path.join(base, "alko_stores.json"), "w", encoding="utf-8") as f:
            json.dump(stores, f, ensure_ascii=False, indent=2)
            f.write("\n")
        with open(os.path.join(base, "closed_dates.json"), "w", encoding="utf-8") as f:
            json.dump(closed, f, ensure_ascii=False, indent=2)
            f.write("\n")


def check_outputs(stores, closed):
    with tempfile.TemporaryDirectory() as tmp:
        generated_assets = os.path.join(tmp, "assets")
        generated_web = os.path.join(tmp, "web")
        write_outputs(stores, closed, (generated_assets, generated_web))
        changed = []
        for actual_base, generated_base in ((ASSETS, generated_assets), (WEB, generated_web)):
            for filename in CHECK_FILES:
                actual = os.path.join(actual_base, filename)
                generated = os.path.join(generated_base, filename)
                if not filecmp.cmp(actual, generated, shallow=False):
                    changed.append(os.path.relpath(actual, ROOT))
        if changed:
            raise SystemExit(
                "Generated store data is out of date. Run "
                "`python tools/convert_stores.py` and commit:\n  "
                + "\n  ".join(changed)
            )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="validate inputs and fail if generated files differ")
    args = parser.parse_args()

    fi, se, no = load_finland(), load_sweden(), load_norway()
    stores = fi + se + no
    stores.sort(key=lambda s: (s["country"], s["name"]))
    validate_output(stores)

    years = [datetime.date.today().year, datetime.date.today().year + 1]
    closed = {"FI": closed_dates_fi(years), "SE": closed_dates_se(years), "NO": closed_dates_no(years)}

    if args.check:
        check_outputs(stores, closed)
    else:
        write_outputs(stores, closed, (ASSETS, WEB))

    print(f"FI {len(fi)} + SE {len(se)} + NO {len(no)} = {len(stores)} stores")
    print(f"closed dates: FI {len(closed['FI'])}, SE {len(closed['SE'])}, NO {len(closed['NO'])}")
    print(f"  -> {ASSETS}")
    print(f"  -> {WEB}")


if __name__ == "__main__":
    main()
