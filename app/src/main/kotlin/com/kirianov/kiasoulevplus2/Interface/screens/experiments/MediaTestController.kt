// ====================================================================================
// ТЕСТ ВИВОДУ НА МАГНІТОЛУ (MediaTestController)
//
// Магнітола показує «що зараз грає» — назву, виконавця, обкладинку — читаючи їх у
// телефона по Bluetooth (AVRCP поверх A2DP). Це не Android Auto: так уміє будь-яка
// BT-магнітола. Якщо наш застосунок стане активним джерелом «зараз грає», магнітола
// покаже те, що ми туди покладемо.
//
// ЦЕ САМЕ ТЕСТ, і в нього одна мета — ВИМІРЯТИ, як швидко магнітола наздоганяє
// оновлення й чи бере картинку. AVRCP задумано під зміну треку, не під живий
// прилад: різні магнітоли притримують метадані по-різному. Тому щосекунди міняємо
// назву на лічильник — на екрані магнітоли одразу видно, оновлює вона щосекунди
// чи раз на десять.
//
// ЧОМУ ТИХА ДОРІЖКА. Магнітола показує метадані лише активного ДЖЕРЕЛА ЗВУКУ. Без
// реального потоку A2DP наша сесія для неї не існує. Тому тримаємо беззвучний потік
// — рівно щоб бути тим джерелом. Це те саме, що робить будь-який плеєр; у шину
// нічого не пишеться, це чисто телефонна музична сесія.
//
// НЕ БЛОК, а помічник екрана: живе Android-API (медіа-сесія, аудіо), тож і лежить у
// шарі інтерфейсу, поряд зі своїм екраном.
// ====================================================================================

package com.kirianov.kiasoulevplus2.Interface.screens.experiments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.kirianov.kiasoulevplus2.Data.GeneralData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Тримає медіа-сесію тесту й оновлює її раз на секунду.
 *
 * Один екземпляр на застосунок; [start]/[stop] можна кликати повторно — повторний
 * start нічого не ламає, а stop прибирає все навіть якщо вже зупинено.
 */
class MediaTestController {

    private var session: MediaSessionCompat? = null
    private var audio: AudioTrack? = null
    private var scope: CoroutineScope? = null
    private var focusHeld = false
    private var audioManager: AudioManager? = null

    val running: Boolean get() = session != null

    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext

        val manager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = manager
        focusHeld = requestFocus(manager)

        audio = silentTrack().also { it.play() }

        val newSession = MediaSessionCompat(app, "KiaMediaTest").apply {
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1.0f)
                    .build(),
            )
            isActive = true
        }
        session = newSession

        val loop = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = loop
        loop.launch { tick(newSession) }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        runCatching { audio?.pause(); audio?.flush(); audio?.release() }
        audio = null
        runCatching { session?.isActive = false; session?.release() }
        session = null
        if (focusHeld) audioManager?.let { abandonFocus(it) }
        focusHeld = false
        audioManager = null
    }

    /**
     * Щосекунди оновлює метадані: назва — лічильник (за ним видно частоту
     * оновлення), виконавець — живі показники, обкладинка — намальована плитка з
     * тим самим лічильником.
     */
    private suspend fun tick(session: MediaSessionCompat) {
        var counter = 0
        while (scope?.isActive == true) {
            val state = GeneralData.state.value
            val socText = state.bms.displaySoc.takeIf { it >= 0.0 }?.let { "SOC ${it.toInt()}%" }
                ?: "SOC —"
            val rangeText = state.ml.prediction?.rangeKm?.let { "· ${it.toInt()} км" } ?: ""

            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "KIA тест $counter")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "$socText $rangeText")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "KiaSoulPlus")
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, tile(counter))
                .build()
            session.setMetadata(metadata)

            counter++
            delay(TICK_MS)
        }
    }

    /** Плитка-обкладинка: великий лічильник на кольоровому тлі. */
    private fun tile(counter: Int): Bitmap {
        val size = ART_SIZE
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(0x10, 0x2A, 0x43))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.5f
        }
        canvas.drawText(counter.toString(), size / 2f, size * 0.66f, paint)
        return bitmap
    }

    private fun silentTrack(): AudioTrack {
        val rate = 44100
        val buffer = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)

        val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(buffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                rate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                buffer,
                AudioTrack.MODE_STREAM,
            )
        }

        // Годувати тишею з окремого потоку, поки трек живий: без даних A2DP-потік
        // магнітола закриє, і сесія перестане бути «джерелом, що грає».
        Thread {
            val zeros = ShortArray(buffer / 2)
            while (true) {
                val written = runCatching { track.write(zeros, 0, zeros.size) }.getOrDefault(-1)
                if (written < 0) break
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) break
            }
        }.apply { isDaemon = true }.start()

        return track
    }

    @Suppress("DEPRECATION")
    private fun requestFocus(manager: AudioManager): Boolean {
        val result = manager.requestAudioFocus(
            null,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN,
        )
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus(manager: AudioManager) {
        manager.abandonAudioFocus(null)
    }

    private companion object {
        /** Раз на секунду: рівно щоб на око зміряти, з яким лагом магнітола наздоганяє. */
        const val TICK_MS = 1000L
        const val ART_SIZE = 300
    }
}
