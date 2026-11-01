-- Re:Spec 최종 DB 데이터 넣기 테스트 (01~03 실행 후)
-- 준비: Authentication -> Users -> Add user 로 respec.test@example.com 계정을 먼저 만든다 (Auto Confirm 체크)
-- 사용법: 블록을 하나씩 드래그해서 Run. SQL Editor는 마지막 문장의 결과만 보여 준다
-- [E] 블록은 오류가 나야 정상. 한 블록씩 따로 실행할 것
-- 테스트 데이터 고정 ID: 루틴 알람 ...b001(N 프리셋), 콘텐츠 알람 ...b002, 루틴 세션 ...c001, 콘텐츠 세션 ...c002


-- [T0] 테스트 계정 확인 (1행이 나와야 함)
select id, email from auth.users where email = 'respec.test@example.com';


-- [T1] 사용자와 설정 넣기 (결과: users 1, settings 1)
insert into rs_user (user_id, user_name, birth_date, gender, onboarding_done)
select id, '테스트사용자', '2003-01-01', 'FEMALE', true
from auth.users where email = 'respec.test@example.com'
on conflict (user_id) do nothing;

insert into rs_user_setting (user_id, user_call_name, music_genre)
select id, '찬미', 'POP'
from auth.users where email = 'respec.test@example.com'
on conflict (user_id) do nothing;

select (select count(*) from rs_user) as users, (select count(*) from rs_user_setting) as settings;


-- [T2] 알람 2개: 루틴 알람(N 프리셋) + 콘텐츠 알람(운동·스트레칭) (결과: 2줄)
insert into rs_alarm (alarm_id, user_id, alarm_time, repeat_days, label, morning_call_type, template_id)
select '00000000-0000-4000-8000-00000000b001', id, '07:00', '1111100', '평일 알람', 'ROUTINE',
       '00000000-0000-4000-8000-0000000000a2'
from auth.users where email = 'respec.test@example.com'
on conflict (alarm_id) do nothing;

insert into rs_alarm (alarm_id, user_id, alarm_time, repeat_days, label, morning_call_type, content_type, content_option)
select '00000000-0000-4000-8000-00000000b002', id, '09:00', '0000011', '주말 알람', 'CONTENT', 'EXERCISE', 'STRETCH'
from auth.users where email = 'respec.test@example.com'
on conflict (alarm_id) do nothing;

select label, morning_call_type, template_id, content_type,
       rs_alarm_routine_total(alarm_id) as routine_total_sec
from rs_alarm order by alarm_time;


-- [T3] 알람 세부 설정: 스트레칭 3분 -> 5분, 이불 정리 0분(빼기)
--  결과: 기본 600 + 120 - 60 = 660초, 이불 정리 0
insert into rs_alarm_routine_item (alarm_id, template_item_id, user_id, duration_sec)
select a.alarm_id, i.template_item_id, a.user_id,
       case i.item_order when 1 then 300 when 4 then 0 end
from rs_alarm a
join rs_routine_template_item i on i.template_id = a.template_id and i.item_order in (1, 4)
where a.alarm_id = '00000000-0000-4000-8000-00000000b001'
on conflict (alarm_id, template_item_id) do update set duration_sec = excluded.duration_sec;

select item_order, item_name, duration_sec,
       rs_alarm_routine_total('00000000-0000-4000-8000-00000000b001') as total_sec
from rs_alarm_routine_items('00000000-0000-4000-8000-00000000b001');


-- [T3-2] 전체 교체 함수: 스트레칭 2분, 이불 정리 1분(기본값이라 저장 안 함), 나머지는 넘기지 않음(기본)
--  결과: 스트레칭 120, 이불 정리 60(기본), 합계 540, 저장된 항목 설정 1개
select item_order, item_name, default_duration_sec, duration_sec
from rs_replace_alarm_routine_items(
  (select user_id from rs_alarm where alarm_id = '00000000-0000-4000-8000-00000000b001'),
  '00000000-0000-4000-8000-00000000b001',
  (select jsonb_agg(jsonb_build_object('template_item_id', template_item_id,
                                       'duration_sec', case item_order when 1 then 120 else 60 end))
   from rs_routine_template_item
   where template_id = '00000000-0000-4000-8000-0000000000a2' and item_order in (1, 4)));

select (select count(*) from rs_alarm_routine_item) as item_settings,
       rs_alarm_routine_total('00000000-0000-4000-8000-00000000b001') as total_sec;


-- [T3-3] 다시 T3 상태로 (스트레칭 5분, 이불 정리 0분). 결과: 합계 660, 저장된 항목 설정 2개
select count(*) as items
from rs_replace_alarm_routine_items(
  (select user_id from rs_alarm where alarm_id = '00000000-0000-4000-8000-00000000b001'),
  '00000000-0000-4000-8000-00000000b001',
  (select jsonb_agg(jsonb_build_object('template_item_id', template_item_id,
                                       'duration_sec', case item_order when 1 then 300 else 0 end))
   from rs_routine_template_item
   where template_id = '00000000-0000-4000-8000-0000000000a2' and item_order in (1, 4)));

select (select count(*) from rs_alarm_routine_item) as item_settings,
       rs_alarm_routine_total('00000000-0000-4000-8000-00000000b001') as total_sec;


-- [T4] 밤 기록 저장 (날짜는 트리거가 계산) + 하루 한정 변경(내일만 06:30) + 원문
--  결과: record_date = KST 현재 - 12시간의 날짜, apply_date = 다음 날, 음성 경로 = {user_id}/{record_date}.m4a
insert into rs_night_record (user_id, ai_summary, tomorrow_goal, tomorrow_notes, condition_label, condition_text,
                             encouragement, insight, override_alarm_id, override_wake_time, extract_status)
select id, '발표 준비로 바쁜 하루였다.', '발표 잘 마무리하기', array['오후 회사 발표', '노트북 챙기기'],
       'TIRED', '조금 피곤하고 긴장됨', '준비한 만큼 잘 해낼 거예요.', '긴장은 준비했다는 신호예요.',
       '00000000-0000-4000-8000-00000000b001', '06:30', 'DONE'
from auth.users where email = 'respec.test@example.com'
on conflict (user_id, record_date) do update
  set tomorrow_goal = excluded.tomorrow_goal, extract_status = excluded.extract_status;

update rs_night_record
set voice_audio_path = user_id::text || '/' || to_char(record_date, 'YYYY-MM-DD') || '.m4a';

insert into rs_night_raw (night_record_id, transcript, started_at, ended_at)
select night_record_id,
       '[{"role": "assistant", "text": "오늘 하루 어땠어요?", "at": "2026-11-01T23:10:05+09:00"},
         {"role": "user", "text": "발표 준비하느라 바빴어요.", "at": "2026-11-01T23:10:12+09:00"}]'::jsonb,
       now() - interval '5 minutes', now()
from rs_night_record
on conflict (night_record_id) do nothing;

select n.record_date, n.apply_date, n.tomorrow_notes, n.voice_audio_path,
       n.override_wake_time, n.override_skip_coaching,
       jsonb_array_length(r.transcript) as transcript_lines, r.expires_at::date as raw_expires
from rs_night_record n left join rs_night_raw r using (night_record_id);


-- [T5] 루틴 세션 + 단계 만들기 (알람 설정이 적용된 실제 항목 목록 사용)
--  결과: 기상 06:30(하루 한정 변경), 계획 합계 660, 단계 5개(이불 정리 빠짐), 1번 DONE
insert into rs_morning_session (session_id, user_id, alarm_id, session_date, morning_call_type, template_id,
                                base_wake_time, target_wake_time, coaching_skipped, planned_total_sec)
select '00000000-0000-4000-8000-00000000c001', a.user_id, a.alarm_id, n.apply_date, 'ROUTINE', a.template_id,
       a.alarm_time, coalesce(n.override_wake_time, a.alarm_time), n.override_skip_coaching,
       rs_alarm_routine_total(a.alarm_id)
from rs_alarm a
join rs_night_record n on n.user_id = a.user_id and n.override_alarm_id = a.alarm_id
where a.alarm_id = '00000000-0000-4000-8000-00000000b001'
on conflict (session_id) do nothing;

insert into rs_session_step (user_id, session_id, step_order, step_name, template_item_id, planned_duration_sec)
select s.user_id, s.session_id, row_number() over (order by r.item_order),
       r.item_name, r.template_item_id, r.duration_sec
from rs_morning_session s
cross join lateral rs_alarm_routine_items(s.alarm_id) r
where s.session_id = '00000000-0000-4000-8000-00000000c001'
  and not s.coaching_skipped
  and r.duration_sec > 0
on conflict (session_id, step_order) do nothing;

update rs_session_step
set step_status = 'DONE', checked_at = now(), ai_verdict = 'done'
where session_id = '00000000-0000-4000-8000-00000000c001' and step_order = 1;

select s.target_wake_time, s.planned_total_sec, p.step_order, p.step_name, p.planned_duration_sec,
       p.step_status, p.ai_verdict
from rs_morning_session s join rs_session_step p using (session_id)
where s.session_id = '00000000-0000-4000-8000-00000000c001'
order by p.step_order;


-- [T6] 콘텐츠 세션 + 단계 (단계 내용은 step_detail에)
--  결과: 콘텐츠 세션 1개, 단계 2개, 내용 JSON 있음
insert into rs_morning_session (session_id, user_id, alarm_id, session_date, morning_call_type,
                                content_type, content_option, base_wake_time, target_wake_time, planned_total_sec)
select '00000000-0000-4000-8000-00000000c002', a.user_id, a.alarm_id, (now() at time zone 'Asia/Seoul')::date,
       'CONTENT', a.content_type, a.content_option, a.alarm_time, a.alarm_time, 300
from rs_alarm a
where a.alarm_id = '00000000-0000-4000-8000-00000000b002'
on conflict (session_id) do nothing;

insert into rs_session_step (user_id, session_id, step_order, step_name, planned_duration_sec, step_detail)
select s.user_id, s.session_id, v.step_order, v.step_name, v.sec, v.detail::jsonb
from rs_morning_session s,
     (values (1, '목 스트레칭', 150, '{"guide": "고개를 천천히 좌우로 기울인다"}'),
             (2, '어깨 돌리기', 150, '{"guide": "어깨를 앞뒤로 크게 돌린다"}')) as v(step_order, step_name, sec, detail)
where s.session_id = '00000000-0000-4000-8000-00000000c002'
on conflict (session_id, step_order) do nothing;

select s.morning_call_type, s.content_type, p.step_order, p.step_name, p.step_detail
from rs_morning_session s join rs_session_step p using (session_id)
where s.session_id = '00000000-0000-4000-8000-00000000c002'
order by p.step_order;


-- [T7] 개인화 전략 로그 + Bandit 상태 (결과: 로그 1, 상태 1)
insert into rs_strategy_log (user_id, session_id, session_date, source, mode, strategy_id, strategy, propensity, prompt_version)
select user_id, session_id, session_date, 'rule', 'rule', 'gentle', '{"coaching": "soft"}', 1, 'test'
from rs_morning_session where session_id = '00000000-0000-4000-8000-00000000c001';

insert into rs_bandit_state (state_key, state) values ('global', '{"counts": {}, "rewards": {}}')
on conflict (state_key) do update set state = excluded.state;

select (select count(*) from rs_strategy_log) as logs, (select count(*) from rs_bandit_state) as bandit_states;


-- 아래 [E1]~[E12]는 모두 오류가 나야 정상. 오류 이름(constraint 또는 메시지)을 확인
-- [E1] 음성 경로가 규칙과 다름 -> rs_night_voice_path
update rs_night_record set voice_audio_path = '00000000-0000-0000-0000-000000000000/2020-01-01.m4a';

-- [E2] 완료로 바꾸면서 체크 시각을 안 넣음 -> rs_step_status_time
update rs_session_step set step_status = 'DONE'
where session_id = '00000000-0000-4000-8000-00000000c001' and step_order = 2;

-- [E3] 루틴 세션에 다른 프리셋(M) 항목을 단계로 넣음 -> '세션 프리셋에 없는 항목입니다'
insert into rs_session_step (user_id, session_id, step_order, step_name, template_item_id)
select s.user_id, s.session_id, 99, '다른 프리셋 항목', i.template_item_id
from rs_morning_session s
join rs_routine_template_item i on i.template_id = '00000000-0000-4000-8000-0000000000a1' and i.item_order = 1
where s.session_id = '00000000-0000-4000-8000-00000000c001';

-- [E4] 콘텐츠 세션에 프리셋 항목을 넣음 -> '콘텐츠 세션의 단계에는 template_item_id를 넣지 않습니다'
insert into rs_session_step (user_id, session_id, step_order, step_name, template_item_id)
select s.user_id, s.session_id, 99, '잘못된 단계', i.template_item_id
from rs_morning_session s
join rs_routine_template_item i on i.template_id = '00000000-0000-4000-8000-0000000000a2' and i.item_order = 1
where s.session_id = '00000000-0000-4000-8000-00000000c002';

-- [E5] 항목 시간이 30초 단위가 아님(45초) -> rs_alarm_routine_item_duration_sec_check
update rs_alarm_routine_item set duration_sec = 45
where alarm_id = '00000000-0000-4000-8000-00000000b001';

-- [E6] 루틴 합계가 상한(900초)을 넘음 -> '루틴 합계 ...초가 상한 900초를 넘습니다'
insert into rs_alarm_routine_item (alarm_id, template_item_id, user_id, duration_sec)
select a.alarm_id, i.template_item_id, a.user_id, 600
from rs_alarm a
join rs_routine_template_item i on i.template_id = a.template_id and i.item_order = 5
where a.alarm_id = '00000000-0000-4000-8000-00000000b001'
on conflict (alarm_id, template_item_id) do update set duration_sec = excluded.duration_sec;

-- [E7] 모든 항목을 0분으로 -> '루틴 항목을 모두 뺄 수는 없습니다'
insert into rs_alarm_routine_item (alarm_id, template_item_id, user_id, duration_sec)
select a.alarm_id, i.template_item_id, a.user_id, 0
from rs_alarm a
join rs_routine_template_item i on i.template_id = a.template_id
where a.alarm_id = '00000000-0000-4000-8000-00000000b001'
on conflict (alarm_id, template_item_id) do update set duration_sec = excluded.duration_sec;

-- [E8] 알람 프리셋(N)이 아닌 M 프리셋 항목을 설정 -> '알람 프리셋에 없는 항목입니다'
insert into rs_alarm_routine_item (alarm_id, template_item_id, user_id, duration_sec)
select a.alarm_id, i.template_item_id, a.user_id, 60
from rs_alarm a
join rs_routine_template_item i on i.template_id = '00000000-0000-4000-8000-0000000000a1' and i.item_order = 1
where a.alarm_id = '00000000-0000-4000-8000-00000000b001';

-- [E9] 콘텐츠 알람에 항목 설정 -> '루틴 알람에만 항목 설정을 할 수 있습니다'
insert into rs_alarm_routine_item (alarm_id, template_item_id, user_id, duration_sec)
select a.alarm_id, i.template_item_id, a.user_id, 60
from rs_alarm a
join rs_routine_template_item i on i.template_id = '00000000-0000-4000-8000-0000000000a2' and i.item_order = 1
where a.alarm_id = '00000000-0000-4000-8000-00000000b002';

-- [E10] 루틴 알람인데 콘텐츠 종류도 넣음 -> rs_alarm_type_fields
insert into rs_alarm (user_id, alarm_time, morning_call_type, template_id, content_type)
select id, '06:00', 'ROUTINE', '00000000-0000-4000-8000-0000000000a2', 'QUIZ'
from auth.users where email = 'respec.test@example.com';

-- [E11] 하루 한정 변경(06:30)이 있는데 대상 알람을 비움 -> rs_night_override_alarm
update rs_night_record set override_alarm_id = null;

-- [E12] Bandit 상태 키 규칙 위반 -> rs_bandit_state_key
insert into rs_bandit_state (state_key, state) values ('user:abc', '{}');

-- [E13] 전체 교체로 상한을 넘김(N 프리셋 6항목을 모두 3분) -> '루틴 합계 1080초가 상한 900초를 넘습니다'
select * from rs_replace_alarm_routine_items(
  (select user_id from rs_alarm where alarm_id = '00000000-0000-4000-8000-00000000b001'),
  '00000000-0000-4000-8000-00000000b001',
  (select jsonb_agg(jsonb_build_object('template_item_id', template_item_id, 'duration_sec', 180))
   from rs_routine_template_item where template_id = '00000000-0000-4000-8000-0000000000a2'));

-- [E14] 다른 사용자 id로 교체 -> '알람이 없습니다'
select * from rs_replace_alarm_routine_items(
  '00000000-0000-0000-0000-000000000000', '00000000-0000-4000-8000-00000000b001', '[]'::jsonb);


-- [T7-2] 항목 설정이 있는 루틴 알람을 콘텐츠 알람으로 바꾸기 (설정은 지워지고 오류 없음)
--  결과: CONTENT, 항목 설정 0개. 확인 뒤 루틴 알람으로 되돌린다(설정은 지워진 채, T8 결과는 그대로)
update rs_alarm
set morning_call_type = 'CONTENT', template_id = null, content_type = 'QUIZ'
where alarm_id = '00000000-0000-4000-8000-00000000b001';

select (select morning_call_type from rs_alarm where alarm_id = '00000000-0000-4000-8000-00000000b001') as call_type,
       (select count(*) from rs_alarm_routine_item) as item_settings;

update rs_alarm
set morning_call_type = 'ROUTINE', template_id = '00000000-0000-4000-8000-0000000000a2', content_type = null
where alarm_id = '00000000-0000-4000-8000-00000000b001';


-- [T8] 알람 프리셋을 M으로 바꾸면 예전 항목 설정이 지워짐
--  결과: 항목 설정 0개, 루틴 합계 600 (M 기본값)
update rs_alarm set template_id = '00000000-0000-4000-8000-0000000000a1'
where alarm_id = '00000000-0000-4000-8000-00000000b001';

select (select count(*) from rs_alarm_routine_item) as item_settings,
       rs_alarm_routine_total('00000000-0000-4000-8000-00000000b001') as total_sec;


-- [T9] 알람을 지우면: 세션 기록은 남고(alarm_id만 비움), 하루 한정 변경값도 비워짐
--  결과: session_alarm 비어 있음, override_wake_time 비어 있음, steps 7 그대로 (루틴 5 + 콘텐츠 2)
delete from rs_alarm where alarm_id = '00000000-0000-4000-8000-00000000b001';

select (select alarm_id from rs_morning_session where session_id = '00000000-0000-4000-8000-00000000c001') as session_alarm,
       (select override_wake_time from rs_night_record limit 1) as override_wake_time,
       (select count(*) from rs_session_step) as steps;


-- [T10] 테스트 데이터 지우기
--  rs_user를 지우면 사용자 데이터가 모두 함께 지워진다 (ON DELETE CASCADE)
--  전체 합산 Bandit 상태('global')는 사용자와 연결이 없어서 따로 지운다
--  테스트 계정은 Authentication -> Users 에서 지운다
delete from rs_user
where user_id = (select id from auth.users where email = 'respec.test@example.com');
delete from rs_bandit_state where state_key = 'global';

select (select count(*) from rs_user) as users, (select count(*) from rs_alarm) as alarms,
       (select count(*) from rs_alarm_routine_item) as item_settings,
       (select count(*) from rs_night_record) as nights, (select count(*) from rs_night_raw) as raws,
       (select count(*) from rs_morning_session) as sessions, (select count(*) from rs_session_step) as steps,
       (select count(*) from rs_strategy_log) as logs, (select count(*) from rs_bandit_state) as bandit_states,
       (select count(*) from rs_routine_template_item) as preset_items;
