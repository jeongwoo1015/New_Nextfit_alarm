"""브리핑 날씨: 앱이 브리핑 토큰 요청에 보낸 위치로 OpenWeather를 불러 오늘 날씨를 정리한다.

위치·날씨는 DB에 저장하지 않는다(브리핑할 때 한 번 쓰고 버림). 날씨를 못 가져와도 브리핑은 날씨 없이 진행한다.
- 현재 날씨(/data/2.5/weather): 날씨 요약, 현재 기온, 지역 이름(앱이 이름을 안 보냈을 때만)
- 5일/3시간 예보(/data/2.5/forecast): 오늘(KST) 남은 시간의 최고·최저 기온과 강수 확률 — 무료 현재 날씨에는 없다
"""
import logging
from dataclasses import replace
from datetime import datetime, timedelta
from typing import Protocol

import httpx

from backend.clients.ai_gateway import Weather
from backend.core.config import Settings
from backend.core.timeutil import KST, now_kst

log = logging.getLogger(__name__)

BASE_URL = "https://api.openweathermap.org/data/2.5"
CACHE_TTL = timedelta(minutes=30)
FORECAST_FALLBACK_SLOTS = 8     # 오늘 남은 예보가 없으면(밤늦게) 앞으로 24시간(3시간 × 8)으로 본다
# 네트워크 오류·HTTP 오류·응답 형식이 다른 경우: 브리핑은 막지 않고 날씨만 뺀다
FETCH_ERRORS = (httpx.HTTPError, ValueError, KeyError, TypeError, AttributeError, IndexError)


class WeatherClient(Protocol):
    def today(self, lat: float, lon: float, location_name: str = "") -> Weather | None: ...   # 실패하면 None


class NoWeather:
    """OPENWEATHER_API_KEY가 없을 때: 항상 날씨 없음."""

    def today(self, lat, lon, location_name=""):
        return None


class OpenWeatherClient:
    def __init__(self, api_key: str, http: httpx.Client | None = None, clock=now_kst):
        self._key, self._clock = api_key, clock
        self._http = http or httpx.Client(timeout=3)    # 브리핑 토큰 발급이 늦어지지 않게 짧게
        self._cache: dict[tuple[float, float], tuple[datetime, Weather]] = {}

    def today(self, lat: float, lon: float, location_name: str = "") -> Weather | None:
        now = self._clock()
        key = (round(lat, 2), round(lon, 2))            # 약 1km 안은 같은 날씨로 본다
        hit = self._cache.get(key)
        if hit and now - hit[0] < CACHE_TTL:
            w = hit[1]
        else:
            w = self._fetch(lat, lon, now)
            if w is None:
                return None
            self._cache[key] = (now, w)
        return replace(w, location_name=location_name) if location_name else w   # 앱이 보낸 한국어 이름 우선

    def _fetch(self, lat: float, lon: float, now: datetime) -> Weather | None:
        try:
            current = self._get("weather", lat, lon)
            desc = (current.get("weather") or [{}])[0].get("description", "")
            temp = (current.get("main") or {}).get("temp")
        except FETCH_ERRORS as e:
            log.warning("현재 날씨를 가져오지 못함 (%s, %s): %s", lat, lon, e)
            return None
        try:
            high, low, pop = self._today_range(self._get("forecast", lat, lon), now)
        except FETCH_ERRORS as e:
            log.warning("날씨 예보를 가져오지 못함 (%s, %s): %s", lat, lon, e)
            high = low = pop = None
        if not desc and temp is None:
            return None
        if temp is not None:                            # 지금 기온이 예보 범위 밖일 수 있다
            high = temp if high is None else max(high, temp)
            low = temp if low is None else min(low, temp)
        return Weather(desc, _round(temp), _round(high), _round(low), pop, current.get("name", ""))

    def _get(self, path: str, lat: float, lon: float) -> dict:
        r = self._http.get(f"{BASE_URL}/{path}",
                           params={"lat": lat, "lon": lon, "appid": self._key, "units": "metric", "lang": "kr"})
        r.raise_for_status()
        return r.json()

    @staticmethod
    def _today_range(forecast: dict, now: datetime) -> tuple[float | None, float | None, int | None]:
        slots = [(datetime.fromtimestamp(x["dt"], KST), x) for x in forecast.get("list", []) if "dt" in x]
        today = [x for at, x in slots if at.date() == now.date() and at + timedelta(hours=3) > now]
        picked = today or [x for _, x in slots[:FORECAST_FALLBACK_SLOTS]]
        highs = [x["main"]["temp_max"] for x in picked if "temp_max" in x.get("main", {})]
        lows = [x["main"]["temp_min"] for x in picked if "temp_min" in x.get("main", {})]
        pops = [x["pop"] for x in picked if x.get("pop") is not None]
        return (max(highs) if highs else None, min(lows) if lows else None,
                round(max(pops) * 100) if pops else None)


def _round(v: float | None) -> float | None:
    return None if v is None else round(v, 1)


def build_weather_client(settings: Settings) -> WeatherClient:
    return OpenWeatherClient(settings.openweather_api_key) if settings.openweather_api_key else NoWeather()
