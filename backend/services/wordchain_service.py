"""끝말잇기 글자 판정 (11/25 앱 요청, AI CONTRACTS B26).

콘텐츠 탭에서 모닝콜 때 끝나지 않은 끝말잇기를 글자로 이어 할 때, 사용자가 단어를 낼 때마다 한 번씩 AI를 부른다.
기록은 남기지 않는다(게임 기록은 PUT .../steps/{n}/score).
"""
import logging

from backend.clients.ai_gateway import AIGateway
from backend.core.errors import ServiceUnavailable

log = logging.getLogger(__name__)


class WordchainService:
    def __init__(self, ai: AIGateway):
        self.ai = ai

    def turn(self, user_id: str, words: list[str], word: str) -> dict:
        """AI 결과를 그대로 돌려준다. 판정하지 못했으면(FAILED) 사용자 단어를 통과시키거나 넥스티 패배로 처리하지 않고 503."""
        res = self.ai.judge_wordchain(words, word)
        if not isinstance(res, dict) or not isinstance(res.get("valid"), bool):
            log.warning("끝말잇기 판정 실패 user=%s result=%s", user_id, res)
            raise ServiceUnavailable("잠시 후 다시 해 주세요", "WORDCHAIN_FAILED")
        return res
