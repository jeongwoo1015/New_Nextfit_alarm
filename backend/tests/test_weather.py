"""OpenWeather 날씨 정리: 네트워크 대신 httpx.MockTransport로 응답을 바꿔 끼운다."""
from datetime import datetime, timedelta

import httpx

from backend.clients.ai_gateway import Weather
from backend.clients.weather import NoWeather, OpenWeatherClient, build_weather_client
from backend.core.config import Settings
from backend.core.timeutil import KST

from .conftest import Clock

NOW = datetime(2026, 11, 6, 7, 0, tzinfo=KST)     # 금요일 아침 브리핑
CURRENT = {"weather": [{"description": "맑음"}], "main": {"temp": 3.46}, "name": "Seoul"}


def slot(at: datetime, lo: float, hi: float, pop: float) -> dict:
    return {"dt": int(at.timestamp()), "main": {"temp_min": lo, "temp_max": hi}, "pop": pop}


FORECAST = {"list": [
    slot(NOW - timedelta(hours=3), -5.0, -4.0, 0.9),       # 이미 지난 시간대: 빼야 함
    slot(NOW, 2.0, 4.0, 0.0),                             # 06~09시 (지금 포함)
    slot(NOW.replace(hour=12), 6.0, 9.24, 0.3),
    slot(NOW.replace(hour=21), 4.0, 5.0, 0.1),
    slot(NOW.replace(hour=0) + timedelta(days=1), -1.0, 1.0, 0.8),   # 내일: 빼야 함
]}


def client(current=CURRENT, forecast=FORECAST, clock=None):
    calls = []

    def handler(request: httpx.Request):
        path = request.url.path.rsplit("/", 1)[-1]
        calls.append((path, dict(request.url.params)))
        body = current if path == "weather" else forecast
        return body if isinstance(body, httpx.Response) else httpx.Response(200, json=body)
    c = OpenWeatherClient("ow-key", http=httpx.Client(transport=httpx.MockTransport(handler)), clock=clock or Clock(NOW))
    return c, calls


def test_today_weather():
    c, calls = client()
    w = c.today(37.5385, 127.0823, "광진구")
    assert w == Weather("맑음", 3.5, 9.2, 2.0, 30, "광진구")          # 오늘 남은 시간대만, 앱이 보낸 이름 우선
    assert [p for p, _ in calls] == ["weather", "forecast"]
    assert calls[0][1] == {"lat": "37.5385", "lon": "127.0823", "appid": "ow-key", "units": "metric", "lang": "kr"}
    assert c.today(37.5385, 127.0823).location_name == "Seoul"      # 이름을 안 보내면 날씨 API의 이름


def test_cache_by_nearby_location():
    clock = Clock(NOW)
    c, calls = client(clock=clock)
    c.today(37.5385, 127.0823, "광진구")
    assert c.today(37.5401, 127.0789, "광진구") is not None and len(calls) == 2    # 약 1km 안은 30분 동안 다시 부르지 않음
    clock.now = NOW + timedelta(minutes=31)
    c.today(37.5385, 127.0823)
    assert len(calls) == 4


def test_current_temp_widens_range_and_late_night_uses_next_slots():
    late = NOW.replace(hour=23, minute=30)
    forecast = {"list": [slot(late + timedelta(hours=h), 0.0, 1.0, 0.2) for h in range(1, 10)]}   # 오늘 남은 예보 없음
    c, _ = client(forecast=forecast, clock=Clock(late))
    w = c.today(37.5, 127.0)
    assert (w.high_c, w.low_c, w.precip_prob) == (3.5, 0.0, 20)     # 다음 24시간 예보 + 지금 기온(3.46도)


def test_failures_do_not_block_briefing():
    c, _ = client(current=httpx.Response(401, json={"message": "Invalid API key"}))
    assert c.today(37.5, 127.0) is None
    c, _ = client(forecast=httpx.Response(500))                     # 예보만 실패: 현재 날씨만이라도
    assert c.today(37.5, 127.0) == Weather("맑음", 3.5, 3.5, 3.5, None, "Seoul")
    c, _ = client(current={"cod": 200}, forecast={"list": "bad"})   # 형식이 다른 응답
    assert c.today(37.5, 127.0) is None
    c, calls = client(current=httpx.Response(503))
    c.today(37.5, 127.0)
    c.today(37.5, 127.0)
    assert len(calls) == 2                                          # 실패는 캐시하지 않음


def test_build_without_key_skips_weather():
    assert isinstance(build_weather_client(Settings()), NoWeather) and NoWeather().today(37.5, 127.0) is None
    assert isinstance(build_weather_client(Settings(openweather_api_key="k")), OpenWeatherClient)
