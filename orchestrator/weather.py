"""Weather lookups for live-data voice queries (Open-Meteo, no API key)."""

from __future__ import annotations

import re
import urllib.parse
import urllib.request
import json

DEFAULT_CITY = "San Francisco"
WMO_DESCRIPTIONS = {
    0: "clear sky",
    1: "mainly clear",
    2: "partly cloudy",
    3: "overcast",
    45: "foggy",
    48: "depositing rime fog",
    51: "light drizzle",
    53: "moderate drizzle",
    55: "dense drizzle",
    61: "slight rain",
    63: "moderate rain",
    65: "heavy rain",
    71: "slight snow",
    73: "moderate snow",
    75: "heavy snow",
    80: "rain showers",
    81: "moderate rain showers",
    82: "violent rain showers",
    95: "thunderstorm",
}


def is_weather_query(prompt: str) -> bool:
    lower = prompt.lower()
    return any(
        k in lower
        for k in (
            "weather",
            "forecast",
            "temperature",
            "rain",
            "snow",
            "humidity",
            "how hot",
            "how cold",
        )
    )


def extract_location(prompt: str) -> str | None:
    patterns = [
        r"weather\s+(?:in|for|at)\s+(.+)",
        r"(?:forecast|temperature)\s+(?:in|for|at)\s+(.+)",
        r"(.+?)\s+weather(?:\s+forecast)?\s*$",
        r"what(?:'s| is) the weather(?: like)?(?: in| for| at)?\s+(.+)",
    ]
    text = prompt.strip().rstrip("?.!")
    for pattern in patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            loc = re.sub(r"\b(like|today|tomorrow|now|please)\b", "", match.group(1), flags=re.I).strip()
            if loc and len(loc) <= 80:
                return loc
    return None


def _get_json(url: str) -> dict:
    req = urllib.request.Request(url, headers={"User-Agent": "PhoneLlama-Orchestrator/1.0"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode())


def geocode(name: str) -> dict | None:
    encoded = urllib.parse.quote(name)
    data = _get_json(
        f"https://geocoding-api.open-meteo.com/v1/search?name={encoded}&count=1&language=en&format=json"
    )
    results = data.get("results") or []
    if not results:
        return None
    r = results[0]
    display = ", ".join(filter(None, [r.get("name"), r.get("admin1"), r.get("country")]))
    return {"name": display, "latitude": r["latitude"], "longitude": r["longitude"]}


def fetch_weather_answer(prompt: str) -> str:
    location = extract_location(prompt) or DEFAULT_CITY
    geo = geocode(location)
    if not geo:
        return f'Could not find location "{location}". Try "weather in Seattle".'

    lat, lon = geo["latitude"], geo["longitude"]
    forecast = _get_json(
        "https://api.open-meteo.com/v1/forecast?"
        f"latitude={lat}&longitude={lon}"
        "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m"
        "&timezone=auto"
    )
    current = forecast.get("current") or {}
    temp_c = current.get("temperature_2m")
    feels_c = current.get("apparent_temperature")
    humidity = current.get("relative_humidity_2m")
    wind = current.get("wind_speed_10m")
    code = int(current.get("weather_code", -1))
    condition = WMO_DESCRIPTIONS.get(code, "variable conditions")

    temp_f = round(temp_c * 9 / 5 + 32) if temp_c is not None else "?"
    feels_f = round(feels_c * 9 / 5 + 32) if feels_c is not None else "?"

    return (
        f"Current weather in {geo['name']}: {condition}. "
        f"{round(temp_c)}°C ({temp_f}°F), feels like {round(feels_c)}°C ({feels_f}°F). "
        f"Humidity {humidity}%, wind {round(wind)} km/h."
    )
