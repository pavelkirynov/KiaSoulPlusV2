package com.kirianov.kiasoulevplus2.Data

/**
 * Повний стан додатка. Кожен блок читає звідси те, що йому потрібно, і пише сюди
 * свій результат — прямих викликів між блоками немає.
 */
data class State(
    /** Запити від інтерфейсу до блока Bluetooth. */
    val request: AppRequest = AppRequest.None,
    val inputBms: InputBmsData = InputBmsData(),

    /** Сирий обмін із шиною: сюди пише блок Bluetooth, звідси читає блок декодерів. */
    val can: CanExchange = CanExchange(),

    /** Розібрані показники: сюди пише блок декодерів. */
    val bms: BmsData = BmsData(),

    /** Помилки блоків авто: веде блок car/dtc. */
    val faults: FaultState = FaultState(),
    val cells: CellData = CellData(),

    /** Тест комірок під навантаженням: веде блок tools/cells. */
    val cellTest: CellTestState = CellTestState(),

    /** Збережені заміри комірок цього авто: веде блок tools/cells. */
    val cellHistory: CellHistory = CellHistory(),
    val vehicle: VehicleData = VehicleData(),

    /** Що відомо про зарядки: рахується за пожиттєвим лічильником BMS. */
    val charge: ChargeLog = ChargeLog(),

    /** Похідні величини: сюди пише блок обчислень. */
    val calculated: CalculatedData = CalculatedData(),

    /** Знімки лічильників за поїздку: їх веде блок обчислень. */
    val tripHistory: TripHistory = TripHistory(),

    /** Чи стримав прогноз обіцянку: веде блок обчислень. */
    val rangeAccuracy: RangeAccuracy = RangeAccuracy(),

    /** Прогноз залишку ходу і те, що встиг вивчити блок прогнозу. */
    val ml: MlData = MlData(),

    /** Діапазон, за який рахувати витрату. Обирає користувач на екрані. */
    val consumptionWindow: ConsumptionWindow = ConsumptionWindow.Trip,

    /** Ручні запити до шини та відповіді на них. */
    val probe: ProbeState = ProbeState(),

    /** Введені вручну напруги: сюди пише блок сховища та інтерфейс. */
    val manualCells: ManualCells = ManualCells(),

    /** Налаштування користувача: їх веде блок tools/settings. */
    val settings: Settings = Settings(),

    /** Які авто відомі й за яке рахуємо: веде блок tools/garage. */
    val garage: Garage = Garage(),

    /** Спаровані Bluetooth-пристрої: їх публікує блок services/bluetooth. */
    val pairedDevices: List<PairedDevice> = emptyList(),

    /** Виміряна крива ємності: її веде блок tools/energy. */
    val curve: BatteryCurve = BatteryCurve(),

    /** Журнал діагностики: його веде блок tools/journal. */
    val journal: Journal = Journal(),

    val connection: ConnectionState = ConnectionState.Disconnected,
    val debugInfo: String = "",

    /**
     * Скільки разів надійшла звістка «водій сів в авто».
     *
     * Не час і не прапорець, а лічильник: він завжди відрізняється від попереднього
     * значення, тож жодна звістка не загубиться через те, що дві прийшли підряд.
     * Ставить її будильник по магнітолі, слухає блок автопідключення — щоб скинути
     * відступ між спробами й не змушувати водія чекати дві хвилини після того, як
     * він уже сів за кермо.
     */
    val arrivals: Long = 0L,
) {
    val isConnected: Boolean get() = connection == ConnectionState.Connected

    /**
     * Чи можна записувати те, що приходить із шини, в облік зарядок активного авто.
     *
     * Поки зв'язку немає, записувати нічого: питання виникає рівно тоді, коли
     * числа йдуть живцем, а хто їх дає — ще не з'ясовано. Перші секунди кожного
     * підключення саме такі: VIN питається першим ділом, але відповідь приходить
     * не миттєво, а лічильники вже читаються.
     *
     * Один раз ця дірка з'їла нічну зарядку: чужі лічильники лягли в теку своєї
     * машини й збили відкриту сесію. Десяток секунд обліку дорожчий за цілу ніч.
     *
     * Але чекати ВІЧНО тут не можна, і в цьому вся різниця з [carLearning]. Авто
     * на зарядці вимкнене, а вимкнене авто VIN подеколи не віддає взагалі —
     * зупинити облік на цій підставі означало б утратити саме ту нічну зарядку,
     * заради якої все це й будувалося. Тому чекаємо лише поки питаємо; далі за
     * чужі числа відповідає сторож пожиттєвих лічильників.
     *
     * Ця поблажливість НЕ поширюється на випадок, коли шина вже назвала інше авто,
     * ніж те, що на екрані. Там ми не «не знаємо, чиї числа» — там ми знаємо
     * напевно, що вони чужі.
     */
    val carAccounting: Boolean
        get() = !isConnected || garage.identified || (!garage.mismatched && !garage.vinPending)

    /**
     * Чи можна ВЧИТИСЯ на тому, що приходить із шини.
     *
     * Тут вимога строгіша, і навмисно: в обліку зарядок чужу батарею впіймає
     * стрибок пожиттєвого лічильника, а криву ємності й модель витрати ловити
     * нема кому — чужі числа зіллються з нашими й лишаться правдоподібними.
     * Ціна помилки теж інша: пропущений відрізок нічого не псує, а вивчений чужий
     * псує все, що на ньому побудовано.
     */
    val carLearning: Boolean get() = !isConnected || garage.identified
}

/**
 * Фаза Bluetooth-з'єднання. Окремий тип замість пари булів прибирає стан
 * «підключаємось і водночас підключені», який інакше був би можливий.
 */
enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
}

/**
 * Дія, якої інтерфейс просить від блока Bluetooth. Блок виконує її та скидає в None,
 * тому інтерфейсу не потрібне посилання на сам блок.
 */
enum class AppRequest {
    None,
    Connect,
    Disconnect,
}

/**
 * Спарований пристрій — рівно те, що потрібно екрану для вибору «будити по
 * цьому». Тип android.bluetooth.BluetoothDevice в інтерфейс не потрапляє:
 * сховище стану лишається без Android, як і решта Data.
 */
data class PairedDevice(
    val name: String,
    val address: String,
)
