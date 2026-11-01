-- Storage 버킷 파일 크기·형식 제한: 이미 02를 실행한 DB에 적용 (파일과 정책은 그대로)
-- 02_security.sql에도 같은 내용이 들어 있으므로, 처음부터 만드는 DB에는 실행하지 않아도 된다
--  voice 10MB / night-raw 50MB (m4a 계열), photo 10MB (jpeg만), music 20MB (mp3만)


update storage.buckets set file_size_limit = 10485760,
  allowed_mime_types = array['audio/mp4', 'audio/x-m4a', 'audio/m4a', 'audio/aac'] where id = 'voice';
update storage.buckets set file_size_limit = 52428800,
  allowed_mime_types = array['audio/mp4', 'audio/x-m4a', 'audio/m4a', 'audio/aac'] where id = 'night-raw';
update storage.buckets set file_size_limit = 10485760,
  allowed_mime_types = array['image/jpeg'] where id = 'photo';
update storage.buckets set file_size_limit = 20971520,
  allowed_mime_types = array['audio/mpeg'] where id = 'music';

-- 확인 (4줄, 크기와 형식이 위와 같아야 함)
select id, public, file_size_limit, allowed_mime_types from storage.buckets order by id;
