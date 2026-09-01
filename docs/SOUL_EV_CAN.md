# Kia Soul EV (2015, 27 kWh) — читання одометра, SOC та інших даних з CAN

Довідка для інтеграції в Android-застосунок (Kotlin, ELM327 через Bluetooth SPP).

Усі формули звірені з вихідниками **SoulEVSpy** (`github.com/langemand/SoulEVSpy`, GPL) —
це єдиний відомий open-source проєкт, який ці значення реально дістає.

---

## 1. Головний висновок

**Одометра немає в жодному DID через запит-відповідь.** Це не помилка реалізації —
на 27 kWh Soul EV його там просто не існує.

Що перевірено і не працює:

| Запит | Результат |
|---|---|
| `7DF 01 A6` | `NO DATA` — стандартний OBD PID одометра не підтримується |
| `7C6 22 B0 02` | відповідає, але одометра немає. Формула `Int24(g:h:i)` працює на Ioniq EV і Kona 64, на Soul EV 27 — ні. Там інше значення, яке зменшується на 1 при вмиканні клімату |
| `7C6 21 01` | `61 00` — порожня/бита відповідь |
| `7E2 21 01` | відповідає, одометра немає |

**Одометр приходить широкомовним кадром `4F0`**, який треба ловити в режимі монітора.
Роздільність 0.1 км, порядок байтів little-endian — тому брутфорс по прямому hex
цілого числа km його ніколи не знайде.

Приклад звірки: панель показує `188459.5` км → raw `1884595` = `0x1CC1B3` →
байти 5,6,7 = `B3 C1 1C`.

---

## 2. Трипи A/B

**Не знайдені.** У всьому проєкті SoulEVSpy немає жодної згадки trip.
Ймовірно, це суто дисплейна величина, яка живе всередині щитка і на шину не транслюється.

Дешевий спосіб перевірити самому, коли буде monitor mode:
запустити `AT MA` без фільтра на 30 секунд, посеред запису **скинути трип A**
і подивитись, який кадр обнулився. Роздільність трипу 0.1 км, тож при скиданні
поле стрибне в `00 00` — це помітно набагато краще, ніж шукати за дельтою.

---

## 3. Карта широкомовних кадрів

Індексація: `b[0]` — **перший байт даних після CAN ID**.

| ID | Значення | Формула |
|---|---|---|
| `4F0` | одометр, км | `(b5 \| b6<<8 \| b7<<16) / 10` |
| `4F0` | швидкість, км/год | `(b1 \| ((b2 & 0x01) << 8)) / 2` |
| `594` | SOC як на панелі, % | `b5/2 + (b6 & 0x07)/10` |
| `598` | SOC точний (BMS), % | `((b5<<8) + b4) / 256` |
| `200` | залишок ходу (GOM), км | `(b2<<1) + (b1>>7)` |
| `200` | приріст при кліматі off, км | `b0 / 10` |
| `653` | температура за бортом, °C | `b5/2 - 40` |
| `581` | заряджання: факт / тип / кВт | `b3 != 0` / `b5: 0x0D=Type1, 0x0E=J1772` / `((b7<<8)+b6)/256` |
| `4B0` | швидкості 4 коліс, км/год | кожне: `((b[msb]<<8) + b[lsb]) / 30`, пари (1,0)(3,2)(5,4)(7,6) |
| `567` | годинник авто | `b1`=год, `b2`=хв, `b3`=сек |
| `433` | ручник | `(b2 & 0x08) != 0` |
| `050` | світло / поворотники / двірники | див. код нижче |

### Окремо: display SOC через запит-відповідь

`7E4 → 21 05`, байт **33** payload'а × 0.5 — це той самий `battery.SOC_display_pct`.

Розкладка ISO-TP:
```
FF   -> payload[0..5]
CF21 -> payload[6..12]
CF22 -> payload[13..19]
CF23 -> payload[20..26]
CF24 -> payload[27..33]
```
Тобто це 7-й байт четвертого consecutive frame.

**Що з чого брати:** `594` — основне джерело (швидко, оновлюється постійно),
`21 05` — звірка при старті сесії. Різниця між display SOC і precise SOC (`598`)
показує буфери зверху й знизу — без цього розрахунок kWh → км буде плисти.

---

## 4. Послідовність ELM для монітора

Порядок узятий 1:1 із SoulEVSpy і має значення:

```
AT CRA 4F0        # фільтр на один ID
AT MA             # старт монітора
<читаємо рядки, таймаут ~1000 мс>
<надсилаємо ПРОБІЛ 0x20>   # зупинка AT MA
AT AR             # повторювати, доки не прийде ">"
AT CRA            # зняти фільтр
```

### Три пастки

**1. Промпт `>` у моніторі не приходить.** Кадри сиплються потоком, доки не зупиниш.
Звичайний `readUntilPrompt` тут зависне до таймауту. Але й `readLine()` до `\r`
з таймаутом не годиться — див. пункт 4 нижче: читати треба шматками в буфер.

**2. Некоректний вихід з `AT MA` ламає все наступне.** Якщо не надіслати пробіл
і не добитись промпту через `AT AR`, адаптер лишається в моніторі, і всі
подальші запити `22 xx` перестають працювати.

**3. Дешеві клони ELM327 v2.1 б'ють ID на два байти з паддінгом:**
замість `653 XX XX ...` віддають `00 00 06 53 XX XX ...`.
Без обробки цього близько половини кадрів не розпізнається,
і це виглядає як «адаптер не тягне». Костиль вбудований у парсер нижче.
Оскільки вікно завжди знімається з фільтром, надійніше звіряти розпакований ID
з тим, який замовляли, ніж вгадувати «схоже на ID».

### Фільтр vs потік

`readOneFrame()` по кожному ID окремо — це ~1.5–2 с на чотири значення.
Нормально для разового зчитування при старті, погано для живого екрана.

Для потоку робити навпаки: **один `AT MA` без фільтра**, читати безперервно
і роутити кадри по ID. Тоді всі значення оновлюються паралельно й безкоштовно.

### Що показала перша спроба на живій машині

Порада «один `AT MA` без фільтра» **на цьому адаптері не працює**. Знято з авто
(Vlink, ELM327 v2.1), вікно 700 мс без фільтра:

```
00 08 01 00 00 00 0F 06
0: 00 00 00 00 80 4B EB
FF 03 E1 03 00 00 11 10 <DATA ERROR
00 00 6E 00 00 B3 C1
  1C
BUFFER FULL
```

Чотири окремі несправності в цих шести рядках:

1. **ID немає ні в одному рядку.** У режиму монітора заголовки показуються лише
   при `AT H1`, а для запит-відповіді стоїть `H0`. Отже перед `AT MA` треба
   `AT H1`, а після виходу — вернути `AT H0`.
2. **`0:` на початку рядка** — це індекс ISO-TP від `AT CAF1`. Автоформатування
   дописує службові поля й ріже кадр. Перед `AT MA` потрібен `AT CAF0`.
3. **`BUFFER FULL`** — без фільтра адаптер захлинається трафіком шини за півсекунди.
   `AT CRA <id>` обов'язковий, тобто одне вікно = один ID.
4. **Кадр, розрізаний між двома рядками** (`... B3 C1` / `1C`) — наслідок читання
   «до `\r` з таймаутом»: таймаут посеред кадру віддає половину кадру як цілий рядок.
   Читати треба шматками в буфер, а межі рядків шукати вже в готовому буфері,
   відкидаючи незавершений хвіст.

При цьому дані там були правильні: `B3 C1 1C` = `0x1CC1B3` = 1884595 = **188459.5 км**,
рівно як у Soul EV Spy. Тобто формула з розділу 2 підтверджена на живому авто —
ламався саме транспорт, а не декодер.

---

## 5. Kotlin: парсер і декодери

```kotlin
package your.app.obd

data class CanFrame(val id: String, val bytes: IntArray) {
    val size get() = bytes.size
    operator fun get(i: Int) = bytes[i]
}

data class SpeedOdo(val speedKmh: Double, val odometerKm: Double)
data class RangeInfo(val rangeKm: Int, val extraIfClimateOffKm: Double)

private val ID_RE = Regex("^[0-9A-F]{3}$")
private val BYTE_RE = Regex("^[0-9A-Fa-f]{2}$")

/**
 * Розбирає сирий рядок з ELM у режимі AT MA.
 * Обробляє баг клонів ELM327 v2.1: "00 00 06 53 XX..." замість "653 XX...".
 */
fun parseMonitorLine(raw: String): CanFrame? {
    val parts = raw.replace(Regex("[\\t\\n\\u000B\\u000C\\r]"), "")
        .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null

    var tokens = parts
    if (parts.size > 4 &&
        parts[0] == "00" && parts[1] == "00" &&
        parts[2].length == 2 && parts[3].length == 2 &&
        parts[2].startsWith("0")
    ) {
        tokens = listOf(parts[2].substring(1) + parts[3]) + parts.drop(4)
    }

    val id = tokens[0].uppercase()
    if (!ID_RE.matches(id)) return null

    val bytes = tokens.drop(1)
        .filter { BYTE_RE.matches(it) }
        .map { it.toInt(16) }
        .toIntArray()

    return CanFrame(id, bytes)
}

// ── 4F0: одометр + швидкість ───────────────────────────────
fun decode4F0(b: IntArray): SpeedOdo? {
    if (b.size < 8) return null
    return SpeedOdo(
        speedKmh = (b[1] or ((b[2] and 0x01) shl 8)) / 2.0,
        odometerKm = (b[5] or (b[6] shl 8) or (b[7] shl 16)) / 10.0
    )
}

// ── 594: SOC як на панелі ──────────────────────────────────
fun decode594(b: IntArray): Double? {
    if (b.size != 8) return null
    return b[5] / 2.0 + (b[6] and 0x07) / 10.0
}

// ── 598: SOC точний ────────────────────────────────────────
fun decode598(b: IntArray): Double? {
    if (b.size != 8) return null
    return ((b[5] shl 8) + b[4]) / 256.0
}

// ── 200: GOM ───────────────────────────────────────────────
fun decode200(b: IntArray): RangeInfo? {
    if (b.size != 8) return null
    return RangeInfo(
        rangeKm = (b[2] shl 1) + (b[1] shr 7),
        extraIfClimateOffKm = b[0] / 10.0
    )
}

// ── 653: температура за бортом ─────────────────────────────
fun decode653(b: IntArray): Double? {
    if (b.size != 8) return null
    return b[5] / 2.0 - 40.0
}

// ── 581: заряджання ────────────────────────────────────────
enum class ChargerType { NONE, TYPE1, J1772 }

data class ChargingState(
    val isCharging: Boolean,
    val chargerType: ChargerType,
    val powerKw: Double
)

fun decode581(b: IntArray): ChargingState? {
    if (b.size != 8) return null
    return ChargingState(
        isCharging = b[3] != 0,
        chargerType = when (b[5]) {
            0x0D -> ChargerType.TYPE1
            0x0E -> ChargerType.J1772
            else -> ChargerType.NONE
        },
        powerKw = ((b[7] shl 8) + b[6]) / 256.0
    )
}

// ── 4B0: швидкості коліс ───────────────────────────────────
data class WheelSpeeds(
    val leftFront: Double, val rightFront: Double,
    val leftRear: Double, val rightRear: Double
)

fun decode4B0(b: IntArray): WheelSpeeds? {
    if (b.size != 8) return null
    fun wheel(msb: Int, lsb: Int) = ((b[msb] shl 8) + b[lsb]) / 30.0
    return WheelSpeeds(wheel(1, 0), wheel(3, 2), wheel(5, 4), wheel(7, 6))
}

// ── 567: годинник ──────────────────────────────────────────
data class CarClock(val hour: Int, val minute: Int, val second: Int)

fun decode567(b: IntArray): CarClock? {
    if (b.size != 8) return null
    return CarClock(b[1], b[2], b[3])
}

// ── 433: ручник ────────────────────────────────────────────
fun decode433(b: IntArray): Boolean? {
    if (b.size <= 2) return null
    return (b[2] and 0x08) != 0
}

// ── 050: світло / поворотники / двірники ───────────────────
enum class LightsMode { OFF, PARKING, ON, AUTOMATIC }
enum class TurnSignal { OFF, LEFT, RIGHT }
enum class WiperSpeed { OFF, INTER_0, INTER_1, INTER_2, INTER_3, INTER_4, NORMAL, FAST }

data class BodyStatus(
    val lights: LightsMode,
    val turnSignal: TurnSignal,
    val wipers: WiperSpeed
)

fun decode050(b: IntArray): BodyStatus? {
    if (b.size < 4) return null
    val b1 = b[1]; val b2 = b[2]

    val lights = when (b1 and 0x03) {
        0x01 -> LightsMode.PARKING
        0x02 -> LightsMode.ON
        0x03 -> LightsMode.AUTOMATIC
        else -> LightsMode.OFF
    }
    val turn = when (b2 and 0x30) {
        0x10 -> TurnSignal.RIGHT
        0x20 -> TurnSignal.LEFT
        else -> TurnSignal.OFF
    }
    val wipers = when (b2 and 0x07) {
        0x01 -> WiperSpeed.NORMAL
        0x04 -> WiperSpeed.FAST
        0x02 -> when (b1 and 0xF0) {
            0x80 -> WiperSpeed.INTER_0
            0x60 -> WiperSpeed.INTER_1
            0x40 -> WiperSpeed.INTER_2
            0x20 -> WiperSpeed.INTER_3
            else -> WiperSpeed.INTER_4
        }
        else -> WiperSpeed.OFF
    }
    return BodyStatus(lights, turn, wipers)
}

// ── display SOC з BMS (7E4 → 21 05) ────────────────────────
fun decodeBms2105DisplaySoc(payload: IntArray): Double? {
    if (payload.size < 34) return null
    if (payload[0] != 0x61 || payload[1] != 0x05) return null
    return payload[33] * 0.5
}

val KNOWN_BROADCAST_IDS = setOf(
    "4F0", "594", "598", "200", "653", "581", "4B0", "567", "433", "050"
)
```

---

## 6. Kotlin: обв'язка до ELM

Якщо в проєкті вже є клас для роботи з сокетом — брати з цього лише `readLine()`,
решта, ймовірно, дублює наявне.

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Припускається ATE0 (ехо вимкнене).
 *
 * BluetoothSocket.getInputStream().read() блокується назавжди і не реагує
 * на скасування корутини, тому читаємо через available() з опитуванням.
 */
class ElmIo(
    private val input: InputStream,
    private val output: OutputStream,
    private val pollIntervalMs: Long = 4L
) {
    suspend fun send(cmd: String, timeoutMs: Long = 3000): String =
        withContext(Dispatchers.IO) {
            writeRaw("$cmd\r")
            readUntilPrompt(timeoutMs)
        }

    suspend fun writeRaw(raw: String) = withContext(Dispatchers.IO) {
        output.write(raw.toByteArray()); output.flush()
    }

    /** Читає один рядок до \r. Потрібно саме для режиму монітора. */
    suspend fun readLine(timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val c = input.read()
                if (c == -1) break
                val ch = c.toChar()
                if (ch == '\r' || ch == '\n') {
                    if (sb.isNotEmpty()) return@withContext sb.toString()
                } else sb.append(ch)
            } else Thread.sleep(pollIntervalMs)
        }
        if (sb.isNotEmpty()) sb.toString() else null
    }

    private fun readUntilPrompt(timeoutMs: Long): String {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val c = input.read()
                if (c == -1) break
                val ch = c.toChar()
                if (ch == '>') return sb.toString()
                sb.append(ch)
            } else Thread.sleep(pollIntervalMs)
        }
        return sb.toString()
    }

    suspend fun flushInput() = withContext(Dispatchers.IO) {
        while (input.available() > 0) input.read()
    }
}

/** Читає один кадр із заданим ID і коректно виходить з монітора. */
suspend fun ElmIo.readOneFrame(canId: String, timeoutMs: Long = 1000): CanFrame? {
    val target = canId.uppercase()

    send("AT CRA $target")
    writeRaw("AT MA\r")

    var frame: CanFrame? = null
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline && frame == null) {
        val line = readLine(deadline - System.currentTimeMillis()) ?: break
        val parsed = parseMonitorLine(line)
        if (parsed != null && parsed.id == target) frame = parsed
    }

    writeRaw(" ")   // ПРОБІЛ зупиняє AT MA
    flushInput()
    repeat(5) { if (send("AT AR", timeoutMs = 500).isNotEmpty()) return@repeat }
    send("AT CRA")

    return frame
}
```

---

## 7. Перше, що перевірити після інтеграції

`readOneFrame("4F0")` → `decode4F0()` → порівняти `odometerKm` з панеллю.
Має збігтись з точністю до 0.1 км.

Якщо кадр не приходить взагалі — подивитись сирі рядки з `AT MA` без фільтра:
скоріш за все спрацював баг клона з розбиттям ID на два байти.

---

## Чи можна виставити час в авто через ELM327

Ні. Причина не в тому, що це заборонено, а в тому, що це не працює:

1. **Годинник живе в магнітолі (AVN), і саме вона його показує.** Кадр `567` вона
   *передає* іншим модулям, а не читає з шини. Тобто якщо надсилати `567` самим,
   ми змагатимемося з магнітолою за той самий ID: вона й далі транслюватиме свій
   час і, головне, для Android Auto і перевірки сертифікатів братиме свій власний
   годинник, а не наш кадр.
2. **Записати годинник у модуль означає write-сервіс** (`2E WriteDataByIdentifier`
   або власна процедура Kia) на невідомий DID. Це вже не діагностика: тим самим
   сервісом пишуться налаштування модулів. Підбирати такі DID навмання на живій
   машині не можна, тому пробник у застосунку взагалі не пускає write-сервіси.
3. **Магнітола може бути не на тій шині.** На Kia/Hyundai цього року мультимедіа
   часто сидить на окремій M-CAN, не зведеній на діагностичну колодку.

### Що показала машина

Вимикання синхронізації по GPS не допомогло, а годинник магнітоли йде **з іншою
швидкістю**. Це знімає підозру з РЕБ: підміна часу по GPS переставляє годинник
стрибком, змінити швидкість ходу вона не може. Рівномірний хід — це кварц RTC у
самій магнітолі, або втрата постійного живлення (запобіжник пам'яті), якщо час
скидається після стоянки.

**Для застосунку з цього випливає головне:** годинник авто не можна брати за
джерело часу ні для чого. Тривалість поїздки і витрата на годину рахуються за
монотонним годинником телефона (`System.nanoTime`), а не за кадром `567` і не за
системним годинником — переведення часу на телефоні теж не має псувати розрахунок.

Читання `567` і вимірювання розходження було реалізоване й відкочене: несправність
знайдена, а постійно тримати цю діагностику в застосунку сенсу немає. Історія
збережена, повернути можна `git revert` того відкату.

---

## Лічильники BMS: Ач і кВт·год лежать поруч

У відповіді `7E4 → 21 01` лічильники за весь час життя батареї йдуть поспіль,
по чотири байти, беззнакові, кожен у десятих своєї одиниці:

| Зсув | Що | Приклад із машини |
|---|---|---|
| 32 | прийнято, **Ач** | 73437.3 |
| 36 | віддано, **Ач** | 73260.8 |
| 40 | прийнято, **кВт·год** | 26937.9 |
| 44 | віддано, **кВт·год** | 25890.8 |

**Пастка.** Спершу застосунок читав як кВт·год зсуви 32 і 36, тобто амперу-години,
і показував 73437 «кВт·год» замість 26937. Числа виглядають абсолютно правдоподібно
— великі лічильники за весь час, — тому на око помилка не видна.

**Чим перевіряється.** Поділити кВт·год на Ач: мусить вийти середня напруга пакета.
На реальних даних це 366.8 В для заряду і 353.4 В для розряду — обидва в межах
робочої напруги Soul EV, і зарядна вище за розрядну, як і має бути фізично (під
струмом заряду напруга росте, під струмом розряду просідає). Якщо відношення
виходить поза 300–420 В, прочитані не ті байти.

Саме тому в застосунку Ач показуються поруч із кВт·год, а не замість них: це дає
звірити показання з Soul EV Spy напряму й одразу побачити підміну одиниці.

**Наскільки це псувало витрату.** Витрата на 100 км рахується різницею лічильників,
тому з Ач замість кВт·год вона була завищена приблизно в 1/0.36 ≈ 2.7 раза.

