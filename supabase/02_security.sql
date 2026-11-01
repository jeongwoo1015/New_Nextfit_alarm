-- Re:Spec 최종 DB 접근 권한 (01_schema.sql 다음에 실행)
-- 앱은 FastAPI 서버를 거쳐 DB에 접근한다. 서버는 service_role 키를 쓰므로 RLS를 거치지 않고,
-- 로그인 토큰의 user_id를 서버 코드에서 직접 확인한다.
-- 아래 정책은 키가 잘못 쓰였을 때를 막는 안전장치다 (anon 키로는 아무것도 못 읽음).


-- 사용자 데이터: 로그인한 본인 행만 읽기·쓰기
do $$
declare t text;
begin
  foreach t in array array['rs_user', 'rs_user_setting', 'rs_alarm', 'rs_alarm_routine_item',
                           'rs_morning_session', 'rs_session_step', 'rs_night_record', 'rs_diary_photo']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('drop policy if exists "own_rows" on %I', t);
    execute format('create policy "own_rows" on %I for all to authenticated
                    using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id)', t);
  end loop;
end $$;


-- 공용 데이터: 로그인한 사용자는 읽기만, 쓰기는 서버만
do $$
declare t text;
begin
  foreach t in array array['rs_app_config', 'rs_routine_template', 'rs_routine_template_item', 'rs_ai_music']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('drop policy if exists "read_all" on %I', t);
    execute format('create policy "read_all" on %I for select to authenticated using (true)', t);
  end loop;
end $$;


-- 밤 대화 원문, 개인화 로그·학습 상태: 정책 없음 = 서버(service_role)만 접근
alter table rs_night_raw enable row level security;
alter table rs_strategy_log enable row level security;
alter table rs_bandit_state enable row level security;


-- Storage 버킷 (모두 비공개, 재생은 서명된 임시 URL)
insert into storage.buckets (id, name, public) values
  ('voice', 'voice', false),          -- {user_id}/{record_date}.m4a
  ('night-raw', 'night-raw', false),  -- {user_id}/{record_date}.m4a, 서버만
  ('photo', 'photo', false),          -- {user_id}/{diary_date}.jpg
  ('music', 'music', false)           -- {music_type}/{genre 또는 mood}/{music_id}.mp3
on conflict (id) do nothing;

-- voice, photo: 본인 폴더({user_id}/...)만
drop policy if exists "own_files_select" on storage.objects;
drop policy if exists "own_files_insert" on storage.objects;
drop policy if exists "own_files_update" on storage.objects;
drop policy if exists "own_files_delete" on storage.objects;
create policy "own_files_select" on storage.objects for select to authenticated
  using (bucket_id in ('voice', 'photo') and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "own_files_insert" on storage.objects for insert to authenticated
  with check (bucket_id in ('voice', 'photo') and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "own_files_update" on storage.objects for update to authenticated
  using (bucket_id in ('voice', 'photo') and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "own_files_delete" on storage.objects for delete to authenticated
  using (bucket_id in ('voice', 'photo') and (storage.foldername(name))[1] = (select auth.uid())::text);

-- music: 로그인한 사용자 읽기만
drop policy if exists "music_read" on storage.objects;
create policy "music_read" on storage.objects for select to authenticated
  using (bucket_id = 'music');

-- night-raw: 정책 없음 = 서버만
