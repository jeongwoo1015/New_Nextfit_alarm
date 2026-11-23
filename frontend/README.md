# Frontend — Android 앱

Kotlin + Jetpack Compose. 앱 → **FastAPI 서버**(`backend/`) → Supabase.
Gemini Live는 서버가 발급한 1회용 토큰으로 앱이 직접 연결한다. **Gemini 키는 앱에 두지 않는다.**

| 항목 | 값 |
|---|---|
| 프로젝트 폴더 | `frontend/RespecLive` (Android Studio에서 이 폴더를 연다) |
| 패키지 | `com.example.respeclive` |
| minSdk / targetSdk | 24 / 37 |
| 주요 라이브러리 | Compose Material3, Navigation Compose, OkHttp |

## 폴더

`app/src/main/java/com/example/respeclive/` 기준

| 폴더 | 역할 |
|---|---|
| `alarm/` | 알람 예약(`AlarmScheduler`)·울림(`AlarmReceiver`)·알림, 재부팅 뒤 다시 예약(`BootReceiver`), 취침 알림 |
| `audio/` | 알람음·진동, 재기상 음악, 수면 음악(포그라운드 서비스), 목소리 녹음·재생·받아쓰기, 통화 오디오 |
| `data/` | 서버 API(`RespecApi`, `ApiClient`), 로그인(`ServerAuth`), 설정 값(`ApiConfig`), 기기 저장값(기상 볼륨·미루기·끝말잇기 단어 등), 캘린더·위치 |
| `live/` | Gemini Live 연결(`LiveClient`, `LiveSession`)과 마이크 입력·음성 출력 |
| `navigation/` | 화면 이동(`AppNavHost`). 시작 때 `GET /users/me`로 온보딩 여부 확인 |
| `ui/` | 화면별 Compose 코드 (아래 표) |

| `ui/` 하위 | 화면 |
|---|---|
| `start/`, `onboarding/` | 시작 화면, 온보딩 |
| `home/` | 홈(알람 목록) |
| `alarm/` | 알람 추가·설정, 모닝콜 유형 |
| `morning/` | 모닝콜(전화 받기) → 브리핑·코칭 대화 → 포춘 → 아침 리포트 |
| `night/` | 굿나잇콜, 밤 대화 |
| `content/` | 콘텐츠 탭, 끝말잇기 게임 |
| `report/`, `diary/`, `profile/`, `settings/` | 리포트, 한 줄 일기, 내 정보, 설정 |
| `nexty/` | 캐릭터 Nexty 그리기·꾸미기 |
| `common/`, `theme/` | 공용 컴포넌트, 테마(라이트·다크·자동) |

하단 탭: 콘텐츠 · 리포트 · **홈** · 일기 · 내 정보

## 설정 (`local.properties`)

`frontend/RespecLive/local.properties`에 넣는다(커밋하지 않음). 빌드 때 `BuildConfig`로 들어간다.

```properties
sdk.dir=...                       # Android Studio가 자동으로 채움
API_BASE_URL=http://127.0.0.1:8000
SUPABASE_URL=https://<project>.supabase.co
SUPABASE_ANON_KEY=<anon key>
SUPABASE_DEV_EMAIL=<테스트 계정 이메일>
SUPABASE_DEV_PASSWORD=<테스트 계정 비밀번호>
```

- `API_BASE_URL`이 비어 있으면 `http://127.0.0.1:8000`
  - 에뮬레이터: `http://10.0.2.2:8000`
  - 실기기(USB): `adb reverse tcp:8000 tcp:8000` 후 `http://127.0.0.1:8000`
- 로그인 화면이 아직 없어서 `SUPABASE_DEV_*` 테스트 계정으로 Supabase Auth 이메일 로그인을 하고,
  받은 access token을 `Authorization: Bearer`로 보낸다(`data/ServerAuth.kt`). 값이 비어 있으면 서버 요청이 모두 실패한다.

## 실행

1. 서버를 먼저 띄운다 — `backend/README.md`의 "실행" 참고
2. Android Studio에서 `frontend/RespecLive`를 열고 `app`을 실행하거나, 명령줄에서:

```bash
cd frontend/RespecLive
./gradlew installDebug          # Windows: gradlew.bat installDebug
./gradlew testDebugUnitTest     # 단위 테스트 (기기·서버 불필요)
```

## 권한

처음 실행할 때 필요한 권한을 묻는다(`ui/common/StartupPermissions.kt`).

| 권한 | 쓰는 곳 |
|---|---|
| 마이크 `RECORD_AUDIO` | 모닝콜·굿나잇콜 대화, 목소리 녹음 |
| 알림 `POST_NOTIFICATIONS`, 전체 화면 알림 `USE_FULL_SCREEN_INTENT` | 잠금 화면에서 모닝콜 띄우기 |
| 정확한 알람 `USE_EXACT_ALARM`, 부팅 `RECEIVE_BOOT_COMPLETED` | 알람 예약, 재부팅 뒤 다시 예약 |
| 캘린더 `READ_CALENDAR` | 오늘·내일 일정을 밤 대화·브리핑에 전달 |
| 대략적 위치 `ACCESS_COARSE_LOCATION` | 브리핑 날씨 |
| 다른 앱 위에 표시 `SYSTEM_ALERT_WINDOW` | 알람이 울릴 때 화면 띄우기 |
| 포그라운드 서비스(미디어 재생) | 수면 음악 |

## 흐름

- **굿나잇콜**: Live 토큰(`POST /night/token`) → 밤 대화 → 받아쓰기 저장 → '내일의 한마디' 목소리 녹음·업로드 → 수면 음악
- **모닝콜**: 알람 울림 → 세션 시작(`POST /morning/sessions`) → 받기 전까지 무응답이면 재기상 음악·볼륨 단계 올림
  → 브리핑 Live → 포춘 → 루틴 코칭 또는 콘텐츠(영어·스트레칭·끝말잇기) → 종료·아침 리포트
- 서버 API 전체 목록은 `backend/README.md`의 API 표를 기준으로 한다.
