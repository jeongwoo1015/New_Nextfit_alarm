package com.example.respeclive.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 상황별 수면 음악 볼륨: 대화·녹음 듣기 중에는 마이크에 섞이지 않게 아주 작게, 그 밖에는 보통 */
enum class SleepLevel(val volume: Float) { QUIET(0.06f), NORMAL(0.35f) }

/**
 * 수면 음악 조작 창구. 실제 재생은 SleepMusicService가 맡아 화면을 끄거나 나가도 이어짐.
 * 모든 명령은 서비스에 Intent로 보내 순서대로 처리됨 (서비스가 아직 안 떴어도 순서가 지켜짐).
 */
object SleepMusic {
    private val _playing = MutableStateFlow(false)

    /** 서비스가 음악을 틀고 있는지 (타이머·알림창 정지로 끝나면 false) */
    val playing: StateFlow<Boolean> = _playing

    internal fun setPlaying(v: Boolean) {
        _playing.value = v
    }

    /** 틀기 시작. url이 없으면 앱 내 파일. 둘 다 없으면 false (서비스를 띄우지 않음) */
    fun play(context: Context, track: com.example.respeclive.data.RespecApi.SleepTrack?, level: SleepLevel): Boolean {
        val hasFallback = context.resources.getIdentifier("sleep_music", "raw", context.packageName) != 0
        if (track == null && !hasFallback) return false
        val intent = intent(context, ACTION_PLAY)
            .putExtra(EXTRA_URL, track?.url)
            .putExtra(EXTRA_ID, track?.musicId)
            .putExtra(EXTRA_LEVEL, level.name)
        return try {
            ContextCompat.startForegroundService(context, intent)
            _playing.value = true
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 다른 곡이면 서서히 바꿈 (같은 곡이거나 재생 중이 아니면 그대로) */
    fun switchTo(context: Context, track: com.example.respeclive.data.RespecApi.SleepTrack) =
        send(context, intent(context, ACTION_SWITCH).putExtra(EXTRA_URL, track.url).putExtra(EXTRA_ID, track.musicId))

    fun setLevel(context: Context, level: SleepLevel) = send(context, intent(context, ACTION_LEVEL).putExtra(EXTRA_LEVEL, level.name))

    fun pause(context: Context) = send(context, intent(context, ACTION_PAUSE))

    fun resume(context: Context) = send(context, intent(context, ACTION_RESUME))

    /** ms 뒤에 서서히 줄여 끔. 이미 타이머가 있으면 그대로 둠 (force면 다시 맞춤) */
    fun startTimer(context: Context, ms: Long, force: Boolean = false) =
        send(context, intent(context, ACTION_TIMER).putExtra(EXTRA_MS, ms).putExtra(EXTRA_FORCE, force))

    fun stop(context: Context) = send(context, intent(context, ACTION_STOP))

    private fun intent(context: Context, action: String) =
        Intent(context.applicationContext, SleepMusicService::class.java).setAction(action)

    // 재생 중이 아니면 보낼 필요가 없음 (앱이 뒤에 있을 때 서비스를 새로 띄우면 시스템이 막음)
    private fun send(context: Context, intent: Intent) {
        if (!_playing.value) return
        try {
            context.applicationContext.startService(intent)
        } catch (_: Exception) {
        }
    }

    internal const val ACTION_PLAY = "sleep.PLAY"
    internal const val ACTION_SWITCH = "sleep.SWITCH"
    internal const val ACTION_LEVEL = "sleep.LEVEL"
    internal const val ACTION_PAUSE = "sleep.PAUSE"
    internal const val ACTION_RESUME = "sleep.RESUME"
    internal const val ACTION_TIMER = "sleep.TIMER"
    internal const val ACTION_STOP = "sleep.STOP"
    internal const val EXTRA_URL = "url"
    internal const val EXTRA_ID = "id"
    internal const val EXTRA_LEVEL = "level"
    internal const val EXTRA_MS = "ms"
    internal const val EXTRA_FORCE = "force"
}

/** 수면 음악 백그라운드 재생 (포그라운드 서비스, 알림창에 재생 중 표시와 정지 버튼) */
class SleepMusicService : Service() {
    private val scope = MainScope()
    private lateinit var player: SleepMusicPlayer
    private var foreground = false
    private var timerJob: Job? = null
    private var timerEndAt = 0L

    override fun onCreate() {
        super.onCreate()
        player = SleepMusicPlayer(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "수면 음악", NotificationManager.IMPORTANCE_LOW).apply {
                description = "굿나잇콜 뒤 수면 음악 재생"
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            SleepMusic.ACTION_PLAY -> {
                goForeground()
                val level = level(intent)
                if (!player.play(intent.getStringExtra(SleepMusic.EXTRA_URL), intent.getStringExtra(SleepMusic.EXTRA_ID), level.volume)) {
                    finish()
                }
            }
            SleepMusic.ACTION_SWITCH -> {
                val url = intent.getStringExtra(SleepMusic.EXTRA_URL)
                val id = intent.getStringExtra(SleepMusic.EXTRA_ID)
                if (foreground && url != null && id != null && id != player.musicId) {
                    scope.launch { player.crossfadeTo(url, id, CROSSFADE_MS) }   // 실패하면 이전 곡 유지
                }
            }
            SleepMusic.ACTION_LEVEL -> player.setBaseVolume(level(intent).volume)
            SleepMusic.ACTION_PAUSE -> player.pause()
            SleepMusic.ACTION_RESUME -> player.resume()
            SleepMusic.ACTION_TIMER -> {
                val force = intent.getBooleanExtra(SleepMusic.EXTRA_FORCE, false)
                if (timerJob == null || force) {
                    val ms = intent.getLongExtra(SleepMusic.EXTRA_MS, DEFAULT_TIMER_MS)
                    timerJob?.cancel()
                    timerEndAt = System.currentTimeMillis() + ms
                    timerJob = scope.launch {
                        delay(ms)
                        player.fadeOutAndRelease(FADE_OUT_MS)
                        finish()
                    }
                    updateNotification()
                }
            }
            SleepMusic.ACTION_STOP -> finish()
        }
        // 재생을 시작하지 않은 채로 다른 명령만 받았으면 바로 끝냄
        if (!foreground) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun level(intent: Intent) =
        runCatching { SleepLevel.valueOf(intent.getStringExtra(SleepMusic.EXTRA_LEVEL) ?: "") }.getOrDefault(SleepLevel.NORMAL)

    private fun goForeground() {
        if (foreground) return
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )
        foreground = true
        SleepMusic.setPlaying(true)
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SleepMusicService::class.java).setAction(SleepMusic.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val text = if (timerEndAt > 0) {
            "재생 중 · ${SimpleDateFormat("a h:mm", Locale.KOREAN).format(Date(timerEndAt))}에 저절로 꺼져요"
        } else {
            "재생 중"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("수면 음악")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "정지", stop)
            .build()
    }

    private fun updateNotification() {
        if (!foreground) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        } catch (_: SecurityException) {   // 알림 권한이 없으면 표시만 안 됨
        }
    }

    private fun finish() {
        timerJob?.cancel()
        timerJob = null
        timerEndAt = 0L
        player.release()
        SleepMusic.setPlaying(false)
        if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        player.release()
        SleepMusic.setPlaying(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "sleep_music"
        private const val NOTIFICATION_ID = 3001
        private const val CROSSFADE_MS = 4_000L
        private const val FADE_OUT_MS = 10_000L
        private const val DEFAULT_TIMER_MS = 10 * 60_000L
    }
}
