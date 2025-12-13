package com.example.media_compress

import android.content.Context
import android.net.Uri
import android.util.Log
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.resize.AtMostResizer
import com.otaliastudios.transcoder.resize.FractionResizer
import com.otaliastudios.transcoder.resize.MultiResizer
import com.otaliastudios.transcoder.resize.Resizer
import com.otaliastudios.transcoder.source.TrimDataSource
import com.otaliastudios.transcoder.source.UriDataSource
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import com.otaliastudios.transcoder.strategy.TrackStrategy
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Future

/** MediaCompressPlugin */
class MediaCompressPlugin : FlutterPlugin, MethodCallHandler {
    private var _context: Context? = null
    private lateinit var _channel: MethodChannel
    private val tag = "MediaCompressPlugin"
    private var transcodeFuture:Future<Void>? = null
    var channelName = "media_compress"

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
                result.success(Utility(channelName).getMediaInfoJson(context, path!!).toString())
            }
            "deleteAllCache" -> {
                result.success(Utility(channelName).deleteAllCache(context, result))
            }
            "cancelCompression" -> {
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
            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        init(binding.applicationContext, binding.binaryMessenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        _channel.setMethodCallHandler(null)
    }

    private fun resize(atMost: Int): MultiResizer {
        // First scales down, then ensures size is at most `atMost`. Order matters!
        val resizer = MultiResizer()
        resizer.addResizer(FractionResizer(0.5F))
        resizer.addResizer(AtMostResizer(atMost))

        return resizer
    }

    private fun compress (context: Context, path: String, quality: Int, duration: Int?, frameRate: Int, result: MethodChannel.Result) {
        val tempDir: String = context.getExternalFilesDir("media_compress")!!.absolutePath
        val out = SimpleDateFormat("yyyy-MM-dd hh-mm-ss", Locale.US).format(Date())
        val destPath: String = tempDir + File.separator + "VID_" + out + path.hashCode() + ".mp4"
        val utility = Utility(channelName)

        var videoTrackStrategy: TrackStrategy = DefaultVideoStrategy.atMost(340).build()
        val audioTrackStrategy: TrackStrategy

        when (quality) {
            0 -> {
                val resizer = resize(480)
                videoTrackStrategy = DefaultVideoStrategy.Builder(resizer)
                    .frameRate(frameRate)
                    .build()
                // videoTrackStrategy = DefaultVideoStrategy.atMost(480, 640).build()
            }
            1 -> {
                val resizer = resize(720)
                videoTrackStrategy = DefaultVideoStrategy.Builder(resizer).frameRate(frameRate).build()
                // videoTrackStrategy = DefaultVideoStrategy.atMost(720, 1280).build()
            }
            2 -> {
                val resizer = resize(1080)
                videoTrackStrategy = DefaultVideoStrategy.Builder(resizer).frameRate(frameRate).build()
                // videoTrackStrategy = DefaultVideoStrategy.atMost(1080, 1920).build()
            }
        }

        val sampleRate = DefaultAudioStrategy.SAMPLE_RATE_AS_INPUT
        val channels = DefaultAudioStrategy.CHANNELS_AS_INPUT
        audioTrackStrategy = DefaultAudioStrategy.builder()
            .channels(channels)
            .sampleRate(sampleRate)
            .build()

        val dataSource = if (duration != null) {
            val source = UriDataSource(context, Uri.parse(path))
            val totalDuration = utility.durationUs(context, path)
            val trimEnd = (1000 * 1000 * duration).toLong()

            val trimEndUs = if (trimEnd < totalDuration) {
                totalDuration-trimEnd
            } else {
                0
            }

            TrimDataSource(source, 0, trimEndUs)
        } else {
            UriDataSource(context, Uri.parse(path))
        }

        transcodeFuture = Transcoder.into(destPath)
            .addDataSource(dataSource)
            .setAudioTrackStrategy(audioTrackStrategy)
            .setVideoTrackStrategy(videoTrackStrategy)
            .setListener(object : TranscoderListener {
                override fun onTranscodeProgress(progress: Double) {
                    _channel.invokeMethod("updateProgress", progress * 100.00)
                }
                override fun onTranscodeCompleted(successCode: Int) {
                    _channel.invokeMethod("updateProgress", 100.00)
                    val json = utility.getMediaInfoJson(context, destPath)
                    json.put("isCancel", false)
                    result.success(json.toString())
                }
                override fun onTranscodeCanceled() {
                    result.success(null)
                }
                override fun onTranscodeFailed(exception: Throwable) {
                    result.error("TRANSCODE_FAILED", exception.message, null)
                }
            }).transcode()
    }

    private fun init(context: Context, messenger: BinaryMessenger) {
        val channel = MethodChannel(messenger, channelName)
        channel.setMethodCallHandler(this)
        _context = context
        _channel = channel
    }
}
