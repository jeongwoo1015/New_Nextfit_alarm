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
| `clients/` | (다음 단계) AI 파트 함수 어댑터, Gemini 임시 토큰, Storage |

## API (2단계까지)

| 메서드 · 경로 | 화면 | 내용 |
|---|---|---|
| `GET /users/me` | 앱 시작 | 사용자·설정, `onboarding_done` |
| `POST /onboarding` | 온보딩 | 사용자·설정 저장 + 고른 유형(루틴 프리셋 / 콘텐츠 종류)으로 07:00 알람 생성 |
| `PATCH /users/me` | 내 정보 | 이름·생년월일·성별 |
| `GET /settings` · `PATCH /settings` | 내 정보·설정 | 호칭·어조·말투·목소리·음악 장르·취침 알림·테마·언어 |
| `GET /alarms` · `POST /alarms` | 홈 | 알람 목록(시각 순)·추가 |
| `GET` · `PATCH` · `DELETE /alarms/{id}` | 알람 설정 | 토글은 `PATCH {"enabled": false}` |
| `GET /alarms/{id}/routine-items` | 알람 세부 설정 | 항목별 시간(0 = 뺀 항목), 합계, 상한 |
| `PUT /alarms/{id}/routine-items` | 알람 세부 설정 | 항목 시간 전체 교체(30초 단위, 합계 ≤ 900초, 최소 한 항목). 보내지 않은 항목은 기본 시간 |
| `GET /presets` · `GET /presets/{id}` | 프리셋 선택 카드 | 활성 프리셋, 항목별 기본 시간(합계 600초) |
| `GET /config/limits` | 알람 세부 설정 | 루틴 합계 상한, 시간 단위, 콘텐츠 고정 시간 |

오류 응답은 모두 `{"error": {"code", "message", "details?"}}`. 전체 명세는 서버 실행 후 `/docs`(OpenAPI).

## Repository (backend-db 담당에게)
`repositories/base.py`의 인터페이스를 `backend/repositories/supabase.py`에 구현하고, 모듈에
`build_repositories() -> Repositories`를 두면 `REPOSITORY_BACKEND=supabase`로 바뀐다. 반환 형식은
`domain/models.py` 데이터 클래스이고, 세부 규칙(없으면 None, id는 DB가 생성 등)은 `base.py` 맨 위에 있다.
메모리 구현(`repositories/memory.py`)이 같은 동작의 예시다.

**요청: 알람 항목 시간 한 번에 바꾸는 DB 함수.** `rs_alarm_routine_item` 트리거가 행마다 합계 상한·최소 1개를 검사해서,
여러 항목을 한 행씩 바꾸면 중간 상태에서 실패할 수 있다(예: A를 늘린 뒤 B를 줄이는 순서). 서버는 항목 시간을 전체 교체로만
보내므로(`AlarmRoutineItemRepository.replace`), 이것을 한 트랜잭션으로 처리하는 함수(예: `rs_set_alarm_routine_items(alarm_id, jsonb)`)를
두고 `replace`에서 RPC로 불러 주면 좋겠다. 함수 전에는 `domain.routine_rules.safe_write_order()` 순서로 쓰면 대부분 통과한다.

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

## 정의서에서 정하지 않아 기본안으로 구현한 것 (팀 확인 필요)
- AI 판정 `not_done` → `STEP_STATUS = SKIPPED` (AI 파트 CONTRACTS 1-3 제안). `ambiguous`/`other`는 상태를 바꾸지 않고 `AI_VERDICT`만 남긴다.
- 하루 한정 변경 대상 알람(`OVERRIDE_ALARM_ID`): 밤 기록이 적용되는 아침(`APPLY_DATE`)에 가장 먼저 울리는 켜진 알람. 그날 울릴 알람이 없으면 변경값을 저장하지 않는다.
- `REPEAT_DAYS = '0000000'`은 울리지 않는 알람으로 본다(1회성 알람은 정의서에 없음).
- 온보딩: 기본 정보 → 페르소나 → 루틴/콘텐츠 선택 → 프리셋 또는 콘텐츠 종류. 고른 유형으로 07:00 알람 1개를 만든다.
  콘텐츠 세부(`CONTENT_OPTION`)는 알람 세부 설정에서 고른다. 사용자 행은 온보딩 때 만든다(이름·생년월일이 NOT NULL).
- 콘텐츠 고정 시간(`RS_APP_CONFIG.CONTENT_DURATION_SEC`)이 비어 있으면 600초로 진행한다.
- 밤 대화로 루틴 항목을 빼거나 다시 넣으면 **다음 날 아침에 울릴 알람**(하루 한정 변경과 같은 대상)에만 적용한다.
  빼기 = 0초, 다시 넣기 = 기본 시간. 규칙(합계 상한·최소 한 항목)에 걸리는 요청은 반영하지 않는다(`routine_rules.apply_conversation`).
- `MUSIC_GENRE`는 NOT NULL인데 기본값이 없어 온보딩에서 안 보내면 `POP`.
- 메모리 시드의 고정 스크립트는 표시(`[stretch_short]`, `[lymph]`)만 있다. 실제 문구는 DB 시드에 있다.
