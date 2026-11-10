-- 오늘의 포춘(11/06): 이미 01을 실행한 DB에 적용 (기존 데이터 그대로)
--  아침 세션에 그날 읽은 포춘 문장을 저장한다. 다시 연결해도 같은 문장, 일기·아침 리포트에서 다시 보여 줄 수 있다
--  밤 '내일의 한마디'를 기기에서 받아쓴 글을 저장한다. 다음 날 포춘의 재료
-- 01_schema.sql에도 같은 내용이 들어 있으므로, 처음부터 만드는 DB에는 실행하지 않아도 된다


alter table rs_morning_session
  add column if not exists fortune_text varchar(200),
  add column if not exists fortune_style varchar(10) check (fortune_style in ('COOKIE', 'HOROSCOPE')),
  add column if not exists fortune_source varchar(10) check (fortune_source in ('ai', 'fallback', 'safe'));

alter table rs_morning_session drop constraint if exists rs_session_fortune;
alter table rs_morning_session add constraint rs_session_fortune check (
  (fortune_text is null and fortune_style is null and fortune_source is null)
  or
  (fortune_text is not null and fortune_style is not null and fortune_source is not null)
);

comment on column rs_morning_session.fortune_text is '오늘의 포춘 문장 (어젯밤 한마디 기반, 없으면 한 줄 일기)';
comment on column rs_morning_session.fortune_style is 'COOKIE 포춘쿠키 / HOROSCOPE 오늘의 운세';
comment on column rs_morning_session.fortune_source is 'ai 생성 / fallback 기본 문장 / safe 위기 표현이 있어 담담한 문장';


alter table rs_night_record
  add column if not exists voice_text varchar(500);

alter table rs_night_record drop constraint if exists rs_night_voice_text;
alter table rs_night_record add constraint rs_night_voice_text check (voice_text is null or voice_audio_path is not null);

comment on column rs_night_record.voice_text is '내일의 한마디를 기기에서 받아쓴 글 (음성과 함께 저장)';


-- 확인 (4줄)
select table_name, column_name, data_type from information_schema.columns
where (table_name = 'rs_morning_session' and column_name like 'fortune%')
   or (table_name = 'rs_night_record' and column_name = 'voice_text')
order by table_name, column_name;
