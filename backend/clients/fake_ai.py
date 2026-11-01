"""테스트·개발용 AI 어댑터. 정해 둔 값을 돌려주고 받은 입력을 기록한다."""
from dataclasses import dataclass, field

from .ai_gateway import (ContentStep, LiveToken, NightContext, NightExtract, RoutineChangeRequest, StrategyDecision,
                         fit_seconds)

QUIZ = [ContentStep(f"퀴즈 {n}번", 120, "SPEECH", f"문제: 문제 {n}\n정답: 정답 {n}") for n in range(1, 4)]


def _token(kind: str) -> LiveToken:
    return LiveToken(f"tok-{kind}", "wss://example/live", "models/fake-live", f"{kind} 시작 신호", "2026-11-01T15:30:00Z")


@dataclass
class FakeAIGateway:
    extract: NightExtract = field(default_factory=lambda: NightExtract("DONE", {"ai_summary": "요약", "tomorrow_notes": []}))
    routine: RoutineChangeRequest = field(default_factory=RoutineChangeRequest)
    content: list[ContentStep] = field(default_factory=lambda: list(QUIZ))
    strategy: dict = field(default_factory=lambda: {"coaching_intensity": "soft", "briefing_length": "normal",
                                                    "encouragement": "high", "strategy_id": "gentle"})
    calls: list = field(default_factory=list)

    def issue_night_token(self, ctx: NightContext) -> LiveToken:
        self.calls.append(("night_token", ctx))
        return _token("night")

    def extract_night_record(self, transcript):
        self.calls.append(("extract", transcript))
        return self.extract

    def parse_routine_changes(self, transcript, items):
        self.calls.append(("routine", [i.template_item_id for i in items]))
        return self.routine

    def issue_briefing_token(self, ctx):
        self.calls.append(("briefing_token", ctx))
        return _token("briefing")

    def issue_coaching_token(self, ctx):
        self.calls.append(("coaching_token", ctx))
        return _token("coaching")

    def compose_content_steps(self, content_type, content_option, duration_sec, today):
        self.calls.append(("content", (content_type, content_option, duration_sec)))
        secs = fit_seconds([s.planned_sec for s in self.content], duration_sec)
        return [ContentStep(s.name, sec, s.response_type, s.script) for s, sec in zip(self.content, secs)]

    def decide_strategy(self, user_id, records, today, condition, speech_style, mode, bandit_state):
        self.calls.append(("decide", (user_id, today, condition, speech_style, mode, len(records.sessions))))
        b, c = self.strategy_blocks(self.strategy)
        row = {"user_id": user_id, "session_date": today.isoformat(), "source": "rule", "mode": mode,
               "strategy_id": self.strategy["strategy_id"], "strategy": self.strategy}
        return StrategyDecision(self.strategy, b, c, row)

    def strategy_blocks(self, strategy):
        sid = (strategy or {}).get("strategy_id", "default")
        return (f"[브리핑 전략 {sid}]", f"[코칭 전략 {sid}]") if sid != "default" else ("", "")
