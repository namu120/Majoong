package app.majung.car

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import app.majung.car.core.CarState
import app.majung.car.core.Effect
import app.majung.car.core.Event
import app.majung.car.core.Geo
import app.majung.car.core.MachineState
import app.majung.car.core.Member
import app.majung.car.core.PickupMachine
import app.majung.car.core.PickupStatus
import app.majung.car.core.TurnSignal
import app.majung.car.data.ApiError
import app.majung.car.data.SyncApi
import app.majung.car.sdk.Fix
import app.majung.car.sdk.LocationPort
import app.majung.car.sdk.MockLocation
import app.majung.car.sdk.NaviPort
import app.majung.car.sdk.PleosLocation
import app.majung.car.sdk.PleosNavi
import app.majung.car.sdk.PleosVehicle
import app.majung.car.sdk.PleosVoice
import app.majung.car.sdk.VehiclePort
import app.majung.car.sdk.VoicePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeState(
    val members: List<Member> = emptyList(),
    val inviteUrl: String? = null,
    val offline: Boolean = false,
    /** 주행 규제 중(CarUxRestrictions). true 면 QR·가족 선택·설정을 숨기고 큰 글씨 안내만 보인다. */
    val restricted: Boolean = false,
    /** 차량 신호가 "방금 탔을 수 있다"고 알려 온 상태 → 종료 제안 버튼 강조 */
    val boardingHint: Boolean = false,
)

class MajungApp : Application() {
    lateinit var controller: PickupController
    override fun onCreate() {
        super.onCreate()
        val location: LocationPort = if (BuildConfig.MOCK_LOCATION) MockLocation() else PleosLocation(this)
        controller = PickupController(this, SyncApi(BuildConfig.SERVER_URL), PleosNavi(this), location, PleosVehicle(this), PleosVoice(this))
        controller.start()
    }
}

/**
 * PickupMachine 의 Effect 를 실제로 실행하고, SDK·서버에서 일어난 일을 Event 로 되돌려 준다.
 * 화면은 state / home 두 흐름만 구독한다.
 */
class PickupController(
    private val context: Context,
    private val api: SyncApi,
    private val navi: NaviPort,
    private val location: LocationPort,
    private val vehicle: VehiclePort,
    private val voice: VoicePort,
) {
    val state = MutableStateFlow(MachineState())
    val home = MutableStateFlow(HomeState())
    /** 차량 진행 방향 기준으로 만남 지점이 있는 방향(도). 시안 ③⑤의 파란 화살표 회전값. heading 이 없으면 null */
    val arrow = MutableStateFlow<Float?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val net = Channel<suspend () -> Unit>(Channel.UNLIMITED)     // 서버 호출은 보낸 순서대로 하나씩
    private val prefs = context.getSharedPreferences("majung", Context.MODE_PRIVATE)
    private var pollJob: Job? = null
    private var lastRev = -1
    private var fix: Fix? = null
    private var hazard: Boolean? = null
    private var turn: TurnSignal? = null
    private var parked: Boolean? = null
    private var progress: Triple<Int?, Int?, String?> = Triple(null, null, null)
    private var car: Car? = null

    fun start() {
        scope.launch(Dispatchers.IO) { for (job in net) runCatching { job() }.onFailure { Log.w("Majung", "net", it) } }
        voice.start()
        navi.onArrived = { dispatch(Event.Arrived) }
        navi.onProgress = { m, s, t -> progress = Triple(m, s, t) }
        navi.start { ok -> if (!ok) Log.e("Majung", "NaviHelper 연결 실패") }
        vehicle.start(
            onHazard = { hazard = it },
            onTurnSignal = { turn = it },
            onParkBrake = { parked = it; dispatch(Event.ParkBrake(it)) },
            onBoardingHint = { if (state.value.pickup?.status == PickupStatus.NEAR) home.value = home.value.copy(boardingHint = true) },
        )
        watchUxRestrictions()
        scope.launch { ensureRegistered(); refreshHome() }
        scope.launch { while (isActive) { uploadCarState(); delay(1000) } }
    }

    // ── 화면에서 부르는 것 ──
    fun dispatch(e: Event) {
        val (next, effects) = PickupMachine.reduce(state.value, e)
        state.value = next
        effects.forEach(::run)
    }

    fun startPickup(memberId: String, spotId: String?) = scope.launch {
        runCatching { withContext(Dispatchers.IO) { api.startPickup(memberId, spotId) } }
            .onSuccess { p -> lastRev = -1; onSnapshot(p); beginPolling(p.pickupId) }
            .onFailure { home.value = home.value.copy(offline = true) }
    }

    /** 주차 중에만 호출한다(주행 중 QR 연결 금지). */
    fun newInvite() = scope.launch {
        runCatching { withContext(Dispatchers.IO) { api.createInvite() } }.onSuccess { home.value = home.value.copy(inviteUrl = it.url) }
    }

    fun refreshHome() = scope.launch {
        runCatching { withContext(Dispatchers.IO) { api.me() } }
            .onSuccess { me ->
                home.value = home.value.copy(members = me.members, offline = false)
                if (me.activePickupId != null && pollJob?.isActive != true) beginPolling(me.activePickupId)   // 앱 재시작·재접속 복구
            }
            .onFailure { home.value = home.value.copy(offline = true) }
    }

    // ── Effect 실행 ──
    private fun run(fx: Effect) {
        when (fx) {
            is Effect.Speak -> voice.speak(fx.text) { if (fx.tag.isNotEmpty()) dispatch(Event.TtsDone(fx.tag)) }
            is Effect.Listen -> voice.listen(fx.timeoutMs, onText = { dispatch(Event.Heard(it)) }, onFail = { dispatch(Event.ListenFailed) })
            Effect.StopListen -> voice.stopListening()
            is Effect.RequestRoute -> {
                (location as? MockLocation)?.setTarget(fx.spot.lat, fx.spot.lng)
                navi.requestRoute(fx.spot, fx.timeoutMs) { ok -> dispatch(if (ok) Event.RouteOk else Event.RouteFailed) }
            }
            Effect.CancelRoute -> navi.cancelRoute()
            is Effect.Resolve -> pickupId()?.let { id ->
                net.trySend {
                    // 통신이 끊겨도 결과는 반드시 전달한다. 409 는 "그 사이 새 요청이 왔다"는 뜻이므로 버린다.
                    repeat(30) {
                        try { onSnapshot(api.resolve(id, fx.seq, fx.result)); return@trySend }
                        catch (e: ApiError) { if (e.status in 400..499) return@trySend }
                        catch (e: Exception) { delay(2000) }
                    }
                }
            }
            is Effect.PostStatus -> pickupId()?.let { id ->
                net.trySend {
                    repeat(30) {
                        try { onSnapshot(api.postStatus(id, fx.status, fx.suggested)); return@trySend }
                        catch (e: ApiError) { if (e.status in 400..499) return@trySend }
                        catch (e: Exception) { delay(2000) }
                    }
                }
            }
        }
    }

    private fun pickupId() = state.value.pickup?.pickupId

    private suspend fun onSnapshot(p: app.majung.car.core.Pickup) = withContext(Dispatchers.Main.immediate) {
        if (p.rev <= lastRev && p.pickupId == pickupId()) return@withContext     // 순서가 뒤바뀐 응답은 버린다
        lastRev = p.rev
        dispatch(Event.Snapshot(p))
        (location as? MockLocation)?.moving = p.status == PickupStatus.EN_ROUTE || p.status == PickupStatus.NEAR
        if (p.status.isTerminal) {
            pollJob?.cancel(); lastRev = -1
            home.value = home.value.copy(boardingHint = false)
            location.stop()
            context.stopService(Intent(context, PickupService::class.java))      // 사용자에게 줄 기능이 없으면 FGS 를 내린다
            refreshHome()
        }
    }

    private fun beginPolling(pickupId: String) {
        context.startForegroundService(Intent(context, PickupService::class.java))
        if (!location.start { f ->
                fix = f; dispatch(Event.Location(f.lat, f.lng))
                val spot = state.value.pickup?.confirmedSpot
                arrow.value = if (spot != null && f.heading != null) (Geo.bearing(f.lat, f.lng, spot.lat, spot.lng) - f.heading).toFloat() else null
            }) Log.w("Majung", "FusedLocation 미지원 → 위치 공유 숨김")
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                try {
                    val p = withContext(Dispatchers.IO) { api.getPickup(pickupId, lastRev.takeIf { it >= 0 }) }
                    home.value = home.value.copy(offline = false)
                    onSnapshot(p)
                } catch (e: ApiError) {
                    if (e.status == 404 || e.status == 403) {          // 서버에서 이미 정리된 픽업 → 만료로 닫는다
                        state.value.pickup?.let { onSnapshot(it.copy(status = PickupStatus.EXPIRED, rev = lastRev + 1)) }
                        break
                    }
                } catch (e: Exception) {
                    // 끊기면 마지막으로 확정한 목적지로 안내를 유지하고, 화면에만 지연을 표시한다.
                    home.value = home.value.copy(offline = true); delay(2000)
                }
            }
        }
    }

    private fun uploadCarState() {
        val id = pickupId() ?: return
        val f = fix ?: return
        val (remaining, eta, nextTurn) = progress
        val body = CarState(f.lat, f.lng, f.heading, hazard, turn, parked,
            remainingM = remaining ?: state.value.distanceM, etaSec = eta, nextTurn = nextTurn, mock = f.mock, ts = f.ts)
        net.trySend { runCatching { api.putCar(id, body) } }                     // 실패한 좌표는 재전송하지 않는다(다음 초에 최신 값이 간다)
    }

    private suspend fun ensureRegistered() {
        api.token = prefs.getString("carToken", null)
        while (api.token == null) {
            runCatching { withContext(Dispatchers.IO) { api.registerCar("우리 차") } }
                .onSuccess { (_, token) -> prefs.edit().putString("carToken", token).apply(); api.token = token }
                .onFailure { home.value = home.value.copy(offline = true); delay(3000) }
        }
    }

    /** 주행 규제 개발 가이드: CarUxRestrictions 를 구독해 주행 중에는 입력을 줄인다. */
    private fun watchUxRestrictions() {
        runCatching {
            car = Car.createCar(context)
            val mgr = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            val apply = { r: android.car.drivingstate.CarUxRestrictions -> home.value = home.value.copy(restricted = r.isRequiresDistractionOptimization) }
            mgr.registerListener { apply(it) }
            mgr.currentCarUxRestrictions?.let(apply)
        }.onFailure { Log.w("Majung", "CarUxRestrictions 사용 불가", it) }
    }
}

/** 픽업 진행 중에만 유지되는 포그라운드 서비스. 화면이 가려져도 위치 전송과 음성 안내가 이어지게 한다. */
class PickupService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pickup", "픽업 진행", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, "pickup").setContentTitle("마중").setContentText("픽업 진행 중 · 위치 공유 중")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).build())
        return START_NOT_STICKY
    }
}
