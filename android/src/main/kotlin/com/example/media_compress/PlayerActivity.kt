package com.example.media_compress.player

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.media_compress.R
import kotlinx.coroutines.*

class PlayerActivity : AppCompatActivity(), PlayerView.ControllerVisibilityListener  {
    private lateinit var player: ExoPlayer
    private lateinit var windowInsetsController: WindowInsetsControllerCompat
    private var playPauseJob: Job? = null

    override fun onVisibilityChanged(visibility: Int) {
        if (visibility == View.VISIBLE) {
            showSystemBars()
        } else {
            hideSystemBars()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        setupBackButton()
        setupWindow()

        val loading = this.findViewById<ProgressBar>(R.id.loading)
        loading.isVisible = true

        val playerView = this.findViewById<PlayerView>(R.id.video_view)
        val playPause = playerView.findViewById<ImageButton>(androidx.media3.ui.R.id.exo_play_pause);

        playerView.setControllerVisibilityListener(this)
        playerView.setBackgroundColor(Color.BLACK)
        playerView.setControllerAnimationEnabled(false)
        playerView.controllerShowTimeoutMs = 2000
        playerView.useController = false

        playerView
            .findViewById<ImageButton>(androidx.media3.ui.R.id.exo_prev)
            ?.visibility = View.GONE
        playerView
            .findViewById<ImageButton>(androidx.media3.ui.R.id.exo_next)
            ?.visibility = View.GONE

        ViewCompat.setOnApplyWindowInsetsListener(playerView) {view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, 0, 0, 0) // Keep video behind

            val orientation = getResources().configuration.orientation

            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                playerView
                    .findViewById<View>(androidx.media3.ui.R.id.exo_controller)
                    .setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            } else {
                playerView
                    .findViewById<View>(androidx.media3.ui.R.id.exo_controller)
                    .setPadding(0, 0, 0, systemBars.bottom)
            }

            insets
        }

        val url = intent.getStringExtra("url") ?: return

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 30_000, 2_000, 5_000)
            .build()

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(false)

        player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .build()
            .also { exoPlayer -> playerView.player = exoPlayer}

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) loading.isVisible = false
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && player.playWhenReady) {
                    playerView.useController = true
                    playerView.controllerShowTimeoutMs = 2000
                    loading.isVisible = false
                    player.play()
                }
            }
        })

        playPause.setOnClickListener {
            if (player.isPlaying) {
                player.pause()

                playPauseJob?.cancel()
                playPauseJob = null
            } else {
                if (player.playbackState == Player.STATE_ENDED) {
                    player.seekTo(0)
                }
                player.play()

                playPauseJob?.cancel()
                playPauseJob = null
                playPauseJob = lifecycleScope.launch {
                    delay(600)
                    playerView.hideController()
                }
            }
        }

        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            //Toast.makeText(this, "landscape", Toast.LENGTH_SHORT).show()
            val playerView = this.findViewById<PlayerView>(R.id.video_view)
            if (!playerView.isControllerFullyVisible() && Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
                windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
            }
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            //Toast.makeText(this, "portrait", Toast.LENGTH_SHORT).show()
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
                windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
        playPauseJob?.cancel()
    }

    private fun hideSystemBars() {
        val types = getTypes()
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(types)
        this.findViewById<ImageButton>(R.id.back_button).isVisible = false
    }

    private fun showSystemBars() {
        val types = getTypes()
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.show(types)
        this.findViewById<ImageButton>(R.id.back_button).isVisible = true
    }

    private fun setupBackButton() {
        this.findViewById<ImageButton>(R.id.back_button).setOnClickListener {
            finish()
        }
    }

    private fun setupWindow() {
        // Enable edge-to-edge display
        WindowCompat.enableEdgeToEdge(window)
        // Initialize WindowInsetsController
        WindowCompat.setDecorFitsSystemWindows(window, false)
        windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    private fun getTypes(): Int {
        val orientation = getResources().configuration.orientation
        if (orientation == Configuration.ORIENTATION_PORTRAIT && Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            return WindowInsetsCompat.Type.navigationBars()
        }

        return WindowInsetsCompat.Type.systemBars()
    }

}
