"""RS_AI_MUSIC.MUSIC_PROMPT: MusicGen 입력 문장. 배치로 미리 만들어 두는 공용 음악이라 LLM을 쓰지 않고 고정 문장을 쓴다.

장르 값은 RS_USER_SETTING.MUSIC_GENRE / RS_AI_MUSIC.GENRE (POP/JAZZ/CLASSIC/DANCE/ACOUSTIC),
무드 값은 RS_NIGHT_RECORD.CONDITION_LABEL (GOOD/NORMAL/TIRED/STRESSED) — 정의서 값 목록.
"""

REWAKE_GENRES = {
    "POP": "Upbeat bright pop instrumental for waking up, starts gently and builds into an energetic groove, "
           "110 BPM, clean synths, light claps, fresh and positive, no vocals",
    "JAZZ": "Warm morning jazz instrumental, soft piano, brushed drums, upright bass and a light saxophone melody, "
            "relaxed but uplifting, gradually livelier, 100 BPM, no vocals",
    "CLASSIC": "Gentle classical morning piece for strings and piano, light and airy like a sunrise, "
               "gradually brightening and growing in volume, 90 BPM, no vocals",
    "DANCE": "Energetic dance track for waking up, four-on-the-floor beat, bright synth arpeggios, "
             "motivating and clear, 124 BPM, no vocals",
    "ACOUSTIC": "Warm acoustic guitar instrumental, light fingerpicking with soft percussion, "
                "cheerful and calm morning mood that slowly picks up energy, 95 BPM, no vocals",
}

SLEEP_MOODS = {
    "GOOD": "Peaceful ambient sleep music for a content, relaxed mind, soft piano and warm pads, "
            "slow and gentle, 60 BPM, slow fade out, no vocals",
    "NORMAL": "Calm ambient sleep music, soft pads and slow sparse piano, steady and quiet, "
              "60 BPM, slow fade out, no vocals",
    "TIRED": "Very soft slow sleep music for a worn-out listener, deep warm drones, gentle low piano, "
             "restful and heavy, 50 BPM, slow fade out, no vocals",
    "STRESSED": "Soothing calming music to release stress, slow breathing rhythm, soft harp and warm pads, "
                "comforting and spacious, 55 BPM, slow fade out, no vocals",
}


def build_music_prompt(music_type: str, genre: str | None = None, mood: str | None = None) -> str:
    """REWAKE이면 genre, SLEEP이면 mood가 필요하다 (정의서 제약: 한쪽만 값이 있다). 모르는 값은 ValueError."""
    if music_type == "REWAKE":
        if mood or genre not in REWAKE_GENRES:
            raise ValueError(f"REWAKE에는 장르({'/'.join(REWAKE_GENRES)})만 필요해요: genre={genre!r}, mood={mood!r}")
        return REWAKE_GENRES[genre]
    if music_type == "SLEEP":
        if genre or mood not in SLEEP_MOODS:
            raise ValueError(f"SLEEP에는 무드({'/'.join(SLEEP_MOODS)})만 필요해요: genre={genre!r}, mood={mood!r}")
        return SLEEP_MOODS[mood]
    raise ValueError(f"MUSIC_TYPE은 REWAKE/SLEEP이에요: {music_type!r}")


def all_music_rows() -> list[dict]:
    """RS_AI_MUSIC 시드용 행 (MUSIC_PATH는 음악을 만든 뒤 채운다)."""
    rows = [{"music_type": "REWAKE", "genre": g, "mood": None, "music_prompt": p} for g, p in REWAKE_GENRES.items()]
    rows += [{"music_type": "SLEEP", "genre": None, "mood": m, "music_prompt": p} for m, p in SLEEP_MOODS.items()]
    return rows
