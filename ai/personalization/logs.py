"""전략 결정 로그. 매 세션 '어떤 전략을 왜 골랐는지'를 남겨야 나중에 효과를 평가할 수 있다(Bandit의 선택 확률 포함).

to_row()는 DB 테이블 한 행으로 넣을 수 있는 평평한 dict이다(중첩 값은 JSON 문자열). 로그 테이블은 정의서에 아직 없어
서버에 요청 중이며, 그전까지는 append_jsonl()로 파일에 남긴다.
"""
import json
from dataclasses import asdict, dataclass, field, fields
from datetime import datetime, timezone

JSON_FIELDS = ("strategy", "rule_ids", "allowed_arms", "stats")


@dataclass
class DecisionLog:
    user_id: str
    session_date: str
    source: str                         # rule / bandit / default / fallback
    strategy_id: str
    strategy: dict
    mode: str = ""                      # off / rule / bandit / ab
    rule_ids: list = field(default_factory=list)
    context_key: str | None = None      # Bandit이 본 상황 칸 (예: "low|tired")
    propensity: float | None = None     # Bandit이 이 전략을 고를 확률 (효과 평가에 필수)
    allowed_arms: list = field(default_factory=list)
    explored: bool | None = None        # 점수가 가장 좋은 전략이 아니라 무작위 시도로 골랐는가
    ab_group: str | None = None         # A/B일 때 배정된 쪽 (rule / bandit)
    ab_propensity: float | None = None  # 그쪽으로 배정될 확률
    stats: dict = field(default_factory=dict)
    prompt_version: str = ""
    error: str | None = None
    created_at: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat(timespec="seconds"))

    def to_row(self) -> dict:
        row = asdict(self)
        for key in JSON_FIELDS:
            row[key] = json.dumps(row[key], ensure_ascii=False)
        return row

    @classmethod
    def from_row(cls, row: dict) -> "DecisionLog":
        names = {f.name for f in fields(cls)}
        data = {k: v for k, v in (row or {}).items() if k in names}
        for key in JSON_FIELDS:
            if isinstance(data.get(key), str):
                try:
                    data[key] = json.loads(data[key])
                except json.JSONDecodeError:
                    data.pop(key)
        data.setdefault("strategy", {})     # 깨진 값이어도 읽기는 실패하지 않는다
        return cls(**data)

    def append_jsonl(self, path: str) -> None:
        with open(path, "a", encoding="utf-8") as f:
            f.write(json.dumps(asdict(self), ensure_ascii=False) + "\n")
