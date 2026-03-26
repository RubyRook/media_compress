package com.example.media_compress

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class Dimensions(val width: Int, val height: Int) {
    fun shortestSide (): Int {
        return if (width < height) width else height
    }

    fun isLandscape (): Boolean {
        return width > height
    }
}

data class VideoData(
    val size: Dimensions,
    val bitrate: Long,
    val duration: Long,
    val frameRate: Float,
)

@UnstableApi
class Utility(private val channelName: String) {

    fun isLandscapeImage(orientation: Int) = orientation != 90 && orientation != 270

    fun deleteFile(file: File) {
        if (file.exists()) {
            file.delete()
        }
    }

    suspend fun getMediaInfoISO(context: Context, path: String): String {
        val json = getMediaInfoJsonISO(context, path)
        val frameRate = json["frameRate"].toString().toLong()
        val duration = json["duration"].toString().toFloatOrNull()

        if (frameRate <= 0 || duration == null) {
            val result = fastTrimISO(context, path, null)
            if (result != null) return result
        }

        return json.toString()
    }

    suspend fun getMediaInfoJsonISO(context: Context, path: String): JSONObject = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        var widthStr: String? = null
        var heightStr: String? = null
        var orientation: String? = null
        var bitRatesStr: String? = null
        var title: String? = null
        var author: String? = null
        var duration: String? = null

        try {
            // This is the heavy part that freezes the UI
            retriever.setDataSource(context, Uri.fromFile(File(path)))
            widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            orientation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            bitRatesStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        } catch (e: Exception) {
            Log.e("VideoUtils", "Error extracting media info", e)
        } finally {
            retriever.release()
        }

        val file = File(path)
        val bitRates = bitRatesStr?.toLongOrNull() ?: 0L
        var width = widthStr?.toLongOrNull() ?: 0L
        var height = heightStr?.toLongOrNull() ?: 0L
        val filesize = file.length()
        val ori = orientation?.toIntOrNull()

        // Also ensure this sub-call doesn't block (it also uses MediaExtractor)
        val frameRate = getVideoFrameRate(context, Uri.fromFile(File(path))) ?: 0L

        if (ori != null && isLandscapeImage(ori)) {
            val tmp = width
            width = height
            height = tmp
        }

        val json = JSONObject()
        json.put("path", path)
        json.put("title", title ?: file.name)
        json.put("author", author ?: "")
        json.put("width", width)
        json.put("height", height)
        json.put("duration", duration?.toFloatOrNull()?.toLong() ?: 0L)
        json.put("filesize", filesize)
        json.put("bitRates", bitRates)
        json.put("frameRate", frameRate.toLong())
        if (ori != null) {
            json.put("orientation", ori)
        }

        return@withContext json // Return the JSONObject to the caller
    }

    suspend fun fastTrimISO(context: Context, path: String, duration: Int?): String? = suspendCancellableCoroutine { continuation ->
        val outPath = genOutPath(context, path)

        val clippingConfigBuilder = ClippingConfiguration.Builder().setStartPositionMs(0)
        if (duration != null && duration > 0) {
            clippingConfigBuilder.setEndPositionMs(duration * 1000L)
        }

        val mediaItem = MediaItem.Builder()
            .setUri(path)
            .setClippingConfiguration(clippingConfigBuilder.build())
            .build()

        val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()

        val transformerListener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                CoroutineScope(Dispatchers.Main).launch {
                    val json = getMediaInfoJsonISO(context, outPath)
                    if (continuation.isActive) {
                        continuation.resume(json.toString())
                    }
                }
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                if (continuation.isActive) {
                    if (exportException.errorCodeName == "ERROR_CANCELLED") {
                        val json = getMediaInfoJson(context, outPath)
                        continuation.resume(json.toString())
                    } else {
                        continuation.resumeWithException(exportException)
                    }
                }
            }
        }

        val currentTransformer = Transformer.Builder(context)
            .addListener(transformerListener)
            .build()

        // Assign to the class-level variable if you need to cancel it externally
        // transformer = currentTransformer

        // Handle coroutine cancellation (e.g., if the user leaves the screen)
        continuation.invokeOnCancellation {
            currentTransformer.cancel()
        }

        currentTransformer.start(editedMediaItem, outPath)
    }

    fun genOutPath(context: Context, path: String): String {
        val tempDir: String = context.getExternalFilesDir(channelName)!!.absolutePath
        val out = SimpleDateFormat("yyyy-MM-dd hh-mm-ss", Locale.US).format(Date())
        return "$tempDir/VID_$out${path.hashCode()}.mp4"
    }

    fun getVideoData(context: Context, path: String): VideoData {
        val file = File(path)
        val retriever = MediaMetadataRetriever()

        retriever.setDataSource(context, Uri.fromFile(file))

        val width = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0

        val height = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0

        val bitrate = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 1_000_000L

        val duration = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

        val frameRate = getVideoFrameRate(context, Uri.fromFile(File(path))) ?: 0F

        retriever.release()
        return VideoData(Dimensions(width, height), bitrate, duration, frameRate)
    }

    fun getVideoFrameRate(context: Context, videoUri: Uri): Float? {
        var frameRate: Float? = null
        var mediaExtractor: MediaExtractor? = null
        try {
            mediaExtractor = MediaExtractor()
            mediaExtractor.setDataSource(context, videoUri, null)

            for (i in 0 until mediaExtractor.trackCount) {
                val format = mediaExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    // Found the video track
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                        Log.d("VideoUtils", "Frame rate from format: $frameRate fps")
                    } else {
                        // If KEY_FRAME_RATE is not available, you might have to estimate it
                        // by checking the sample time of the first few frames,
                        // though this is less accurate and assumes a stable frame rate.
                        Log.d("VideoUtils", "KEY_FRAME_RATE not found, trying estimation (less reliable).")
                        // The snippet from search results suggests a basic estimation:
                        // mediaExtractor.advance()
                        // val fpsEstimate = 1000000f / mediaExtractor.sampleTime.toFloat()
                        // frameRate = fpsEstimate
                    }
                    break // Found the video track, no need to check other tracks
                }
            }
        } catch (e: Exception) {
            Log.e("VideoUtils", "Error extracting frame rate", e)
        } finally {
            mediaExtractor?.release()
        }
        return frameRate
    }

    fun getMediaInfoJson(context: Context, path: String): JSONObject {
        // Use native MediaMetadataRetriever for basic info that is usually reliable
        val retriever = MediaMetadataRetriever()
        var widthStr: String? = null
        var heightStr: String? = null
        var orientation: String? = null
        var bitRatesStr: String? = null
        var title: String? = null
        var author: String? = null
        var duration: String? = null

        try {
            retriever.setDataSource(context, Uri.fromFile(File(path)))
            widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            orientation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            bitRatesStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        }
        catch (e: Exception) {
            Log.e("VideoUtils", "Error extracting media info", e)
        }
        finally {
            retriever.release()
        }

        val file = File(path)
        val bitRates = bitRatesStr?.toLongOrNull() ?: 0L
        var width = widthStr?.toLongOrNull() ?: 0L
        var height = heightStr?.toLongOrNull() ?: 0L
        val filesize = file.length()
        val ori = orientation?.toIntOrNull()
        val frameRate = getVideoFrameRate(context, Uri.fromFile(File(path))) ?: 0L

        if (ori != null && isLandscapeImage(ori)) {
            val tmp = width
            width = height
            height = tmp
        }

        val json = JSONObject()

        json.put("path", path)
        json.put("title", title ?: file.name)
        json.put("author", author ?: "")
        json.put("width", width)
        json.put("height", height)
        // Ensure parsing is safe in case of non-numeric values
        json.put("duration", duration?.toFloatOrNull()?.toLong() ?: 0L)
        json.put("filesize", filesize)
        json.put("bitRates", bitRates)
        json.put("frameRate", frameRate.toLong())
        if (ori != null) {
            json.put("orientation", ori)
        }

        return json
    }

    fun getBitmap(path: String, position: Long, result: MethodChannel.Result): Bitmap {
        var bitmap: Bitmap? = null
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(path)
            bitmap = retriever.getFrameAtTime(position, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) { // Catch all exceptions for safety
            result.error(channelName, "Assume this is a corrupt video file", null)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // It's not necessary to send an error for cleanup failures
            }
        }

        // CRITICAL FIX: Check for null before using the bitmap
        if (bitmap == null) {
            result.success(null) // Let Flutter know there is no bitmap
            // Return a dummy bitmap to prevent a crash if the caller isn't null-safe
            // return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            return createBitmap(1, 1)
        }

        val width = bitmap.width
        val height = bitmap.height
        val max = max(width, height)
        if (max > 512) {
            val scale = 512f / max
            val w = (scale * width).roundToInt()
            val h = (scale * height).roundToInt()
            // bitmap = Bitmap.createScaledBitmap(bitmap, w, h, true)
            bitmap = bitmap.scale(w, h)
        }

        return bitmap
    }

    fun deleteAllCache(context: Context, result: MethodChannel.Result) {
        val dir = context.getExternalFilesDir("media_compress")
        result.success(dir?.deleteRecursively())
    }

    fun isCbrSupported(mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC): Boolean {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue
            if (codecInfo.supportedTypes.contains(mimeType)) {
                val capabilities = codecInfo.getCapabilitiesForType(mimeType)
                val cbrSupported = capabilities.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                return cbrSupported == true
            }
        }
        return false
    }

    fun isVideoIsHdr(path: String): Boolean {
        var isHdr = false
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(path)

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                        val colorTransfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                        if (colorTransfer == MediaFormat.COLOR_TRANSFER_HLG || colorTransfer == 6) {
                            isHdr = true
                            break
                        }
                    }

                    val colorStandard = try {
                        format.getInteger(MediaFormat.KEY_COLOR_STANDARD)
                    }
                    catch (_: Exception) {
                        0
                    }
                    if (colorStandard == MediaFormat.COLOR_STANDARD_BT2020) {
                        isHdr = true
                        break
                    }
                }
            }
        } catch (e: IOException) {
            Log.e("VideoUtils", "Error reading video metadata", e)
        } finally {
            extractor.release()
        }
        return isHdr
    }
}