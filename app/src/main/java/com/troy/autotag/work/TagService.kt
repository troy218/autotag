package com.troy.autotag.work

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
import com.troy.autotag.MainActivity

/**
 * 태그 작업을 돌리는 포그라운드 서비스.
 *
 * 화면을 끄거나 다른 앱으로 가도 작업이 죽지 않게 하고(특히 삼성 One UI의
 * 백그라운드 앱 절전), 알림으로 진행 상황을 보여 주고 알림에서 중지할 수
 * 있게 한다. 실제 일은 [TagWork.execute]가 하며 이 서비스는 껍데기다.
 */
class TagService : Service() {

    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TagWork.requestStop()
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) return START_NOT_STICKY

        createChannel()
        startInForeground("태그 채우는 중…", 0, 0)
        worker = Thread {
            try {
                TagWork.execute(applicationContext) { done, total, name ->
                    updateNotification(done, total, name)
                }
            } finally {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }.apply { name = "AutoTag-worker"; start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        TagWork.requestStop()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 알림

    private fun buildNotification(text: String, done: Int, total: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TagService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("AutoTag")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "중지", stopIntent)
        if (total > 0) builder.setProgress(total, done, false)
        return builder.build()
    }

    private fun startInForeground(text: String, done: Int, total: Int) {
        val notification = buildNotification(text, done, total)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // dataSync 타입이 거부되는 기기/상황에서는 일반 포그라운드로 폴백
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }
    }

    private fun updateNotification(done: Int, total: Int, name: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.notify(NOTIFICATION_ID, buildNotification("($done/$total) $name", done, total))
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "태그 작업",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "태그 채우기 진행 상황" }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "tagging"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.troy.autotag.action.STOP"

        /** 포그라운드 서비스를 시작한다. 실패하면 일반 스레드로라도 돌린다. */
        fun start(context: Context): Boolean {
            return try {
                context.startForegroundService(Intent(context, TagService::class.java))
                true
            } catch (t: Throwable) {
                // 앱이 백그라운드라 서비스 시작이 막힌 드문 경우의 폴백
                Thread {
                    TagWork.execute(context.applicationContext) { _, _, _ -> }
                }.apply { name = "AutoTag-worker-fallback"; start() }
                true
            }
        }
    }
}
