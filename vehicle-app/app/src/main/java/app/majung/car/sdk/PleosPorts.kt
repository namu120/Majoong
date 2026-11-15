package app.majung.car.sdk

// ─────────────────────────────────────────────────────────────────────────────
// Pleos Connect SDK 어댑터.
//
// ⚠ 이 파일은 컴파일해 보지 못했다. SDK 를 받을 수 없는 환경에서 공개 문서(2026-11-15)만 보고 썼다.
//   [확인] = 문서의 코드 예시에 그대로 나온 호출
//   [추정] = 문서에 이름만 있고 시그니처가 없는 호출 → Android Studio 자동완성 / API Reference 로 맞출 것
//   import 가 빠진 SDK 타입은 IDE 의 자동 import 로 채운다(패키지 경로가 문서에 없음).
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode                      // [확인]
import ai.pleos.playground.navi.data.RouteStartInfo                         // [확인]
import ai.pleos.playground.navi.helper.NaviHelper                           // [확인]
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener     // [확인]
import ai.pleos.playground.stt.SpeechToText                                 // [확인]
import ai.pleos.playground.tts.TextToSpeech                                 // [확인]
import ai.pleos.playground.tts.listener.OnInitializeListener                // [확인]
import app.majung.car.core.Spot
import app.majung.car.core.TurnSignal

private const val TAG = "Majung"
private val main = Handler(Looper.getMainLooper())

// ── NaviHelper v3.0.5 ────────────────────────────────────────────────────────
class PleosNavi(private val context: Context) : NaviPort {
    private val navi = NaviHelper(context.applicationContext)                // [확인]
    private var pending: ((Boolean) -> Unit)? = null
    private var timeout: Runnable? = null
    override var onArrived: (() -> Unit)? = null
    override var onProgress: ((Int?, Int?, String?) -> Unit)? = null

    private fun finish(ok: Boolean) = main.post {
        timeout?.let(main::removeCallbacks); timeout = null
        pending?.also { pending = null }?.invoke(ok)
    }

    // 리스너 인터페이스의 메서드는 문서에 3개만 예시로 나온다. 나머지는 IDE 의 "Implement members" 로 채우고
    // 아래 주석의 연결만 해 주면 된다.
    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { Log.d(TAG, "onRouteStarted $routeStartInfo") }   // [확인]
        // M1 검증: requestRoute 성공을 뜻하는 콜백이 onRoutingCompleted 인지 onDestinationChanged 인지 로그로 확인.
        //          둘 다 온다면 먼저 온 쪽에서 finish(true) 가 한 번만 처리된다(pending 을 비우므로 안전).
        override fun onRoutingCompleted() { Log.d(TAG, "onRoutingCompleted"); finish(true) }                           // [확인]
        override fun onError(errorCode: NaviErrorCode) { Log.w(TAG, "navi onError $errorCode"); finish(false) }        // [확인]

        // [추정] 아래는 문서에 콜백 이름만 있다. 시그니처를 맞춘 뒤 주석을 풀 것.
        // override fun onDestinationChanged() { finish(true) }
        // override fun onDestinationArrived(/* DestinationArrivedInfo? */) { main.post { onArrived?.invoke() } }
        // override fun onRouteStateInfo(info: RouteStateInfo) { Log.d(TAG, "$info")  /* 남은 거리·시간 필드가 있으면 onProgress 로 */ }
        // override fun onTBTInfo(info: TBTInfo) { Log.d(TAG, "$info")               /* 다음 회전 → onProgress(nextTurn) */ }
    }

    override fun start(onReady: (Boolean) -> Unit) {
        navi.initialize(                                                     // [확인]
            onConnected = { navi.addListener(listener); main.post { onReady(true) } },
            onFailed = { t -> Log.e(TAG, "navi init failed", t); main.post { onReady(false) } },
        )
    }

    override fun requestRoute(spot: Spot, timeoutMs: Long, onResult: (Boolean) -> Unit) {
        finish(false)                                                        // 이전 요청이 남아 있으면 실패로 닫는다
        main.post {
            pending = onResult
            timeout = Runnable { Log.w(TAG, "route timeout"); finish(false) }.also { main.postDelayed(it, timeoutMs) }
            // [확인] 문서 예시의 인자 이름 그대로. import 는 RouteInfo 자동완성으로.
            navi.requestRoute(
                RouteInfo(
                    latitude = spot.lat, longitude = spot.lng,
                    displayLatitude = null, displayLongitude = null,
                    poiName = spot.name, poiId = "", poiSubId = "0",       // M1 검증: poiId 를 비워도 되는지
                    address = spot.memo,
                )
            )
        }
    }

    override fun cancelRoute() { navi.cancelRoute() }                        // [확인]
    override fun stop() { navi.removeListener(listener); navi.release() }    // [확인]
}

// ── FusedLocation v1.1.2 ─────────────────────────────────────────────────────
class PleosLocation(private val context: Context) : LocationPort {
    private val fused = FusedLocation(context.applicationContext)            // [확인]
    private var cb: ((Fix) -> Unit)? = null

    private val listener = object : FusedLocationListener {                  // [확인] onChanged(LocationData?), onError(Exception)
        override fun onChanged(location: LocationData?) {
            location ?: return                                               // 위치를 쓸 수 없으면 null 이 온다
            // [추정] 필드 이름. 문서에는 "latitude, longitude, altitude, heading, confidence, timestamp"가 있다고만 나온다.
            val fix = Fix(location.latitude, location.longitude, location.heading?.toDouble(), location.timestamp)
            main.post { cb?.invoke(fix) }
        }
        override fun onError(e: Exception) { Log.w(TAG, "location error", e) }
    }

    override fun start(onFix: (Fix) -> Unit): Boolean {
        if (!fused.isSupported()) return false                               // [확인] 미지원이면 위치 기능을 숨긴다
        fused.initialize()                                                   // [확인]
        cb = onFix
        fused.registerFusedLocationCallback(listener)                        // [확인]
        return true
    }
    override fun stop() { cb = null; fused.unregisterFusedLocationCallback(listener); fused.release() }   // [확인]
    // addProximityAlert 는 시그니처가 문서에 없어 쓰지 않았다. 근접 판정은 PickupMachine 이 좌표로 직접 한다(150 m).
}

// ── TextToSpeech / SpeechToText v3.3.1 ───────────────────────────────────────
class PleosVoice(private val context: Context) : VoicePort {
    // ON_DEVICE: 네트워크·앱 등록이 필요 없어 변수가 가장 적다. 한국어 품질이 부족하면 HYBRID + 앱 등록으로 바꾼다.
    private val tts = TextToSpeech(context.applicationContext, ai.pleos.playground.tts.constant.Mode.ON_DEVICE)   // [확인]
    private val stt = SpeechToText(context.applicationContext, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)   // [확인]
    private val queue = ArrayDeque<Pair<String, () -> Unit>>()
    private var speaking = false
    private var ttsReady = false
    private var heard: ((String) -> Unit)? = null
    private var failed: (() -> Unit)? = null
    private var listenTimeout: Runnable? = null

    private val ttsListener = object : ai.pleos.playground.tts.listener.EventListener {      // [추정] 패키지. 콜백 6개는 [확인]
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() { main.post { next() } }
        override fun onStop() { main.post { next() } }
        override fun onError(errorType: ai.pleos.playground.tts.constant.ErrorType) { Log.w(TAG, "tts $errorType"); main.post { next() } }
    }
    private val sttListener = object : ai.pleos.playground.stt.listener.ResultListener {     // [추정] 패키지. 콜백 7개는 [확인]
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdated(stt: String, completed: Boolean) { if (completed) main.post { endListen(stt) } }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() { main.postDelayed({ endListen(null) }, 300) }      // 최종 결과 없이 끝나면 실패
        override fun onError(errorType: ai.pleos.playground.stt.constant.ErrorType) { Log.w(TAG, "stt $errorType"); main.post { endListen(null) } }
    }

    override fun start() {
        tts.initialize(object : OnInitializeListener {                       // [확인]
            override fun onSuccess() {
                tts.addEventListener(ttsListener)                            // [확인]
                // tts.setLocale(Locale.KOREA)                               // [추정] M1 검증: 한국어 지원·기본 로캘
                main.post { ttsReady = true; pump() }
            }
            override fun onFailure(errorType: ai.pleos.playground.tts.constant.ErrorType) { Log.e(TAG, "tts init $errorType") }
        })
        stt.initialize()                                                     // [추정] TTS 와 같은 형태일 것
        stt.addListener(sttListener)                                         // [확인]
    }

    override fun speak(text: String, onDone: () -> Unit) { main.post { queue.addLast(text to onDone); pump() } }

    private fun pump() {
        if (speaking || !ttsReady) return
        val (text, _) = queue.firstOrNull() ?: return
        speaking = true
        // 선행 묵음 가이드(150~200 ms): SDK 가 오디오 데이터를 내주지 않으므로 앱에서 무음을 붙일 수 없다.
        // M1 검증: 첫 음절이 잘리는지 녹음으로 확인. 잘리면 사무국에 문의(직접 AudioTrack 재생은 범위 밖).
        tts.speak(text)                                                      // [확인]
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
            // M1 검증: 기어 D(주행 규제 on)에서 request() 가 되는지. 안 되면 onError → 화면 버튼 또는 무응답 처리로 넘어간다.
            runCatching { stt.request() }.onFailure { endListen(null) }      // [확인]
        }
    }
    private fun endListen(text: String?) {
        val ok = heard; val ng = failed
        if (ok == null && ng == null) return                                 // 이미 끝난 세션
        heard = null; failed = null
        listenTimeout?.let(main::removeCallbacks); listenTimeout = null
        runCatching { stt.stop() }                                           // [확인] 마이크 반납
        if (text.isNullOrBlank()) ng?.invoke() else ok?.invoke(text)
    }
    override fun stopListening() { main.post { heard = null; failed = null; listenTimeout?.let(main::removeCallbacks); runCatching { stt.stop() } } }

    override fun stop() {
        runCatching { tts.stop() }                                           // [확인]
        tts.removeEventListener(ttsListener); tts.release()                  // [확인]
        stt.removeListener(sttListener); stt.release()                       // [확인] removeListener / [추정] release
    }
}

// ── Vehicle v2.3.2 ───────────────────────────────────────────────────────────
class PleosVehicle(private val context: Context) : VehiclePort {
    private val vehicle = Vehicle(context.applicationContext)                // [확인]

    override fun start(onHazard: (Boolean) -> Unit, onTurnSignal: (TurnSignal) -> Unit, onParkBrake: (Boolean) -> Unit, onBoardingHint: () -> Unit) {
        vehicle.initialize()                                                 // [확인]
        // 문서에 나온 것은 도메인 접근(getXxx)과 capability 패턴뿐이다:
        //   val door = vehicle.getDoor()
        //   door.checkDoorPositionCapabilities(onComplete = { config, areaMap -> config.accessType … }, onFailed = { e -> … })
        // 아래 세 묶음은 같은 패턴으로 M1 에서 채운다. capability 가 READ 가 아니면 콜백을 아예 연결하지 않는다
        // → 서버에 null 로 올라가고 탑승자 화면에서 해당 표시가 숨겨진다.
        //
        // 1) 비상등·방향지시등  (권한 CAR_LIGHTS)        vehicle.getTurnSignal() / vehicle.getLight()
        //      → onTurnSignal(LEFT|RIGHT|OFF), onHazard(true|false)
        //      ※ 비상등 전용 API 가 있는지 미확인. 없으면 좌·우가 동시에 켜진 상태를 비상등으로 본다.
        // 2) 주차 브레이크      (권한 CAR_DRIVE)         vehicle.getBrake()
        //      → onParkBrake(true|false)   출발 감지의 보조 신호
        // 3) 종료 제안 (M3, 선택) 도어 열림→닫힘 뒤 새 좌석 착좌 또는 안전벨트 체결
        //      vehicle.getDoor() / vehicle.getSeat() / vehicle.getSafety()
        //      → onBoardingHint()   "픽업을 종료할까요?" 제안만 한다. 탑승 판정이 아니다.
        Log.i(TAG, "Vehicle 신호 연결은 M1 검증 후 구현")
    }
    override fun stop() { vehicle.release() }                                // [확인] 비동기 콜백이 끝난 뒤에 호출할 것
}
