package com.example.media_compress

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.example.media_compress.player.PlayerActivity
import com.google.common.collect.ImmutableList
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.source.TrimDataSource
import com.otaliastudios.transcoder.source.UriDataSource
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import java.util.*
import java.util.concurrent.Future
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

const val channelName = "media_compress"

/** MediaCompressPlugin */
@UnstableApi
class MediaCompressPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    val tag = "MediaCompressPlugin"
    val utility = Utility(channelName)

    private var _activity: Activity? = null
    private var _context: Context? = null
    private lateinit var _channel: MethodChannel
    private var transformer: Transformer? = null
    private var transcodeFuture: Future<Void>? = null
    private val scope = MainScope()

    override fun onMethodCall(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        val context = _context
        val channel = _channel

        if (context == null || channel == null) {
            Log.w(tag, "Calling VideoCompress plugin before initialization")
            return
        }

        when (call.method) {
            "play" -> {
                val activity = _activity
                val url = call.argument<String>("url")

                if (activity != null) {
                    val intent = Intent(activity, PlayerActivity::class.java)
                    intent.putExtra("url", url)
                    activity.startActivity(intent)
                }
                else {
                    val intent = Intent(context, PlayerActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    intent.putExtra("url", url)
                    context.startActivity(intent)
                }
                result.success(null)
            }
            "isHdrVideo" -> {
                val path = call.argument<String>("path")
                val response = utility.isVideoIsHdr(path!!)
                result.success(response)
            }
            "isHdrEditingSupported" -> {
                val response = isHdrEditingSupported()
                result.success(response)
            }
            "getByteThumbnail" -> {
                val path = call.argument<String>("path")
                val quality = call.argument<Int>("quality")!!
                val position = call.argument<Int>("position")!! // to long
                ThumbnailUtility(channelName).getByteThumbnail(path!!, quality, position.toLong(), result)
            }
            "getMediaInfo" -> {
                val path = call.argument<String>("path")
                scope.launch {
                    try {
                        val jsonString = utility.getMediaInfoISO(context, path!!)
                        result.success(jsonString)
                    } catch (e: Exception) {
                        result.error("Get info failed", e.message, null)
                    }
                }
            }
            "deleteAllCache" -> {
                utility.deleteAllCache(context, result)
            }
            "cancelCompression" -> {
                transformer?.cancel()
                transcodeFuture?.cancel(true)
                result.success(false)
            }
            "compress" -> {
                val path = call.argument<String>("path")!!
                val quality = call.argument<Int>("quality")!!
                val duration = call.argument<Int>("duration")
                val frameRate = call.argument<Int>("frameRate") ?: 30
                Log.d("TAG-FrameRate", frameRate.toString())

                compress(context, path, quality, duration, frameRate, result)
            }
            "fastTrims" -> {
                val path = call.argument<String>("path")!!
                val duration = call.argument<Int>("duration")
                fastTrims(context, path, duration, result)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        _activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        _activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        _activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        _activity = null
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val channel = MethodChannel(binding.binaryMessenger, channelName)
        channel.setMethodCallHandler(this)

        _channel = channel
        _context = binding.applicationContext
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        transformer?.cancel()
        transformer = null
        _channel.setMethodCallHandler(null)
    }

    private fun compress(
        context: Context,
        path: String,
        quality: Int,
        duration: Int?,
        frameRate: Int,
        result: MethodChannel.Result
    ) {
        val isVideoHdr = utility.isVideoIsHdr(path)
        val isHdrEditingSupported = isHdrEditingSupported()

        if (isVideoHdr && !isHdrEditingSupported) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                media3Compress(context, path, quality, duration, frameRate, true, result)
            }
            else {
                transcoderCompress(context, path, quality, duration, frameRate, result)
            }
        }
        else {
            media3Compress(context, path, quality, duration, frameRate, false, result)
        }
    }

    private fun transcoderCompress(context: Context, path: String, quality: Int, duration: Int?, frameRate: Int, result: MethodChannel.Result) {
        Log.d("TAG-Compress", "`transcoder` Take place")

        try {
            val outPath = utility.genOutPath(context, path)
            val originInfo = utility.getVideoData(context, path)

            var bitrate = originInfo.bitrate
            var videoTrackStrategy = DefaultVideoStrategy.atMost(480)

            when (quality) {
                0 -> {
                    videoTrackStrategy = DefaultVideoStrategy
                        .atMost(480)
                        .bitRate(1_000_000)
                }
                1 -> {
                    bitrate = if (bitrate < 1_800_000) bitrate else 1_800_000 // 1.8 Mbps
                    // bitrate = (bitrate * .75).toLong()
                    videoTrackStrategy = DefaultVideoStrategy.atMost(720)
                        .bitRate(bitrate)
                }
                2 -> {
                    bitrate = if (bitrate < 2_000_000) bitrate else 2_000_000 // 2.0 Mbps
                    bitrate = (bitrate * .75).toLong()
                    videoTrackStrategy = DefaultVideoStrategy
                        .atMost(1080, 1920)
                        .bitRate(bitrate)
                }
            }

            if (frameRate < originInfo.frameRate) {
                videoTrackStrategy.frameRate(frameRate)
            }

            val dataSource = if (duration != null) {
                val originDuration = originInfo.duration * 1000
                val trimEndUs = (1000 * 1000 * duration).toLong()
                val source = UriDataSource(context, path.toUri())

                if (trimEndUs >= originDuration) {
                    source
                }
                else {
                    TrimDataSource(source, 0, originDuration-trimEndUs)
                }
            } else {
                UriDataSource(context, path.toUri())
            }

            val audioTrackStrategy = DefaultAudioStrategy.builder()
                .channels(DefaultAudioStrategy.CHANNELS_AS_INPUT)
                .sampleRate(DefaultAudioStrategy.SAMPLE_RATE_AS_INPUT)
                .build()

            transcodeFuture = Transcoder.into(outPath)
                .addDataSource(dataSource)
                .setAudioTrackStrategy(audioTrackStrategy)
                .setVideoTrackStrategy(videoTrackStrategy.build())
                .setListener(object : TranscoderListener {
                    override fun onTranscodeProgress(progress: Double) {
                        _channel.invokeMethod("updateProgress", progress * 100.00)
                    }
                    override fun onTranscodeCompleted(successCode: Int) {
                        _channel.invokeMethod("updateProgress", 100.00)
                        val json = utility.getMediaInfoJson(context, outPath)
                        result.success(json.toString())
                    }

                    override fun onTranscodeCanceled() {
                        result.success(null)
                    }
                    override fun onTranscodeFailed(exception: Throwable) {
                        result.success(null)
                    }
                }).transcode()
        }
        catch (e: Exception) {
            result.error("Transformer_Failed", e.message, e.cause)
        }
    }

    private fun media3Compress(context: Context, path: String, quality: Int, duration: Int?, frameRate: Int, hdrToSdr: Boolean, result: MethodChannel.Result) {
        Log.d("TAG-Compress", "`media3` Take place")
        try {
            val outPath = utility.genOutPath(context, path)
            val originInfo = utility.getVideoData(context, path)

            var bitrate = originInfo.bitrate
            var videoSize = 480

            when (quality) {
                0 -> { // Low
                    bitrate = 1_000_000 // 1 Mbps
                    videoSize = 480
                }
                1 -> { // High
                    bitrate = 2_000_000
                    videoSize = 720
                }
                2 -> { // Very High
                    bitrate = 2_400_000
                    videoSize = 1080
                }
            }

            // Create a MediaItem from the source path
            val mediaItemBuilder = MediaItem.Builder().setUri(path)

            // 1. Apply trimming if a duration is provided
            if (duration != null && duration > 0) {
                // Trim the video from the start to the specified duration in microseconds.
                val endPositionMs = duration * 1000L
                val clippingConfiguration = ClippingConfiguration.Builder()
                    .setStartPositionMs(0)
                    .setEndPositionMs(endPositionMs)
                    .build()
                mediaItemBuilder.setClippingConfiguration(clippingConfiguration)
            }

            val mediaItem = mediaItemBuilder.build()
            val videoEffects = mutableListOf<Effect>(
                Presentation.createForShortSide(videoSize),
            )

            if (frameRate < originInfo.frameRate) {
                videoEffects.add(FrameDropEffect.createDefaultFrameDropEffect(frameRate.toFloat()))
            }

            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(listOf(), videoEffects))
                .build()

            val videoEncoder = VideoEncoderSettings
                .Builder()
                .setBitrate((bitrate * 0.8).toInt())

            val videoEncoderSettings = videoEncoder.build()

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(videoEncoderSettings)
                .setEnableFallback(true)
                .setEnableCodecDbLite(true)

            // Handler for progress updates
            val mainHandler = Handler(Looper.getMainLooper())
            lateinit var progressChecker: Runnable

            transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(encoderFactory.build())
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        mainHandler.removeCallbacks(progressChecker)
                        _channel.invokeMethod("updateProgress", 100.00)
                        val json = utility.getMediaInfoJson(context, outPath)
                        result.success(json.toString())
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        mainHandler.removeCallbacks(progressChecker)
                        if (exportException.errorCodeName == "ERROR_CANCELLED") {
                            val json = utility.getMediaInfoJson(context, outPath)
                            result.success(json.toString()) // Or result.success(null) if you prefer
                        } else {
                            result.error("Transformer_Failed", exportException.message, exportException.cause)
                        }
                    }

                })
                .build()

            // Create the Runnable to check for progress
            val progressHolder = ProgressHolder()
            progressChecker = object : Runnable {
                override fun run() {
                    if (transformer != null) {
                        when (transformer?.getProgress(progressHolder)) {
                            Transformer.PROGRESS_STATE_AVAILABLE -> {
                                _channel.invokeMethod("updateProgress", progressHolder.progress.toDouble())
                            }
                        }
                        mainHandler.postDelayed(this, 1)
                    }
                    else {
                        mainHandler.removeCallbacks(this)
                    }

                }
            }
            // Start transformation and progress polling
            mainHandler.post(progressChecker)

            if (hdrToSdr) {
                Log.d("TAG-Compress", "composition")

                val videoSequence = EditedMediaItemSequence
                    .withVideoFrom(listOf(editedMediaItem))
                val composition = Composition.Builder(ImmutableList.of(videoSequence))
                    .setHdrMode(HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                    .build()

                transformer?.start(composition, outPath)
            }
            else {
                Log.d("TAG-Compress", "editedMediaItem")
                transformer?.start(editedMediaItem, outPath)
            }
        }
        catch (e: Exception) {
            result.error("Transformer_Failed", e.message, e.cause)
        }
    }

    private fun fastTrims(context: Context, path: String, duration: Int?, result: MethodChannel.Result) {
        val outPath = utility.genOutPath(context, path)

        val clippingConfigBuilder = ClippingConfiguration.Builder()
            .setStartPositionMs(0)

        // Ensure duration is set if provided
        if (duration != null && duration > 0) {
            clippingConfigBuilder.setEndPositionMs(duration * 1000L)
        }

        val mediaItem = MediaItem.Builder()
            .setUri(path)
            .setClippingConfiguration(clippingConfigBuilder.build())
            .build()

        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .build()

        // Handler for progress updates
        val mainHandler = Handler(Looper.getMainLooper())
        lateinit var progressChecker: Runnable

        transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    mainHandler.removeCallbacks(progressChecker)
                    _channel.invokeMethod("updateProgress", 100.00)
                    val json = utility.getMediaInfoJson(context, outPath)
                    result.success(json.toString())
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    mainHandler.removeCallbacks(progressChecker)
                    if (exportException.errorCodeName == "ERROR_CANCELLED") {
                        val json = utility.getMediaInfoJson(context, outPath)
                        result.success(json.toString()) // Or result.success(null) if you prefer
                    } else {
                        result.error("Transformer_Failed", exportException.message, exportException.cause)
                    }
                }

            })
            .build()

        // Create the Runnable to check for progress
        val progressHolder = ProgressHolder()
        progressChecker = object : Runnable {
            override fun run() {
                if (transformer != null) {
                    when (transformer?.getProgress(progressHolder)) {
                        Transformer.PROGRESS_STATE_AVAILABLE -> {
                            _channel.invokeMethod("updateProgress", progressHolder.progress.toDouble())
                        }
                    }
                    mainHandler.postDelayed(this, 1)
                }
                else {
                    mainHandler.removeCallbacks(this)
                }

            }
        }
        // Start transformation and progress polling
        mainHandler.post(progressChecker)
        transformer?.start(editedMediaItem, outPath)
    }

    private fun isHdrEditingSupported(): Boolean {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val codecInfos = codecList.codecInfos

        for (info in codecInfos) {
            if (info.isEncoder) {
                for (type in info.supportedTypes) {
                    if (type.contains("video/")) {
                        val capabilities = info.getCapabilitiesForType(type)
                        if (capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing)) {
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

}