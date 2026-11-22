package com.example.respeclive.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** 끝말잇기 승패: 시간 초과 또는 더 이을 단어가 없음 */
enum class WordChainWinner { USER, NEXTY }
enum class WordChainReason { TIMEOUT, NO_WORD }
data class WordChainResult(val winner: WordChainWinner, val reason: WordChainReason, val inMorning: Boolean)

/** 주고받은 말 한 줄. 모닝콜은 넥스티·내 말 전체(음성 받아쓰기), 이어 하기는 단어 하나 */
data class WordChainLine(val mine: Boolean, val text: String)

/** 한 세션(그날 끝말잇기 한 판)의 기록. 기기에만 저장 (서버는 아침 통화 원문을 저장하지 않음) */
data class WordChainRecord(
    val morning: List<WordChainLine> = emptyList(),
    val game: List<WordChainLine> = emptyList(),
    val result: WordChainResult? = null,
)

/** 끝말잇기 규칙: 앞 단어의 마지막 글자로 시작, 두음법칙(리→이, 라→나 등) 허용, 2~6글자 한글, 이미 나온 단어 금지 */
object WordChainRules {
    const val MIN_LEN = 2
    const val MAX_LEN = 6
    private val HANGUL = Regex("[가-힣]+")

    /** 두음법칙으로 바뀐 첫 글자 (없으면 null). ㄹ+ㅑㅕㅖㅛㅠㅣ → ㅇ, ㄹ+그 밖 → ㄴ, ㄴ+ㅕㅛㅠㅣ → ㅇ */
    fun dueum(c: Char): Char? {
        if (c !in '가'..'힣') return null
        val code = c - '가'
        val initial = code / (21 * 28)
        val vowel = (code / 28) % 21
        val final = code % 28
        val toIeung = vowel in setOf(2, 6, 7, 12, 17, 20)     // ㅑ ㅕ ㅖ ㅛ ㅠ ㅣ
        val newInitial = when (initial) {
            5 -> if (toIeung) 11 else 2                         // ㄹ
            2 -> if (vowel in setOf(6, 12, 17, 20)) 11 else return null   // ㄴ + ㅕㅛㅠㅣ
            else -> return null
        }
        return '가' + (newInitial * 21 * 28 + vowel * 28 + final)
    }

    /** 이 단어 다음에 올 수 있는 첫 글자들 */
    fun nextStarts(word: String): Set<Char> {
        val last = word.lastOrNull() ?: return emptySet()
        return setOfNotNull(last, dueum(last))
    }

    /** 입력에서 한글만 남김 ("사과!" → "사과") */
    fun clean(input: String): String = HANGUL.findAll(input).joinToString("") { it.value }

    /** 잘못된 이유 (null이면 이어도 되는 단어) */
    fun problem(word: String, previous: String?, requiredStart: Char?, used: Collection<String>): String? = when {
        word.length < MIN_LEN -> "두 글자 이상 단어를 말해 주세요"
        word.length > MAX_LEN -> "${MAX_LEN}글자 이하 단어를 말해 주세요"
        word in used -> "이미 나온 단어예요"
        previous != null && word.first() !in nextStarts(previous) ->
            "'${nextStarts(previous).joinToString("' 또는 '")}'(으)로 시작해야 해요"
        previous == null && requiredStart != null && word.first() !in setOfNotNull(requiredStart, dueum(requiredStart)) ->
            "'$requiredStart'(으)로 시작해야 해요"
        else -> null
    }
}

/** 넥스티(앱)가 말하는 단어 목록 (assets/wordchain_words.txt) */
class WordChainDictionary(words: Collection<String>) {
    private val byStart: Map<Char, List<String>> = words
        .filter { it.length in WordChainRules.MIN_LEN..WordChainRules.MAX_LEN && it.all { c -> c in '가'..'힣' } }
        .distinct()
        .groupBy { it.first() }

    /** previous 다음에 이을 단어. 사용자가 이어 갈 수 있는 단어를 먼저 고르고, 없으면 아무거나, 그래도 없으면 null(넥스티 패배) */
    fun pick(previous: String?, requiredStart: Char?, used: Collection<String>, random: Random = Random.Default): String? {
        val starts = previous?.let { WordChainRules.nextStarts(it) }
            ?: requiredStart?.let { setOfNotNull(it, WordChainRules.dueum(it)) }
        val candidates = (starts?.flatMap { byStart[it].orEmpty() } ?: byStart.values.flatten())
            .filter { it !in used }
        if (candidates.isEmpty()) return null
        val friendly = candidates.filter { c -> WordChainRules.nextStarts(c).any { s -> byStart[s].orEmpty().any { it !in used && it != c } } }
        return (friendly.ifEmpty { candidates }).random(random)
    }

    companion object {
        @Volatile private var cached: WordChainDictionary? = null

        fun load(context: Context): WordChainDictionary = cached ?: synchronized(this) {
            cached ?: WordChainDictionary(
                context.assets.open("wordchain_words.txt").bufferedReader().readLines()
                    .filterNot { it.startsWith("#") }
                    .flatMap { it.split(Regex("\\s+")) }
                    .filter { it.isNotBlank() }
            ).also { cached = it }
        }
    }
}

/**
 * 모닝콜 끝말잇기 받아쓰기에서 이어 하기를 시작할 자리와 승패를 찾음.
 * AI가 승패를 알려 주지 않아서(지금 AI는 다섯 번쯤 주고받으면 마무리) 말로 판단:
 * 내가 '패스·모르겠어·졌어'처럼 말하면 넥스티 승, 넥스티가 '제가 졌어요·떠오르지 않아요'처럼 말하면 내 승
 */
object WordChainMorning {
    private val QUOTED = Regex("['\"‘’“”]([가-힣]{2,6})['\"‘’“”]")
    private val STARTS_WITH = Regex("['\"‘’“”]?([가-힣])['\"‘’“”]?\\s*(?:\\(으\\))?\\s*(?:으)?로\\s*시작")
    private val USER_GAVE_UP = Regex("패스|모르겠|졌어|졌다|항복|못\\s*하겠|생각이\\s*안\\s*나|떠오르지\\s*않")
    private val NEXTY_GAVE_UP = Regex("제가\\s*졌|넥스티가\\s*졌|떠오르지\\s*않|이을\\s*단어가\\s*없|생각이\\s*안\\s*나|당신이\\s*이겼|이기셨")

    private val TOKEN = Regex("[가-힣]{2,10}")
    private val ENDINGS = listOf("이에요", "예요", "입니다", "이죠", "이요", "요")

    /** 끝말잇기에서 마지막으로 이어진 단어와 누가 말했는지 */
    data class ResumePoint(val word: String, val mine: Boolean)

    private val START_WORD = Regex("시작\\s*단어[는은]?\\s*['\"‘“]?([가-힣]{2,10})")
    private val LEAD_WORD = Regex("^\\s*([가-힣]{2,6})\\s*(?:[.!~?]|$)")
    /** 줄 맨 앞에 와도 끝말잇기 단어가 아닌 감탄·칭찬 */
    private val NOT_WORDS = setOf("좋아요", "좋아", "맞아요", "맞아", "그럼", "정답", "잘했어요", "훌륭해요", "멋져요", "대단해요",
        "아쉬워요", "괜찮아요", "우와", "와우", "오케이", "그래요", "네네", "알겠어요", "자아", "이번엔")
    /** 끝말잇기를 마무리하는 말 (다음 순서로 넘어가기) */
    private val WRAP_UP = Regex("다음\\s*순서|넘어갈게|넘어가 볼게|포춘|여기까지|마무리|마칠게|마치셨|좋은 하루")

    fun isWrapUp(text: String): Boolean = WRAP_UP.containsMatchIn(text)

    private fun stem(t: String): String =
        ENDINGS.firstOrNull { t.endsWith(it) && t.length - it.length >= 2 }?.let { t.dropLast(it.length) } ?: t

    /** 말 한 줄에서 단어 후보: 따옴표 단어 먼저, 그다음 말끝(예요·요 등)을 뗀 한글 낱말 */
    private fun candidates(text: String): List<String> {
        val quoted = QUOTED.findAll(text).map { it.groupValues[1] }.toList()
        val plain = TOKEN.findAll(text).map { stem(it.value) }.filter { it.length in 2..6 }.toList()
        return quoted + plain
    }

    private fun chains(word: String, prev: String?) = prev == null || (word != prev && word.first() in WordChainRules.nextStarts(prev))

    /**
     * 받아쓰기에서 끝말잇기 단어만 골라 순서대로 (채팅에는 이것만 보여 줌).
     * 넥스티 말(AI 글이라 정확함): 따옴표 단어 → "시작 단어는 ○○" → 앞 단어에 이어지는 낱말 → 줄 맨 앞 단어("기러기. 재미있게…").
     * 내 말(음성 받아쓰기라 틀릴 수 있음): 한글이 없거나('kier') '패스·모르겠어'면 빼고, 앞 단어에 이어지는 낱말 → 첫 낱말
     */
    fun chainWords(lines: List<WordChainLine>): List<WordChainLine> {
        val out = mutableListOf<WordChainLine>()
        for (line in lines) {
            val prev = out.lastOrNull()?.text
            val cands = candidates(line.text).filter { it !in NOT_WORDS && out.none { o -> o.text == it } }
            val pick = if (!line.mine) {
                val quoted = QUOTED.findAll(line.text).map { it.groupValues[1] }.toList()
                quoted.lastOrNull { chains(it, prev) } ?: quoted.lastOrNull()
                    ?: START_WORD.find(line.text)?.groupValues?.get(1)?.let { stem(it) }?.takeIf { prev == null }
                    ?: cands.lastOrNull { prev != null && chains(it, prev) }
                    ?: LEAD_WORD.find(line.text)?.groupValues?.get(1)?.let { stem(it) }?.takeIf { it !in NOT_WORDS && it.length >= 2 }
            } else {
                if (USER_GAVE_UP.containsMatchIn(line.text)) null
                else cands.firstOrNull { prev != null && chains(it, prev) } ?: cands.firstOrNull()
            }
            if (pick != null && pick.length in WordChainRules.MIN_LEN..WordChainRules.MAX_LEN) out += WordChainLine(line.mine, pick)
        }
        return out
    }

    /** 이어 하기를 시작할 자리: 끝말잇기 단어 중 마지막 */
    fun resumePoint(lines: List<WordChainLine>): ResumePoint? =
        chainWords(lines).lastOrNull()?.let { ResumePoint(it.text, it.mine) }

    /** 마지막 넥스티 말에서 넥스티가 말한 단어 (이어진 줄의 마지막이 넥스티 단어일 때) */
    fun lastNextyWord(lines: List<WordChainLine>): String? = resumePoint(lines)?.takeIf { !it.mine }?.word

    /** "'과'로 시작하는 단어" 같은 안내에서 다음 첫 글자 */
    fun requiredStart(lines: List<WordChainLine>): Char? =
        lines.asReversed().filter { !it.mine }.firstNotNullOfOrNull { l ->
            STARTS_WITH.findAll(l.text).lastOrNull()?.groupValues?.get(1)?.firstOrNull()
        }

    /** 말로 정해진 승패 (없으면 null) */
    fun decided(lines: List<WordChainLine>): WordChainResult? {
        for (line in lines) {
            if (line.mine && USER_GAVE_UP.containsMatchIn(line.text)) {
                return WordChainResult(WordChainWinner.NEXTY, WordChainReason.NO_WORD, inMorning = true)
            }
            if (!line.mine && NEXTY_GAVE_UP.containsMatchIn(line.text)) {
                return WordChainResult(WordChainWinner.USER, WordChainReason.NO_WORD, inMorning = true)
            }
        }
        return null
    }
}

/** 세션별 끝말잇기 기록 저장 (SharedPreferences, JSON) */
object WordChainStore {
    private const val PREFS = "wordchain"
    private const val MAX_SESSIONS = 30

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, sessionId: String): WordChainRecord {
        val raw = prefs(context).getString(sessionId, null) ?: return WordChainRecord()
        return runCatching { decode(JSONObject(raw)) }.getOrDefault(WordChainRecord())
    }

    private fun put(context: Context, sessionId: String, record: WordChainRecord) {
        val p = prefs(context)
        val e = p.edit().putString(sessionId, encode(record).toString())
        // 오래된 기록 정리 (세션 수만 제한)
        val keys = p.all.keys.filter { it != sessionId }
        if (keys.size >= MAX_SESSIONS) keys.take(keys.size - MAX_SESSIONS + 1).forEach { e.remove(it) }
        e.apply()
    }

    /** 모닝콜 중 받아쓰기 저장. 이미 승패가 났으면 결과는 그대로 둠 */
    fun saveMorning(context: Context, sessionId: String, lines: List<WordChainLine>, result: WordChainResult? = null) {
        val old = get(context, sessionId)
        put(context, sessionId, old.copy(morning = lines, result = old.result ?: result ?: WordChainMorning.decided(lines)))
    }

    fun saveMorningResult(context: Context, sessionId: String, result: WordChainResult) {
        val old = get(context, sessionId)
        if (old.result == null) put(context, sessionId, old.copy(result = result))
    }

    fun saveGame(context: Context, sessionId: String, game: List<WordChainLine>, result: WordChainResult?) {
        val old = get(context, sessionId)
        put(context, sessionId, old.copy(game = game, result = old.result ?: result))
    }

    private fun encode(r: WordChainRecord) = JSONObject().apply {
        put("morning", lines(r.morning))
        put("game", lines(r.game))
        r.result?.let {
            put("result", JSONObject().put("winner", it.winner.name).put("reason", it.reason.name).put("morning", it.inMorning))
        }
    }

    private fun lines(list: List<WordChainLine>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("mine", it.mine).put("text", it.text)) }
    }

    private fun decode(o: JSONObject): WordChainRecord {
        fun lines(key: String) = o.optJSONArray(key)?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { WordChainLine(it.optBoolean("mine"), it.optString("text")) }
        } ?: emptyList()
        val result = o.optJSONObject("result")?.let {
            runCatching {
                WordChainResult(WordChainWinner.valueOf(it.getString("winner")), WordChainReason.valueOf(it.getString("reason")),
                    it.optBoolean("morning"))
            }.getOrNull()
        }
        return WordChainRecord(lines("morning"), lines("game"), result)
    }
}
