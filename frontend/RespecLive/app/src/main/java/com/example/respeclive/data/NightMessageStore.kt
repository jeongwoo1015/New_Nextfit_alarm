package com.example.respeclive.data

import android.content.Context
import java.io.File

/**
 * 굿나잇콜에서 남긴 '내일의 나에게 한 마디' 저장소.
 * 서버 업로드는 RespecApi.uploadVoice()가 담당하고, 여기에는 로컬 사본과 서버의 밤 기록 id·apply_date만 보관.
 * 아침에는 recordId()로 서버 음성(voice_url)을 재생하고, 조회에 실패하면 latestMessage() 로컬 사본으로 대체.
 */
object NightMessageStore {
    private const val PREFS = "respec_night"
    private const val KEY_PATH = "tomorrow_message_path"
    private const val KEY_SAVED_AT = "tomorrow_message_saved_at"
    private const val KEY_TEXT = "tomorrow_message_text"
    private const val KEY_APPLY_DATE = "night_apply_date"
    private const val KEY_RECORD_ID = "night_record_id"

    fun newMessageFile(context: Context): File =
        File(context.filesDir, "messages/message_${System.currentTimeMillis()}.m4a")

    /** text: 녹음을 받아쓴 글자 (못 바꿨으면 null). 아침에 재생하면서 함께 보여 줌 */
    fun save(context: Context, file: File, text: String?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_PATH, null)?.let { if (it != file.absolutePath) File(it).delete() } // 이전 메시지 삭제
        prefs.edit()
            .putString(KEY_PATH, file.absolutePath)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .putString(KEY_TEXT, text)
            .apply()
    }

    fun latestMessage(context: Context): File? {
        val path = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PATH, null) ?: return null
        return File(path).takeIf { it.exists() }
    }

    fun latestText(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TEXT, null)

    /** 마지막으로 저장한 시각 (없으면 0) */
    fun savedAtMillis(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SAVED_AT, 0L)

    /** 서버 밤 기록 id와 '아침에 적용될 날짜'(YYYY-MM-DD) */
    fun saveRecord(context: Context, nightRecordId: String?, applyDate: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RECORD_ID, nightRecordId)
            .putString(KEY_APPLY_DATE, applyDate)
            .apply()
    }

    fun recordId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECORD_ID, null)

    fun applyDate(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_APPLY_DATE, null)
}
