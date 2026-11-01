-- 기상 성공 기준 확정(11/01): 이미 01을 실행한 DB에 적용 (기존 데이터 그대로)
--  성공 = 브리핑 전 확인 멘트 2~3개에 모두 응답 + 스누즈 0회 + 판정 전 8초 무응답 재기상 음악 0회. 성공 시각은 첫 응답
--  실패해도 브리핑·루틴은 진행한다. 스누즈 횟수 제한 없음
-- 01_schema.sql에도 같은 내용이 들어 있으므로, 처음부터 만드는 DB에는 실행하지 않아도 된다


alter table rs_morning_session
  add column if not exists snooze_count smallint not null default 0 check (snooze_count >= 0),
  add column if not exists wake_checked_at timestamptz;

comment on column rs_morning_session.snooze_count is '"5분 뒤 다시 울리기" 횟수';
comment on column rs_morning_session.wake_checked_at is '브리핑 전 확인 멘트를 모두 마친 시각 = 기상 성공 판정 시점';

-- 확인 (2줄)
select column_name, data_type, column_default from information_schema.columns
where table_name = 'rs_morning_session' and column_name in ('snooze_count', 'wake_checked_at');
