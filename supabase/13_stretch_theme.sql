-- 스트레칭 부위 12개·스트레칭 3분, 테마 AUTO(11/18): 이미 01을 실행한 DB에 적용 (기존 데이터 그대로)
--  1. 아침 세션 STRETCH_AREA: 새 부위 코드 12개 허용. 예전 5개(11/13)는 기존 기록 때문에 그대로 둔다
--  2. 앱 설정 STRETCH_DURATION_SEC: 스트레칭만 3분(180초). 영어·게임은 CONTENT_DURATION_SEC(300초) 그대로
--  3. 사용자 설정 THEME: SYSTEM 대신 AUTO(일출·일몰 기준). 저장된 SYSTEM은 AUTO로 바꾸고 기본값도 AUTO
--  검사 규칙은 이름을 가정하지 않고, 해당 칸을 검사하는 규칙을 찾아 지운 뒤 다시 만든다
-- 01_schema.sql에도 같은 내용이 들어 있으므로, 처음부터 만드는 DB에는 실행하지 않아도 된다


-- 1. 스트레칭 부위
do $$
declare c record;
begin
  for c in select conname from pg_constraint
           where conrelid = 'rs_morning_session'::regclass and contype = 'c'
             and pg_get_constraintdef(oid) like '%stretch_area%'
  loop
    execute format('alter table rs_morning_session drop constraint %I', c.conname);
  end loop;
end $$;

alter table rs_morning_session add constraint rs_session_stretch_area check (stretch_area in (
  'FULL', 'NECK', 'SHOULDER', 'CHEST_BACK', 'WRIST_HAND', 'ELBOW_ARM',
  'LOW_BACK', 'PELVIS', 'GLUTE', 'THIGH', 'CALF', 'ANKLE_FOOT',
  'NECK_SHOULDER', 'ARM_WRIST', 'BACK_HIP', 'LEG'   -- 11/13 예전 값 (기존 기록용)
));

comment on column rs_morning_session.stretch_area is '그날 고른 스트레칭 부위: FULL 전신 / NECK 목 / SHOULDER 어깨 / CHEST_BACK 가슴·윗등 / WRIST_HAND 손목·손 / ELBOW_ARM 팔꿈치·위팔 / LOW_BACK 허리 / PELVIS 골반 / GLUTE 엉덩이 / THIGH 허벅지 / CALF 종아리 / ANKLE_FOOT 발목·발 (예전 값 NECK_SHOULDER·ARM_WRIST·BACK_HIP·LEG는 기존 기록에만)';


-- 2. 스트레칭 시간
alter table rs_app_config
  add column if not exists stretch_duration_sec int not null default 180 check (stretch_duration_sec > 0);

comment on column rs_app_config.stretch_duration_sec is '스트레칭 콘텐츠 시간(초). 영어·게임은 CONTENT_DURATION_SEC';


-- 3. 테마
do $$
declare c record;
begin
  for c in select conname from pg_constraint
           where conrelid = 'rs_user_setting'::regclass and contype = 'c'
             and pg_get_constraintdef(oid) like '%theme%'
  loop
    execute format('alter table rs_user_setting drop constraint %I', c.conname);
  end loop;
end $$;

update rs_user_setting set theme = 'AUTO' where theme = 'SYSTEM';
alter table rs_user_setting alter column theme set default 'AUTO';
alter table rs_user_setting add constraint rs_setting_theme check (theme in ('LIGHT', 'DARK', 'AUTO'));

comment on column rs_user_setting.theme is 'LIGHT / DARK / AUTO(해가 떠 있으면 라이트, 지면 다크)';


-- 확인 (5줄)
select 'rs_morning_session' as table_name, conname as name, null as value from pg_constraint where conname = 'rs_session_stretch_area'
union all
select 'rs_user_setting', conname, null from pg_constraint where conname = 'rs_setting_theme'
union all
select 'rs_user_setting', 'theme 기본값', column_default from information_schema.columns
where table_name = 'rs_user_setting' and column_name = 'theme'
union all
select 'rs_user_setting', 'SYSTEM으로 남은 행', count(*)::text from rs_user_setting where theme = 'SYSTEM'
union all
select 'rs_app_config', 'stretch_duration_sec', stretch_duration_sec::text from rs_app_config;
