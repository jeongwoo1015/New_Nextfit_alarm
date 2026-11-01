# backend — Backend Service (FastAPI)

앱 → **FastAPI 서버** → Supabase. Gemini Live는 앱이 서버가 발급한 임시 토큰으로 직접 연결한다.
기준 자료는 **Re:Spec 최종 DB 테이블 정의서(10/31 수정)** 이다.

| 폴더 | 역할 |
|---|---|
| `app/` | FastAPI 앱 생성, 라우터 등록 |
| `core/` | 설정(.env), 로그인 토큰 검증 → user_id, 오류 형식, KST 시간 |
| `domain/` | DB 없이 동작하는 규칙 — 알람 검사, 하루 한정 변경 대상 알람, 세션 스냅샷·단계 생성, 판정 반영 |
| `api/` `services/` | (다음 단계) 화면별 엔드포인트와 서비스 흐름 |
| `repositories/` | (다음 단계) DB 접근 인터페이스 + 테스트용 메모리 구현. Supabase 구현은 backend-db 담당 |
| `clients/` | (다음 단계) AI 파트 함수 어댑터, Gemini 임시 토큰, Storage |

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
