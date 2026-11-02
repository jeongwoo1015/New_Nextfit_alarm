from dataclasses import replace
from datetime import date, time

import pytest

from backend.clients.ai_gateway import (RealAIGateway, UnavailableAIGateway, build_ai_gateway, fit_seconds, night_view,
                                        to_night_record_fields)
from backend.core.config import Settings
from backend.core.errors import ServiceUnavailable
from backend.domain.models import NightRecord

AI_ROW = {"ai_summary": "발표를 마쳤다", "tomorrow_goal": "보고서 끝내기", "tomorrow_notes": ["10시 회의"],
          "condition_label": "TIRED", "condition_text": "지쳤지만 뿌듯함", "encouragement": "잘했어요", "insight": "",
          "diary_text": "발표 끝!", "override_wake_time": "06:40", "override_skip_coaching": True, "extract_status": "DONE"}


def test_extract_row_maps_to_1101_columns():
    f = to_night_record_fields(AI_ROW)
    assert f["override_wake_time"] == time(6, 40) and f["override_skip_coaching"] is True
    assert "extract_status" not in f
    assert f["tomorrow_notes"] == ["10시 회의"] and f["condition_label"] == "TIRED"


@pytest.mark.parametrize("value, skip", [(True, True), (False, False), (None, False), ("true", False)])
def test_skip_coaching_only_when_true(value, skip):
    assert to_night_record_fields({**AI_ROW, "override_skip_coaching": value})["override_skip_coaching"] is skip


def test_missing_wake_time_is_none():
    assert to_night_record_fields({**AI_ROW, "override_wake_time": None})["override_wake_time"] is None


def test_without_key_gateway_is_unavailable():
    gw = build_ai_gateway(Settings())
    assert isinstance(gw, UnavailableAIGateway)
    with pytest.raises(ServiceUnavailable):
        gw.extract_night_record([])
    assert gw.strategy_blocks({"strategy_id": "gentle"}) == ("", "")      # 전략 문장이 없으면 기본 방식


def test_with_key_and_ai_package_gateway_is_real():
    assert isinstance(build_ai_gateway(Settings(gemini_api_key="k")), RealAIGateway)   # feat/ai 병합 후


def test_fit_seconds_keeps_ratio_and_total():
    assert sum(fit_seconds([200, 400], 600)) == 600 and fit_seconds([200, 400], 600) == [200, 400]
    assert sum(fit_seconds([120, 120, 120], 480)) == 480                       # 10분 구성을 8분 고정 시간에 맞춤
    assert fit_seconds([None, 0, 300], 300)[0] >= 10


def test_night_view_only_for_extracted_record():
    rec = NightRecord("r", "u", date(2026, 11, 5), date(2026, 11, 6), tomorrow_goal="보고서", tomorrow_notes=("회의",))
    assert night_view(rec) is None                                              # PENDING
    v = night_view(replace(rec, extract_status="DONE"))
    assert v.tomorrow_goal == "보고서" and v.tomorrow_notes == ("회의",) and v.ai_summary == ""
