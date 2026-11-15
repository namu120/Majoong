package app.majung.car.core

/**
 * 차량 쪽 픽업 로직 전체. 순수 함수 (상태, 사건) → (새 상태, 할 일 목록).
 * SDK·네트워크·시간을 직접 만지지 않는다 → PickupController 가 Effect 를 실행하고 결과를 Event 로 돌려준다.
 *
 * 지켜야 할 규칙 (기획서 5.3)
 *  1. 운전자가 동의해도 내비 반영 성공을 확인하기 전에는 확정(CONFIRMED)을 보고하지 않는다.
 *  2. 거절·무응답·인식 불명확·경로 실패·시간 초과는 모두 기존 지점 유지.
 *  3. 새 제안(seq)이 오면 진행 중이던 이전 제안 처리는 버린다. 늦게 온 콜백은 phase 가 안 맞아 무시된다.
 *  4. 같은 안내는 한 번만 읽는다(준비 상태는 member.seq, 메모는 지점별).
 */
sealed interface Phase {
    data object Idle : Phase
    /** TTS 로 질문을 읽는 중 */
    data class Asking(val seq: Int, val retried: Boolean = false) : Phase
    /** STT 로 응답을 듣는 중. 화면의 네/아니요 버튼도 이 구간과 Asking 에서 유효 */
    data class Listening(val seq: Int, val retried: Boolean) : Phase
    /** requestRoute 를 보내고 성공 콜백을 기다리는 중 */
    data class Routing(val seq: Int) : Phase
}

data class MachineState(
    val pickup: Pickup? = null,
    val phase: Phase = Phase.Idle,
    val announcedMemberSeq: Int = 0,
    val memoReadForSpot: String? = null,
    val routeActive: Boolean = false,       // 확정 지점으로 내비 안내가 걸려 있는가
    val parked: Boolean? = null,            // 주차 브레이크. null = 신호 미지원
    val distanceM: Int? = null,
    /** 지연 알림 화면(④)에서 운전자가 "확인"을 누른 member.seq */
    val ackedLateSeq: Int = 0,
    /** 이미 결과를 정한 제안 seq. Resolve 가 서버에 닿기 전에 온 스냅숏이 같은 제안을 다시 묻게 하지 않는다. */
    val handledSeq: Int = 0,
)

sealed interface Event {
    data class Snapshot(val pickup: Pickup) : Event
    data class TtsDone(val tag: String) : Event
    data class Heard(val text: String) : Event
    data object ListenFailed : Event                 // STT 오류·시간 초과·주행 중 사용 불가
    data class Tapped(val yes: Boolean) : Event      // 화면 버튼 (플랫폼이 허용하는 상태에서만 노출)
    data object RouteOk : Event
    data object RouteFailed : Event                  // onError 또는 시간 초과
    data class Location(val lat: Double, val lng: Double) : Event
    data object Arrived : Event                      // NaviHelper onDestinationArrived
    data class ParkBrake(val on: Boolean?) : Event
    data object AckLate : Event
    data class End(val suggested: Boolean = false) : Event
    data object Cancel : Event
}

sealed interface Effect {
    data class Speak(val text: String, val tag: String = "") : Effect
    data class Listen(val timeoutMs: Long) : Effect
    data object StopListen : Effect
    data class RequestRoute(val spot: Spot, val timeoutMs: Long) : Effect
    data object CancelRoute : Effect
    data class Resolve(val seq: Int, val result: ProposalResult) : Effect
    data class PostStatus(val status: PickupStatus, val suggested: Boolean = false) : Effect
}

object PickupMachine {
    const val LISTEN_MS = 6_000L
    const val ROUTE_TIMEOUT_MS = 45_000L      // 에뮬레이터 실측: 내비 앱이 꺼져 있으면 첫 콜백까지 27초 걸렸다
    const val NEAR_M = 150

    fun reduce(s: MachineState, e: Event): Pair<MachineState, List<Effect>> {
        val fx = mutableListOf<Effect>()
        val next = when (e) {
            is Event.Snapshot -> onSnapshot(s, e.pickup, fx)
            is Event.TtsDone -> {
                val ph = s.phase
                if (ph is Phase.Asking && e.tag == askTag(ph.seq)) { fx += Effect.Listen(LISTEN_MS); s.copy(phase = Phase.Listening(ph.seq, ph.retried)) } else s
            }
            is Event.Heard -> when (val ph = s.phase) {
                is Phase.Listening -> when (VoiceAnswer.parse(e.text)) {
                    Answer.YES -> accept(s, ph.seq, fx)
                    Answer.NO -> keep(s, ph.seq, ProposalResult.DECLINED, fx)
                    Answer.UNCLEAR -> unclear(s, ph, fx)
                }
                else -> s
            }
            Event.ListenFailed -> (s.phase as? Phase.Listening)?.let { unclear(s, it, fx) } ?: s
            is Event.Tapped -> when (val ph = s.phase) {
                is Phase.Asking -> if (e.yes) accept(s, ph.seq, fx) else keep(s, ph.seq, ProposalResult.DECLINED, fx)
                is Phase.Listening -> if (e.yes) accept(s, ph.seq, fx) else keep(s, ph.seq, ProposalResult.DECLINED, fx)
                else -> s
            }
            Event.RouteOk -> (s.phase as? Phase.Routing)?.let { ph ->
                val spot = s.pickup?.proposal?.spot
                fx += Effect.Resolve(ph.seq, ProposalResult.CONFIRMED)
                if (spot != null) fx += Effect.Speak(Phrases.routeStarted(spot))
                depart(s.copy(phase = Phase.Idle, handledSeq = ph.seq, routeActive = true, memoReadForSpot = null), fx, justConfirmed = true)
            } ?: s
            Event.RouteFailed -> (s.phase as? Phase.Routing)?.let { ph ->
                fx += Effect.Resolve(ph.seq, ProposalResult.ROUTE_FAILED)
                fx += Effect.Speak(if (s.pickup?.confirmedSpot != null) Phrases.ROUTE_FAILED_KEEP else Phrases.ROUTE_FAILED_FIRST)
                s.copy(phase = Phase.Idle, handledSeq = ph.seq)
            } ?: s
            is Event.Location -> onLocation(s, e, fx)
            Event.Arrived -> near(s, fx)
            is Event.ParkBrake -> depart(s.copy(parked = e.on), fx, justConfirmed = false)
            Event.AckLate -> s.copy(ackedLateSeq = s.pickup?.member?.seq ?: s.ackedLateSeq)
            is Event.End -> { if (s.pickup != null) fx += Effect.PostStatus(PickupStatus.ENDED, e.suggested); s }
            Event.Cancel -> { if (s.pickup != null) { fx += Effect.PostStatus(PickupStatus.CANCELLED); fx += Effect.CancelRoute }; s }
        }
        return next to fx
    }

    fun askTag(seq: Int) = "ask:$seq"

    private fun onSnapshot(s0: MachineState, p: Pickup, fx: MutableList<Effect>): MachineState {
        if (p.status.isTerminal) {
            if (s0.phase != Phase.Idle) fx += Effect.StopListen
            if (p.status != PickupStatus.ENDED && s0.routeActive) fx += Effect.CancelRoute
            return MachineState(parked = s0.parked)                 // 임시 상태를 모두 비운다
        }
        var s = (if (s0.pickup != null && s0.pickup.pickupId != p.pickupId) MachineState(parked = s0.parked) else s0).copy(pickup = p)

        // 앱이 픽업 도중 다시 시작된 경우: 확정 지점이 있으면 내비 안내는 이미 걸려 있다고 보고, 이미 전달된 준비 상태는 다시 읽지 않는다.
        if (p.confirmedSpot != null && !s.routeActive) s = s.copy(routeActive = true)
        if (s0.pickup == null && p.confirmedSpot != null) s = s.copy(announcedMemberSeq = p.member.seq, ackedLateSeq = p.member.seq)

        // 준비 상태: seq 가 올라갈 때 한 번만 읽는다.
        if (p.member.seq > s.announcedMemberSeq) {
            fx += Effect.Speak(Phrases.member(p.memberName, p.member))
            s = s.copy(announcedMemberSeq = p.member.seq)
        }

        val prop = p.proposal?.takeIf { it.seq > s.handledSeq }
        val phaseSeq = when (val ph = s.phase) { is Phase.Asking -> ph.seq; is Phase.Listening -> ph.seq; is Phase.Routing -> ph.seq; Phase.Idle -> null }
        when {
            prop == null -> if (phaseSeq != null) { fx += Effect.StopListen; s = s.copy(phase = Phase.Idle) }
            prop.seq == phaseSeq -> Unit                             // 이미 처리 중
            else -> {
                if (phaseSeq != null) fx += Effect.StopListen        // 새 요청이 이전 요청을 대체
                s = if (p.confirmedSpot == null) {
                    // 첫 목적지: 운전자가 방금 픽업을 시작했으므로 다시 묻지 않고 바로 반영한다.
                    fx += Effect.RequestRoute(prop.spot, ROUTE_TIMEOUT_MS)
                    s.copy(phase = Phase.Routing(prop.seq))
                } else {
                    fx += Effect.Speak(Phrases.askChange(prop), askTag(prop.seq))
                    s.copy(phase = Phase.Asking(prop.seq))
                }
            }
        }
        return depart(s, fx, justConfirmed = false)
    }

    private fun accept(s: MachineState, seq: Int, fx: MutableList<Effect>): MachineState {
        val spot = s.pickup?.proposal?.takeIf { it.seq == seq }?.spot ?: return s.copy(phase = Phase.Idle)
        fx += Effect.StopListen
        fx += Effect.RequestRoute(spot, ROUTE_TIMEOUT_MS)
        return s.copy(phase = Phase.Routing(seq))
    }

    private fun keep(s: MachineState, seq: Int, result: ProposalResult, fx: MutableList<Effect>): MachineState {
        fx += Effect.StopListen
        fx += Effect.Resolve(seq, result)
        fx += Effect.Speak(Phrases.KEEP)
        return s.copy(phase = Phase.Idle, handledSeq = seq)
    }

    /** 인식 불명확·실패: 한 번만 되묻고, 그래도 안 되면 무응답으로 보고 기존 지점 유지 */
    private fun unclear(s: MachineState, ph: Phase.Listening, fx: MutableList<Effect>): MachineState =
        if (!ph.retried) { fx += Effect.Speak(Phrases.ASK_AGAIN, askTag(ph.seq)); s.copy(phase = Phase.Asking(ph.seq, retried = true)) }
        else keep(s, ph.seq, ProposalResult.NO_ANSWER, fx)

    /** 출발 판정: 안내가 걸려 있고 주차 브레이크가 풀렸으면(신호가 없으면 안내 시작만으로) EN_ROUTE. 서버가 중복을 무시한다. */
    private fun depart(s: MachineState, fx: MutableList<Effect>, justConfirmed: Boolean): MachineState {
        val status = s.pickup?.status ?: return s
        val waiting = status == PickupStatus.REQUESTED || status == PickupStatus.SPOT_CONFIRMED
        if (s.routeActive && (waiting || justConfirmed) && s.parked != true && status != PickupStatus.EN_ROUTE)
            fx += Effect.PostStatus(PickupStatus.EN_ROUTE)
        return s
    }

    private fun onLocation(s: MachineState, e: Event.Location, fx: MutableList<Effect>): MachineState {
        val spot = s.pickup?.confirmedSpot ?: return s.copy(distanceM = null)
        val d = Geo.distanceM(e.lat, e.lng, spot.lat, spot.lng).toInt()
        val s1 = s.copy(distanceM = d)
        return if (d <= NEAR_M) near(s1, fx) else s1
    }

    private fun near(s: MachineState, fx: MutableList<Effect>): MachineState {
        val p = s.pickup ?: return s
        val spot = p.confirmedSpot ?: return s
        if (p.status == PickupStatus.EN_ROUTE) fx += Effect.PostStatus(PickupStatus.NEAR)
        if (p.status != PickupStatus.EN_ROUTE && p.status != PickupStatus.NEAR) return s
        // 도착 직전 메모("3번 출구 앞, 회색 코트")는 지점당 한 번. 질문 중에는 끼어들지 않는다.
        if (spot.memo.isNotBlank() && s.memoReadForSpot != spot.spotId && s.phase == Phase.Idle) {
            fx += Effect.Speak(Phrases.memo(spot))
            return s.copy(memoReadForSpot = spot.spotId)
        }
        return s
    }
}

// ── 화면에 무엇을 보여 줄지 (디자인 시안 ②~⑤, D 와 1:1) ─────────────────
enum class Tone { PLAIN, BLUE, GREEN }
sealed interface DrivingView {
    val name: String
    /** ② 마중 가는 중 / ③ 준비 중 / ⑤ 기다리는 중 */
    data class Progress(override val name: String, val headline: String, val tone: Tone, val spotName: String,
                        val distanceM: Int?, val showArrow: Boolean, val canEnd: Boolean) : DrivingView
    /** ④ N분 늦어요 */
    data class Late(override val name: String, val minutes: Int, val reason: String, val spotName: String, val distanceM: Int?) : DrivingView
    /** D 변경 요청 */
    data class Change(override val name: String, val newSpot: String, val oldSpot: String, val reason: String,
                      val listening: Boolean, val routing: Boolean) : DrivingView
    /** 탑승자의 수락·지점 선택을 기다리는 중 / 첫 목적지 설정 중 */
    data class Waiting(override val name: String, val message: String) : DrivingView
}

fun MachineState.toView(): DrivingView? {
    val p = pickup ?: return null
    val spot = p.confirmedSpot
    val prop = p.proposal?.takeIf { it.seq > handledSeq }
    if (spot == null) return DrivingView.Waiting(p.memberName, if (prop == null) "장소를 고르는 중" else "목적지 설정 중")
    if (prop != null && phase != Phase.Idle)
        return DrivingView.Change(p.memberName, prop.spot.name, spot.name, prop.reason, phase is Phase.Listening, phase is Phase.Routing)
    val m = p.member
    if (m.state == MemberState.LATE && m.seq > ackedLateSeq)
        return DrivingView.Late(p.memberName, m.lateMinutes ?: 10, m.reason, spot.name, distanceM)
    val close = p.status == PickupStatus.NEAR || (distanceM ?: Int.MAX_VALUE) <= 500
    val (headline, tone) = when {
        m.state == MemberState.OUTSIDE -> "기다리는 중" to Tone.GREEN
        close -> "준비 중" to Tone.PLAIN
        else -> "마중 가는 중" to Tone.PLAIN
    }
    return DrivingView.Progress(p.memberName, headline, tone, spot.name, distanceM, showArrow = close, canEnd = p.status == PickupStatus.NEAR)
}
