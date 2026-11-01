"""루틴 프리셋 목록과 개인화(항목 빼기·되돌리기)."""
from dataclasses import dataclass

from backend.core.errors import NotFound, ValidationFailed
from backend.domain.models import RoutineTemplate, TemplateItem
from backend.repositories.registry import Repositories


@dataclass(frozen=True)
class Preset:
    template: RoutineTemplate
    items: list[TemplateItem]

    def total_sec(self, coaching_min: int) -> int:
        return sum((i.duration_10_sec if coaching_min == 10 else i.duration_20_sec) or 0 for i in self.items)


@dataclass(frozen=True)
class UserItem:
    item: TemplateItem
    enabled: bool


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

    def user_items(self, user_id: str, template_id: str) -> list[UserItem]:
        """프리셋 항목 + 사용자가 뺐는지. ROUTINE_MODE = CUSTOM 알람에만 적용된다."""
        excluded = self.r.routine_items.list_excluded(user_id)
        return [UserItem(i, i.template_item_id not in excluded) for i in self.preset(template_id).items]

    def set_enabled(self, user_id: str, template_item_id: str, enabled: bool) -> list[UserItem]:
        """항목 빼기(enabled=False) / 다시 넣기(True). 항목 이름·순서·시간은 바꿀 수 없다(정의서 10/31).

        모든 항목을 빼는 요청은 막는다(AI 파트의 대화 처리와 같은 규칙).
        """
        item = self.r.templates.get_item(template_item_id)
        if not item:
            raise NotFound("루틴 항목을 찾을 수 없어요", "TEMPLATE_ITEM_NOT_FOUND")
        if enabled:
            self.r.routine_items.restore(user_id, template_item_id)
        else:
            remaining = [u for u in self.user_items(user_id, item.template_id) if u.enabled and u.item.template_item_id != template_item_id]
            if not remaining:
                raise ValidationFailed("루틴 항목을 모두 뺄 수는 없어요", "LAST_ROUTINE_ITEM")
            self.r.routine_items.exclude(user_id, template_item_id)
        return self.user_items(user_id, item.template_id)
