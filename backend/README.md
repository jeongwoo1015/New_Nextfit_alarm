# backend — Backend Service (FastAPI)

앱 → **FastAPI 서버** → Supabase. Gemini Live는 앱이 서버가 발급한 임시 토큰으로 직접 연결한다.
기준 자료는 **Re:Spec 최종 DB 테이블 정의서(11/01 수정)** 와 `feat/backend-db`의 `supabase/*.sql`이다.

| 폴더 | 역할 |
|---|---|
| `app/` | FastAPI 앱 생성, 라우터 등록 |
| `core/` | 설정(.env), 로그인 토큰 검증 → user_id, 오류 형식, KST 시간 |
| `domain/` | DB 없이 동작하는 규칙 — 알람 검사, 알람별 루틴 항목 시간, 하루 한정 변경 대상 알람, 세션 스냅샷·단계 생성, 판정 반영 |
| `api/` | 화면별 엔드포인트와 요청·응답 형식(`schemas.py`) |
| `services/` | 서비스 흐름 — 사용자 확인(USER_ID 비교), 검사, 저장 순서 |
| `repositories/` | DB 접근 인터페이스(`base.py`) + 메모리 구현 + 프리셋 시드. Supabase 구현은 backend-db 담당 |
| `clients/` | AI 파트 어댑터(`ai_gateway.py`: Live 토큰·추출·콘텐츠·개인화, `ai_stats.py`: 리포트·일기 통계) + 테스트용 `fake_ai.py` |
| `jobs/` | 배치. `maintenance.py`(30일 원문 정리, FAILED 재추출, 탈퇴)는 backend-db 담당 |

## API (5단계까지)

| 메서드 · 경로 | 화면 | 내용 |
|---|---|---|
| `GET /users/me` | 앱 시작 | 사용자·설정, `onboarding_done` |
| `POST /onboarding` | 온보딩 | 사용자·설정 저장 + 고른 유형(루틴 프리셋 / 콘텐츠 종류)으로 07:00 알람 생성 |
| `PATCH /users/me` | 내 정보 | 이름·생년월일·성별 |
| `DELETE /users/me` | 설정 | 탈퇴: 파일 먼저 지우고 Auth 계정 삭제(DB 행은 CASCADE), `jobs.maintenance.delete_account` |
| `GET /settings` · `PATCH /settings` | 내 정보·설정 | 호칭·어조·말투·목소리·음악 장르·취침 알림·테마·언어 |
| `GET /alarms` · `POST /alarms` | 홈 | 알람 목록(시각 순)·추가 |
| `GET` · `PATCH` · `DELETE /alarms/{id}` | 알람 설정 | 토글은 `PATCH {"enabled": false}` |
| `GET /alarms/{id}/routine-items` | 알람 세부 설정 | 항목별 시간(0 = 뺀 항목), 합계, 상한 |
| `PUT /alarms/{id}/routine-items` | 알람 세부 설정 | 항목 시간 전체 교체(30초 단위, 합계 ≤ 900초, 최소 한 항목). 보내지 않은 항목은 기본 시간 |
| `GET /presets` · `GET /presets/{id}` | 프리셋 선택 카드 | 활성 프리셋, 항목별 기본 시간(합계 600초) |
| `GET /config/limits` | 알람 세부 설정 | 루틴 합계 상한, 시간 단위, 콘텐츠 고정 시간 |
| `POST /night/token` | 굿나잇콜 시작 | 오늘·내일 일정을 보내면 지시문이 고정된 1회용 Live 토큰·시작 신호 |
| `POST /night/transcript` | 굿나잇콜 종료 | 받아쓰기 저장(202, `extract_status: PENDING`), 추출은 백그라운드 |
| `GET /night/records/{id}` · `GET /night/records?record_date=` | 밤 기록·일기 | 추출 결과, 하루 한정 변경, 목소리 서명 URL |
| `PUT /night/records/{id}/voice` | 굿나잇콜 | 목소리 m4a를 본문 그대로(`Content-Type: audio/mp4`, 10MB 이하) |
| `POST /morning/sessions` | 알람 울림 | `{alarm_id}` → 오늘 세션 만들기(하루 한정 변경 반영) 또는 기존 세션(재연결) |
| `POST /morning/sessions/{id}/rewake` · `/response` | 모닝콜 | 8초 무응답 재기상(AI 음악) 횟수 +1 / 첫 음성 응답 시각(= 기상 성공 시각) |
| `POST /morning/sessions/{id}/snooze` | 모닝콜 | "5분 뒤 다시 울리기" 횟수 +1 (제한 없음). 다시 울리면 `POST /morning/sessions`로 같은 세션 |
| `POST /morning/sessions/{id}/wake-check` | 브리핑 | 확인 멘트(2~3개)를 모두 마침 — 브리핑 Live의 `wake_check_done`을 앱이 전달. 이때 기상 성공 판정 |
| `POST /morning/sessions/{id}/briefing-token` | 브리핑 | 오늘 일정을 보내면 브리핑 Live 토큰. 그날 처음이면 개인화 전략을 정해 로그 |
| `POST /morning/sessions/{id}/coaching-token` | 루틴·콘텐츠 | `start_routine` 뒤(재연결도) 호출. 처음이면 단계를 PENDING으로 만들고 남은 단계만 토큰에 |
| `POST /morning/sessions/{id}/steps/{n}/verdict` | 코칭 | Live `report_step_result` 전달 |
| `PATCH /morning/sessions/{id}/steps/{n}` | 체크리스트 | 직접 체크·건너뛰기·해제 |
| `PATCH /morning/sessions/{id}/steps/{n}/duration` | 코칭 | 오늘 진행 중 변경("오늘 스트레칭 좀 적게", Live 함수를 앱이 전달). PENDING만, 30초 단위, 오늘 합계 ≤ 상한, 0초 = 오늘만 빼기 |
| `POST /morning/sessions/{id}/end` | 종료 | `end_session` 또는 통화 종료. 기상 성공 기록 |
| `GET /morning/sessions/{id}` · `GET /morning/sessions/today` | 루틴 화면 | 세션, 단계, 진행 개수(`finished` = "완료!") |
| `GET /reports?period=week\|month\|year&date=` | 리포트 | 상단 `overall` = 전체 기간 누적(기상 성공률·루틴 성공률), `totals`·`series` = 선택한 기간(평균 기상 시각, 루틴 완료 52/70, 그래프 칸 주: 월~일 / 월: 1주~5주 / 연: 1~12월), 현재·최장 연속 달성일 |
| `GET /diary/calendar?month=YYYY-MM` | 한 줄 일기 | 날짜별 사진 URL 또는 루틴 성공률, 세션·밤 기록 여부 |
| `GET /diary/{date}` | 일기 상세 | 그날 밤 기록(한 줄 일기)·아침 세션과 단계·사진·하루 요약. 세션은 알람이 먼저 울린 순 |
| `PUT /diary/{date}/photo` | 일기 사진 | jpg를 본문 그대로(`Content-Type: image/jpeg`, 10MB 이하, 미래 날짜 불가). 다시 올리면 덮어씀 |
| `GET /music/rewake` | 모닝콜 | 재기상 음악(설정의 음악 장르) 서명 URL, 12시간 유효(밤에 미리 받아 두기). 없으면 404 `MUSIC_NOT_FOUND` → 기본 알람음 |
| `GET /music/sleep?mood=` | 굿나잇콜 | 수면 음악(무드 = 오늘 밤 기록의 컨디션, 없으면 NORMAL) 서명 URL |

오류 응답은 모두 `{"error": {"code", "message", "details?"}}`. 전체 명세는 서버 실행 후 `/docs`(OpenAPI).

## Repository (backend-db 담당에게)
backend-db 초안(`repository_functions_draft.md`)의 함수 이름·묶음으로 `repositories/base.py`를 만들었다(같은 파일을
두 브랜치에서 고치면 main에서 충돌하므로 인터페이스는 backend-service가 관리한다). backend-db는 `supabase_*.py`에
구현하고 `backend/repositories/supabase.py`의 `build_repositories() -> Repositories`로 묶으면 `REPOSITORY_BACKEND=supabase`로 바뀐다.

| 묶음 | 인터페이스 | 메모리 구현 |
|---|---|---|
| 1. 사용자·설정 | `UserRepository` | 있음 |
| 2. 공용 데이터(프리셋·앱 설정·음악) | `PresetRepository` | 있음 (`pick_music`은 None) |
| 3. 알람 | `AlarmRepository` | 있음 |
| 4. 밤 기록 | `NightRepository` (+ `get_night` 추가) | 있음 |
| 5. 모닝 세션·단계 | `SessionRepository` | 있음 |
| 6. 개인화 | `PersonalizationRepository` | 있음 |
| 7. Storage | `StorageClient` | 있음 |

초안과 달라진 점
- 사용자 확인: 쿼리에 `user_id` 조건을 넣고 다른 사용자 행이면 None → 서버는 404 (403 대신, 데이터 존재 여부도 숨김).
- 알람 항목 시간: `replace_alarm_routine(user_id, alarm_id, overrides)` 전체 교체 하나 → DB 함수 `rs_replace_alarm_routine_items`
  (`supabase/06_alarm_items_replace.sql`, 합계 검사는 커밋 때).
- `get_night(user_id, night_record_id)` 추가(밤 원문 저장 직후 응답, 목소리 업로드).
- 추가: `update_user`, `delete_account`, `get_template`, `list_photos`, `get_night`.
- `AlarmRoutineItem`은 DB 함수 `rs_alarm_routine_items` 한 줄 + `default_duration_sec`(평평한 형태).
- `planned_total_sec`는 `MorningSession`에 두고 단계를 만든 뒤 `update_session`으로 채운다.
- 추출 결과는 AI가 11/01 이름(`override_skip_coaching`)으로 준다. `save_night_extract`는 11/01 컬럼 이름만 받는다.
- 재연결 때 전략 문장은 저장하지 않는다. `StrategyLog.strategy`로 AI의 `strategy_block()`을 다시 부르면 같은 문장이 나온다.
- 의존성 `supabase>=2.32,<3`은 `backend/requirements.txt`에 들어 있다.

## 실행

```bash
cd backend
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements.txt     # macOS/Linux: .venv/bin/python
.venv/Scripts/python -m pytest                               # 단위 테스트 (네트워크·DB 불필요)
cd .. && backend/.venv/Scripts/python -m uvicorn backend.app.main:app --reload
```

환경변수는 `backend/.env.example`을 복사해 `backend/.env`로 채운다(커밋하지 않음).

## 인증
`Authorization: Bearer <Supabase access token>`. `SUPABASE_JWT_SECRET`이 있으면 HS256, 없으면
`{SUPABASE_URL}/auth/v1/.well-known/jwks.json`으로 검증한다. 서버는 service_role 키를 쓰므로 RLS가 적용되지 않아,
모든 API가 토큰의 user_id와 데이터의 USER_ID를 직접 비교한다(`core.auth.ensure_owner`).

## AI 연동
`clients/ai_gateway.py`만 `ai.*`를 import한다. `ai/`(feat/ai 11/02판, CONTRACTS 5장 반영)와 `GEMINI_API_KEY`가 있으면 실제 함수를 부르고,
없으면 AI 기능은 503(`AI_UNAVAILABLE`)이다. 밤 토큰에는 루틴 항목 이름과 현재 시간(초)을 함께 넣고, 콘텐츠는 AI가 300초로
구성한 단계를 서버 설정 시간에 맞춰 늘이거나 줄인다.

밤 흐름: 토큰 발급 때 내일 울릴 알람(시각, 루틴이면 지금 쓰는 항목 이름)을 지시문에 넣는다 → 원문 저장 → 백그라운드에서
`extract_night_record` → 하루 한정 변경 대상 알람 지정 → `parse_routine_changes` → `apply_changes` → 범위별로 `replace_night_routine`(내일만) / `replace_alarm_routine`(앞으로 계속).
루틴 변경이 실패해도 밤 기록 저장은 유지한다. 추출이 실패하면 `FAILED`로 두고 원문은 남긴다(재추출 배치는 6단계).

아침 흐름: 세션 시작(스냅샷) → 브리핑 토큰(어젯밤 기록, 오늘 일정, 프리셋/콘텐츠 이름, 전략 문장) → `start_routine` →
코칭 토큰(처음이면 루틴 = 어젯밤 내일만 바꾼 값 또는 알람 항목 시간 중 0초 초과, 콘텐츠 = AI `compose_content_steps` → 단계 PENDING 생성,
`planned_total_sec` 기록) → 판정 반영 → 종료. 개인화는 그날 처음 브리핑 때 `decide()` 한 번, 로그 저장,
재연결 때는 로그의 `strategy`로 같은 문장(`strategy_blocks`). 개인화가 실패하면 기본 방식으로 진행한다.
AI 쪽 브리핑 입력의 `coaching_min`은 0(코칭 안 함)/10(진행)으로만 넘긴다(AI는 0인지만 본다). 콘텐츠 구성은 AI가 10/20분만 받아
가까운 쪽으로 만든 뒤 고정 시간에 맞춰 비율대로 조정한다.

리포트·일기 통계는 AI 파트 `ai.personalization.user_model`(`compute_stats`, `aggregate`, `period_series`, `day_summary`)을
그대로 쓴다(개인화와 화면이 같은 숫자를 보도록 AI 파트가 만든 함수, Gemini 키 불필요). 상단 성공률(누적)과 연속 달성일은 전체 기록으로 센다.
계산 가정(기상 시각 = 첫 응답, 세션 없는 날은 건너뛰되 3일 넘게 비면 연속이 끊김, 루틴 완료율 = DONE ÷ 전체)은 그쪽 설명을 따른다.

## 브랜치·파일 담당
역할별 브랜치끼리 병합해도 된다(11/01). 같은 파일을 두 브랜치에서 고치지 않는다: base.py·models·서비스·API·requirements·
메모리 구현·`jobs/personalization.py`는 backend-service, `repositories/supabase.py`·`jobs/maintenance.py`·`supabase/*.sql`은 backend-db,
`ai/`는 ai 담당. 이 브랜치에는 feat/backend-db와 feat/ai가 병합돼 있어 실제 `ai` 함수로 테스트한다(`tests/test_ai_real.py`).

## 기상 성공 기준 (11/01 팀 확정)
브리핑 전 확인 멘트 2~3개(예: "잘 주무셨어요?")에 모두 응답했고, 그때까지 ① 스누즈("5분 뒤 다시 울리기")를 한 번도 하지 않았고
② 8초 무응답으로 AI 재기상 음악이 한 번도 나오지 않았으면 성공. 기상 성공 시각은 첫 응답(`first_response_at`).
판정은 확인 멘트를 마친 순간(`POST .../wake-check`, `wake_checked_at`) 한 번만 하고, 그 뒤의 재기상·스누즈는 판정을 바꾸지 않는다.
실패해도 브리핑·루틴은 그대로 진행한다. 확인 멘트를 끝내지 못한 채 세션이 끝나면 실패(기본값 FALSE)로 남는다.
DB에 `rs_morning_session.snooze_count`, `wake_checked_at` 컬럼이 필요하다(backend-db에 요청).

## 정의서에서 정하지 않아 기본안으로 구현한 것 (팀 확인 필요)
- AI 판정 `not_done` → `STEP_STATUS = SKIPPED` (AI 파트 CONTRACTS 1-3 제안). `ambiguous`/`other`는 상태를 바꾸지 않고 `AI_VERDICT`만 남긴다.
- 하루 한정 변경 대상 알람(`OVERRIDE_ALARM_ID`): 밤 기록이 적용되는 아침(`APPLY_DATE`)에 가장 먼저 울리는 켜진 알람. 그날 울릴 알람이 없으면 변경값을 저장하지 않는다.
- `REPEAT_DAYS = '0000000'`은 울리지 않는 알람으로 본다(1회성 알람은 정의서에 없음).
- 온보딩: 기본 정보 → 페르소나 → 루틴/콘텐츠 선택 → 프리셋 또는 콘텐츠 종류. 고른 유형으로 07:00 알람 1개를 만든다.
  콘텐츠 세부(`CONTENT_OPTION`)는 알람 세부 설정에서 고른다. 사용자 행은 온보딩 때 만든다(이름·생년월일이 NOT NULL).
- 콘텐츠 고정 시간은 5분(팀 확정, DB 시드 `content_duration_sec = 300`). 값이 비어 있어도 300초로 진행한다.
- 루틴 항목 시간을 바꾸는 범위는 셋(정의서 11/01 팀 확정): **앞으로 계속** = 알람 설정(`replace_alarm_routine`),
  **내일만** = 밤 대화의 기본(`replace_night_routine`, 알람 설정 위에 덮어씀, 대상 알람은 다음 날 아침에 울릴 알람),
  **오늘 진행 중** = 코칭 중 단계 시간 변경(`update_step_duration`, 0초는 SKIPPED). 내일 울릴 알람이 있으면 밤 기록에
  `override_alarm_id`를 항상 남긴다. AI가 항목별 시간과 범위를 준다(범위를 안 주면 '내일만', `clients.ai_gateway.to_item_changes`).
  규칙(30초 단위·합계 상한·최소 한 항목)에 걸리는 요청은 반영하지 않는다(`routine_rules.apply_changes`).
- 밤 대화에서 하루 한정 변경에 동의했는데 다음 날 아침에 울릴 알람이 없으면 변경값을 저장하지 않는다.

- 개인화 Bandit 상태는 전체 합산(`state_key = 'global'`), 최근 7일 기록으로 결정. 보상 기록·학습(`set_reward`, `learn`)은 6단계 하루 끝 배치.
- 리포트 월간 그래프는 날짜별이 아니라 주 단위(1주~5주, AI `period_series` 기준) — 앱 화면과 맞출 것.
- 일기 사진은 오늘 이전 날짜만 받는다.
- 업로드 Content-Type은 Storage 버킷 허용 형식과 같게 검사한다(목소리 `audio/mp4`·`audio/x-m4a`·`audio/m4a`·`audio/aac`, 사진 `image/jpeg`, 다르면 415).
- 음악 파일(RS_AI_MUSIC 행 + music 버킷 mp3)은 아직 만든 사람이 없다. AI 파트 `ai/tasks/music.py`의 프롬프트로 생성·업로드할 담당을 정해야 한다.
- 날씨는 지역 정보가 정의서에 없어 아직 넘기지 않는다(AI 브리핑은 날씨를 건너뜀).
- 대화 원문 role은 `user` / `assistant`만 받는다(AI CONTRACTS 2-2 권고). 사용자 발화가 없으면 422(`EMPTY_TRANSCRIPT`).
- `MUSIC_GENRE`는 NOT NULL인데 기본값이 없어 온보딩에서 안 보내면 `POP`.
- 메모리 시드의 고정 스크립트는 표시(`[stretch_short]`, `[lymph]`)만 있다. 실제 문구는 DB 시드에 있다.
