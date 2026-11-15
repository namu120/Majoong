package app.majung.car.sdk

// ─────────────────────────────────────────────────────────────────────────────
// Pleos Connect SDK 어댑터.
//
// 시그니처는 실제 SDK 라이브러리(NaviHelper 3.0.5, FusedLocation 1.1.3, Vehicle 2.3.2, TextToSpeech·SpeechToText 3.3.1)를
// 열어 확인했고 컴파일된다. 다만 **에뮬레이터에서 실행해 본 적은 없다.** 아래 "실행 검증 필요" 항목은 동작을 추정한 것이다.
//
// 실행 검증 필요
//  - requestRoute 성공을 뜻하는 콜백이 onRoutingCompleted 인지 onDestinationChanged 인지 (둘 다 성공으로 받는다)
//  - poiId 를 비워도 목적지가 설정되는지
//  - 주행 규제 중 STT request() 가 허용되는지, ON_DEVICE 모드의 한국어 지원
//  - 비상등 전용 API 가 SDK 에 없다 → 탑승자 화면의 비상등 표시는 지금 연결되지 않는다
// ─────────────────────────────────────────────────────────────────────────────

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.majung.car.core.Spot
import app.majung.car.core.TurnSignal
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode

private const val TAG = "Majung"
private val main = Handler(Looper.getMainLooper())

// ── NaviHelper ───────────────────────────────────────────────────────────────
class PleosNavi(context: Context) : NaviPort {
    private val navi = NaviHelper(context.applicationContext)
    private var pending: ((Boolean) -> Unit)? = null
    private var timeout: Runnable? = null
    private var remainingM: Int? = null
    private var etaSec: Int? = null
    private var nextTurn: String? = null
    override var onArrived: (() -> Unit)? = null
    override var onProgress: ((Int?, Int?, String?) -> Unit)? = null

    private fun finish(ok: Boolean) {
        main.post {
            timeout?.let(main::removeCallbacks); timeout = null
            pending?.also { pending = null }?.invoke(ok)
        }
    }
    private fun publish() { main.post { onProgress?.invoke(remainingM, etaSec, nextTurn) } }

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { Log.d(TAG, "onRouteStarted $routeStartInfo") }
        // 먼저 온 쪽에서 한 번만 처리된다(finish 가 pending 을 비운다).
        override fun onRoutingCompleted() { Log.d(TAG, "onRoutingCompleted"); finish(true) }
        override fun onDestinationChanged() { Log.d(TAG, "onDestinationChanged"); finish(true) }
        override fun onError(errorCode: NaviErrorCode) { Log.w(TAG, "navi onError $errorCode"); finish(false) }
        override fun onDestinationArrived(info: DestinationArrivedInfo) { main.post { onArrived?.invoke() } }
        // 남은 거리·도착 예정. 단위(m·초 추정)는 에뮬레이터 로그로 확인할 것.
        override fun onTripInfo(info: TripInfo) { Log.d(TAG, "onTripInfo $info"); remainingM = info.distance; etaSec = info.eta; publish() }
        override fun onTBTInfo(list: List<TBTInfo>) {
            val t = list.firstOrNull() ?: return
            val dir = when (t.type) {
                TurnType.TURN_LEFT, TurnType.TURN_SHARP_L, TurnType.TURN_KEEP_L -> "좌회전"
                TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R, TurnType.TURN_KEEP_R -> "우회전"
                TurnType.TURN_UTURN -> "유턴"
                TurnType.TURN_STRAIGHT -> "직진"
                else -> null
            }
            nextTurn = dir?.let { "${t.distance.toInt()} m 앞 $it" }; publish()
        }
    }

    override fun start(onReady: (Boolean) -> Unit) {
        navi.initialize(
            onConnected = { navi.addListener(listener); main.post { onReady(true) } },
            onFailed = { t -> Log.e(TAG, "navi init failed", t); main.post { onReady(false) } },
        )
    }

    override fun requestRoute(spot: Spot, timeoutMs: Long, onResult: (Boolean) -> Unit) {
        finish(false)                                    // 이전 요청이 남아 있으면 실패로 닫는다
        main.post {
            pending = onResult
            timeout = Runnable { Log.w(TAG, "route timeout"); finish(false) }.also { main.postDelayed(it, timeoutMs) }
            runCatching {
                navi.requestRoute(RouteInfo(latitude = spot.lat, longitude = spot.lng, poiName = spot.name, poiId = "", poiSubId = "0", address = spot.memo))
            }.onFailure { Log.e(TAG, "requestRoute", it); finish(false) }
        }
    }

    override fun cancelRoute() { runCatching { navi.cancelRoute() }; remainingM = null; etaSec = null; nextTurn = null }
    override fun stop() { navi.removeListener(listener); navi.release() }
}

// ── FusedLocation ────────────────────────────────────────────────────────────
class PleosLocation(context: Context) : LocationPort {
    private val fused = FusedLocation(context.applicationContext)
    private var cb: ((Fix) -> Unit)? = null
    private var initialized = false

    private val listener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return                               // 위치를 쓸 수 없으면 null 이 온다
            val fix = Fix(location.latitude, location.longitude, location.heading.toDouble(), location.timestampMillis)
            main.post { cb?.invoke(fix) }
        }
        override fun onError(e: Exception) { Log.w(TAG, "location error", e) }
    }

    override fun start(onFix: (Fix) -> Unit): Boolean {
        if (runCatching { fused.isSupported() }.getOrDefault(false).not()) return false     // 미지원이면 위치 기능을 숨긴다
        if (!initialized) { fused.initialize(); initialized = true }
        cb = onFix
        fused.registerFusedLocationCallback(listener)
        return true
    }
    override fun stop() { if (cb != null) { cb = null; runCatching { fused.unregisterFusedLocationCallback(listener) } } }
    // 근접 판정은 PickupMachine 이 좌표로 직접 한다(150 m). addProximityAlert(ProximityAlertRequest, ProximityAlertListener) 로 바꿀 수 있다.
}

// ── TextToSpeech / SpeechToText ──────────────────────────────────────────────
class PleosVoice(context: Context) : VoicePort {
    // ON_DEVICE: 네트워크·앱 등록이 필요 없다. 한국어 품질이 부족하면 HYBRID + registerApp 으로 바꾼다.
    private val tts = TextToSpeech(context.applicationContext, TtsMode.ON_DEVICE)
    private val stt = SpeechToText(context.applicationContext, SttMode.ON_DEVICE)
    private val queue = ArrayDeque<Pair<String, () -> Unit>>()
    private var speaking = false
    private var ttsReady = false
    private var heard: ((String) -> Unit)? = null
    private var failed: (() -> Unit)? = null
    private var listenTimeout: Runnable? = null

    private val ttsListener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() { main.post { next() } }
        override fun onStop() { main.post { next() } }
        override fun onError(errorType: TtsError) { Log.w(TAG, "tts $errorType"); main.post { next() } }
    }
    private val sttListener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdated(stt: String, completed: Boolean) { if (completed) main.post { endListen(stt) } }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() { main.postDelayed({ endListen(null) }, 300) }      // 최종 결과 없이 끝나면 실패
        override fun onError(errorType: SttError) { Log.w(TAG, "stt $errorType"); main.post { endListen(null) } }
    }

    override fun start() {
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                tts.addEventListener(ttsListener)
                runCatching { tts.setLocale(Locale.KOREA) }
                // 오디오 포커스 가이드: 내비 안내·음악과 공존(duck)하는 안내 음성으로 분류한다.
                runCatching {
                    tts.setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                }
                main.post { ttsReady = true; pump() }
            }
            override fun onFailure(errorType: TtsError) { Log.e(TAG, "tts init $errorType") }
        })
        stt.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
            override fun onSuccess() { stt.addListener(sttListener); runCatching { stt.setLocale(Locale.KOREA) } }
            override fun onFailure(errorType: SttError) { Log.e(TAG, "stt init $errorType") }
        })
    }

    override fun speak(text: String, onDone: () -> Unit) { main.post { queue.addLast(text to onDone); pump() } }

    private fun pump() {
        if (speaking) return
        val head = queue.firstOrNull() ?: return
        if (!ttsReady) { queue.removeFirst(); head.second(); pump(); return }      // TTS 를 못 쓰면 화면 안내만으로 흐름을 잇는다
        speaking = true
        // 선행 묵음 가이드(150~200 ms): SDK 가 오디오 데이터를 내주지 않아 앱에서 무음을 붙일 수 없다. 첫 음절 잘림은 실행 검증 항목.
        runCatching { tts.speak(head.first, true) }.onFailure { main.post { next() } }
    }
    private fun next() {
        if (!speaking) return
        speaking = false
        queue.removeFirstOrNull()?.second?.invoke()
        pump()
    }

    override fun listen(timeoutMs: Long, onText: (String) -> Unit, onFail: () -> Unit) {
        main.post {
            heard = onText; failed = onFail
            listenTimeout = Runnable { endListen(null) }.also { main.postDelayed(it, timeoutMs) }
            runCatching { stt.request() }.onFailure { endListen(null) }      // 실패하면 화면 버튼 또는 무응답 처리로 넘어간다
        }
    }
    private fun endListen(text: String?) {
        val ok = heard; val ng = failed
        if (ok == null && ng == null) return                                 // 이미 끝난 세션
        heard = null; failed = null
        listenTimeout?.let(main::removeCallbacks); listenTimeout = null
        runCatching { stt.stop() }                                           // 마이크 반납
        if (text.isNullOrBlank()) ng?.invoke() else ok?.invoke(text)
    }
    override fun stopListening() { main.post { heard = null; failed = null; listenTimeout?.let(main::removeCallbacks); runCatching { stt.stop() } } }

    override fun stop() {
        runCatching { tts.stop() }
        tts.removeEventListener(ttsListener); tts.release()
        stt.removeListener(sttListener); stt.release()
    }
}

// ── Vehicle ──────────────────────────────────────────────────────────────────
class PleosVehicle(context: Context) : VehiclePort {
    private val vehicle = Vehicle(context.applicationContext)
    private var turnListener: TurnSignalStateListener? = null
    private var gearListener: GearSelectionListener? = null

    /**
     * SDK 에 주차 브레이크 API 는 없고(Brake 는 브레이크 페달만) 기어 API 가 있다 → 기어 P 를 "주차"로 본다.
     * 비상등 전용 API 도 없어 onHazard 는 연결하지 않는다(탑승자 화면에서 숨겨진다).
     * 종료 제안(onBoardingHint)은 Door·Seat(SeatOccupancy, SeatBeltBuckledState) 리스너로 구현할 수 있으나 아직 연결하지 않았다.
     */
    override fun start(onHazard: (Boolean) -> Unit, onTurnSignal: (TurnSignal) -> Unit, onParkBrake: (Boolean) -> Unit, onBoardingHint: () -> Unit) {
        runCatching {
            vehicle.initialize()
            val turn = { v: TurnSignalValue? -> main.post { onTurnSignal(when (v) { TurnSignalValue.LEFT -> TurnSignal.LEFT; TurnSignalValue.RIGHT -> TurnSignal.RIGHT; else -> TurnSignal.OFF }) }; Unit }
            val gear = { g: VehicleGear? -> main.post { onParkBrake(g == VehicleGear.GEAR_PARK) }; Unit }
            vehicle.getTurnSignal().apply {
                getCurrentTurnSignalState(turn) { Log.w(TAG, "turn signal 미지원", it) }
                registerTurnSignalState(object : TurnSignalStateListener { override fun onTurnSignalStateUpdated(value: TurnSignalValue?) { turn(value) }; override fun onFailed(e: Exception) { Log.w(TAG, "vehicle listener", e) } }.also { turnListener = it })
            }
            vehicle.getGear().apply {
                getCurrentGearSelectionState(gear) { Log.w(TAG, "gear 미지원", it) }
                registerGearSelection(object : GearSelectionListener { override fun onSelected(value: VehicleGear?) { gear(value) }; override fun onFailed(e: Exception) { Log.w(TAG, "vehicle listener", e) } }.also { gearListener = it })
            }
        }.onFailure { Log.w(TAG, "Vehicle SDK 사용 불가", it) }
    }

    override fun stop() {
        runCatching {
            turnListener?.let { vehicle.getTurnSignal().unregisterTurnSignalState(it) }
            gearListener?.let { vehicle.getGear().unregisterGearSelection(it) }
            vehicle.release()
        }
    }
}
