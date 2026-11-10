"""가상 사용자의 발화를 합성 음성(16kHz 16bit 모노 PCM)으로 만든다. 맥의 `say`(한국어 Yuna, 한글이 없는 글은 영어 Samantha)를 쓰므로 맥에서만 동작한다."""
import hashlib
import os
import re
import subprocess
import tempfile
import wave

VOICE = "Yuna"
VOICE_EN = "Samantha"          # 한글이 하나도 없는 발화(영어 콘텐츠 시험)에 쓴다
CACHE = os.path.join(tempfile.gettempdir(), "nextfit_tts_cache")


class TTSUnavailable(Exception):
    pass


def synthesize(text: str) -> bytes:
    """같은 글자는 캐시를 재사용한다. 반환: raw PCM(16000Hz, 16bit, mono)."""
    os.makedirs(CACHE, exist_ok=True)
    voice = VOICE if re.search(r"[가-힣]", text) else VOICE_EN
    key = hashlib.sha1(f"{voice}:{text}".encode()).hexdigest()[:16]
    wav = os.path.join(CACHE, f"{key}.wav")
    if not os.path.exists(wav):
        src, aiff = os.path.join(CACHE, f"{key}.txt"), os.path.join(CACHE, f"{key}.aiff")
        with open(src, "w", encoding="utf-8") as f:
            f.write(text)
        try:
            subprocess.run(["say", "-v", voice, "-o", aiff, "-f", src], check=True, capture_output=True)
            subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", aiff, wav], check=True, capture_output=True)
        except (FileNotFoundError, subprocess.CalledProcessError) as e:
            raise TTSUnavailable(f"합성 음성을 만들 수 없어요(맥의 say/afconvert 필요): {e}") from None
        finally:
            for p in (src, aiff):
                if os.path.exists(p):
                    os.remove(p)
    with wave.open(wav) as w:
        return w.readframes(w.getnframes())
