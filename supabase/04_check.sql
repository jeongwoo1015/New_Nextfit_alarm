-- Re:Spec 최종 DB 확인 쿼리 (01~03 실행 후, 블록을 하나씩 선택해서 실행)


-- [1] 테이블 15개가 있는지 (15가 나와야 함)
select count(*) as tables
from information_schema.tables
where table_schema = 'public' and table_name like 'rs\_%';


-- [2] 모든 테이블에 RLS가 켜져 있는지 (15줄 모두 true)
select relname as table_name, relrowsecurity as rls_on
from pg_class
where relnamespace = 'public'::regnamespace and relkind = 'r' and relname like 'rs\_%'
order by relname;


-- [3] 정책 목록
--  own_rows 8개: rs_user, rs_user_setting, rs_alarm, rs_alarm_routine_item, rs_morning_session,
--               rs_session_step, rs_night_record, rs_diary_photo
--  read_all 4개: rs_app_config, rs_routine_template, rs_routine_template_item, rs_ai_music
--  정책 없음(서버만): rs_night_raw, rs_strategy_log, rs_bandit_state
select tablename, policyname, cmd, roles
from pg_policies
where schemaname = 'public'
order by tablename;


-- [4] Storage 버킷 4개 (voice, night-raw, photo, music, 모두 public = false)
--     크기: music 20971520, night-raw 52428800, photo·voice 10485760
select id, public, file_size_limit, allowed_mime_types from storage.buckets order by id;


-- [5] 앱 설정 (routine_max_sec = 900)
select routine_max_sec, content_duration_sec from rs_app_config;


-- [6] 프리셋별 항목 수와 기본 합계 (5줄 모두 600)
select t.template_name, count(*) as items, sum(i.default_duration_sec) as total_sec
from rs_routine_template t
join rs_routine_template_item i on i.template_id = t.template_id
group by t.template_id, t.template_name
order by t.template_id;


-- [7] 스크립트가 들어간 항목 (스트레칭 4개(M, N, O, Q) + 림프 1개 = 5행)
select t.template_name, i.item_order, i.item_name, left(i.script, 30) as script_start
from rs_routine_template_item i
join rs_routine_template t on t.template_id = i.template_id
where i.script is not null
order by t.template_id, i.item_order;


-- [8] 밤 날짜 계산 규칙 확인 (지금 저장하면 들어갈 날짜)
select ((now() at time zone 'Asia/Seoul') - interval '12 hours')::date as record_date,
       ((now() at time zone 'Asia/Seoul') - interval '12 hours')::date + 1 as apply_date;
