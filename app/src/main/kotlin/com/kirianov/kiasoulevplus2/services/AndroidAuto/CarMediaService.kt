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
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
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
            setCallback(callback)
            setPlaybackState(stateOf(PlaybackStateCompat.STATE_STOPPED))
            isActive = true
        }
        sessionToken = session.sessionToken

        watchState()
    }

    /**
     * Керування «відтворенням» картинки.
     *
     * Нічого не грає й не звучить: аудіофокус ми не просимо взагалі. «Відтворення»
     * тут — лише спосіб попросити хост показати екран плеера, бо обкладинка на
     * ньому єдина поверхня, де наш графік читається в машині.
     *
     * Кнопки перемотування зайняті ділом: вони гортають картинки. Хост намалює їх
     * однаково, тож хай краще щось роблять, ніж стоять бутафорією.
     */
    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            val index = CarChartModel.PICTURES.indexOfFirst { it.first == mediaId }
            show(if (index >= 0) index else 0)
        }

        override fun onPlay() = show(pictureIndex)

        override fun onSkipToNext() = show(pictureIndex + 1)

        override fun onSkipToPrevious() = show(pictureIndex - 1)

        override fun onStop() {
            showing = false
            session.setPlaybackState(stateOf(PlaybackStateCompat.STATE_STOPPED))
        }

        override fun onPause() = onStop()
    }

    /** Яку картинку показуємо і чи показуємо взагалі. */
    private var pictureIndex = 0
    private var showing = false

    private fun show(index: Int) {
        val size = CarChartModel.PICTURES.size
        pictureIndex = ((index % size) + size) % size
        showing = true
        session.setPlaybackState(stateOf(PlaybackStateCompat.STATE_PLAYING))
        publishArt()
    }

    /**
     * Кладе поточну картинку в метадані сесії.
     *
     * Обкладинка — саме Bitmap, а не посилання на файл. Посилання довелося б
     * віддавати через провайдера з окремим дозволом для чужого процесу, і мовчазна
     * відмова там виглядала б як порожній екран без жодної підказки. Bitmap
     * доїжджає завжди — за це й платимо розміром, тому картинка в RGB_565.
     */
    private fun publishArt() {
        if (!showing) return
        val (id, title) = CarChartModel.PICTURES[pictureIndex]
        val chart = CarChartModel.chartFor(id, GeneralData.state.value)

        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, chart.subtitle)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, CarChartArtist.bitmapOf(chart))
                .build(),
        )
    }

    /**
     * Стан «відтворення».
     *
     * Дозволені дії названі поіменно, і перемотування серед них навмисно: саме воно
     * гортає картинки. Позиція завжди нуль — тривалості в картинки немає.
     */
    private fun stateOf(state: Int) = PlaybackStateCompat.Builder()
        .setState(state, 0L, 0f)
        .setActions(
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
        )
        .build()

    override fun onDestroy() {
        scope.cancel()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    /**
     * Корінь медіатеки — і підказка хосту, як її малювати.
     *
     * Саме тут прилади стають видимими. Без підказки хост малює розділи списком, а
     * в рядку списку іконці відведено кілька міліметрів — прилад у ній видно, але
     * читати його на ходу неможливо. Сітка натомість дає кожному розділу велику
     * квадратну плитку, і картинка в ній — уже прилад, а не значок.
     *
     * Підказки дві, і вони різні навмисно: сіткою малюємо лише розділи (browsable),
     * а числа всередині розділу лишаються списком (playable) — там важливий підпис,
     * а не картинка, і в сітці підписи не помістилися б.
     */
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot = BrowserRoot(
        CarMediaModel.ROOT_ID,
        Bundle().apply {
            putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            )
            putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            )
        },
    )

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
     *
     * СПОВІЩАЄМО ЛИШЕ ТЕ, ЩО СПРАВДІ ЗМІНИЛОСЯ, і це не оптимізація, а лікування.
     * Раніше на будь-яку зміну переспоряджався ВЕСЬ дерев'яний список — і корінь, і
     * усі розділи. Для хоста це означає «перечитай вузол», а вузол, який водій саме
     * зараз читає, перечитується разом із поверненням до нього; на машинному екрані
     * це виглядало як заголовок від одного розділу поверх плиток іншого. А оскільки
     * заряд ворушиться щосекунди, вузол «Батарея» переспоряджався постійно, і зайти
     * в нього надовго було неможливо.
     *
     * Тепер кожен вузол порівнюється сам із собою: змінилися саме його рядки —
     * сповіщаємо саме його. Розділ, у який водій зайшов почитати, здебільшого стоїть.
     */
    private fun watchState() {
        var previous = emptyMap<String, List<CarMediaItem>>()

        GeneralData.state
            .map { state -> NODE_IDS.associateWith { CarMediaModel.childrenOf(it, state) } }
            .distinctUntilChanged()
            .conflate()
            .onEach { nodes ->
                nodes.forEach { (id, children) ->
                    if (previous[id] != children) notifyChildrenChanged(id)
                }
                previous = nodes
                // Картинка на весь екран оновлюється тим самим тактом: вона живе в
                // метаданих сесії, а не в дереві, тож про неї треба сказати окремо.
                publishArt()
                delay(REFRESH_INTERVAL_MS)
            }
            .launchIn(scope)
    }

    private fun toMediaItem(item: CarMediaItem): MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(item.id)
            .setTitle(item.title)
            .setSubtitle(item.subtitle)
            // Іконка — єдина щілина, крізь яку медіа-браузер дає показати своє:
            // хост бере її як картинку й малює як є. Там, де приладу немає,
            // іконки теж немає: хай хост малює свою піктограму.
            .apply {
                item.gauge?.let {
                    setIconBitmap(CarGaugeArtist.bitmapOf(it))
                    // Ще раз, уже поштучно: частина хостів читає стиль не з кореня,
                    // а з самого елемента. Дублювання дешеве, а різниця між плиткою
                    // й значком у рядку — це різниця між «видно на ходу» і «ні».
                    setExtras(
                        Bundle().apply {
                            putInt(
                                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
                                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                            )
                        },
                    )
                }
            }
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

        /**
         * Частіше за це хост усе одно не перемальовує список.
         *
         * Три секунди, а не дві: кожне сповіщення для хоста — привід перечитати
         * вузол, а тепер, коли перечитується лише те, що змінилося, зайва частота
         * нічого не додає. Числа на плитках однаково заокруглені й ворушаться
         * повільніше.
         */
        const val REFRESH_INTERVAL_MS = 3_000L

        /** Усі вузли дерева, включно з коренем: кожен порівнюється сам із собою. */
        val NODE_IDS = listOf(
            CarMediaModel.ROOT_ID,
            CarMediaModel.BATTERY_ID,
            CarMediaModel.PERFORMANCE_ID,
            CarMediaModel.ENERGY_ID,
            CarMediaModel.TRIP_ID,
            CarMediaModel.SCREEN_ID,
        )
    }
}
