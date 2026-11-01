"""매일 한 번 돌리는 데이터 정리 작업 (보관·삭제 정책).

실행: 저장소 루트에서 backend/.venv/Scripts/python -m backend.jobs.maintenance
      (REPOSITORY_BACKEND=supabase. 재추출은 GEMINI_API_KEY와 ai 패키지가 있어야 실제로 다시 추출된다)

1. 30일 지난 밤 대화 원문 비우기: 받아쓰기·원본 음성 경로를 비우고 night-raw 파일을 지운다. 요약(RS_NIGHT_RECORD)은 남는다.
2. 추출 실패(FAILED) 밤 기록 다시 추출: 원문이 남아 있는 동안 매일 다시 시도한다.
   하루 한정 변경 대상 알람은 원래 통화가 끝난 시각 기준으로 고른다(재시도한 날 기준이 아님).
"""
import logging
from dataclasses import dataclass, field
from datetime import datetime

from backend.core.timeutil import now_kst
from backend.repositories.registry import Repositories
from backend.services.night_service import NightService

log = logging.getLogger(__name__)

RETRY_LIMIT = 20   # 한 번에 다시 추출할 최대 개수 (AI 호출 비용)


@dataclass
class MaintenanceReport:
    expired_audio: int = 0            # 비운 원본 음성 경로 수 (받아쓰기는 개수를 따로 세지 않는다)
    deleted_files: int = 0
    retried: int = 0
    recovered: int = 0
    errors: list[str] = field(default_factory=list)


def purge_expired_raw(repos: Repositories, now: datetime, report: MaintenanceReport) -> None:
    paths = repos.nights.purge_expired_raw(now)
    report.expired_audio = len(paths)
    if paths:
        try:
            report.deleted_files = repos.storage.delete_files("night-raw", paths)
        except Exception as e:   # DB는 이미 비웠으므로 남은 파일은 계정 삭제 때 함께 지워진다
            report.errors.append(f"원본 음성 파일 삭제 실패 {len(paths)}개: {e}")


def retry_failed_extracts(repos: Repositories, ai, now: datetime, report: MaintenanceReport, limit: int = RETRY_LIMIT) -> None:
    svc = NightService(repos, ai, lambda: now)
    for user_id, night_record_id in repos.nights.list_failed_extracts(limit):
        raw = repos.nights.get_night_raw(night_record_id)
        saved_at = (raw.ended_at or raw.started_at) if raw else None
        report.retried += 1
        try:
            if svc.extract(user_id, night_record_id, saved_at).status == "DONE":
                report.recovered += 1
        except Exception as e:
            report.errors.append(f"재추출 실패 {night_record_id}: {e}")


def delete_account(repos: Repositories, user_id: str) -> int:
    """탈퇴: Storage 파일을 먼저 지우고 Auth 계정을 지운다(DB 행은 CASCADE). 지운 파일 수를 돌려준다."""
    count = repos.storage.delete_user_files(user_id)
    repos.users.delete_account(user_id)
    return count


def run(repos: Repositories, ai, now: datetime | None = None) -> MaintenanceReport:
    now = now or now_kst()
    report = MaintenanceReport()
    purge_expired_raw(repos, now, report)
    retry_failed_extracts(repos, ai, now, report)
    return report


def main() -> None:
    from backend.clients.ai_gateway import build_ai_gateway
    from backend.core.config import get_settings
    from backend.repositories.registry import build_repositories

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)
    settings = get_settings()
    report = run(build_repositories(settings.repository_backend), build_ai_gateway(settings))
    log.info("원문 정리 완료(원본 음성 %d개, 지운 파일 %d개), 재추출 %d건 중 %d건 성공", report.expired_audio, report.deleted_files,
             report.retried, report.recovered)
    for e in report.errors:
        log.warning(e)


if __name__ == "__main__":
    main()
