package net.osmtracker.route

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Encodes a short, silent MP4 replay from a captured map and projected route points.
 * The caller owns and releases the background bitmap.
 */
object RouteVideoExporter {
    fun export(
        background: Bitmap,
        points: List<PointF>,
        output: File,
        routeColor: Int,
        onComplete: (String?) -> Unit
    ) {
        Thread {
            val error = try {
                encode(background, points, output, routeColor)
                null
            } catch (e: Exception) {
                e.localizedMessage ?: "خطای ناشناخته در ساخت ویدئو"
            }
            onComplete(error)
        }.start()
    }

    private fun encode(background: Bitmap, points: List<PointF>, output: File, routeColor: Int) {
        require(points.size >= 2) { "مسیر برای ساخت ویدئو کافی نیست" }
        val scale = min(1f, 1280f / max(background.width, background.height).toFloat())
        val width = (background.width * scale).toInt().coerceAtLeast(2) and -2
        val height = (background.height * scale).toInt().coerceAtLeast(2) and -2
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfoColorFormat.SURFACE)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 3).coerceIn(1_500_000, 5_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, 24)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var inputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var encoderStarted = false
        var muxerStarted = false
        var trackIndex = -1
        var sawEndOfStream = false
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            encoder.start()
            encoderStarted = true

            fun drain(endOfStream: Boolean) {
                if (endOfStream) encoder.signalEndOfInputStream()
                var idleCount = 0
                while (true) {
                    val index = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000L else 0L)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream || idleCount++ > 100) return
                        }
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) throw IllegalStateException("قالب خروجی ویدئو دوباره تغییر کرد")
                            trackIndex = muxer!!.addTrack(encoder.outputFormat)
                            muxer!!.start()
                            muxerStarted = true
                        }
                        index >= 0 -> {
                            val encoded = encoder.getOutputBuffer(index)
                            if (encoded != null && bufferInfo.size > 0) {
                                if (!muxerStarted) throw IllegalStateException("قالب خروجی ویدئو آماده نشد")
                                encoded.position(bufferInfo.offset)
                                encoded.limit(bufferInfo.offset + bufferInfo.size)
                                muxer!!.writeSampleData(trackIndex, encoded, bufferInfo)
                            }
                            val isEnd = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(index, false)
                            if (isEnd) {
                                sawEndOfStream = true
                                return
                            }
                        }
                    }
                }
            }

            val frameCount = max(48, min(180, points.size * 2))
            val targetRect = Rect(0, 0, width, height)
            val backgroundPaint = Paint(Paint.FILTER_BITMAP_FLAG)
            val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 11f * scale
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = routeColor
                style = Paint.Style.STROKE
                strokeWidth = 7f * scale
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 190, 120) }
            val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0, 122, 255)
                style = Paint.Style.FILL
            }
            val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 4f * scale
            }
            val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(155, 8, 27, 40) }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 27f * scale
            }
            val framePath = Path()

            for (frame in 0 until frameCount) {
                val lastIndex = (frame.toFloat() / (frameCount - 1) * (points.size - 1))
                    .toInt().coerceIn(1, points.lastIndex)
                framePath.reset()
                for (i in 0..lastIndex) {
                    val p = points[i]
                    val x = p.x * width / background.width.toFloat()
                    val y = p.y * height / background.height.toFloat()
                    if (i == 0) framePath.moveTo(x, y) else framePath.lineTo(x, y)
                }

                val canvas: Canvas = inputSurface!!.lockCanvas(null)
                try {
                    canvas.drawBitmap(background, null, targetRect, backgroundPaint)
                    canvas.drawRect(0f, 0f, width.toFloat(), 52f * scale, bannerPaint)
                    canvas.drawText("مسیر | بازپخش مسیر", 20f * scale, 36f * scale, titlePaint)
                    canvas.drawPath(framePath, outlinePaint)
                    canvas.drawPath(framePath, routePaint)

                    val first = points.first()
                    val startX = first.x * width / background.width.toFloat()
                    val startY = first.y * height / background.height.toFloat()
                    canvas.drawCircle(startX, startY, 10f * scale, startPaint)

                    val current = points[lastIndex]
                    val currentX = current.x * width / background.width.toFloat()
                    val currentY = current.y * height / background.height.toFloat()
                    canvas.drawCircle(currentX, currentY, 13f * scale, currentPaint)
                    canvas.drawCircle(currentX, currentY, 13f * scale, whitePaint)
                } finally {
                    inputSurface!!.unlockCanvasAndPost(canvas)
                }
                drain(false)
                Thread.sleep(42L)
            }

            drain(true)
            if (!sawEndOfStream || !muxerStarted) {
                throw IllegalStateException("کدگذاری ویدئو کامل نشد")
            }
        } finally {
            if (encoderStarted) {
                try { encoder.stop() } catch (_: Exception) {}
            }
            try { encoder.release() } catch (_: Exception) {}
            try { inputSurface?.release() } catch (_: Exception) {}
            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Exception) {}
            }
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private object MediaCodecInfoColorFormat {
        const val SURFACE = 0x7F000789
    }
}
