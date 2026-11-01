"""가상 사용자의 발화를 합성 음성(16kHz 16bit 모노 PCM)으로 만든다. 맥의 `say`(한국어 Yuna)를 쓰므로 맥에서만 동작한다."""
import hashlib
import os
import subprocess
import tempfile
import wave

VOICE = "Yuna"
CACHE = os.path.join(tempfile.gettempdir(), "nextfit_tts_cache")


class TTSUnavailable(Exception):
    pass


def synthesize(text: str) -> bytes:
    """같은 글자는 캐시를 재사용한다. 반환: raw PCM(16000Hz, 16bit, mono)."""
    os.makedirs(CACHE, exist_ok=True)
    key = hashlib.sha1(f"{VOICE}:{text}".encode()).hexdigest()[:16]
    wav = os.path.join(CACHE, f"{key}.wav")
    if not os.path.exists(wav):
        src, aiff = os.path.join(CACHE, f"{key}.txt"), os.path.join(CACHE, f"{key}.aiff")
        with open(src, "w", encoding="utf-8") as f:
            f.write(text)
        try:
            subprocess.run(["say", "-v", VOICE, "-o", aiff, "-f", src], check=True, capture_output=True)
            subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", aiff, wav], check=True, capture_output=True)
        except (FileNotFoundError, subprocess.CalledProcessError) as e:
            raise TTSUnavailable(f"합성 음성을 만들 수 없어요(맥의 say/afconvert 필요): {e}") from None
        finally:
            for p in (src, aiff):
                if os.path.exists(p):
                    os.remove(p)
    with wave.open(wav) as w:
        return w.readframes(w.getnframes())
