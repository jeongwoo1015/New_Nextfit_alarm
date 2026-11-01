"""DB 접근 인터페이스. Supabase 구현은 backend-db 담당이 같은 메서드로 만든다.

규칙
- 테이블 이름·컬럼은 정의서(소문자). 반환은 `backend.domain.models`의 데이터 클래스.
- 없으면 None(get) 또는 빈 목록(list). 예외는 DB 오류일 때만.
- PK가 gen_random_uuid()인 테이블은 create가 id를 채운 객체를 돌려준다(인자로 받은 id는 무시해도 된다).
- created_at·updated_at은 DB 기본값/트리거가 채운다. 반환 객체에 그 값을 담아 준다.
- 사용자 확인(USER_ID 비교)은 서비스가 한다. Repository는 service_role로 그대로 읽고 쓴다.
"""
from typing import Protocol

from backend.domain.models import Alarm, RoutineTemplate, TemplateItem, User, UserSetting


class UserRepository(Protocol):
    """rs_user"""
    def get(self, user_id: str) -> User | None: ...
    def create(self, user: User) -> User: ...
    def update(self, user: User) -> User: ...


class UserSettingRepository(Protocol):
    """rs_user_setting (PK = user_id)"""
    def get(self, user_id: str) -> UserSetting | None: ...
    def upsert(self, setting: UserSetting) -> UserSetting: ...


class AlarmRepository(Protocol):
    """rs_alarm"""
    def list_by_user(self, user_id: str) -> list[Alarm]: ...      # alarm_time 오름차순
    def get(self, alarm_id: str) -> Alarm | None: ...
    def create(self, alarm: Alarm) -> Alarm: ...
    def update(self, alarm: Alarm) -> Alarm: ...                   # alarm_id 기준 전체 컬럼 갱신
    def delete(self, alarm_id: str) -> None: ...                    # 세션의 alarm_id는 FK ON DELETE SET NULL


class RoutineTemplateRepository(Protocol):
    """rs_routine_template + rs_routine_template_item (공용, 읽기 전용)"""
    def list_templates(self, active_only: bool = True) -> list[RoutineTemplate]: ...
    def get_template(self, template_id: str) -> RoutineTemplate | None: ...
    def list_items(self, template_id: str) -> list[TemplateItem]: ...   # item_order 오름차순
    def get_item(self, template_item_id: str) -> TemplateItem | None: ...


class UserRoutineItemRepository(Protocol):
    """rs_user_routine_item — 사용자가 뺀 항목만 한 줄씩(IS_ENABLED = FALSE). 다시 넣으면 행을 지운다."""
    def list_excluded(self, user_id: str) -> set[str]: ...           # 뺀 template_item_id 집합
    def exclude(self, user_id: str, template_item_id: str) -> None: ...  # upsert, is_enabled=false, updated_at=now()
    def restore(self, user_id: str, template_item_id: str) -> None: ...  # 행 삭제 (없어도 오류 아님)
