#!/usr/bin/env python3
"""Fetch all Alko stores in Finland from OpenStreetMap via the Overpass API and
write app/src/main/assets/alko_stores.json.

No third-party dependencies (uses the Python standard library only).
Data: (c) OpenStreetMap contributors, licensed under the ODbL.

Usage:
    python tools/fetch_alko.py
"""
import json
import os
import sys
import urllib.parse
import urllib.request

# Bounding box covering Finland (south, west, north, east). Using a bbox instead
# of an area lookup keeps the query fast and avoids Overpass area-resolution timeouts.
QUERY = """[out:json][timeout:180];
(
  nwr["shop"="alcohol"]["name"~"Alko",i](59.0,19.0,70.5,31.8);
);
out center tags;"""

ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]


def display_name(tags: dict) -> str:
    """Build a human-friendly store name. OSM `name` is usually just "Alko",
    so append the `branch` (e.g. "Turku keskusta") or the city when available."""
    name = tags.get("name", "Alko")
    branch = tags.get("branch")
    if branch:
        return f"{name} {branch}"
    city = tags.get("addr:city")
    if city:
        return f"{name} ({city})"
    return name


def fetch() -> dict:
    body = urllib.parse.urlencode({"data": QUERY}).encode()
    headers = {
        "User-Agent": "Gompashi/1.0 (Alko store fetcher; +https://github.com/Ville-Mattila/Gompashi)",
        "Accept": "application/json",
    }
    last_err = None
    for endpoint in ENDPOINTS:
        try:
            req = urllib.request.Request(endpoint, data=body, headers=headers)
            with urllib.request.urlopen(req, timeout=180) as resp:
                return json.load(resp)
        except Exception as err:  # noqa: BLE001 - try the next mirror
            last_err = err
            print(f"  ! {endpoint} failed: {err}", file=sys.stderr)
    raise SystemExit(f"All Overpass endpoints failed: {last_err}")


def main() -> None:
    out_path = os.path.abspath(
        os.path.join(
            os.path.dirname(__file__),
            "..",
            "app",
            "src",
            "main",
            "assets",
            "alko_stores.json",
        )
    )
    os.makedirs(os.path.dirname(out_path), exist_ok=True)

    payload = fetch()
    if payload.get("remark"):
        print(f"  ! Overpass remark: {payload['remark']}", file=sys.stderr)
    stores = []
    for el in payload.get("elements", []):
        tags = el.get("tags", {})
        lat = el.get("lat")
        lon = el.get("lon")
        if lat is None or lon is None:
            center = el.get("center") or {}
            lat = center.get("lat")
            lon = center.get("lon")
        if lat is None or lon is None:
            continue
        stores.append({"name": display_name(tags), "lat": lat, "lon": lon})

    stores.sort(key=lambda s: s["name"])
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(stores, f, ensure_ascii=False, indent=2)
        f.write("\n")

    if not stores:
        raise SystemExit("No stores returned — aborting (check Overpass availability).")
    print(f"Wrote {len(stores)} stores to {out_path}")


if __name__ == "__main__":
    main()
