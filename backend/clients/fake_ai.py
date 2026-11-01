"""테스트·개발용 AI 어댑터. 정해 둔 값을 돌려주고 받은 입력을 기록한다."""
from dataclasses import dataclass, field

from .ai_gateway import LiveToken, NightContext, NightExtract, RoutineChangeRequest


@dataclass
class FakeAIGateway:
    extract: NightExtract = field(default_factory=lambda: NightExtract("DONE", {"ai_summary": "요약", "tomorrow_notes": []}))
    routine: RoutineChangeRequest = field(default_factory=RoutineChangeRequest)
    calls: list = field(default_factory=list)

    def issue_night_token(self, ctx: NightContext) -> LiveToken:
        self.calls.append(("night_token", ctx))
        return LiveToken("tok-night", "wss://example/live", "models/fake-live", "대화를 시작해 주세요.", "2026-11-01T15:30:00Z")

    def extract_night_record(self, transcript):
        self.calls.append(("extract", transcript))
        return self.extract

    def parse_routine_changes(self, transcript, items):
        self.calls.append(("routine", [i.template_item_id for i in items]))
        return self.routine
