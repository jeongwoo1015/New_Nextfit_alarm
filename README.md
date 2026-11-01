# Nextfit_alarm
AI morning call &amp; routine prototype

Re:Spec의 AI 모닝콜 + 모닝루틴 기능을 구현하는 7일 프로토타입 프로젝트입니다.

## Prototype Goal

7일 안에 Re:Spec의 핵심 밤 → 아침 사용자 경험을 실제 데이터로 End-to-End 구현합니다.

## User Flow

Night AI Conversation  
→ TOMORROW_CONTEXT 저장  
→ Voice Message 녹음  
→ AI Morning Call  
→ Personalized Morning Briefing  
→ 3-Step Morning Routine  
→ Previous Voice Message 재생

## Prototype Scope

- 테스트 사용자 1명
- 알람 1개
- 루틴 모닝콜만 구현
- 밤 AI 대화 3~4개 질문
- TOMORROW_CONTEXT 실제 DB 저장 및 다음날 조회
- 사용자 음성 메시지 녹음 / 저장 / 재생
- 사전 생성 AI 음악 재생
- 무응답 시 음악 볼륨 1단계 증가
- 전날 목표·특이사항 기반 개인화 모닝 브리핑
- 3단계 모닝루틴
- Morning Session 및 루틴 완료 여부 DB 저장

## 3-Step Morning Routine

1. 물 한 컵
2. 가벼운 스트레칭
3. 오늘 할 일 한 가지 정하기

## Tech Stack

### Frontend
- Android
- Kotlin
- Jetpack Compose

### AI
- Gemini API

### Backend
- Supabase
- Database
- Storage

## Branch Strategy

```text
main
├── feat/frontend
├── feat/ai
├── feat/backend-db
└── feat/backend-service