# AI 파트 ↔ 팀 전달 사항 (CONTRACTS)

## 0. 한눈에 보기

```
[앱]  ── 토큰 요청(+일정) ──▶  [서버]  ── 지시문 생성 → 임시 토큰 발급 ──▶ [앱]
[앱]  ══ 토큰으로 Gemini Live 직접 연결 (음성) ══▶ Gemini
[앱]  ── 통화 종료 후 원문 [{role,text,at}] ──▶ [서버] ── 추출 함수 호출 ──▶ [DB]
```

| AI 파트가 제공 | 위치 | 쓰는 사람 |
|---|---|---|
| Live 설정 조립 (모델, 지시문, 함수 선언, 목소리) | `ai/live/config.py` `build_setup(kind, inp)` | 서버 |
| 임시 토큰 요청 본문 | `ai/live/config.py` `token_request_body(setup)` | 서버 |
| 입력 형식 (정의서 컬럼 기준) | `ai/live/inputs.py` | 서버 |
| 통화 후 추출 / 콘텐츠 단계 / 항목 빼기 / 음악 프롬프트 | `ai/tasks/` | 서버 |
| 개인화 (오늘의 말하는 방식 결정, 결과 학습, 전략 로그) | `ai/personalization/` (6절) | 서버 |
| 앱 대역 PC 프로그램과 자동 시나리오 | `ai/devclient/` | AI 파트 (참고용) |

환경변수: `GEMINI_API_KEY`(서버에만 둔다), 선택 `GEMINI_TEXT_MODELS`(쉼표로 구분한 텍스트 모델 순서).

## 1. 서버 담당

### 1-1. 임시 토큰 발급 (대화 시작 전)
`kind`는 `night` / `briefing` / `coaching`입니다.

```python
from ai.live.config import build_setup, token_request_body
setup = build_setup("briefing", BriefingInput(...))       # 입력은 ai/live/inputs.py 참고
body = token_request_body(setup)                           # uses=1, 만료 30분, 시작 제한 2분
# POST https://generativelanguage.googleapis.com/v1alpha/auth_tokens   헤더 x-goog-api-key: <서버 키>
# 응답의 name 값을 앱에 전달
```

| 입력 | 서버가 채울 값 | 출처 |
|---|---|---|
| `Persona` | `USER_CALL_NAME`, `TONE`, `SPEECH_STYLE`, `VOICE_TYPE` | `RS_USER_SETTING` |
| 밤: `tomorrow_wake_time`, `tomorrow_coaching_min` | 내일 울릴 알람의 시각과 `COACHING_MIN` | `RS_ALARM` |
| 밤: `routine_items` | 루틴 알람이 `CUSTOM`일 때 현재 사용 중인 프리셋 항목 이름 | `RS_ROUTINE_TEMPLATE_ITEM` − `RS_USER_ROUTINE_ITEM` |
| 브리핑: `coaching_min`, `now_hhmm`, `morning_call_type`, `program_name` | 오늘 적용 코칭 길이(하루 한정 변경 반영, 0이면 코칭 없음), 브리핑 시작 시각(이미 일어난 뒤라 알람 시각 대신 현재 시각을 말함), 알람 유형, 프리셋·콘텐츠 이름 | `RS_MORNING_SESSION` 스냅샷 값 |
| 브리핑: `content_name`, `routine_follows` | 콘텐츠 이름(있으면 브리핑 뒤에 **콘텐츠 세션**이 먼저 이어짐), 기본 루틴 세션이 이어지는지. 코칭이 0분이면 둘 다 진행하지 않음 | `RS_ALARM`(정의서 수정 후) |
| 단계 세션(콘텐츠·기본 루틴 공통): `steps`, `morning_call_type`(`CONTENT`/`ROUTINE`), `routine_follows`, `next_program_name` | 아직 `PENDING`인 단계(STEP_ORDER는 세션 전체 번호: 콘텐츠가 먼저, 기본 루틴이 이어짐). 콘텐츠 세션 뒤에 기본 루틴이 이어지면 `routine_follows=True`와 루틴 프리셋 이름 | `RS_SESSION_STEP`, 프리셋 |
| 브리핑·코칭: `night` | `APPLY_DATE` = 오늘인 밤 기록 | `RS_NIGHT_RECORD` |
| 코칭: `steps` | 아직 `PENDING`인 단계 (`STEP_ORDER`, `STEP_NAME`, `PLANNED_DURATION_SEC`, `RESPONSE_TYPE`, `SCRIPT`) | `RS_SESSION_STEP` + 프리셋 항목 |
| 브리핑·코칭: `strategy_block` | 개인화 Agent가 만든 문장(`briefing_block` / `coaching_block`). 개인화를 쓰지 않으면 빈 문자열 | `decide()` 결과 (6-2) |
| **일정** `CalendarEvent` | 앱이 토큰 요청에 같이 보낸 오늘(과 내일) 일정을 정리 | 앱 → 서버 (DB 저장 안 함) |
| **날씨** `Weather` | 날씨 API 결과를 정리 (`summary`, 기온, 최고/최저, 강수확률) | 서버가 날씨 API 호출 (DB 저장 안 함) |

- 일정과 날씨는 **정의서에 없는 실행 시점 입력**입니다. 저장하지 않습니다.
- 지시문은 토큰에 고정됩니다. 앱이 연결 때 다른 지시문을 보내도 무시됩니다(측정: 실험 D).
- 날씨를 가져오려면 지역(도시 또는 좌표)이 필요한데 정의서에 위치 컬럼이 없습니다. → **확인 필요 (앱이 요청에 지역을 보낼지, 설정에 추가할지)**.

### 1-2. 통화 종료 후 처리 (앱이 원문을 보낸 뒤)

| 함수 | 입력 → 출력 | 서버가 할 일 |
|---|---|---|
| `extract_night_record(transcript, llm)` (`ai/tasks/night_record.py`) | `RS_NIGHT_RAW.TRANSCRIPT` → `to_row()` | 결과 컬럼을 `RS_NIGHT_RECORD`에 저장. `EXTRACT_STATUS`가 `FAILED`면 원문으로 다시 호출. `OVERRIDE_WAKE_TIME`이나 `OVERRIDE_COACHING_MIN`이 있으면 **`OVERRIDE_ALARM_ID`를 서버가 지정** (AI는 알람을 고르지 않음) |
| `parse_routine_changes(transcript, items, llm)` (`ai/tasks/routine_changes.py`) | 원문 + 프리셋 항목 → `remove`, `restore` (`TEMPLATE_ITEM_ID` 목록) | `remove`는 `RS_USER_ROUTINE_ITEM`에 `IS_ENABLED=FALSE` 행 추가, `restore`는 행 삭제. `ROUTINE_MODE=CUSTOM` 알람에만 적용. 모든 항목을 빼는 요청은 AI 쪽에서 막음 |
| `compose_content_steps(type, option, coaching_min, llm)` (`ai/tasks/content_steps.py`) | 콘텐츠 알람 → 단계 목록 (이름, 시간 합 = 코칭 길이, 응답 유형, 내용) | `RS_SESSION_STEP`을 `PENDING`으로 생성. AI 호출이 실패하면 `source="fallback"`으로 기본 단계를 돌려줌. 코칭 0분이면 빈 목록 |
| `build_music_prompt(type, genre\|mood)` / `all_music_rows()` (`ai/tasks/music.py`) | → `MUSIC_PROMPT` | `RS_AI_MUSIC` 시드 9행 (REWAKE 5, SLEEP 4) |

`llm`은 `ai/tasks/llm.py`의 `GeminiText()`입니다. 503이면 다음 모델로 넘어가고, 응답의 모든 부분을 이어 붙이고, 총 시간 상한(기본 30초)을 둡니다.
추출 결과 `tomorrow_notes`는 배열, `condition_label`은 `GOOD/NORMAL/TIRED/STRESSED` 코드값만, 값이 없으면 `null`입니다.

### 1-3. 판정 값 처리 (확인 필요)
코칭 중 AI가 단계마다 `report_step_result(step_order, verdict)`를 부릅니다. verdict는 `AI_VERDICT` 5종입니다.

| verdict | 제안하는 `STEP_STATUS` | 비고 |
|---|---|---|
| `done` | `DONE` | |
| `skipped` | `SKIPPED` | |
| `not_done` | `SKIPPED` | **정의서에 대응 값이 없음 → 확인 필요** |
| `ambiguous`, `other` | `PENDING` 유지 | `AI_VERDICT`에만 마지막 값 저장 |

- AI가 같은 단계에 판정을 두 번 보내는 경우가 있었습니다 → **마지막 값만 사용**하세요.
- `CHECKED_AT`은 `DONE`/`SKIPPED`가 될 때만 채웁니다 (정의서 제약).

## 2. 앱 담당

### 2-1. 연결
1. 서버에서 받은 토큰 `name`으로 연결합니다.
   `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained?access_token=<name>`
2. 첫 메시지는 `{"setup": {"model": "models/gemini-3.8-live"}}`만 보냅니다. 나머지 설정은 토큰에 고정돼 있습니다 (`setupComplete`가 오면 준비됨).
3. **시작 신호 글자**를 `{"realtimeInput": {"text": "<신호>"}}`로 보냅니다. 사용자가 말한 것이 아니므로 받아쓰기에 넣지 않습니다. 신호 문구는 `ai/live/config.py`의 `START_SIGNALS`를 쓰세요 (코칭은 반드시 해당 문구, 짧은 신호를 쓰면 AI가 사용자 답 전에 1번 단계를 완료 처리함 — 측정).
4. 사용자 음성은 `{"realtimeInput": {"audio": {"mimeType": "audio/pcm;rate=16000", "data": <base64>}}}`로 보냅니다 (16kHz, 16bit, 모노).
5. 토큰은 **한 번만** 쓸 수 있습니다. 재연결할 때는 서버에서 새 토큰을 받으세요.

> 아직 검증하지 못한 것: 실제 마이크로 연속 전송할 때의 말 끝 감지(합성 음성에서는 `audioStreamEnd`로 끝을 알렸음), AI 음성의 출력 샘플레이트(측정 코드는 24kHz 16bit 모노로 가정, 재생 확인 안 함), 10분을 넘는 연결의 끊김 신호.

### 2-2. 받아쓰기를 서버에 보내기 (밤 대화)
- 연결 설정에 입력·출력 받아쓰기가 켜져 있습니다. `inputTranscription`(사용자), `outputTranscription`(AI)을 모아 통화가 끝나면 서버에 보내세요.
- 형식은 정의서 그대로 `[{role, text, at}]`입니다. **`role` 값은 `"user"`와 `"assistant"`로 통일**해 주세요 (정의서에 값 목록이 없어서 정한 안입니다. 서버 쪽은 `user/human/assistant/model/ai/bot`을 모두 받지만 통일이 안전합니다).
- 출력 받아쓰기는 조각이라 이어 붙이면 문장 사이 공백이 없을 수 있습니다("에요.오늘은"). 추출에는 문제없었지만 화면에 보여 줄 때는 주의하세요.
- 사용자 받아쓰기에 오류가 날 수 있습니다 (예: "회의가"→"회이가", 합성 음성 기준 5문장 중 1회).

### 2-3. 함수 호출 처리
| 함수 | 앱이 할 일 |
|---|---|
| `end_session` | AI는 **사용자가 끝내겠다고 말했을 때만** 호출합니다(AI가 먼저 끝내지 않음). 사용자는 통화 종료 버튼으로 언제든 끝낼 수 있습니다. **응답을 보내지 않고** 작별 음성 재생이 끝나면 연결을 닫습니다. (응답을 보내면 AI가 영어 마무리 문장을 덧붙인 적이 있음 — 측정) |
| `start_routine` | **다음 루틴 세션을 시작하라는 신호**입니다. ① 브리핑에서 AI가 "콘텐츠(또는 루틴)를 시작할 준비가 됐어요?"라고 묻고 사용자가 준비됐다고 답한 다음 응답, ② 콘텐츠 세션에서 마지막 단계 뒤 "루틴 시작할 준비가 됐어요?"에 사용자가 준비됐다고 답한 다음 응답에 호출됩니다. 응답을 보내지 않고, 앱이 지금 세션을 닫고 **새 토큰으로 다음 세션**(브리핑 뒤에는 콘텐츠 세션, 없으면 기본 루틴 세션)을 시작합니다. 코칭 0분이거나 이어질 세션이 없으면 호출되지 않습니다. |
| `report_step_result(step_order, verdict)` | 서버에 전달해 단계 체크에 반영하고 `{"result":"ok"}`로 응답합니다. |

- 브리핑과 단계 세션은 AI가 스스로 끝내지 않습니다. 끝나는 방법은 `start_routine`(준비됐다고 답함) 또는 사용자의 종료(버튼, `end_session`)뿐이고, **`turnComplete`는 종료가 아닙니다**(AI가 말을 마치고 사용자의 다음 말을 기다리는 중).
- 코칭에서 AI가 한 응답에 다음 단계까지 미리 판정하거나 같은 단계에 판정을 여러 번 보내는 경우가 있었습니다(지시문을 고치기 전 7회 중 3회, 이후 5회 중 0회 — 표본이 작아 확정할 수 없음). 같은 단계의 판정이 여러 번 오면 **마지막 값만** 쓰세요. 사용자가 끝내자고 해서 `end_session`이 오면 `PENDING` 단계가 남아 있어도 닫고, 남은 단계는 `PENDING`으로 둡니다.

### 2-4. 대화 흐름 (새 설계, 사용자가 끝낸다)
| 세션 | 흐름 |
|---|---|
| 밤 | AI가 오늘 일정이 잘 끝났는지 물으며 시작 → 한 일·감정을 이야기 → AI가 오늘 하루를 한두 문장으로 요약해 사용자에게 확인(한 줄 일기의 바탕) → 하루 이야기 뒤에 **내일의 핵심 목표 한 가지와 내일 특이사항**을 자연스럽게 묻기(`TOMORROW_GOAL`, `TOMORROW_NOTES`). 사용자가 피곤해 보이거나 직접 알람 변경·코칭 축소를 말할 때만 내일 아침 조정을 다루고, 사용자가 동의·요청해야 확정. 끝은 사용자가 정함 |
| 브리핑 | AI가 "일어났어요?"만 묻고 대기 → 일어났다고 답하면 현재 시각·날씨·오늘 일정·어젯밤 이어받기를 4~6문장으로 브리핑(알람 시각은 말하지 않음. 개인화 전략이 `short`인 날은 2~3문장, 6절 참고) → "콘텐츠(없으면 기본 루틴) 시작할 준비됐어요?" → 준비됐다고 하면 `start_routine`. 브리핑 세션에서는 단계를 진행하지 않음. 코칭 0분이면 응원으로 끝나고 사용자가 끝냄 |
| 콘텐츠 세션 | 콘텐츠 단계를 하나씩 안내·판정(`report_step_result`) → 마지막 단계 뒤 "루틴 시작할 준비됐어요?" → 준비됐다고 하면 `start_routine`. 기본 루틴이 없으면 마무리 한마디 뒤 사용자가 끝냄 |
| 기본 루틴 세션(코칭) | 루틴 단계를 하나씩 안내·판정(`report_step_result`), 마지막 단계 뒤 마무리 한마디. 끝은 사용자가 정함 |

## 3. DB 담당 (정의서 확인·보완 요청)

| # | 내용 | 기본안 / 현재 가정 |
|---|---|---|
| 1 | `RS_NIGHT_RAW.TRANSCRIPT`의 `role` 값 목록 | `user`, `assistant` |
| 2 | `STEP_STATUS`에 `not_done` 대응 값이 없음 | `SKIPPED`로 처리 (1-3 참고) |
| 3 | 콘텐츠 단계의 상세 내용(퀴즈 문제·정답, 동작 설명)을 둘 곳이 없음. 재연결 때 필요 | 서버가 보관하도록 요청 |
| 4 | 프리셋 `SCRIPT`(고정 안내문) 실제 문구 (정의서에 "스트레칭 동작 순서"라고만 있음) | 문구를 받으면 `SCRIPT`에 넣기. AI는 있으면 그대로 읽음 |
| 5 | 콘텐츠 세부 값 목록(`CONTENT_OPTION`) 확정 | 예시 값으로 구현, 모르는 값은 일반 구성으로 처리 |
| 6 | 컨디션 라벨·음악 장르 값 목록 확정 | `GOOD/NORMAL/TIRED/STRESSED`, `POP/JAZZ/CLASSIC/DANCE/ACOUSTIC` |
| 7 | 알람이 실제로 울린 시각·끈 시각 컬럼 (반응 시간 계산용) | 없으면 개인화에서 반응 시간 통계를 쓰지 못함 |
| 8 | 날씨용 지역 정보 | 1-1 참고 |
| 9 | 퀴즈 정확성 | AI가 만든 퀴즈에 사실 오류가 있었음(20분 역사 퀴즈 5문제 중 2문제). 팀의 문제은행 제공 또는 검증 호출 추가를 검토 |
| 10 | **콘텐츠 루틴과 기본 루틴을 한 아침에 함께 진행** — 정의서 수정 요청 | 제품 의도는 콘텐츠 루틴과 기본 루틴이 별도이고 둘 다 진행하는 것입니다(브리핑 → 콘텐츠 세션 → 기본 루틴 세션). 정의서는 알람 유형을 `ROUTINE` 또는 `CONTENT` 중 하나로 제한해(`TEMPLATE_ID`·`ROUTINE_MODE`와 `CONTENT_*`가 배타, 세션 스냅샷도 동일) 맞지 않습니다. **AI 쪽은 둘 다 진행한다고 가정하고 구현했습니다.** 제안: ① `RS_ALARM`·`RS_MORNING_SESSION`에서 `TEMPLATE_ID`·`ROUTINE_MODE`와 `CONTENT_TYPE`·`CONTENT_OPTION`을 함께 둘 수 있게 하고(둘 다 비워도 되는 규칙은 유지) ② `MORNING_CALL_TYPE`은 폐지하거나 `BOTH` 값을 추가 ③ `RS_SESSION_STEP.STEP_ORDER`는 세션 전체 번호로 콘텐츠 단계가 먼저, 기본 루틴 단계가 이어지게 하고(`TEMPLATE_ITEM_ID`가 NULL이면 콘텐츠) ④ 코칭 길이가 콘텐츠와 루틴에 어떻게 적용되는지 결정 |
| 11 | **개인화 전략 로그 테이블** (정의서에 없음) | 6-4 참고. 매 아침 세션마다 1행. 없으면 개인화 효과를 나중에 평가할 수 없음 |
| 12 | **Bandit 상태 저장 위치** (정의서에 없음) | 6-4 참고. JSON 한 덩어리를 읽고 쓰는 칸. 사용자별 또는 전체 합산 |

## 4. 하지 않기로 한 것 (결정 기록)
- 위기 표현 감지 기록 저장: 하지 않음 (정의서 기본안). 감지 코드·재안내 가드도 만들지 않음.
- 지시문 안에는 AI 자체의 안전 규칙(위기 표현 시 안전 확인, 109/119 안내)이 들어 있습니다. 코드로 보장하지는 않습니다.
- 아침 대화 원문 저장: 하지 않음.
- 항목별 시간 개인화: 하지 않음 (10분/20분 버전 선택과 항목 빼기만).

## 5. 측정 결과와 한계 (2026-11-01, 합성 음성, 표본 작음)
- 지연: 말 끝(또는 신호)부터 첫 소리까지 대부분 1~2.5초, 코칭 중 3.5~3.8초가 나온 턴이 있었습니다. 연결+설정 0.5~1.0초.
- 새 흐름(밤 → 밤 기록 추출 → 브리핑 → 코칭)을 페르소나 2종·사용자 행동 4가지로 하루 전체를 돌렸습니다. 첫 4회 중 2회가 규칙을 모두 통과했고, 나머지 2회에서 나온 문제(밤 답변 3문장, 브리핑에서 질문과 `start_routine`을 한 턴에 함)를 고친 뒤 같은 2가지를 다시 돌려 모두 통과했습니다. 표본이 작아 통과율을 확정하지는 못합니다.
- **콘텐츠를 브리핑 세션 안에서 진행하면 불안정했습니다**(하루 전체 4회와 브리핑만 3회, 총 7회 중 3회만 통과: 한 응답에 여러 문제·정답을 말함, 판정 호출 누락, 문제마다 준비 확인). 그래서 **콘텐츠는 별도 세션(단계 세션)으로 분리**했습니다. 분리 후 하루 전체(밤 → 브리핑 → 콘텐츠 → 기본 루틴) 4회에서 브리핑·콘텐츠·기본 루틴 12개 세션이 모두 규칙을 통과했고, 밤 대화는 4회 중 2회가 반말 설정에서 존댓말이 섞이거나 3문장으로 답해 실패했습니다. 지시문 예시를 어조에 맞춰 바꾼 뒤 반말 설정의 밤 대화 4회는 모두 통과했습니다(표본이 작음). 앱이 연결을 한 번 더 열어야 합니다(브리핑 → 콘텐츠 → 기본 루틴 순으로 새 토큰 3번).
- 사용자 음성은 합성 음성(Yuna)입니다. 실제 마이크·소음·사투리는 검증하지 못했습니다.
- 모델 응답은 매번 달라집니다. 같은 입력도 통과/경고가 갈릴 수 있어 `--repeat`로 반복해서 봐야 합니다.
- 요금과 무료 한도는 확인하지 못했습니다 (실험과 시나리오를 합쳐 Live 연결 40회 이상, 텍스트 호출 약 20회에서 한도 오류는 없었음).
- **개인화 전략 연결 확인(2026-11-01, Live 8회)**: 전략 문장을 프롬프트 끝에 붙이는 것만으로는 브리핑 `short`가 먹히지 않았습니다(첫 시도 6문장). 브리핑 프롬프트의 "4~6문장" 규칙과 "2~3문장" 전략이 겹쳐 있으면 AI가 긴 쪽을 따랐기 때문입니다. 전략이 `short`인 날에는 4~6문장 규칙 자체를 2~3문장 규칙으로 바꿔 쓰도록 고친 뒤 2회 모두 3~4문장으로 통과했습니다(표본이 작음). 응원 많이 1회 통과. 코칭 `soft` 1회 통과, `firm` 3회 중 2회 통과했고 첫 시도 1회는 `end_session`이 호출되지 않고 대사 끝에 영어가 섞였습니다(전략과 무관한 Live 쪽 일시적 오류로 보이나 1회 관찰이라 확정하지 못함). **`soft`와 `firm`의 말투 차이는 작았습니다.** 코칭 강도가 실제로 말투를 바꾸는지는 약하게만 확인됐고, 사용자가 실제로 더 잘 일어나는지는 알 수 없습니다.
- 목소리 매핑(`FEMALE=Kore`, `MALE=Puck`, `NEUTRAL=Zephyr`)은 설정이 통과하는 것만 확인했고, 성별 인상은 귀로 확인하지 않았습니다.

## 6. 개인화(User Model · Rule · Bandit · Agent)

개인화가 하는 일은 **오늘 아침 대화를 어떤 방식으로 할지 정하는 것**뿐입니다. 정의서에서 바꿀 수 있는 것은 말투와 분량이라, 바꾸는 값은 세 가지입니다.

| 전략 값 | 선택지 | 프롬프트에서 달라지는 것 |
|---|---|---|
| 코칭 강도 | `soft` / `normal` / `firm` | 코칭 세션에서 안내하는 말투 (부드럽게 / 기본 / 짧고 또렷하게) |
| 브리핑 길이 | `short` / `normal` | `short`면 브리핑 2~3문장 (기본은 4~6문장) |
| 응원 정도 | `low` / `normal` / `high` | 응원 표현의 양 |

항목 시간 변경은 하지 않습니다(정의서에 없음). 기본 전략이면 프롬프트에 아무것도 붙지 않아 지금과 똑같이 동작합니다. 사용자가 고른 어조·말투와 안전 규칙이 전략보다 항상 우선합니다.

코드는 `ai/personalization/`에 있고 파일별 역할은 그 폴더의 `README.md`에 있습니다. 서버가 쓰는 것은 `agent.py`의 `decide()`와 `learn()` 두 개와 `records.py`의 `build_sessions()` 하나입니다.

### 6-1. Bandit 설계 결정 (2026-11-01)
**채택한 안(과제용 짧은 버전)**: 상황 4칸 × 전략 3개

| 항목 | 내용 |
|---|---|
| 상황(Context) 4칸 | 최근 기상 성공률(50% 미만 / 그 외) × 어젯밤 컨디션(`TIRED`·`STRESSED` / 그 외). 기록이 부족하면 "그 외"로 봄 |
| 전략 3개 | `standard`(기본), `gentle`(부드럽게 + 응원 많이), `push`(단호하게 + 브리핑 짧게) |
| 안전 규칙 | 피곤한 날과 사용자 말투 설정이 `KIND`인 사용자에게는 `push`를 후보에서 뺌 |
| 학습 방식 | ε-greedy(15%는 후보 중 무작위 시도), 점수는 User Model의 하루 결과 점수(0~1) |
| 상태 저장 | 사용자별 JSON(`to_dict`/`from_dict`). 여러 사용자의 상태를 합치는 `merge`도 제공 |
| 로그(서버 요청) | 매 세션 상황 칸, 고른 전략, **선택 확률**, 후보 목록, 결과 점수. 나중에 효과를 평가하려면 선택 확률이 꼭 필요 |

**기록해 두는 다른 계획**

| 계획 | 내용 | 켜는 방법 / 필요한 것 |
|---|---|---|
| **8칸 계획** | 4칸에 요일(평일/주말)을 더함 → 2 × 2 × 2 = 8칸 | `CONTEXT_FEATURES = ("wake", "tired", "day")` 한 줄. 코드는 이미 들어 있음 |
| 옛 16칸 계획 | 기상 성공률 4구간(모름/낮음/중간/높음) × 컨디션 × 요일 | 데이터가 많을 때만 의미 있음 |
| 앱 운영용 긴 버전 | 반응 시간·연속 달성일 등 상황 특징 추가, 전체 사용자 기록 합산 학습, LinUCB·Thompson sampling 같은 다른 알고리즘, 저장된 선택 확률로 오프라인 평가 | 실제 사용자 데이터가 수백~수천 세션 쌓인 뒤. 반응 시간은 알람이 울린 시각 컬럼 필요(3절 7번) |

**4칸을 고른 이유(데이터 양 계산)**

| 상황 | 세션 수 | 조합 수 | 조합당 관찰 횟수 |
|---|---|---|---|
| 사용자 1명 4주, 8칸 × 3개 | 28 | 24 | 약 1회 (배울 수 없음) |
| 사용자 10~15명 4주를 합산, 4칸 × 3개 | 약 300~400 | 12 | 약 25~30회 |

- 위 숫자는 하루 세션 1개라는 가정의 추정치입니다. 사용자 한 명의 기록만으로는 4주 안에 의미 있게 배우기 어렵고, 여러 사용자를 합쳐야 경향이 보이기 시작합니다.
- 10~15명, 4주 규모로는 Rule과 Bandit의 효과 차이를 통계적으로 확정하기 어렵습니다. 이 구조는 효과 입증이 아니라 **비교 실험이 가능한 구조와 로그를 갖추는 것**이 목적입니다.
- 가상 사용자 시뮬레이션은 코드가 도는지 확인하는 용도이며 효과의 근거가 아닙니다.

### 6-2. 서버가 하는 일 (하루 흐름)

```
[아침 브리핑 직전]  최근 기록 읽기 → decide() → 전략 + 프롬프트용 문장 + 로그 1행
                    → 문장을 BriefingInput / CoachingInput의 strategy_block에 넣어 토큰 발급 → 로그 저장
[하루가 끝난 뒤]   그날 결과 점수 계산 → learn() → Bandit 상태 저장 (mode가 bandit/ab일 때만)
```

```python
from ai.personalization.records import build_sessions
from ai.personalization.agent import decide, learn
from ai.personalization.bandit import ContextualBandit
from ai.personalization.reward import session_reward

sessions = build_sessions(session_rows, step_rows, night_rows)        # 최근 기록 (6-3)
bandit = ContextualBandit.from_dict(saved_state)                      # 저장된 상태가 없으면 None을 넣어도 새로 시작
d = decide(user_id, sessions, today, condition="TIRED", speech_style="KIND", mode="rule", bandit=bandit)

briefing = BriefingInput(..., strategy_block=d.briefing_block)       # 브리핑 세션
coaching = CoachingInput(..., strategy_block=d.coaching_block)       # 콘텐츠·기본 루틴 세션 둘 다
save(d.log.to_row())                                                  # 전략 로그 (6-4)

# 하루가 끝난 뒤
learn(bandit, d.log, session_reward(today_session))                  # bandit이 고른 날만 학습함. 결과를 bandit.to_dict()로 저장
```

| mode | 하는 일 |
|---|---|
| `off` | 개인화 안 함 (기본 전략) |
| `rule` | 사람이 정한 if-then 규칙으로 결정 (R0·R1·R3·R4·U1). Bandit과 비교하는 기준이라 **처음에는 이것을 권장** |
| `bandit` | 상황 4칸에서 전략 3개 중 하나를 고르고 결과로 학습 (6-1). 상태가 없으면 `rule`로 대신함 |
| `ab` | 사용자·날짜로 정해지는 50:50 배정으로 Rule 또는 Bandit을 씀. 같은 날은 항상 같은 결과. 배정과 확률은 로그에 남음 |

- `condition`은 어젯밤 `RS_NIGHT_RECORD.CONDITION_LABEL`, `speech_style`은 `RS_USER_SETTING.SPEECH_STYLE`입니다. 값이 없으면 `None`을 넣으세요.
- `decide()`는 **예외를 던지지 않습니다.** 어느 단계에서 오류가 나도 기본 전략으로 돌아가고 로그의 `source`가 `fallback`, `error`에 이유가 남습니다. 서버에서 따로 try/except를 두지 않아도 대화는 이어집니다.
- 코칭 0분인 날이나 아침에 이어질 세션이 없는 날도 호출해도 됩니다(문장이 쓰이지 않을 뿐입니다).
- 같은 날 재연결(새 토큰)할 때는 `decide()`를 다시 부르지 말고 **그날 처음 정한 블록을 다시 쓰세요.** 다시 부르면 Bandit이 다른 전략을 고를 수 있습니다.

### 6-3. 서버가 넘기는 입력 (`build_sessions`)

DB에서 읽은 행(dict) 세 목록을 넣습니다. 컬럼 이름은 정의서와 같고 소문자로 읽습니다. 컬럼이 없거나 NULL이어도 오류 없이 빈 값으로 처리합니다. 시간대가 없는 시각은 KST로 봅니다.

| 목록 | 쓰는 컬럼 | 용도 |
|---|---|---|
| `RS_MORNING_SESSION` | `session_id`, `user_id`, `session_date`, `morning_call_type`, `coaching_min`, `target_wake_time`, `first_response_at`, `session_end_at`, `rewake_count`, `wake_success` | 기상 성공률, 연속 달성일, 결과 점수 |
| `RS_SESSION_STEP` | `session_id`, `step_order`, `step_name`, `step_status`, `template_item_id`, `planned_duration_sec`, `checked_at`, `ai_verdict` | 건너뜀 비율, 루틴 완료율, 항목별 수행률 |
| `RS_NIGHT_RECORD` | `user_id`, `apply_date`, `condition_label` | 그날 아침에 적용된 어젯밤 컨디션 (`apply_date`가 세션 날짜와 같은 행) |

- 최근 7일 기록이면 충분합니다. 세션 3개 미만이면 통계가 "모름"이 되어 Rule·Bandit 모두 기본 쪽으로 갑니다.
- 선택 칸: `alarm_rang_at`(알람이 울린 시각), `alarm_mode`. **정의서에 없는 칸**이고, 없으면 반응 시간 통계만 못 씁니다(3절 7번).
- 오늘 세션은 `decide()`에 넣지 않아도 됩니다(`today` 이전 기록만 통계에 씁니다).

### 6-4. 서버(DB)에 요청하는 저장소 — 정의서에 없음

**① 전략 로그** (3절 11번). 매 세션 1행. `d.log.to_row()`가 아래 컬럼과 같은 이름의 평평한 dict를 돌려줍니다(중첩 값은 JSON 문자열). 테이블이 생기기 전에는 `d.log.append_jsonl(경로)`로 파일에 남길 수 있습니다.

| 컬럼 | 내용 |
|---|---|
| `user_id`, `session_date` | 누구의 어느 날인지 |
| `source` | `rule` / `bandit` / `default` / `fallback` |
| `mode` | 서버가 넘긴 mode |
| `strategy_id`, `strategy` | 고른 전략 이름과 값(JSON) |
| `rule_ids` | Rule이 적용한 규칙 번호(JSON) |
| `context_key` | Bandit이 본 상황 칸 (예: `low\|tired`) |
| `propensity` | Bandit이 그 전략을 고를 확률. **효과를 평가하려면 꼭 필요** |
| `allowed_arms`, `explored` | 후보 목록(JSON), 무작위 시도였는지 |
| `ab_group`, `ab_propensity` | A/B일 때 배정된 쪽과 그 확률 |
| `stats` | 결정에 쓴 통계(JSON) |
| `prompt_version` | 어떤 프롬프트 버전으로 말했는지 (`ai/live/version.py`) |
| `error`, `created_at` | 오류 내용, 기록 시각 |

결과 점수는 이 테이블에 칸을 더하거나(`reward`) 세션에서 다시 계산해도 됩니다. 점수는 `session_reward(세션)`으로 같은 값이 나옵니다.

**② Bandit 상태** (3절 12번). `bandit.to_dict()`가 돌려주는 작은 JSON 한 덩어리(횟수와 점수 합)입니다. 아침마다 읽고 하루가 끝나면 씁니다. 두 가지 방식이 있습니다.
- 사용자별 상태: 간단하지만 혼자 4주 기록으로는 거의 배우지 못합니다(6-1).
- 전체 합산 상태: 한 행만 두고 모든 사용자가 같이 갱신합니다. 여러 사용자를 합쳐야 경향이 보입니다. 동시에 갱신될 때 덮어쓰기가 나지 않도록 하루 끝에 한 번씩 몰아서 `learn()`을 부르는 방식을 권장합니다.

### 6-5. 안전 규칙과 결정 기록
- 피곤한 날(`TIRED`·`STRESSED`)과 말투 설정 `KIND`인 사용자에게는 강한 전략(`push`)이 나오지 않습니다. 어느 mode에서도 같습니다(Rule의 U1, Bandit의 후보 제한, 전체 조합 테스트로 확인).
- 위기 여부는 판정하지 않습니다(4절).
- 전략은 말투와 분량만 바꾸고, 사용자가 고른 어조·말투와 안전 규칙을 바꾸지 못합니다.
- Rule의 임계값(기상 성공률 50% 등)은 팀장님 자료의 예시를 가정값으로 쓴 것입니다.

### 6-6. 지금 알고 있는 한계
- 코칭 강도 `soft`와 `firm`의 말투 차이는 합성 음성 실험에서 작았습니다(5절). 브리핑 길이만 분명히 확인됐습니다.
- 가상 사용자 시뮬레이션(`python -m ai.personalization.sim`)은 코드가 끝까지 도는지 보는 용도입니다. 가상 사용자의 반응을 우리가 정했으므로 개인화 효과의 근거가 아닙니다.
- Rule과 Bandit 중 무엇이 나은지는 10~15명, 4주 규모로 통계적으로 확정할 수 없습니다(6-1).
- 사용자가 실제로 더 잘 일어나는지는 실제 사용자 데이터로만 알 수 있습니다.

## 부록: 자동 시나리오 실행
```bash
.venv/bin/python -m ai.devclient.run --list
.venv/bin/python -m ai.devclient.run --all              # 맥에서만 (합성 음성 사용). 실제 Gemini를 호출함
.venv/bin/python -m ai.devclient.run coaching_quiz --repeat 3
.venv/bin/python -m pytest ai                            # 네트워크 없는 단위 테스트
```
