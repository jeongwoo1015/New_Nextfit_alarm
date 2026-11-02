"""AI 음악 9곡 등록: 파일을 music 버킷에 올리고 rs_ai_music에 행을 넣는다(다시 실행하면 교체).

실행: 저장소 루트에서
  backend/.venv/Scripts/python -m backend.jobs.seed_music <폴더>            # 올리기
  backend/.venv/Scripts/python -m backend.jobs.seed_music <폴더> --dry-run  # 무엇을 올릴지만 보기
  backend/.venv/Scripts/python -m backend.jobs.seed_music --delete          # 등록한 음악 전부 지우기

파일 이름: {MUSIC_TYPE}_{GENRE 또는 MOOD}.mp3 (또는 .wav)
  REWAKE_POP, REWAKE_JAZZ, REWAKE_CLASSIC, REWAKE_DANCE, REWAKE_ACOUSTIC,
  SLEEP_GOOD, SLEEP_NORMAL, SLEEP_TIRED, SLEEP_STRESSED
  wav는 mp3로 바꿔서 올린다(16bit PCM, pip install lameenc 필요).
경로: {music_type}/{genre 또는 mood}/{music_id}.mp3. 프롬프트는 ai/tasks/music.py의 all_music_rows().
"""
import argparse
import io
import sys
import uuid
import wave
from pathlib import Path

from ai.tasks.music import all_music_rows
from backend.repositories.supabase import build_repositories

BUCKET = "music"


def _key(row: dict) -> str:
    return row["genre"] or row["mood"]


def wav_to_mp3(data: bytes) -> bytes:
    try:
        import lameenc
    except ImportError:
        sys.exit("wav를 mp3로 바꾸려면 lameenc가 필요해요: backend/.venv/Scripts/python -m pip install lameenc")
    with wave.open(io.BytesIO(data)) as w:
        if w.getsampwidth() != 2:
            sys.exit("16bit PCM wav만 바꿀 수 있어요. mp3로 받아 주세요")
        enc = lameenc.Encoder()
        enc.set_bit_rate(128)
        enc.set_in_sample_rate(w.getframerate())
        enc.set_channels(w.getnchannels())
        enc.set_quality(2)
        return bytes(enc.encode(w.readframes(w.getnframes())) + enc.flush())


def find_file(folder: Path, row: dict) -> Path | None:
    name = f"{row['music_type']}_{_key(row)}".lower()
    hits = [p for p in folder.iterdir() if p.stem.lower() == name and p.suffix.lower() in (".mp3", ".wav")]
    return sorted(hits, key=lambda p: p.suffix.lower() != ".mp3")[0] if hits else None


def seed(folder: Path, dry_run: bool = False) -> None:
    repos = build_repositories()
    db, storage = repos.presets.db, repos.storage
    done, missing = 0, []
    for row in all_music_rows():
        label = f"{row['music_type']}/{_key(row)}"
        src = find_file(folder, row)
        if not src:
            missing.append(label)
            continue
        q = db.table("rs_ai_music").select("music_id, music_path").eq("music_type", row["music_type"])
        q = q.eq("genre", row["genre"]) if row["genre"] else q.eq("mood", row["mood"])
        existing = q.execute().data
        music_id = existing[0]["music_id"] if existing else str(uuid.uuid4())
        path = f"{label}/{music_id}.mp3"
        if dry_run:
            print(f"{label}: {src.name} -> {path} ({'교체' if existing else '새로 등록'})")
            continue
        data = src.read_bytes()
        if src.suffix.lower() == ".wav":
            data = wav_to_mp3(data)
        storage._upload(BUCKET, path, data, "audio/mpeg")
        values = {"music_type": row["music_type"], "genre": row["genre"], "mood": row["mood"],
                  "music_prompt": row["music_prompt"], "music_path": path}
        if existing:
            db.table("rs_ai_music").update(values).eq("music_id", music_id).execute()
            old = existing[0]["music_path"]
            if old and old != path:
                storage.delete_files(BUCKET, [old])
        else:
            db.table("rs_ai_music").insert({"music_id": music_id, **values}).execute()
        print(f"{label}: {src.name} 올림 ({len(data) // 1024}KB)")
        done += 1
    print(f"\n{'확인만 함' if dry_run else f'{done}곡 등록'}. 없는 파일 {len(missing)}개: {', '.join(missing) or '없음'}")


def delete_all() -> None:
    repos = build_repositories()
    db = repos.presets.db
    rows = db.table("rs_ai_music").select("music_id, music_path").execute().data
    repos.storage.delete_files(BUCKET, [r["music_path"] for r in rows])
    if rows:
        db.table("rs_ai_music").delete().in_("music_id", [r["music_id"] for r in rows]).execute()
    print(f"{len(rows)}곡 삭제")


def main() -> None:
    ap = argparse.ArgumentParser(description="AI 음악 9곡 등록")
    ap.add_argument("folder", nargs="?", help="음악 파일 폴더")
    ap.add_argument("--dry-run", action="store_true", help="올리지 않고 계획만 보기")
    ap.add_argument("--delete", action="store_true", help="등록한 음악 전부 지우기")
    args = ap.parse_args()
    if args.delete:
        delete_all()
    elif args.folder:
        seed(Path(args.folder), args.dry_run)
    else:
        ap.error("폴더를 넣거나 --delete를 쓰세요")


if __name__ == "__main__":
    main()
