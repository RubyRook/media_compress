package com.example.media_compress

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

data class VideoSize(val width: Int, val height: Int) {
    fun shortestSide (): Int {
        return if (width < height) width else height
    }

    fun isLandscape (): Boolean {
        return width > height
    }
}

data class VideoData(val size: VideoSize, val bitrate: Long, val duration: Long)

class Utility(private val channelName: String) {

    fun isLandscapeImage(orientation: Int) = orientation != 90 && orientation != 270

    fun deleteFile(file: File) {
        if (file.exists()) {
            file.delete()
        }
    }

    fun durationMillis(context: Context, path: String): Long {
        val file = File(path)
        val retriever = MediaMetadataRetriever()

        retriever.setDataSource(context, Uri.fromFile(file))
        val durationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)

        retriever.release()
        return durationString?.toLongOrNull() ?: 0L
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

        retriever.release()
        return VideoData(VideoSize(width, height), bitrate, duration)
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
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        val width = bitmap.width
        val height = bitmap.height
        val max = max(width, height)
        if (max > 512) {
            val scale = 512f / max
            val w = (scale * width).roundToInt()
            val h = (scale * height).roundToInt()
            bitmap = Bitmap.createScaledBitmap(bitmap, w, h, true)
        }

        return bitmap
    }

    fun deleteAllCache(context: Context, result: MethodChannel.Result) {
        val dir = context.getExternalFilesDir("media_compress")
        result.success(dir?.deleteRecursively())
    }
}