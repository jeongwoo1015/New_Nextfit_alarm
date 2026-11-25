"""끝말잇기 글자 판정 (AI CONTRACTS B26)."""
from fastapi import APIRouter, Depends

from backend.app.deps import wordchain_service
from backend.core.auth import current_user_id
from backend.services.wordchain_service import WordchainService

from .schemas import WordchainTurnIn, WordchainTurnOut

router = APIRouter(tags=["wordchain"])


@router.post("/wordchain/turn", response_model=WordchainTurnOut)
def wordchain_turn(body: WordchainTurnIn, user_id: str = Depends(current_user_id),
                   svc: WordchainService = Depends(wordchain_service)):
    """사용자가 낸 단어를 판정하고, 맞으면 넥스티가 이을 단어를 준다(보통 1초 안팎, 재시도가 붙으면 몇 초).
    맞음 `{"valid": true, "next": "정원"}`, 이을 단어 없음 `{"valid": true, "next": null}`(넥스티 패배),
    틀림 `{"valid": false, "reason": "사전에 없는 말이에요"}`. AI가 판정하지 못하면 503 `WORDCHAIN_FAILED`,
    AI를 쓸 수 없으면 503 `AI_UNAVAILABLE` (앱은 자기 규칙·단어 목록으로 이어 간다)."""
    return WordchainTurnOut(**{k: v for k, v in svc.turn(user_id, body.words, body.word).items()
                               if k in WordchainTurnOut.model_fields})
