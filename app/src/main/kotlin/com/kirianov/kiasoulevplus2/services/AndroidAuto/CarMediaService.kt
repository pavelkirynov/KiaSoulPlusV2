// ====================================================================================
// МЕДІА-СЕРВІС ДЛЯ ANDROID AUTO (CarMediaService)
//
// Показує показники авто як «медіатеку». Причина — у шапці CarMediaModel: медіа-браузер
// хост підхоплює в будь-якого встановленого застосунка, а template-застосунок — лише
// з Play Market або з увімкненими «невідомими джерелами».
//
// Сесія тут порожня і нічого не грає: вона потрібна лише тому, що хост відмовляється
// працювати з медіа-браузером без токена сесії.
// ====================================================================================

package com.kirianov.kiasoulevplus2.services.AndroidAuto

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.kirianov.kiasoulevplus2.Data.GeneralData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class CarMediaService : MediaBrowserServiceCompat() {

    private lateinit var session: MediaSessionCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()

        session = MediaSessionCompat(this, SESSION_TAG).apply {
            // Порожній стан: без нього хост вважає сесію непридатною й ховає застосунок.
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 0f)
                    .setActions(0L)
                    .build(),
            )
            isActive = true
        }
        sessionToken = session.sessionToken

        watchState()
    }

    override fun onDestroy() {
        scope.cancel()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot = BrowserRoot(CarMediaModel.ROOT_ID, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        val items = CarMediaModel.childrenOf(parentId, GeneralData.state.value)
        result.sendResult(items.map(::toMediaItem).toMutableList())
    }

    /**
     * Оновлює список, коли показники змінилися.
     *
     * Опитування CAN оновлює стан кожні 800 мс, а хост Android Auto обмежує частоту
     * оновлень і на надто балакучий сервіс просто перестає реагувати. Тому спершу
     * порівнюються вже готові рядки, а потім пауза в кінці збірки проріджує потік:
     * conflate дає доїхати лише останньому стану, який стався за час паузи.
     */
    private fun watchState() {
        GeneralData.state
            .map { state -> SECTION_IDS.associateWith { CarMediaModel.childrenOf(it, state) } }
            .distinctUntilChanged()
            .conflate()
            .onEach { sections ->
                notifyChildrenChanged(CarMediaModel.ROOT_ID)
                sections.keys.forEach(::notifyChildrenChanged)
                delay(REFRESH_INTERVAL_MS)
            }
            .launchIn(scope)
    }

    private fun toMediaItem(item: CarMediaItem): MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(item.id)
            .setTitle(item.title)
            .setSubtitle(item.subtitle)
            .build()

        val flags = if (item.browsable) {
            MediaItem.FLAG_BROWSABLE
        } else {
            MediaItem.FLAG_PLAYABLE
        }
        return MediaItem(description, flags)
    }

    private companion object {
        const val SESSION_TAG = "KiaSoulEvPlusV2"

        /** Частіше за це хост усе одно не перемальовує список. */
        const val REFRESH_INTERVAL_MS = 2_000L

        val SECTION_IDS = listOf(
            CarMediaModel.BATTERY_ID,
            CarMediaModel.PERFORMANCE_ID,
            CarMediaModel.ENERGY_ID,
            CarMediaModel.TRIP_ID,
        )
    }
}
