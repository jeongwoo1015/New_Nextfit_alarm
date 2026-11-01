"""정의서 "초기 프리셋 시드 데이터(5종)" — 메모리 저장소와 테스트에서 쓴다. DB 시드는 backend-db 담당.

시간은 초 단위, None은 그 버전에서 진행하지 않는 항목. 고정 스크립트(SCRIPT) 문구는 아직 받지 못해
비워 둔다(정의서에는 "스트레칭", "림프 순환" 표시만 있음). 값이 있으면 AI가 그대로 읽으므로 임시 문구를 넣지 않는다.
"""
import uuid

from backend.domain.models import RoutineTemplate, TemplateItem

_NS = uuid.UUID("6f1c2a52-0d6b-4f3e-9c1e-2b7a5d0c9e10")

# (프리셋 키, 이름, [(항목명, 10분 버전 초, 20분 버전 초, 응답 유형)])
PRESETS = [
    ("M", "아침 붓기 삭제 · 셀프마사지 루틴", [
        ("스트레칭(축소)", 120, 300, "PLAY"), ("햇빛 보기", 60, 60, "QA"), ("물 한 잔", 60, 60, "QA"),
        ("세안", 120, 180, "QA"), ("목·얼굴 셀프마사지", 240, 300, "PLAY"), ("림프 스트레칭", None, 180, "PLAY"),
        ("스킨케어", None, 120, "QA")]),
    ("N", "간단하게 하루를 바꾸는 현실 모닝루틴", [
        ("스트레칭(축소)", 180, 300, "PLAY"), ("햇빛 보기", 60, 60, "QA"), ("물 한 잔", 60, 60, "QA"),
        ("이불 정리", 60, 60, "QA"), ("세수·양치", 120, 180, "QA"), ("오늘 할 일 3개 말하기", 120, 240, "SPEECH"),
        ("가장 중요한 일 1개와 시작 시각", None, 180, "SPEECH"), ("하루 목표 한 줄", None, 120, "SPEECH")]),
    ("O", "아침 15분 영어 습관루틴", [
        ("스트레칭(축소)", 120, 240, "PLAY"), ("햇빛 보기", 30, 60, "QA"), ("물 한 잔", 30, 60, "QA"),
        ("세수·양치", 60, 120, "QA"), ("AI 영어 대화", 360, 720, "SPEECH")]),
    ("P", "하루를 차분하게 여는 웰니스 모닝 루틴", [
        ("스트레칭(축소)", None, 240, "PLAY"), ("햇빛 보기", 60, 60, "QA"), ("물 한 잔", 60, 60, "QA"),
        ("명상", 180, 300, "PLAY"), ("독서", 180, 360, "QA"), ("모닝일기", 120, 180, "SPEECH")]),
    ("Q", "갓생도전 · 유산소 모닝루틴", [
        ("스트레칭(축소)", 180, 300, "PLAY"), ("물 한 잔", 60, 60, "QA"), ("유산소(제자리 러닝 등)", 360, 600, "PLAY"),
        ("샤워", None, 240, "QA")]),
]


def template_id(key: str) -> str:
    return str(uuid.uuid5(_NS, f"template:{key}"))


def seed_templates() -> tuple[list[RoutineTemplate], list[TemplateItem]]:
    templates, items = [], []
    for key, name, rows in PRESETS:
        tid = template_id(key)
        templates.append(RoutineTemplate(tid, name))
        for order, (item_name, d10, d20, rtype) in enumerate(rows, 1):
            items.append(TemplateItem(str(uuid.uuid5(_NS, f"item:{key}:{order}")), tid, item_name, order, d10, d20, rtype))
    return templates, items
