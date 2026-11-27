-- 모닝 세션 기록 보강(11/26 회의): 이미 01을 실행한 DB에 적용 (기존 데이터 그대로)
--  1. REPORTED_BEDTIME: 모닝콜에서 사용자가 말한 어젯밤 잠든 시각. 리포트 취침 시각 추이는 굿나잇콜이 끝난 시각이
--     기본이고, 이 값이 있으면 이 값으로 덮어쓴다. 앱은 "HH:MM"으로 보내고 서버가 날짜를 붙여 저장한다
--  2. AUTO_CONNECTED: 알람마다 3번 미룬 뒤 자동 연결로 시작한 세션. 사용자가 스스로 반응한 게 아니므로
--     이 세션은 기상 볼륨 학습에서 뺀다 (AI CONTRACTS B28)
--  3. FLOW_COMPLETED_AT: 포춘까지 모닝콜의 모든 구성을 마친 시각. 리포트 기상 성공률은 이 값이 있는 날을 성공으로 센다.
--     통화를 중간에 끊어도 SESSION_END_AT은 찍히므로 끝까지 했는지는 이 칸으로 구분한다
-- 01_schema.sql에도 같은 내용이 들어 있으므로, 처음부터 만드는 DB에는 실행하지 않아도 된다


alter table rs_morning_session
  add column if not exists reported_bedtime timestamptz,
  add column if not exists auto_connected boolean not null default false,
  add column if not exists flow_completed_at timestamptz;

comment on column rs_morning_session.reported_bedtime is '모닝콜에서 사용자가 말한 어젯밤 잠든 시각 (리포트 취침 시각을 이 값으로 덮어씀)';
comment on column rs_morning_session.auto_connected is '3번 미룬 뒤 자동 연결로 시작한 세션 (기상 볼륨 학습에서 제외)';
comment on column rs_morning_session.flow_completed_at is '포춘까지 모닝콜의 모든 구성을 마친 시각 (리포트 기상 성공 기준)';


-- 확인 (3줄)
select column_name, data_type, is_nullable, column_default
from information_schema.columns
where table_name = 'rs_morning_session'
  and column_name in ('reported_bedtime', 'auto_connected', 'flow_completed_at')
order by column_name;
