package io.github.figs0203.afinador.songbook

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import io.github.figs0203.afinador.R
import io.github.figs0203.afinador.ui.applyEdgeToEdge
import java.util.UUID

/**
 * Entry point for new songs.
 *
 * The songbook receives pages through the Android share sheet or a link
 * (`ACTION_VIEW`), downloads the page and keeps only the chord text. When a page
 * cannot be read, the same screen accepts the copied text, so importing always
 * has a path forward — and works offline in that case.
 *
 * Ultimate Guitar playlists also arrive as links: their list of songs is read
 * first, confirmed by the user, and then each tab is imported in order.
 */
class ImportActivity : Activity(), PlaylistImportService.Companion.ProgressListener {

    private lateinit var urlInput: EditText
    private lateinit var titleInput: EditText
    private lateinit var pasteInput: EditText
    private lateinit var progress: TextView
    private lateinit var importBar: ProgressBar
    private lateinit var linkButton: TextView
    private lateinit var saveButton: TextView
    private val store by lazy { SongStore(this) }
    private var currentPlaylistUrl: String? = null

    override fun onProgress(
        done: Boolean,
        total: Int,
        current: Int,
        title: String,
        added: Int,
        skipped: Int,
        failed: Int,
        error: String?
    ) {
        if (isFinishing) return
        if (!done) {
            if (total > 0) {
                val percent = (current * 100 / total).coerceIn(0, 100)
                importBar.isIndeterminate = false
                importBar.visibility = View.VISIBLE
                importBar.progress = percent
                showMessage(
                    when {
                        current == 0 -> getString(R.string.playlist_found, total)
                        title.isBlank() ->
                            getString(R.string.playlist_progress_stage, current, total, percent)
                        else ->
                            getString(R.string.playlist_progress_percent, current, total, percent, title)
                    },
                    false
                )
            } else {
                // Todavía no se sabe cuántas canciones hay: la barra se mueve
                // para que se vea que la app está trabajando y no colgada.
                importBar.isIndeterminate = true
                importBar.visibility = View.VISIBLE
                showMessage(title.ifBlank { getString(R.string.playlist_loading) }, false)
            }
        } else {
            hideProgressBar()
            setBusy(false)
            if (error != null) {
                showMessage(error, true)
            } else {
                finishPlaylist(added, skipped, failed)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_song)
        applyEdgeToEdge(findViewById(R.id.root))

        urlInput = findViewById(R.id.urlInput)
        titleInput = findViewById(R.id.titleInput)
        pasteInput = findViewById(R.id.pasteInput)
        progress = findViewById(R.id.importProgress)
        importBar = findViewById(R.id.importBar)
        linkButton = findViewById(R.id.linkButton)
        saveButton = findViewById(R.id.saveButton)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        linkButton.setOnClickListener { importLink() }
        saveButton.setOnClickListener { savePasted() }

        PlaylistImportService.registerListener(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        handleIntent(intent)
    }

    override fun onDestroy() {
        PlaylistImportService.unregisterListener(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (shared.isEmpty()) return
                val url = SongImporter.extractUrl(shared)
                if (url != null) {
                    urlInput.setText(url)
                    importLink()
                } else {
                    pasteInput.setText(shared)
                    titleInput.setText(intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty())
                    showMessage(getString(R.string.import_text_received), false)
                }
            }

            Intent.ACTION_VIEW -> {
                val url = intent.dataString
                if (!url.isNullOrBlank()) {
                    urlInput.setText(url)
                    importLink()
                }
            }
        }
    }

    private fun importLink() {
        val raw = urlInput.text.toString().trim()
        val url = SongImporter.normalizeUrl(raw)
        if (url == null) {
            showMessage(
                getString(if (raw.isBlank()) R.string.import_need_link else R.string.import_invalid_link),
                true
            )
            return
        }

        if (PlaylistImporter.isPlaylistUrl(url)) {
            readPlaylist(url)
            return
        }

        setBusy(true)
        Thread {
            val result = runCatching { SongImporter.importFromUrl(url) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                setBusy(false)
                result
                    .onSuccess { imported -> saveSong(imported, SongImporter.labelFor(url), url) }
                    .onFailure { error ->
                        showMessage(error.message ?: getString(R.string.import_failed), true)
                    }
            }
        }.start()
    }

    // --------------------------------------------------------- playlists

    private fun readPlaylist(url: String) {
        currentPlaylistUrl = url
        setBusy(true)
        showMessage(getString(R.string.playlist_loading), false)
        Thread {
            val result = runCatching { PlaylistImporter.importFromUrl(url) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                setBusy(false)
                result
                    .onSuccess { playlist ->
                        if (playlist.entries.isEmpty()) {
                            showMessage(getString(R.string.playlist_empty), true)
                        } else {
                            confirmPlaylist(playlist)
                        }
                    }
                    .onFailure { error ->
                        showMessage(error.message ?: getString(R.string.playlist_failed), true)
                    }
            }
        }.start()
    }

    private fun confirmPlaylist(playlist: PlaylistImporter.Playlist) {
        val name = playlist.name.ifBlank { getString(R.string.playlist_default_name) }
        val message = buildString {
            append(getString(R.string.playlist_confirm_message, name, playlist.entries.size))
            if (playlist.truncated) {
                append("\n\n")
                append(getString(R.string.playlist_truncated, playlist.entries.size))
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.playlist_confirm_title)
            .setMessage(message)
            .setNegativeButton(R.string.songbook_cancel, null)
            .setPositiveButton(R.string.playlist_confirm_action) { _, _ ->
                importPlaylist(playlist)
            }
            .show()
    }

    /** Downloads every tab of the list, one by one, keeping only its text. */
    private fun importPlaylist(playlist: PlaylistImporter.Playlist) {
        setBusy(true)
        val url = currentPlaylistUrl
        if (url.isNullOrBlank()) {
            setBusy(false)
            showMessage(getString(R.string.playlist_failed), true)
            return
        }
        showMessage(getString(R.string.playlist_loading), false)
        PlaylistImportService.start(this, url)
    }

    private fun finishPlaylist(added: Int, skipped: Int, failed: Int) {
        setBusy(false)
        val summary = buildString {
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

        if (added == 0) {
            showMessage(summary, failed > 0)
            return
        }

        Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
        startActivity(Intent(this, SongLibraryActivity::class.java))
        finish()
    }

    private fun savePasted() {
        val text = pasteInput.text.toString().trimEnd()
        if (text.count { !it.isWhitespace() } < 8) {
            showMessage(getString(R.string.import_need_text), true)
            return
        }
        saveSong(SongImporter.Imported("", "", text), getString(R.string.song_source_text), null)
    }

    private fun saveSong(imported: SongImporter.Imported, source: String, url: String?) {
        val title = titleInput.text.toString().trim()
            .ifBlank { imported.title }
            .ifBlank { getString(R.string.song_unknown_title) }

        val song = Song(
            id = UUID.randomUUID().toString(),
            title = title.take(120),
            artist = imported.artist.take(120),
            source = source,
            url = url,
            text = imported.text,
            savedAt = System.currentTimeMillis()
        )
        store.add(song)

        startActivity(
            Intent(this, SongViewActivity::class.java)
                .putExtra(SongViewActivity.EXTRA_SONG_ID, song.id)
        )
        finish()
    }

    private fun hideProgressBar() {
        importBar.isIndeterminate = false
        importBar.progress = 0
        importBar.visibility = View.GONE
    }

    private fun setBusy(busy: Boolean) {
        linkButton.isEnabled = !busy
        saveButton.isEnabled = !busy
        linkButton.alpha = if (busy) 0.5f else 1f
        saveButton.alpha = if (busy) 0.5f else 1f
        showMessage(if (busy) getString(R.string.import_downloading) else "", false)
    }

    private fun showMessage(text: String, warning: Boolean) {
        progress.text = text
        progress.setTextColor(getColor(if (warning) R.color.warning else R.color.text_secondary))
    }
}
