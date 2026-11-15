package app.majung.car.core

// 서버 계약(server/server.mjs)의 응답을 그대로 옮긴 모델. Android 의존성 없음 → JVM 단위 테스트 가능.

enum class PickupStatus { REQUESTED, SPOT_CONFIRMED, EN_ROUTE, NEAR, ENDED, CANCELLED, EXPIRED;
    val isTerminal get() = this == ENDED || this == CANCELLED || this == EXPIRED
}
enum class MemberState { PREPARING, OUTSIDE, LATE }
enum class ProposalResult(val wire: String) { CONFIRMED("confirmed"), DECLINED("declined"), NO_ANSWER("no_answer"), ROUTE_FAILED("route_failed") }
enum class TurnSignal { LEFT, RIGHT, OFF }

data class Spot(val spotId: String, val name: String, val lat: Double, val lng: Double, val memo: String = "", val isDefault: Boolean = false)
data class Proposal(val seq: Int, val spot: Spot, val reason: String, val byMember: Boolean)
data class MemberStatus(val state: MemberState, val lateMinutes: Int?, val reason: String, val seq: Int)
data class Member(val memberId: String, val name: String, val spots: List<Spot>)

data class Pickup(
    val pickupId: String,
    val rev: Int,
    val status: PickupStatus,
    val memberName: String,
    val accepted: Boolean,
    val confirmedSpot: Spot?,
    val proposal: Proposal?,
    val member: MemberStatus,
)

/** 차량이 서버에 덮어쓰는 최신 상태. 지원되지 않는 신호는 null 로 보내면 탑승자 화면에서 숨겨진다. */
data class CarState(
    val lat: Double, val lng: Double, val heading: Double?,
    val hazard: Boolean? = null, val turnSignal: TurnSignal? = null, val parked: Boolean? = null,
    val remainingM: Int? = null, val etaSec: Int? = null, val nextTurn: String? = null,
    val mock: Boolean = false, val ts: Long,
)
