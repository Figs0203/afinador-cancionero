package io.github.figs0203.afinador.songbook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.figs0203.afinador.R
import io.github.figs0203.afinador.songbook.SongLibraryActivity

class PlaylistImportService : Service() {

    companion object {
        const val ACTION_START = "io.github.figs0203.afinador.action.START_PLAYLIST_IMPORT"
        const val EXTRA_URL = "playlist_url"

        // Broadcast constants kept for backwards compatibility; progress is now
        // delivered through [ProgressListener] because Android 14+ no longer
        // delivers implicit broadcasts to non-exported receivers.
        const val BROADCAST_PROGRESS = "io.github.figs0203.afinador.PLAYLIST_IMPORT_PROGRESS"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_CURRENT = "current"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DONE = "done"
        const val EXTRA_ADDED = "added"
        const val EXTRA_SKIPPED = "skipped"
        const val EXTRA_FAILED = "failed"
        const val EXTRA_ERROR = "error"

        private const val CHANNEL_ID = "playlist_import"
        private const val NOTIF_ID = 4242

        /**
         * Long lists are saved every so often instead of only at the end, so a
         * phone that kills the service keeps what was already downloaded.
         */
        private const val BATCH_SIZE = 100
        private const val FLUSH_INTERVAL_MS = 20_000L

        interface ProgressListener {
            fun onProgress(
                done: Boolean,
                total: Int,
                current: Int,
                title: String,
                added: Int,
                skipped: Int,
                failed: Int,
                error: String?
            )
        }

        data class ProgressState(
            val done: Boolean,
            val total: Int,
            val current: Int,
            val title: String,
            val added: Int,
            val skipped: Int,
            val failed: Int,
            val error: String?
        )

        @Volatile
        var progressListener: ProgressListener? = null

        @Volatile
        var lastProgress: ProgressState? = null

        private val mainHandler = Handler(Looper.getMainLooper())

        fun registerListener(listener: ProgressListener) {
            progressListener = listener
            lastProgress?.let { state ->
                mainHandler.post {
                    listener.onProgress(
                        state.done, state.total, state.current, state.title,
                        state.added, state.skipped, state.failed, state.error
                    )
                }
            }
        }

        fun unregisterListener(listener: ProgressListener) {
            if (progressListener === listener) progressListener = null
        }

        fun start(context: Context, url: String) {
            val intent = Intent(context, PlaylistImportService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.playlist_import_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager?.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("Preparando importación…", 0, 0, true))
        reportProgress(
            done = false, total = 0, current = 0,
            title = getString(R.string.playlist_loading),
            added = 0, skipped = 0, failed = 0, error = null
        )
        Thread { runImport(url, startId) }.start()
        return START_NOT_STICKY
    }

    private fun runImport(url: String, startId: Int) {
        var total = 0
        var added = 0
        var skipped = 0
        var failed = 0
        try {
            updateNotification(getString(R.string.playlist_loading), 0, 0, true)
            reportProgress(
                done = false, total = 0, current = 0,
                title = getString(R.string.playlist_loading),
                added = 0, skipped = 0, failed = 0, error = null
            )

            val playlist = PlaylistImporter.importFromUrl(url)
            total = playlist.entries.size
            if (total == 0) {
                val err = getString(R.string.playlist_empty)
                reportProgress(
                    done = true, total = 0, current = 0, title = "",
                    added = 0, skipped = 0, failed = 0, error = err
                )
                notifyFinal(err)
                return
            }

            // The size of the list is known from here on, so the screen can show
            // a real bar instead of an endless "reading the list" message.
            reportProgress(
                done = false, total = total, current = 0, title = "",
                added = 0, skipped = 0, failed = 0, error = null
            )
            updateNotification(getString(R.string.playlist_found, total), 0, total, false)

            val store = SongStore(applicationContext)
            val knownLinks = store.all().mapNotNull { it.url }.toMutableSet()
            val pending = mutableListOf<Song>()
            var lastFlush = System.currentTimeMillis()

            fun flush() {
                if (pending.isEmpty()) return
                val batch = pending.toList()
                pending.clear()
                store.addAll(batch)
                lastFlush = System.currentTimeMillis()
            }

            playlist.entries.forEachIndexed { index, entry ->
                val current = index + 1
                val msg = getString(R.string.playlist_progress, current, total, entry.title)
                updateNotification(msg, current, total, false)

                if (!knownLinks.add(entry.url)) {
                    skipped++
                } else {
                    // Dentro de una lista no se puede esperar minutos por una
                    // sola canción: con dos intentos cortos, el resto de la
                    // lista sigue avanzando aunque ese enlace falle.
                    val imported = runCatching {
                        SongImporter.importFromUrl(
                            entry.url,
                            attempts = 2,
                            connectTimeout = 12_000,
                            readTimeout = 30_000
                        )
                    }.getOrNull()
                    if (imported == null) {
                        failed++
                    } else {
                        pending.add(
                            Song(
                                id = java.util.UUID.randomUUID().toString(),
                                title = imported.title.ifBlank { entry.title }
                                    .ifBlank { getString(R.string.song_unknown_title) }.take(120),
                                artist = imported.artist.ifBlank { entry.artist }.take(120),
                                source = SongImporter.labelFor(entry.url),
                                url = entry.url,
                                text = imported.text,
                                savedAt = System.currentTimeMillis()
                            )
                        )
                        added++
                    }
                }

                reportProgress(
                    done = false, total = total, current = current,
                    title = entry.title,
                    added = added, skipped = skipped, failed = failed,
                    error = null
                )

                if (pending.size >= BATCH_SIZE ||
                    System.currentTimeMillis() - lastFlush >= FLUSH_INTERVAL_MS
                ) {
                    flush()
                }
            }
            flush()

            val summary = buildSummary(added, skipped, failed)
            reportProgress(
                done = true, total = total, current = total, title = "",
                added = added, skipped = skipped, failed = failed, error = null
            )
            notifyFinal(summary)
        } catch (error: OutOfMemoryError) {
            // A list of thousands of tabs can exhaust the heap while its page is
            // parsed. Reporting it keeps the screen from waiting forever.
            val message = getString(R.string.playlist_too_large, added)
            reportProgress(
                done = true, total = total, current = 0, title = "",
                added = added, skipped = skipped, failed = failed, error = message
            )
            notifyFinal(message)
        } catch (error: Throwable) {
            val message = if (error is SongImporter.TimeoutException) {
                getString(
                    R.string.playlist_timeout,
                    (PlaylistImporter.PAGE_DEADLINE_MS / 1000L).toInt()
                )
            } else {
                error.message ?: getString(R.string.playlist_failed)
            }
            reportProgress(
                done = true, total = total, current = 0, title = "",
                added = added, skipped = skipped, failed = failed, error = message
            )
            notifyFinal(message)
        } finally {
            stopSelf(startId)
        }
    }

    /**
     * If the import never reached a result (the system stopped the service, the
     * app was killed), the screen must not keep showing "reading the list".
     */
    override fun onDestroy() {
        val state = lastProgress
        if (state != null && !state.done) {
            reportProgress(
                done = true, total = state.total, current = state.current, title = "",
                added = state.added, skipped = state.skipped, failed = state.failed,
                error = getString(R.string.playlist_interrupted, state.added)
            )
        }
        super.onDestroy()
    }

    private fun reportProgress(
        done: Boolean,
        total: Int,
        current: Int,
        title: String,
        added: Int,
        skipped: Int,
        failed: Int,
        error: String?
    ) {
        val state = ProgressState(done, total, current, title, added, skipped, failed, error)
        lastProgress = state
        val listener = progressListener
        if (listener != null) {
            mainHandler.post {
                listener.onProgress(done, total, current, title, added, skipped, failed, error)
            }
        }
    }

    private fun notificationBuilder(text: String, icon: Int): Notification.Builder {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(icon)
            .setContentTitle(getString(R.string.playlist_import_notification_title))
            .setContentText(text)
    }

    private fun buildNotification(text: String, current: Int, total: Int, indeterminate: Boolean = false): Notification {
        val builder = notificationBuilder(text, android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (total > 0) {
            builder.setProgress(total, current.coerceIn(0, total), indeterminate && current == 0)
        } else if (indeterminate) {
            builder.setProgress(0, 0, true)
        }
        val openIntent = Intent(this, SongLibraryActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(pending)
        return builder.build()
    }

    private fun updateNotification(text: String, current: Int, total: Int, indeterminate: Boolean = false) {
        notificationManager?.notify(NOTIF_ID, buildNotification(text, current, total, indeterminate))
    }

    private fun notifyFinal(text: String) {
        val builder = notificationBuilder(text, android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .setAutoCancel(true)
        val openIntent = Intent(this, SongLibraryActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(pending)
        notificationManager?.notify(NOTIF_ID, builder.build())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    private fun buildSummary(added: Int, skipped: Int, failed: Int): String = buildString {
        append(getString(R.string.playlist_summary_added, added))
        if (skipped > 0) append(" · ").append(
            getString(
                if (skipped == 1) R.string.playlist_summary_skipped_one else R.string.playlist_summary_skipped,
                skipped
            )
        )
        if (failed > 0) append(" · ").append(
            getString(
                if (failed == 1) R.string.playlist_summary_failed_one else R.string.playlist_summary_failed,
                failed
            )
        )
    }
}
