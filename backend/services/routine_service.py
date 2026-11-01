"""루틴 프리셋 목록 (프리셋 선택 카드)과 앱 설정값."""
from dataclasses import dataclass

from backend.core.errors import NotFound
from backend.domain import codes
from backend.domain.models import RoutineTemplate, TemplateItem
from backend.domain.session_rules import content_duration_sec
from backend.repositories.registry import Repositories


@dataclass(frozen=True)
class Preset:
    template: RoutineTemplate
    items: list[TemplateItem]

    @property
    def total_sec(self) -> int:
        return sum(i.default_duration_sec for i in self.items)


@dataclass(frozen=True)
class RoutineLimits:
    routine_max_sec: int
    duration_step_sec: int
    content_duration_sec: int


class RoutineService:
    def __init__(self, repos: Repositories):
        self.r = repos

    def presets(self) -> list[Preset]:
        return [Preset(t, self.r.templates.list_items(t.template_id)) for t in self.r.templates.list_templates(active_only=True)]

    def preset(self, template_id: str) -> Preset:
        t = self.r.templates.get_template(template_id)
        if not t:
            raise NotFound("루틴 프리셋을 찾을 수 없어요", "TEMPLATE_NOT_FOUND")
        return Preset(t, self.r.templates.list_items(template_id))

    def limits(self) -> RoutineLimits:
        cfg = self.r.config.get()
        return RoutineLimits(cfg.routine_max_sec, codes.DURATION_STEP_SEC, content_duration_sec(cfg))
