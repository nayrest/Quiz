package com.example.quiz


import com.example.quiz.R
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.random.Random
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.widget.Toast


// --- Стартовый экран ---
class MainActivity : AppCompatActivity(), UsbEventListener {
    private lateinit var usbManager: UsbManager
    private val usbReceiver = UsbReceiver().apply {
        setListener(this@MainActivity)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.startButton).setOnClickListener {
            startActivity(Intent(this, UserInputActivity::class.java))
        }

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        checkConnectedDevices()
    }

    override fun onResume() {
        super.onResume()

        // Регистрируем ресивер с правильным флагом для Android 12+
        val filter = IntentFilter().apply {
            addAction(UsbReceiver.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(usbReceiver)
    }

    private fun checkConnectedDevices() {
        val deviceList = usbManager.deviceList
        if (deviceList.isNotEmpty()) {
            val device = deviceList.values.first()
            if (!usbManager.hasPermission(device)) {
                requestUsbPermission(device)
            } else {
                setupUsbConnection(device)
            }
        } else {
            Toast.makeText(this, "Arduino не подключено", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val usbPermissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(UsbReceiver.ACTION_USB_PERMISSION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        usbManager.requestPermission(device, usbPermissionIntent)
    }

    override fun setupUsbConnection(device: UsbDevice) {
        usbConnection = usbManager.openDevice(device)
        val usbInterface = device.getInterface(1)
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            Log.d("USB", "Found device: vendorId=${device.vendorId}, productId=${device.productId}, name=${device.deviceName}")
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                Log.d("USB", "Interface $i: class=${intf.interfaceClass}, subclass=${intf.interfaceSubclass}, endpointCount=${intf.endpointCount}")
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    Log.d("USB", "  Endpoint $j: address=${ep.address}, direction=${ep.direction}")
                }
            }
        }


        // Находим endpoint OUT
        usbEndpoint = (0 until usbInterface.endpointCount)
            .map { usbInterface.getEndpoint(it) }
            .firstOrNull { ep ->
                ep.direction == UsbConstants.USB_DIR_OUT
            }

        usbConnection?.claimInterface(usbInterface, true)

        if (usbEndpoint != null) {
            Toast.makeText(this, "Устройство готово", Toast.LENGTH_SHORT).show()
            Log.d("USB", "Используем endpoint: ${usbEndpoint!!.address}")
        } else {
            Toast.makeText(this, "Ошибка: Endpoint не найден", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUsbDeviceDetached() {
        usbConnection?.close()
        usbConnection = null
        usbEndpoint = null
        Toast.makeText(this, "USB отключено", Toast.LENGTH_SHORT).show()
    }
}

// --- Экран ввода имени ---
class UserInputActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_input)

        val nameInput = findViewById<EditText>(R.id.nameInput)
        val continueButton = findViewById<Button>(R.id.continueButton)

        continueButton.isEnabled = false

        nameInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                continueButton.isEnabled = !s.isNullOrBlank()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        continueButton.setOnClickListener {
            val name = nameInput.text.toString()
            getSharedPreferences("GameData", MODE_PRIVATE)
                .edit().putString("UserName", name).apply()

            sendCommandToArduino("step 0")
            startActivity(Intent(this, LoadingActivity::class.java))
        }
    }
}

// --- Экран загрузки ---
class LoadingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_loading)

        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({ sendCommandToArduino("4 1 1 0") }, 0)
        handler.postDelayed({ sendCommandToArduino("4 0 0 0") }, 500)

        handler.postDelayed({
            startActivity(Intent(this, QuizActivity::class.java))
            finish()
        }, 0)
    }
}

// --- Экран викторины ---
data class Question(val text: String, val answers: List<String>, val correctAnswer: Int)

class QuizActivity : AppCompatActivity() {
    private var isLoadingDialogActive = false

    private var score = 0
    private var currentBlockIndex = 0
    private var availableQuestions = mutableListOf<Question>()
    private var usedQuestions = mutableSetOf<Int>()

    // Массив контрольных точек
    private val locations = listOf("Костел", "БМ 43", "БМ 50", "БМ 39", "БМ 33", "БМ 44", "БМ 34", "БМ 21", "БМ 26", "БМ 18", "БМ 14", "БМ 12/1", "БМ 7", "БМ 6", "БМ 3", "БМ 4", "БМ 2")
    private var currentLocationIndex = 0 // Индекс текущей точки

    var currentLocation = locations[currentLocationIndex]

    private val questionBlocks = listOf(
        listOf(
            Question("Кто пожертвовал землю для строительства католического храма в Севастополе?", listOf("Казимир Александрович Скирмунт", "Ю. Каминский", "Константин Калиновский", "Н. И. Третесский"), 1),
            Question("Почему первоначальный проект костёла не был полностью реализован?", listOf("Из-за нехватки средств", "Из-за разрушений в ходе Второй мировой войны", "Из-за отсутствия разрешения министерства внутренних дел", "Не удалось полностью реализовать декоративные элементы фасада"), 0),
            Question("Какое здание находилось в костёле после его закрытия в 1936 году?", listOf("Государственная библиотека", "Театр оперы и балета", "Кинотеатр «Дружба»", "Музей истории Севастополя"), 2)
        ),
        listOf(
            Question("Кто является архитектором здания по адресу Большая Морская, 43?", listOf("Азиз Сафович Уразов", "Василий Александрович Рулёв", "Николай Иустинович Третесский", "Александр Михайлович Вейзен"), 0),
            Question("Каким материалом облицован главный фасад здания?", listOf("Постелистый бутовый камень и ракушечник", "Железобетонные панели", "Деревянные брусья", "Альминская плитка"), 3),
            Question("Что находилось в доме № 43 со стороны улицы Советская в советское время? Это заведение традиционно размещается на первом этаже этого дома с самого первого дня постройки и до наших дней.", listOf("Магазины одежды и обуви", "Предприятия общепита, такие как \"Пышечная\" и кафе \"Чай\"", "Кинотеатр и парикмахерская", "Банк и аптека"), 0)
        ),
        listOf(
            Question("В этом здании традиционно размещались клуб, а ныне спортшкола для детей, занимающихся определенным видом спорта. О каком виде спорта идет речь?", listOf("Бокс", "Шахматы/шашки", "Футбол", "Бадминтон"), 1),
            Question("Кто является автором проекта здания?", listOf("Самуил Иванович Уптон", "Александр Иванович Гегелло", "Александр Михайлович Вейзен", "Михаил Александрович Врангель"), 3),
            Question("Как раньше называлось здание на Большой Морской 50?", listOf("Корпус «А» жилкомбината № 1 Севморзавода", "Жилой дом", "Дом Хлудова", "Работный отель"), 0)
        ),
        listOf(
            Question("Какие архитекторы занимались проектированием жилого квартала на улице Большая Морская №35-43?", listOf("А. С. Уразов, В. А. Петров и А. М. Хабенский", "И. П. Кузнецов, А. А. Лебедев и М. Г. Попов", "В. М. Карро, А. Л. Смирнов и Н. В. Сидоров", "Д. А. Чернышев, П. В. Борисов и В. С. Медведев"), 0),
            Question("Какое архитектурное решение было использовано для создания ощущения большего пространства на улице Большая Морская?", listOf("Установка высоких заборов вдоль улицы", "Построение многоуровневых жилых комплексов", "Применение зеркальных фасадов", "Использование курдонеров со скверами"), 3),
            Question("В каком году застройка улицы Большая Морская получила первую премию на республиканском конкурсе как лучший завершенный жилой ансамбль?", listOf("1960 год", "1949 год", "1955 год", "1952 год"), 3)
        ),
        listOf(
            Question("Какое архитектурное оформление имеет главный вход в дом № 33 на Большой Морской улице?", listOf("Круглый проём с колоннами и декоративным карнизом", "Многоступенчатый вход с арочным проёмом и декоративным остеклением", "Прямоугольный проём с порталом в профилированном наличнике с прямым сандриком", "Балкон с металлическим ограждением"), 2),
            Question("В этом здании в 1895 году размещалось агентство по продаже этого востребованного в быту товара. Этот товар активно использовали в темное время суток, а также для запуска и работы механизмов.", listOf("Спички", "Восковые Свечи", "Керосин", "Сальные свечи"), 2),
            Question("Сколько этажей в доме №44?", listOf("2", "3", "4", "5"), 1)
        ),
        listOf(
            Question("Назовите имя градоначальника, владевшего зданием по адресу ул. Большая Морская 44.", listOf("Ф.Н. Еранцев", "П.И. Кислинский", "П.А. Перелешин", "А.И. Никонов"), 0),
            Question("В этом здании в 1895 году размещалось агентство по продаже этого востребованного в быту товара. Этот товар активно использовали в темное время суток, а также для запуска и работы механизмов.", listOf("Спички", "Восковые Свечи", "Керосин", "Сальные свечи"), 2),
            Question("Сколько этажей в доме №44?", listOf("2", "3", "4", "5"), 1)
        ),
        listOf(
            Question("Какую функцию выполняло здание №34 на Большой Морской после войны?", listOf("Было административным офисом для правительства города.", "Служило складом для оружия и боеприпасов.", "Размещало жилье для офицеров Черноморского флота.", "Размещало военный госпиталь для раненых солдат."), 2),
            Question("Какое название у первого послевоенного кинотеатра, построенного в 1950 году?", listOf("Москва", "Маяк", "Победа", "Октябрь"), 2),
            Question("Чье имя носила в своей истории ул. Большая Морская?", listOf("Екатерининская", "Генерала Коробкова", "Карла Маркса", "Генерала Петрова"), 2)
        ),
        listOf(
            Question("В каком стиле построено здание по адресу Большая Морская, 21?", listOf("Модерн", "Классицизм", "Конструктивизм", "Барокко"), 0),
            Question("Какое ведомство изначально арендовало здание после его постройки?", listOf("Министерство обороны", "Городская администрация", "Почтово-телеграфное ведомство", "Торговая палата"), 2),
            Question("Какую функцию выполняло здание в годы обороны Севастополя 1941-1942 гг.?", listOf("Городская контора связи", "Военный госпиталь", "Командный пункт обороны", "Жилой дом для офицеров"), 0)
        ),
        listOf(
            Question("Из какого материала построены стены здания № 26?", listOf("Красный кирпич", "Постелистый бутовый камень и ракушечник", "Железобетонные панели", "Деревянные брусья"), 1),
            Question("Какую форму в плане имеет здание № 26?", listOf("Г-образную", "П-образную", "Квадратную", "Треугольную"), 0),
            Question("Какие особенности имеет крыша здания № 26 на Большой Морской улице?", listOf("Плоская", "Шатровая", "Полувальмовая", "Многоскатная вальмовая"), 3)
        ),
        listOf(
            Question("Какой элемент ограды дома №18 является уменьшенной копией ограды Летнего сада в Санкт-Петербурге?", listOf("Декоративные колонны", "Калитки", "Позолоченные гирлянды с копьевидными завершениями", "Каменные фундаменты"), 2),
            Question("Из какого материала построены капитальные стены дома № 18?", listOf("Известняк на сложном растворе", "Красный кирпич", "Железобетонные панели", "Деревянные брусья"), 0),
            Question("Какой архитектурный ордер использован для колонн, оформляющих лоджию третьего этажа дома №18?", listOf("Ионический", "Тосканский", "Коринфский", "Композитный"), 1)
        ),
        listOf(
            Question("Какой элемент отделки здания №14 заимствован из Санкт-Петербурга?", listOf("Купол в стиле Исаакиевского собора", "Атланты, как у Нового Эрмитажа", "Мозаика в стиле Русского музея", "Ограда, копирующая ограду Летнего сада"), 3),
            Question("Какой элемент объединяет здание №14 с домами №16 и №18 на Большой Морской улице?", listOf("Общий внутренний двор", "Подземный переход", "Курдонёр", "Арка с колоннадой"), 2),
            Question("Сколько этажей имеет здание №14 на участке с ризалитом?", listOf("2", "3", "4", "5"), 2)
        ),
        listOf(
            Question("Какое название носит православный храм- памятник архитектуры дореволюционной России на улице Большой Морской?", listOf("Покровский собор", "Храм Святых Апостолов Петра и Павла", "Владимирский собор", "Свято-Никольский храм"), 0),
            Question("Когда была проведена масштабная реконструкция улицы Большая Морская?", listOf("В 1960-е годы", "В 1930-е годы.", "В 1970-е годы.", "В 1950-е годы."), 3),
            Question("Когда было восстановлено трамвайное движение по улице после войны?", listOf("В 1950 году", "В 1948 году", "В 1961 году", "В 1949 году"), 1)
        ),
        listOf(
            Question("Какие особенности оформления фасадов жилого дома № 7 на Большой Морской?", listOf("Оформление фасадов только в стиле конструктивизма без классических элементов.", "Использование пилястр и колонн ионического ордера с акцентированной центральной частью.", "Отсутствие оконных проемов на первом этаже здания.", "Все фасады здания имеют одинаковое оформление, без выделенной центральной части."), 1),
            Question("Какой особенностью отличается расположение жилого дома № 7 на Большой Морской?", listOf("Его восточный фасад примыкает к подпорной стенке спуска Шестакова.", "Оно является самым высоким зданием на улице.", "В его подвале сохранились средневековые катакомбы.", "Здание стоит отдельно, не примыкая к другим постройкам."), 0),
            Question("Здание стоит отдельно, не примыкая к другим постройкам.", listOf("И. И. Степанов", "А. А. Щербаков", "П. И. Орлов", "М. В. Герасимов"), 0)
        ),
        listOf(
            Question("Какой строительный материал использовался для облицовки зданий на Большой Морской улице, включая дом №6?", listOf("Красный кирпич", "Бетонные панели", "Инкерманский известняк", "Гранит"), 2),
            Question("Какой статус имеет здание по адресу Большая Морская, 6?", listOf("Обычный жилой дом", "Объект культурного наследия регионального значения", "Административное здание", "Памятник федерального значения"), 1),
            Question("К какому архитектурному стилю относится дом по адресу ул. Большая Морская, 6?", listOf("Барокко", "Сталинский классицизм", "Конструктивизм", "Готика"), 1)
        ),
        listOf(
            Question("Каковы архитектурные и градостроительные особенности дома № 3 на улице Большая Морская в Севастополе?", listOf("Здание формирует участок застройки, выходящий на площадь Лазарева, ограниченный Большой Морской улицей и спуском Шестакова, и является элементом целостного стилистического решения застройки.", "Дом № 3 является отдельно стоящим зданием, не связанным с окружающей застройкой.",
                    "Здание не имеет исторической и архитектурной ценности, так как было полностью перестроено в XXI веке.", "Дом построен в стиле модерн с асимметричным фасадом и большими витражными окнами."), 0),
            Question("Какие материалы использовались при строительстве жилого дома № 3, и какова его конструктивная система?", listOf("Какие материалы использовались при строительстве жилого дома № 3, и какова его конструктивная система?", "Конструкция здания полностью металлическая, с облицовкой современными композитными панелями.", "Здание построено из монолитного железобетона с несущими колоннами.", "Фундаменты и капитальные стены 1-го и 2-го этажей выполнены из бутового камня, 3-й этаж построен из евпаторийского ракушечника."), 3),
            Question("Назовите год постройки дома.", listOf("1949 г.", "1954 г.", "1938 г.", "1961 г."), 1)
        ),
        listOf(
            Question("Где расположен жилой дом № 4?", listOf("На западной стороне улицы Большая Морская, с уклоном к западу.", "На восточной стороне улицы Большая Морская, ближе к морю.", "В центре площади Нахимова.", "У побережья Севастополя."), 0),
            Question("В каком архитектурном стиле выполнен жилой дом № 4?", listOf("Готика", "Сталинский классицизм", "Конструктивизм", "Барокко"), 1),
            Question("Из какого материала выполнены верхние этажи дома № 4?", listOf("Из дерева", "Из стекла и металлоконструкций", "Из кирпича", "Из ракушечника"), 3)
        ),
        listOf(
            Question("Какое название ранее носил дом по адресу Большая Морская, 2?", listOf("Дом Хлудова", "Дом Головина", "Дом Михайлова", "Дом Рахманинова"), 0),
            Question("Какое известное культурное событие прошло в Городском собрании 9 января 1914 года?", listOf("Первый концерт оркестра Черноморского флота", "Вечер эгофутуристов с участием Маяковского и Северянина", "Выступление Фёдора Шаляпина", "Дебютный показ фильма «Броненосец Потёмкин»"), 1),
            Question("Какая организация размещалась в здании в 1926 году?", listOf("Морской кадетский корпус", "Исполнительный комитет горсовета со всеми отделами", "Севастопольский драматический театр", "Центральный рынок"), 1)
        )
    )


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quiz)

        loadNextBlock()
    }

    private fun loadNextBlock() {
        if (currentBlockIndex >= questionBlocks.size) {
            showResults()
            return
        }
        availableQuestions = questionBlocks[currentBlockIndex].toMutableList()
        usedQuestions.clear()
        showNextQuestion()
    }

    private fun showNextQuestion() {
        disableLeds()
        if (availableQuestions.isEmpty()) {
            loadNextBlock()
            return
        }
        val questionIndex = availableQuestions.indices.random()
        val question = availableQuestions.removeAt(questionIndex)
        findViewById<TextView>(R.id.questionTextView).text = question.text
        val buttons = listOf(
            findViewById<Button>(R.id.answerButton1),
            findViewById<Button>(R.id.answerButton2),
            findViewById<Button>(R.id.answerButton3),
            findViewById<Button>(R.id.answerButton4)
        )
        buttons.forEachIndexed { index, button ->
            button.text = question.answers[index]
            button.setOnClickListener { checkAnswer(index, question.correctAnswer) }
        }
    }

    private fun checkAnswer(selectedIndex: Int, correctIndex: Int) {
        if (selectedIndex == correctIndex) {
            score++
            when (currentLocation) {
                "Костел" -> {
                    sendCommandToArduino("4 0 1 0") // Верный ответ
                }
                "БМ 43" -> {
                    sendCommandToArduino("3 0 1 0") // Верный ответ
                }
                "БМ 50" -> {
                    sendCommandToArduino("1 0 1 0") // Верный ответ
                }
                "БМ 39" -> {
                    sendCommandToArduino("2 0 1 0") // Верный ответ
                }
                "БМ 33" -> {
                    sendCommandToArduino("5 0 1 0") // Верный ответ
                }
                "БМ 44" -> {
                    sendCommandToArduino("0 0 1 0") // Верный ответ
                }
                "БМ 34" -> {
                    sendCommandToArduino("9 0 1 0") // Верный ответ
                }
                "БМ 21" -> {
                    sendCommandToArduino("11 0 1 0") // Верный ответ
                }
                "БМ 26" -> {
                    sendCommandToArduino("7 0 1 0") // Верный ответ
                }
                "БМ 18" -> {
                    sendCommandToArduino("8 0 1 0") // Верный ответ
                }
                "БМ 14" -> {
                    sendCommandToArduino("10 0 1 0") // Верный ответ
                }
                "БМ 12/1" -> {
                    sendCommandToArduino("15 0 1 0") // Верный ответ
                }
                "БМ 7" -> {
                    sendCommandToArduino("6 0 1 0") // Верный ответ
                }
                "БМ 6" -> {
                    sendCommandToArduino("14 0 1 0") // Верный ответ
                }
                "БМ 3" -> {
                    sendCommandToArduino("12 0 1 0") // Верный ответ
                }
                "БМ 4" -> {
                    sendCommandToArduino("13 0 1 0") // Верный ответ
                }
                "БМ 2" -> {
                    sendCommandToArduino("16 0 1 0") // Верный ответ
                }
            }
            showDialog(true)
        } else {
            when (currentLocation) {
                "Костел" -> {
                    sendCommandToArduino("4 1 0 0") // Неверный ответ
                }
                "БМ 43" -> {
                    sendCommandToArduino("3 1 0 0") // Верный ответ
                }
                "БМ 50" -> {
                    sendCommandToArduino("1 1 0 0") // Верный ответ
                }
                "БМ 39" -> {
                    sendCommandToArduino("2 1 0 0") // Верный ответ
                }
                "БМ 33" -> {
                    sendCommandToArduino("5 1 0 0") // Верный ответ
                }
                "БМ 44" -> {
                    sendCommandToArduino("0 1 0 0") // Верный ответ
                }
                "БМ 34" -> {
                    sendCommandToArduino("9 1 0 0") // Верный ответ
                }
                "БМ 21" -> {
                    sendCommandToArduino("11 1 0 0") // Верный ответ
                }
                "БМ 26" -> {
                    sendCommandToArduino("7 1 0 0") // Верный ответ
                }
                "БМ 18" -> {
                    sendCommandToArduino("8 1 0 0") // Верный ответ
                }
                "БМ 14" -> {
                    sendCommandToArduino("10 1 0 0") // Верный ответ
                }
                "БМ 12/1" -> {
                    sendCommandToArduino("15 1 0 0") // Верный ответ
                }
                "БМ 7" -> {
                    sendCommandToArduino("6 1 0 0") // Верный ответ
                }
                "БМ 6" -> {
                    sendCommandToArduino("14 1 0 0") // Верный ответ
                }
                "БМ 3" -> {
                    sendCommandToArduino("12 1 0 0") // Верный ответ
                }
                "БМ 4" -> {
                    sendCommandToArduino("13 1 0 0")
                }
                "БМ 2" -> {
                    sendCommandToArduino("16 1 0 0")
                }
            }
            if (availableQuestions.isEmpty()) {
                showDialog(false, true) // Все вопросы использованы – принудительный переход
            } else {
                showDialog(false, false) // Обычный диалог с выбором
            }
        }
    }

    private fun showDialog(correct: Boolean, forceNextBlock: Boolean = false) {
        val builder = AlertDialog.Builder(this)
        if (correct) {
            builder.setTitle("Верно!")
                .setMessage("+1 очко")
                .setPositiveButton("OK") { _, _ -> moveToNextBlock() }
        } else {
            builder.setTitle("Неправильный ответ")
            if (forceNextBlock) {
                builder.setMessage("Все вопросы блока использованы.")
                    .setPositiveButton("OK") { _, _ -> moveToNextBlock() }
            } else {
                builder.setMessage("Выберите действие:")
                    .setPositiveButton("Ответить на другой вопрос") { _, _ -> showNextQuestion() }
                    .setNegativeButton("Перейти к следующему блоку") { _, _ -> moveToNextBlock() }
            }
        }
        builder.setCancelable(false).show()
    }

    private fun disableLeds() {
        when (currentLocation) {
            "Костел" -> {
                sendCommandToArduino("4 0 0 0") // Неверный ответ
            }
            "БМ 43" -> {
                sendCommandToArduino("3 0 0 0") // Верный ответ
            }
            "БМ 50" -> {
                sendCommandToArduino("1 0 0 0") // Верный ответ
            }
            "БМ 39" -> {
                sendCommandToArduino("2 0 0 0") // Верный ответ
            }
            "БМ 33" -> {
                sendCommandToArduino("5 0 0 0") // Верный ответ
            }
            "БМ 44" -> {
                sendCommandToArduino("0 0 0 0") // Верный ответ
            }
            "БМ 34" -> {
                sendCommandToArduino("9 0 0 0") // Верный ответ
            }
            "БМ 21" -> {
                sendCommandToArduino("11 0 0 0") // Верный ответ
            }
            "БМ 26" -> {
                sendCommandToArduino("7 0 0 0") // Верный ответ
            }
            "БМ 18" -> {
                sendCommandToArduino("8 0 0 0") // Верный ответ
            }
            "БМ 14" -> {
                sendCommandToArduino("10 0 0 0") // Верный ответ
            }
            "БМ 12/1" -> {
                sendCommandToArduino("15 0 0 0") // Верный ответ
            }
            "БМ 7" -> {
                sendCommandToArduino("6 0 0 0") // Верный ответ
            }
            "БМ 6" -> {
                sendCommandToArduino("14 0 0 0") // Верный ответ
            }
            "БМ 3" -> {
                sendCommandToArduino("12 0 0 0") // Верный ответ
            }
            "БМ 4" -> {
                sendCommandToArduino("13 0 0 0")
            }
            "БМ 2" -> {
                sendCommandToArduino("16 0 0 0")
            }
        }
    }

    private fun moveToNextBlock() {
        disableLeds()
        currentBlockIndex++
        startLoadingDialog()  // эта функция должна быть в этом же классе
    }

    private var commandRunnable: CommandRunnable? = null  // <-- Здесь тип именно CommandRunnable

    private class CommandRunnable(
        private val command1: String,
        private val command2: String,
        private val handler: Handler
    ) : Runnable {

        @Volatile
        private var isRunning = true

        fun stop() {
            isRunning = false
        }

        override fun run() {
            if (!isRunning) return

            sendCommandToArduino(command1)

            handler.postDelayed({
                sendCommandToArduino(command2)

                if (isRunning) {
                    handler.postDelayed(this, 500)
                }
            }, 500)
        }
    }

    // --- Диалог загрузки с управлением контрольными точками ---
    private fun startLoadingDialog() {
        isLoadingDialogActive = true

        currentLocationIndex = (currentLocationIndex + 1).coerceAtMost(locations.lastIndex)
        currentLocation = locations[currentLocationIndex]

        val handler = Handler(Looper.getMainLooper())
        var delayLoading: Long = 0

        when (currentLocation) {
            "Костел" -> {
                sendCommandToArduino("step -200")
                commandRunnable = CommandRunnable("4 1 1 0", "4 0 0 0", handler)
                delayLoading = 2000L
            }
            "БМ 43" -> {
                sendCommandToArduino("step -100")
                commandRunnable = CommandRunnable("3 1 1 0", "3 0 0 0", handler)
                delayLoading = 1000L
            }
            "БМ 50" -> {
                sendCommandToArduino("step -240")
                commandRunnable = CommandRunnable("1 1 1 0", "1 0 0 0", handler)
                delayLoading = 2400L
            }
            "БМ 39" -> {
                sendCommandToArduino("step -300")
                commandRunnable = CommandRunnable("2 1 1 0", "2 0 0 0", handler)
                delayLoading = 3000L
            }
            "БМ 33" -> {
                sendCommandToArduino("step -400")
                commandRunnable = CommandRunnable("5 1 1 0", "5 0 0 0", handler)
                delayLoading = 4000L
            }
            "БМ 44" -> {
                sendCommandToArduino("step -100")
                commandRunnable = CommandRunnable("0 1 1 0", "0 0 0 0", handler)
                delayLoading = 1000L
            }
            "БМ 34" -> {
                sendCommandToArduino("step -150")
                commandRunnable = CommandRunnable("9 1 1 0", "9 0 0 0", handler)
                delayLoading = 1500L
            }
            "БМ 21" -> {
                sendCommandToArduino("step -160")
                commandRunnable = CommandRunnable("11 1 1 0", "11 0 0 0", handler)
                delayLoading = 1600L
            }
            "БМ 26" -> {
                sendCommandToArduino("step -110")
                commandRunnable = CommandRunnable("7 1 1 0", "7 0 0 0", handler)
                delayLoading = 1100L
            }
            "БМ 18" -> {
                sendCommandToArduino("step -200")
                commandRunnable = CommandRunnable("8 1 1 0", "8 0 0 0", handler)
                delayLoading = 2000L
            }
            "БМ 14" -> {
                sendCommandToArduino("step -110")
                commandRunnable = CommandRunnable("10 1 1 0", "10 0 0 0", handler)
                delayLoading = 1100L
            }
            "БМ 12/1" -> {
                sendCommandToArduino("step -90")
                commandRunnable = CommandRunnable("15 1 1 0", "15 0 0 0", handler)
                delayLoading = 900L
            }
            "БМ 7" -> {
                sendCommandToArduino("step -90")
                commandRunnable = CommandRunnable("6 1 1 0", "6 0 0 0", handler)
                delayLoading = 900L
            }
            "БМ 6" -> {
                sendCommandToArduino("step -100")
                commandRunnable = CommandRunnable("14 1 1 0", "14 0 0 0", handler)
                delayLoading = 1000L
            }
            "БМ 3" -> {
                sendCommandToArduino("step -100")
                commandRunnable = CommandRunnable("12 1 1 0", "12 0 0 0", handler)
                delayLoading = 1000L

            }
            "БМ 4" -> {
                sendCommandToArduino("step -100")
                commandRunnable = CommandRunnable("13 1 1 0", "13 0 0 0", handler)
                delayLoading = 1000L
            }
            "БМ 2" -> {
                sendCommandToArduino("step -100")
                commandRunnable = CommandRunnable("16 1 1 0", "16 0 0 0", handler)
                delayLoading = 1000L
            }
            else -> {
                sendCommandToArduino("step 0")
                //commandRunnable = CommandRunnable("1 1 1 0", "1 0 0 0")
            }
        }

        handler.post(commandRunnable!!)


        val loadingDialog = AlertDialog.Builder(this)
            .setView(layoutInflater.inflate(R.layout.dialog_loading, null))
            .setCancelable(false)
            .show()

        handler.postDelayed({
            loadingDialog.dismiss()
            commandRunnable?.stop()
            handler.removeCallbacks(commandRunnable!!)
            commandRunnable = null
            loadNextBlock()
        }, delayLoading)
    }



    private fun showResults() {
        val intent = Intent(this, ResultsActivity::class.java)
        intent.putExtra("SCORE", score)
        startActivity(intent)
        finish()
    }
}


// --- Экран результатов ---
class ResultsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_results)

        val userName = getSharedPreferences("GameData", MODE_PRIVATE).getString("UserName", "Игрок")
        val score = intent.getIntExtra("SCORE", 0)

        findViewById<TextView>(R.id.resultsTextView).text = "$userName, ваш результат: $score"
        findViewById<Button>(R.id.mainMenuButton).setOnClickListener {
            sendCommandToArduino("step 2000")
            startActivity(Intent(this, MainActivity::class.java))
        }
    }
}

// --- Глобальные переменные для USB ---
var usbConnection: UsbDeviceConnection? = null
var usbEndpoint: UsbEndpoint? = null

fun sendCommandToArduino(command: String) {
    val formattedCommand = "$command\r\n"
    val buffer = formattedCommand.toByteArray(Charsets.UTF_8)

    usbConnection?.let { connection ->
        usbEndpoint?.let { endpoint ->
            val bytesSent = connection.bulkTransfer(endpoint, buffer, buffer.size, 1000)
            if (bytesSent >= 0) {
                Log.d("USB", "Команда отправлена: ${String(buffer)}")
            } else {
                Log.e("USB", "Ошибка отправки: $bytesSent")
            }
        } ?: Log.e("USB", "Endpoint не инициализирован")
    } ?: Log.e("USB", "Соединение не установлено")
}
