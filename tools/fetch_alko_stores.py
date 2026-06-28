#!/usr/bin/env python3
"""Fetch stores.json from Alko's store API.

Used by the manual data refresh workflow. No extra deps.
"""
import datetime
import json
import os
import re
import urllib.parse
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUTPUT = os.path.join(ROOT, "stores.json")
API_URL = os.environ.get("ALKO_STORES_API_URL", "https://www.alko.fi/api/stores")
USER_AGENT = os.environ.get(
    "ALKO_STORES_USER_AGENT",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36",
)


def fetch_json(url):
    req = urllib.request.Request(
        url,
        headers={
            "Accept": "application/json, text/plain, */*",
            "Accept-Language": "fi-FI,fi;q=0.9,en;q=0.8",
            "Referer": "https://www.alko.fi/myymalat-palvelut",
            "User-Agent": USER_AGENT,
        },
    )
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.load(response)


def fetch_all_stores():
    limit = 100
    skip = 0
    stores = []

    while True:
        url = API_URL + "?" + urllib.parse.urlencode({"limit": limit, "skip": skip})
        payload = fetch_json(url)
        data = payload.get("data")
        total = payload.get("totalAmount")

        if not isinstance(data, list):
            raise ValueError("API response data field is not a list")
        if not isinstance(total, int):
            raise ValueError("API response totalAmount field is not an integer")

        stores.extend(data)
        print(f"Fetched {len(stores)}/{total} stores")

        if len(stores) >= total or not data:
            break
        skip += limit

    return stores


def null_if_blank(value):
    if not isinstance(value, str):
        return value if value is not None else None

    stripped = value.strip()
    return stripped or None


def normalize_opening_hours(entry):
    hours = null_if_blank(entry.get("hours"))
    if hours is None or hours.lower() == "kiinni":
        return None

    match = re.match(r"^(\d{1,2})(?:\.(\d{2}))?[\u2013-](\d{1,2})(?:\.(\d{2}))?$", hours)
    if not match:
        return None

    open_hour, open_minute, close_hour, close_minute = match.groups()
    return [
        f"{int(open_hour):02d}:{open_minute or '00'}",
        f"{int(close_hour):02d}:{close_minute or '00'}",
    ]


def weekday_index(date):
    return datetime.date.fromisoformat(date).weekday()


def normalize_upcoming_hours(open_hours):
    return [
        {
            "date": entry["date"],
            "hours": normalize_opening_hours(entry),
        }
        for entry in open_hours
    ]


def normalize_weekly_hours(open_hours):
    upcoming_hours = normalize_upcoming_hours(open_hours)
    by_weekday = [None] * 7
    start_index = next((i for i, entry in enumerate(upcoming_hours) if weekday_index(entry["date"]) == 0), -1)

    if start_index == -1 or start_index + 7 > len(upcoming_hours):
        start_index = 0

    for entry in upcoming_hours[start_index:start_index + 7]:
        by_weekday[weekday_index(entry["date"])] = entry["hours"]

    return by_weekday


def assert_unique_store_ids(stores):
    seen = set()
    duplicates = set()

    for store in stores:
        store_id = store.get("id")
        if store_id in seen:
            duplicates.add(store_id)
        seen.add(store_id)

    if duplicates:
        raise ValueError(f"Duplicate store id: {', '.join(sorted(str(i) for i in duplicates))}")


def normalize_store(store):
    open_hours = store.get("openHours") or []
    return {
        "id": store["id"],
        "name": null_if_blank(store.get("name")),
        "address": null_if_blank(store.get("address")),
        "postalCode": store.get("postalCode"),
        "city": null_if_blank(store.get("city")),
        "lat": store["latitude"],
        "lon": store["longitude"],
        "hours": normalize_weekly_hours(open_hours),
        "upcomingHours": normalize_upcoming_hours(open_hours),
    }


def normalize_stores(stores):
    assert_unique_store_ids(stores)
    return [normalize_store(store) for store in stores]


def main():
    stores = fetch_all_stores()
    normalized = normalize_stores(stores)
    payload = {
        "generatedAt": datetime.datetime.now(datetime.UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
        "stores": normalized,
    }

    with open(OUTPUT, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print(f"Wrote {len(normalized)} stores to {OUTPUT}")


if __name__ == "__main__":
    main()
