package com.example.media_compress

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

const val channelName = "media_compress"
const val tag = "MediaCompressPlugin"
val utility = Utility(channelName)

/** MediaCompressPlugin */
@UnstableApi
class MediaCompressPlugin : FlutterPlugin, MethodCallHandler {
    private var _context: Context? = null
    private lateinit var _channel: MethodChannel
    private var transformer: Transformer? = null

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
            "getByteThumbnail" -> {
                val path = call.argument<String>("path")
                val quality = call.argument<Int>("quality")!!
                val position = call.argument<Int>("position")!! // to long
                ThumbnailUtility(channelName).getByteThumbnail(path!!, quality, position.toLong(), result)
            }
            "getMediaInfo" -> {
                val path = call.argument<String>("path")
                val res = utility.getMediaInfoJson(context, path!!).toString()
                result.success(res)
            }
            "deleteAllCache" -> {
                utility.deleteAllCache(context, result)
            }
            "cancelCompression" -> {
                transformer?.cancel()
//                VideoCompressor.cancel()
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
            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        init(binding.applicationContext, binding.binaryMessenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        transformer?.cancel()
        transformer = null
        _channel.setMethodCallHandler(null)
    }

    private fun compress (context: Context, path: String, quality: Int, duration: Int?, frameRate: Int, result: MethodChannel.Result) {
         media3Compress(context, path, quality, duration, frameRate, result)
    }

    private fun media3Compress (context: Context, path: String, quality: Int, duration: Int?, frameRate: Int, result: MethodChannel.Result) {
        Log.d("TAG-Compress", "`media3` Take place")
        try {
            val tempDir: String = context.getExternalFilesDir("media_compress")!!.absolutePath
            val out = SimpleDateFormat("yyyy-MM-dd hh-mm-ss", Locale.US).format(Date())
            val destPath: String = tempDir + File.separator + "VID_" + out + path.hashCode() + ".mp4"
            val originInfo = utility.getVideoData(context, path)

            var bitrate = originInfo.bitrate
            var videoSize = 480

            when (quality) {
                0 -> { // Low
                    bitrate = 1_000_000 // 1 Mbps
                    videoSize = 480
                }
                1 -> { // Medium
                    bitrate = if (bitrate < 1_500_000) bitrate else 1_500_000 // 1.5 Mbps
                    videoSize = 540
                }
                2 -> { // High
                    bitrate = if (bitrate < 2_500_000) bitrate else 2_500_000 // 2.5 Mbps
                    videoSize = 720
                }
                3 -> { // Very High
                    bitrate = if (bitrate < 3_500_000) bitrate else 3_500_000 // 3.5 Mbps
                    videoSize = 1080
                }
            }

            Log.d("TAG-bitrate", bitrate.toString())

            // Create a MediaItem from the source path
            val mediaItemBuilder = MediaItem.Builder().setUri(path)

            // 1. Apply trimming if a duration is provided
            if (duration != null && duration > 0) {
                // Trim the video from the start to the specified duration in microseconds.
                val endPositionMs = duration * 1000L
                val clippingConfiguration = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(0)
                    .setEndPositionMs(endPositionMs)
                    .build()
                mediaItemBuilder.setClippingConfiguration(clippingConfiguration)
            }

            val mediaItem = mediaItemBuilder.build()

            val videoEffects = listOf(
                FrameDropEffect.createDefaultFrameDropEffect(frameRate.toFloat()),
                Presentation.createForShortSide(videoSize),
                ScaleAndRotateTransformation.Builder().setRotationDegrees(0f).build(), // No rotation
            )

            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(listOf(), videoEffects))
                .build()

            val videoEncoderSettings = VideoEncoderSettings.Builder()
                .setBitrate(bitrate.toInt())
                .build()

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(videoEncoderSettings)

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
                        val json = utility.getMediaInfoJson(context, destPath)
                        json.put("isCancel", false)
                        result.success(json.toString())
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        mainHandler.removeCallbacks(progressChecker)
                        if (exportException.errorCodeName == "ERROR_CANCELLED") {
                            val json = utility.getMediaInfoJson(context, destPath)
                            json.put("isCancel", true)
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
            transformer?.start(editedMediaItem, destPath)
        }
        catch (e: Exception) {
            result.error("Transformer_Failed", e.message, e.cause)
        }
    }

    private fun init(context: Context, messenger: BinaryMessenger) {
        val channel = MethodChannel(messenger, channelName)
        channel.setMethodCallHandler(this)
        _context = context
        _channel = channel
    }
}