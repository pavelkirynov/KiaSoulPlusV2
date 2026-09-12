// ====================================================================================
// ВИМІРЯНА КРИВА ЄМНОСТІ (EnergyLevels)
//
// Тримає, скільки кВт·год виявилося в кожному відсотку шкали, і складає з цього
// криву. Нічого не читає й нікуди не пише — чистий стан, тож перевіряється тестами.
//
// КРИВИХ ТУТ ДВІ, І ЦЕ НЕ ПРИМХА. Обидві міряють те саме — енергію на відсоток
// шкали, — але різними приладами, і розходження між ними саме по собі є
// вимірюванням.
//
//   A «за лічильниками»: ΔkWhOut − ΔkWhIn з пожиттєвих лічильників BMS. Приладу
//     байдуже, чи дивився телефон: обрив зв'язку заміру не псує.
//
//   B «за струмом»: ΔkWhOut з лічильника (він звірений з інтегралом струму й
//     сходиться) мінус рекуперація, порахована інтегруванням потужності. Потребує
//     неперервного шматка спостережень, зате не залежить від лічильника прийнятої.
//
// ЧОМУ ЛІЧИЛЬНИК ПРИЙНЯТОЇ ПІД ПІДОЗРОЮ. Три незалежні заміри показали одне й те
// саме: він рахує НЕ фізичну енергію. Нічна зарядка з розетки — 49.6 кВт·год за
// лічильником станції, +23.0 за лічильником BMS; SOC при цьому виріс на 85 %, тобто
// на всю шкалу вийшло б 27 кВт·год. Швидка зарядка постійним струмом — 7.44 кВт·год
// за інтегралом струму, 5.10 за лічильником, на всю шкалу знову 27. І 74 А·год на
// всю шкалу — рівно паспорт РІДНОГО пакета Soul EV, 75 А·год.
//
// Лічильник відданої при цьому чесний: на поїздці інтеграл струму дав ті самі
// 30.6 А·год, що й він. Тому крива B будується на ньому, а A лишається для
// порівняння — на графіку видно, куди заводить лічильник прийнятої.
//
// ГОЛОВНЕ ПРАВИЛО ПОБУДОВИ: КРИВА ЙДЕ ЗВЕРХУ ВНИЗ. Верхня точка — ємність із
// налаштувань авто, далі вниз по виміряних нахилах. Куди крива прийде на нулі —
// те й є відповідь: прийшла в нуль — пакет саме такий, як заявлено; сіла вище —
// стільки насправді не набралося.
//
// Раніше було навпаки: сума кривої задавалася наперед, а невиміряне тиснулося
// коефіцієнтом, щоб зійтися з аксіомою. Така крива фізично НЕ МОГЛА суперечити
// заявленій ємності — і саме тому нічого про неї не казала.
//
// НЕВИМІРЯНІ КОШИКИ доводить [RateBins.profile]: між островами нахил переходить
// плавно, за краями продовжується найближчим виміряним. Кожна точка знає, вимір
// вона чи доведення, і на графіку це видно пунктиром.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.energy

import com.kirianov.kiasoulevplus2.Data.CurvePoint
import com.kirianov.kiasoulevplus2.Data.DistancePoint
import com.kirianov.kiasoulevplus2.Data.Pack
import com.kirianov.kiasoulevplus2.Data.VoltagePoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class EnergyLevels {

    /** Крива A: енергія за різницею пожиттєвих лічильників. */
    private val counter = RateBins(MIN_RATE_KWH_PER_PERCENT, MAX_RATE_KWH_PER_PERCENT)

    /** Крива B: віддане за лічильником мінус рекуперація за інтегралом струму. */
    private val power = RateBins(MIN_RATE_KWH_PER_PERCENT, MAX_RATE_KWH_PER_PERCENT)

    /**
     * Кілометри на відсоток шкали.
     *
     * Це НЕ властивість батареї, а суміш того, як їздили: той самий відсоток у
     * місті з кліматом і на трасі дає різне число. Тут він потрібен саме таким —
     * щоб бачити, як довго тягнуться відсотки в реальній їзді.
     */
    private val distance = RateBins(MIN_KM_PER_PERCENT, MAX_KM_PER_PERCENT)

    val samples: Int get() = counter.samples
    val powerSamples: Int get() = power.samples
    val distanceSamples: Int get() = distance.samples

    /**
     * Заміри ПОВНОЇ ємності — із зарядок, що починалися з низьких відсотків.
     *
     * Тримаються окремо від кошиків, бо це принципово інший вимір. У побудові
     * кривої вони більше НЕ БЕРУТЬ УЧАСТІ: рахуються за лічильником прийнятої
     * енергії, а він, як з'ясувалося, міряє шкалу рідного пакета, а не кіловат-
     * години. Лишилися заради одного рядка на екрані — щоб цю розбіжність було
     * видно, а не щоб на ній щось будувати.
     */
    private var totalSumKwh = 0.0

    var fullChargeSamples: Int = 0
        private set

    /**
     * Суми для кривої НАПРУГИ по шкалі.
     *
     * Зберігаються не середні, а суми найменших квадратів: n, ΣI, ΣU, ΣI², ΣI·U.
     * З них виходить пряма U = U0 − I·R, тобто одразу дві речі — напруга спокою
     * U0 і внутрішній опір R.
     *
     * ЧОМУ НЕ ПРОСТО СЕРЕДНЄ. Під навантаженням напруга просідає, і просідає
     * по-різному залежно від струму. Середнє по замірах змішало б криву самої
     * батареї з тим, як водій тиснув педаль. Пряма ж прибирає просадку рівно
     * настільки, наскільки вона пояснюється струмом, — і лишає те, що від струму
     * не залежить.
     */
    private val volN = DoubleArray(BINS)
    private val volI = DoubleArray(BINS)
    private val volU = DoubleArray(BINS)
    private val volII = DoubleArray(BINS)
    private val volIU = DoubleArray(BINS)

    /** Замір кривої A: шкала пройшла від A до B, лічильники дали стільки нетто. */
    fun learn(fromPercent: Double, toPercent: Double, netKwh: Double): Boolean =
        counter.learn(fromPercent, toPercent, netKwh)

    /** Замір кривої B: віддане за лічильником мінус проінтегрована рекуперація. */
    fun learnPower(fromPercent: Double, toPercent: Double, netKwh: Double): Boolean =
        power.learn(fromPercent, toPercent, netKwh)

    /** Замір кілометрів: скільки проїхано за цей шматок шкали. */
    fun learnDistance(fromPercent: Double, toPercent: Double, km: Double): Boolean =
        distance.learn(fromPercent, toPercent, km)

    /**
     * Додає замір повної ємності із зарядки.
     *
     * @return чи прийнято замір.
     */
    fun learnFullCharge(fromPercent: Double, toPercent: Double, energyInKwh: Double): Boolean {
        if (fromPercent > MAX_START_PERCENT || toPercent < MIN_FINISH_PERCENT) return false
        val span = toPercent - fromPercent
        if (span < MIN_CHARGE_SPAN_PERCENT) return false
        if (energyInKwh <= 0.0 || !energyInKwh.isFinite()) return false

        val total = energyInKwh / span * 100.0
        if (total < MIN_TOTAL_KWH || total > MAX_TOTAL_KWH) return false

        totalSumKwh += total
        fullChargeSamples++
        return true
    }

    /** Що показала зарядка з низьких відсотків, кВт·год; null — таких не було. */
    val measuredTotalKwh: Double?
        get() = if (fullChargeSamples > 0) totalSumKwh / fullChargeSamples else null

    /**
     * Додає замір напруги: на цьому відсотку шкали пакет тримав [volts] під
     * струмом [amps] (від'ємний — розряд, за домовленістю застосунку).
     *
     * @return чи прийнято замір. Відмова означає, що навантаження завелике: під
     * ним просадка вже не лінійна, і пряма почала б брехати.
     */
    fun learnVoltage(socPercent: Double, volts: Double, amps: Double): Boolean {
        if (!volts.isFinite() || volts < MIN_PLAUSIBLE_VOLTS || volts > MAX_PLAUSIBLE_VOLTS) return false
        if (!amps.isFinite() || abs(amps) > MAX_LOAD_AMPS) return false
        if (socPercent < 0.0 || socPercent > 100.0) return false

        val bin = binOf(socPercent)
        volN[bin] += 1.0
        volI[bin] += amps
        volU[bin] += volts
        volII[bin] += amps * amps
        volIU[bin] += amps * volts
        return true
    }

    /**
     * Напруга спокою на цьому відсотку шкали, В; null — замірів замало.
     *
     * Якщо струми в кошику майже однакові, прямої не побудувати — тоді береться
     * середня напруга. Це чесно: за однакового навантаження просадка теж
     * однакова, і криву вона зміщує, але не спотворює її форму.
     */
    fun restVoltageAt(socPercent: Double): Double? = restVoltageOfBin(binOf(socPercent))

    private fun restVoltageOfBin(bin: Int): Double? {
        val n = volN[bin]
        if (n < MIN_VOLTAGE_SAMPLES) return null

        val meanI = volI[bin] / n
        val meanU = volU[bin] / n
        val spread = volII[bin] / n - meanI * meanI
        if (spread < MIN_CURRENT_SPREAD) return meanU

        // Нахил прямої = -R, вільний член = напруга спокою.
        val slope = (volIU[bin] / n - meanI * meanU) / spread
        val rest = meanU - slope * meanI
        return if (rest in MIN_PLAUSIBLE_VOLTS..MAX_PLAUSIBLE_VOLTS) rest else meanU
    }

    /** Крива напруги по шкалі: точки лише там, де є заміри. */
    fun voltageCurve(): List<VoltagePoint> = (0 until BINS).mapNotNull { bin ->
        restVoltageOfBin(bin)?.let {
            VoltagePoint(socPercent = (bin + 0.5) * BIN_WIDTH_PERCENT, volts = it)
        }
    }

    /** Нахил кривої A в цьому місці шкали, кВт·год на відсоток; null — не міряли. */
    fun rateAt(socPercent: Double): Double? = counter.rateAt(socPercent)

    val measuredFromPercent: Double? get() = counter.measuredFromPercent
    val measuredToPercent: Double? get() = counter.measuredToPercent
    val coveredPercent: Double get() = counter.coveredPercent
    val powerCoveredPercent: Double get() = power.coveredPercent
    val distanceCoveredPercent: Double get() = distance.coveredPercent

    /**
     * Крива A від 0 до 100 % через один відсоток.
     *
     * @param nominalKwh ємність із налаштувань авто: з неї крива починається на
     * ста відсотках і йде вниз по виміряних нахилах.
     */
    fun curve(nominalKwh: Double): List<CurvePoint> = build(counter, nominalKwh)

    /** Крива B — те саме, але за струмом. Порожня, поки замірів немає. */
    fun powerCurve(nominalKwh: Double): List<CurvePoint> =
        if (power.hasMeasurements) build(power, nominalKwh) else emptyList()

    /**
     * Кілометри на відсоток шкали. Порожньо, поки не проїхали нічого.
     *
     * Невиміряне тут НЕ добудовується: на відміну від енергії, доводити пробіг
     * нема з чого — він залежить не від батареї, а від дороги.
     */
    fun distanceCurve(): List<DistancePoint> =
        if (!distance.hasMeasurements) {
            emptyList()
        } else {
            (0 until BINS).mapNotNull { bin ->
                distance.rateOf(bin)?.let {
                    DistancePoint(socPercent = (bin + 0.5) * BIN_WIDTH_PERCENT, km = it)
                }
            }
        }

    /** Скільки кВт·год набралося по всій шкалі за кривою A. */
    fun capacityKwh(nominalKwh: Double): Double = total(counter, nominalKwh)

    /** Те саме за кривою B; null — замірів ще немає. */
    fun powerCapacityKwh(nominalKwh: Double): Double? =
        if (power.hasMeasurements) total(power, nominalKwh) else null

    private fun total(bins: RateBins, nominalKwh: Double): Double =
        bins.profile(nominalKwh / 100.0).sum() * BIN_WIDTH_PERCENT

    /**
     * Складає криву зверху вниз.
     *
     * Верхня точка — [nominalKwh] на ста відсотках. Далі вниз віднімається нахил
     * кожного кошика. Де крива опиниться на нулі — там і відповідь: нуль означає
     * «пакет саме такий», додатне — «стільки не набралося», від'ємне — «пакет
     * більший за заявлений». Нічого не підганяємо: промах і є результат.
     */
    private fun build(bins: RateBins, nominalKwh: Double): List<CurvePoint> {
        if (nominalKwh <= 0.0) return emptyList()
        val rate = bins.profile(nominalKwh / 100.0)

        // Точка на межі кошиків зміряна, якщо зміряний хоч один із двох сусідніх:
        // інакше поодинокий виміряний кошик малювався б пунктиром з обох боків.
        fun measuredAt(edge: Int): Boolean =
            (edge < BINS && bins.measured(edge)) || (edge > 0 && bins.measured(edge - 1))

        val points = ArrayList<CurvePoint>(BINS + 1)
        var energy = nominalKwh
        points += CurvePoint(100.0, energy, measuredAt(BINS))
        for (bin in BINS - 1 downTo 0) {
            energy -= rate[bin] * BIN_WIDTH_PERCENT
            points += CurvePoint(bin * BIN_WIDTH_PERCENT, energy, measuredAt(bin))
        }
        return points.asReversed()
    }

    fun snapshot() = LevelsSnapshot(
        sumKwh = counter.values,
        sumPercent = counter.percents,
        samples = counter.samples,
        sumPowerKwh = power.values,
        sumPowerPercent = power.percents,
        powerSamples = power.samples,
        sumKm = distance.values,
        sumKmPercent = distance.percents,
        distanceSamples = distance.samples,
        totalSumKwh = totalSumKwh,
        fullChargeSamples = fullChargeSamples,
        voltage = VoltageSums(
            n = volN.copyOf(),
            i = volI.copyOf(),
            u = volU.copyOf(),
            ii = volII.copyOf(),
            iu = volIU.copyOf(),
        ),
    )

    fun restore(snapshot: LevelsSnapshot) {
        counter.restore(snapshot.sumKwh, snapshot.sumPercent, snapshot.samples)
        power.restore(snapshot.sumPowerKwh, snapshot.sumPowerPercent, snapshot.powerSamples)
        distance.restore(snapshot.sumKm, snapshot.sumKmPercent, snapshot.distanceSamples)
        totalSumKwh = snapshot.totalSumKwh
        fullChargeSamples = snapshot.fullChargeSamples

        // Суми напруги приводяться до нової ширини кошика так само, як решта:
        // вони теж суми, і жодного заміру при цьому не втрачено.
        snapshot.voltage?.let { v ->
            RateBins.resize(v.n)?.copyInto(volN)
            RateBins.resize(v.i)?.copyInto(volI)
            RateBins.resize(v.u)?.copyInto(volU)
            RateBins.resize(v.ii)?.copyInto(volII)
            RateBins.resize(v.iu)?.copyInto(volIU)
        }
    }

    fun reset() {
        counter.reset()
        power.reset()
        distance.reset()
        volN.fill(0.0); volI.fill(0.0); volU.fill(0.0); volII.fill(0.0); volIU.fill(0.0)
        totalSumKwh = 0.0
        fullChargeSamples = 0
    }

    private fun binOf(socPercent: Double): Int =
        (socPercent / BIN_WIDTH_PERCENT).toInt().coerceIn(0, BINS - 1)

    companion object {
        const val BINS = RateBins.BINS
        const val BIN_WIDTH_PERCENT = RateBins.BIN_WIDTH_PERCENT

        /** Батарея на 10 кВт·год — менше не буває навіть у гібрида. */
        const val MIN_RATE_KWH_PER_PERCENT = 0.1

        /**
         * Батарея на 150 кВт·год — більше в Soul EV не влізе фізично.
         *
         * Межа широка навмисно: у кінці шкали відсоток коштує в рази більше, ніж
         * угорі, і вузька межа відкидала б саме ті заміри, яких найбільше не
         * вистачає.
         */
        const val MAX_RATE_KWH_PER_PERCENT = 1.5

        /**
         * Межі для кілометрів на відсоток.
         *
         * Знизу — сто метрів: менше означає, що одометр не встиг оновитися.
         * Зверху — двадцять кілометрів на відсоток: це вже не їзда, а дірка в
         * спостереженні, крізь яку проїхали більше, ніж ми бачили.
         */
        const val MIN_KM_PER_PERCENT = 0.1
        const val MAX_KM_PER_PERCENT = 20.0

        /** Зарядка мусить починатися не вище цього відсотка, щоб міряти повну ємність. */
        const val MAX_START_PERCENT = 6.0

        /** І доходити щонайменше до цього. */
        const val MIN_FINISH_PERCENT = 98.0

        /** Скільки відсотків шкали має охопити зарядка. */
        const val MIN_CHARGE_SPAN_PERCENT = 90.0

        /**
         * Найбільше навантаження, під яким ще беремо напругу: 30 % від струму
         * повної потужності. Вище просадка перестає бути лінійною за струмом, і
         * пряма, якою ми її прибираємо, почала б брехати.
         */
        const val MAX_LOAD_SHARE = 0.30
        val MAX_LOAD_AMPS = Pack.MAX_CURRENT_A * MAX_LOAD_SHARE

        /** Менше замірів у кошику — і пряма ще нічого не означає. */
        const val MIN_VOLTAGE_SAMPLES = 8.0

        /** Розкид струмів, нижче якого прямої не будуємо, А². */
        const val MIN_CURRENT_SPREAD = 4.0

        /** Поза цими межами це не напруга пакета, а помилка читання. */
        const val MIN_PLAUSIBLE_VOLTS = 250.0
        const val MAX_PLAUSIBLE_VOLTS = 450.0

        /** Правдоподібні межі повної ємності цього пакета, кВт·год. */
        const val MIN_TOTAL_KWH = 20.0
        const val MAX_TOTAL_KWH = 120.0
    }
}

/** Усе, що крива пам'ятає, у вигляді, придатному для файлу. */
data class LevelsSnapshot(
    val sumKwh: DoubleArray,
    val sumPercent: DoubleArray,
    val samples: Int,

    /**
     * Криві B і кілометрів. Порожні в старих файлах — почнуть набиратися заново,
     * а крива A від цього не постраждає: вона в тих самих полях, що й була.
     */
    val sumPowerKwh: DoubleArray = DoubleArray(0),
    val sumPowerPercent: DoubleArray = DoubleArray(0),
    val powerSamples: Int = 0,
    val sumKm: DoubleArray = DoubleArray(0),
    val sumKmPercent: DoubleArray = DoubleArray(0),
    val distanceSamples: Int = 0,

    val totalSumKwh: Double = 0.0,
    val fullChargeSamples: Int = 0,

    /**
     * Закладка на початок зарядки, яку ще не дораховано.
     *
     * Лежить у тому самому файлі, що й заміри, бо мусить пережити не лише обрив
     * зв'язку, а й перезапуск застосунку: телефон їде з машиною, зарядка йде без
     * нього годинами, і закладку в пам'яті процесу до ранку не донести.
     *
     * Від'ємний відсоток означає «закладки немає».
     */
    val pendingSocPercent: Double = -1.0,
    val pendingChargedKwh: Double = 0.0,
    val pendingDischargedKwh: Double = 0.0,
    val pendingAtMs: Long = 0L,

    /**
     * Суми для кривої напруги. null у старих файлах — крива просто почне
     * набиратися заново, решта замірів від цього не постраждає.
     */
    val voltage: VoltageSums? = null,
) {
    // equals/hashCode для масивів data class не робить сам, а тести їх порівнюють.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LevelsSnapshot) return false
        return samples == other.samples &&
            powerSamples == other.powerSamples &&
            distanceSamples == other.distanceSamples &&
            fullChargeSamples == other.fullChargeSamples &&
            totalSumKwh == other.totalSumKwh &&
            pendingSocPercent == other.pendingSocPercent &&
            pendingChargedKwh == other.pendingChargedKwh &&
            pendingDischargedKwh == other.pendingDischargedKwh &&
            pendingAtMs == other.pendingAtMs &&
            voltage == other.voltage &&
            sumKwh.contentEquals(other.sumKwh) &&
            sumPercent.contentEquals(other.sumPercent) &&
            sumPowerKwh.contentEquals(other.sumPowerKwh) &&
            sumPowerPercent.contentEquals(other.sumPowerPercent) &&
            sumKm.contentEquals(other.sumKm) &&
            sumKmPercent.contentEquals(other.sumKmPercent)
    }

    override fun hashCode(): Int =
        (sumKwh.contentHashCode() * 31 + sumPercent.contentHashCode()) * 31 + samples
}

/** Суми найменших квадратів для кривої напруги, по кошиках шкали. */
data class VoltageSums(
    val n: DoubleArray,
    val i: DoubleArray,
    val u: DoubleArray,
    val ii: DoubleArray,
    val iu: DoubleArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoltageSums) return false
        return n.contentEquals(other.n) && i.contentEquals(other.i) &&
            u.contentEquals(other.u) && ii.contentEquals(other.ii) && iu.contentEquals(other.iu)
    }

    override fun hashCode(): Int =
        (((n.contentHashCode() * 31 + i.contentHashCode()) * 31 +
            u.contentHashCode()) * 31 + ii.contentHashCode()) * 31 + iu.contentHashCode()
}
