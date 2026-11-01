"""초기 프리셋 시드 — feat/backend-db `supabase/03_seed_presets.sql`과 같은 값(메모리 저장소·테스트용).

프리셋 ID는 DB와 같은 고정값이다. 항목 ID는 DB가 만들기 때문에 여기서는 임의 값이다(항목은 이름·순서로 찾는다).
고정 스크립트(SCRIPT) 실제 문구는 DB 시드에만 있다. 여기서는 스크립트가 있는 항목인지만 표시한다.
"""
import uuid

from backend.domain.models import RoutineTemplate, TemplateItem

_NS = uuid.UUID("6f1c2a52-0d6b-4f3e-9c1e-2b7a5d0c9e10")
STRETCH, LYMPH = "[stretch_short]", "[lymph]"

# (프리셋 키, 고정 ID, 이름, [(항목명, 기본 시간 초, 응답 유형, 스크립트)])
PRESETS = [
    ("M", "00000000-0000-4000-8000-0000000000a1", "아침 붓기 삭제 · 셀프마사지 루틴", [
        ("스트레칭 (축소)", 120, "PLAY", STRETCH), ("햇빛 보기", 60, "QA", None), ("물 한 잔", 60, "QA", None),
        ("세안", 120, "QA", None), ("목·얼굴 셀프마사지", 240, "PLAY", LYMPH)]),
    ("N", "00000000-0000-4000-8000-0000000000a2", "간단하게 하루를 바꾸는 현실 모닝루틴", [
        ("스트레칭 (축소)", 180, "PLAY", STRETCH), ("햇빛 보기", 60, "QA", None), ("물 한 잔", 60, "QA", None),
        ("이불 정리", 60, "QA", None), ("세수·양치", 120, "QA", None), ("오늘 할 일 3개 말하기", 120, "SPEECH", None)]),
    ("O", "00000000-0000-4000-8000-0000000000a3", "아침 15분 영어 습관루틴", [
        ("스트레칭 (축소)", 120, "PLAY", STRETCH), ("햇빛 보기", 30, "QA", None), ("물 한 잔", 30, "QA", None),
        ("세수·양치", 60, "QA", None), ("AI 영어 대화", 360, "SPEECH", None)]),
    ("P", "00000000-0000-4000-8000-0000000000a4", "하루를 차분하게 여는 웰니스 모닝 루틴", [
        ("햇빛 보기", 60, "QA", None), ("물 한 잔", 60, "QA", None), ("명상", 180, "PLAY", None),
        ("독서", 180, "QA", None), ("모닝일기", 120, "SPEECH", None)]),
    ("Q", "00000000-0000-4000-8000-0000000000a5", "갓생도전 · 유산소 모닝루틴", [
        ("스트레칭 (축소)", 180, "PLAY", STRETCH), ("물 한 잔", 60, "QA", None), ("유산소 (제자리 러닝 등)", 360, "PLAY", None)]),
]

TEMPLATE_IDS = {key: tid for key, tid, _, _ in PRESETS}


def template_id(key: str) -> str:
    return TEMPLATE_IDS[key]


def seed_templates() -> tuple[list[RoutineTemplate], list[TemplateItem]]:
    templates, items = [], []
    for key, tid, name, rows in PRESETS:
        templates.append(RoutineTemplate(tid, name))
        for order, (item_name, sec, rtype, script) in enumerate(rows, 1):
            items.append(TemplateItem(str(uuid.uuid5(_NS, f"item:{key}:{order}")), tid, item_name, order, sec, rtype, script))
    return templates, items
