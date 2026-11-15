package app.majung.car.data

import app.majung.car.core.CarState
import app.majung.car.core.Member
import app.majung.car.core.MemberState
import app.majung.car.core.MemberStatus
import app.majung.car.core.Pickup
import app.majung.car.core.PickupStatus
import app.majung.car.core.Proposal
import app.majung.car.core.ProposalResult
import app.majung.car.core.Spot
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiError(val status: Int, val code: String) : Exception("$status $code")

/**
 * 서버 계약(개발요청서 6장)의 차량 쪽 호출. 전부 블로킹 → Dispatchers.IO 에서 부른다.
 * 차량용 에뮬레이터에 Google 서비스가 없을 수 있어 전용 실시간 SDK 대신 HTTPS + 롱폴링만 쓴다.
 */
class SyncApi(private val baseUrl: String, var token: String? = null) {

    data class CarInfo(val carId: String, val alias: String, val members: List<Member>, val activePickupId: String?)
    data class Invite(val url: String, val expiresAt: Long)

    fun registerCar(alias: String): Pair<String, String> =
        call("POST", "/api/cars", JSONObject().put("alias", alias)).let { it.getString("carId") to it.getString("carToken") }

    fun me(): CarInfo = call("GET", "/api/cars/me").let { j ->
        CarInfo(j.getString("carId"), j.getString("alias"), j.getJSONArray("members").map { m ->
            Member(m.getString("memberId"), m.getString("name"), m.getJSONArray("spots").map(::spot))
        }, j.optStringOrNull("activePickupId"))
    }
    fun createInvite(): Invite = call("POST", "/api/invites").let { Invite(it.getString("url"), it.getLong("expiresAt")) }
    fun removeMember(memberId: String) { call("DELETE", "/api/cars/me/members/$memberId") }

    fun startPickup(memberId: String, spotId: String?): Pickup =
        pickup(call("POST", "/api/pickups", JSONObject().put("memberId", memberId).apply { spotId?.let { put("spotId", it) } }))

    /** since 를 주면 서버가 변화가 생길 때까지(최대 25초) 응답을 붙잡는다. */
    fun getPickup(pickupId: String, since: Int? = null): Pickup =
        pickup(call("GET", "/api/pickups/$pickupId" + (since?.let { "?since=$it" } ?: ""), readTimeoutMs = 35_000))

    /** 409 stale_proposal 이면 그 사이 새 요청이 온 것 → 호출부에서 조용히 버린다. */
    fun resolve(pickupId: String, seq: Int, result: ProposalResult): Pickup =
        pickup(call("POST", "/api/pickups/$pickupId/proposals/$seq/resolve", JSONObject().put("result", result.wire)))

    fun postStatus(pickupId: String, status: PickupStatus, suggested: Boolean = false): Pickup =
        pickup(call("POST", "/api/pickups/$pickupId/status", JSONObject().put("status", status.name).put("endedBy", if (suggested) "suggested" else "manual")))

    fun putCar(pickupId: String, c: CarState) {
        call("PUT", "/api/pickups/$pickupId/car", JSONObject()
            .put("lat", c.lat).put("lng", c.lng).put("heading", c.heading ?: JSONObject.NULL)
            .put("hazard", c.hazard ?: JSONObject.NULL).put("turnSignal", c.turnSignal?.name ?: JSONObject.NULL)
            .put("parked", c.parked ?: JSONObject.NULL).put("remainingM", c.remainingM ?: JSONObject.NULL)
            .put("etaSec", c.etaSec ?: JSONObject.NULL).put("nextTurn", c.nextTurn ?: JSONObject.NULL)
            .put("mock", c.mock).put("ts", c.ts))
    }

    // ── 내부 ──
    private fun call(method: String, path: String, body: JSONObject? = null, readTimeoutMs: Int = 8_000): JSONObject {
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = readTimeoutMs
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (method != "GET") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write((body ?: JSONObject()).toString().toByteArray()) }
            }
        }
        try {
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (status !in 200..299) throw ApiError(status, json.optString("error", "http_$status"))
            return json
        } finally { conn.disconnect() }
    }

    private fun spot(j: JSONObject) = Spot(j.getString("spotId"), j.getString("name"), j.getDouble("lat"), j.getDouble("lng"), j.optString("memo"), j.optBoolean("isDefault"))

    private fun pickup(j: JSONObject): Pickup {
        val m = j.getJSONObject("member")
        return Pickup(
            pickupId = j.getString("pickupId"), rev = j.getInt("rev"), status = PickupStatus.valueOf(j.getString("status")),
            memberName = j.optString("memberName", "가족"), accepted = j.optBoolean("accepted"),
            confirmedSpot = j.optJSONObject("confirmedSpot")?.let(::spot),
            proposal = j.optJSONObject("proposal")?.let { Proposal(it.getInt("seq"), spot(it.getJSONObject("spot")), it.optString("reason"), it.optString("by") == "member") },
            member = MemberStatus(MemberState.valueOf(m.getString("state")), if (m.isNull("lateMinutes")) null else m.getInt("lateMinutes"), m.optString("reason"), m.getInt("seq")),
        )
    }
    private fun <T> JSONArray.map(f: (JSONObject) -> T): List<T> = (0 until length()).map { f(getJSONObject(it)) }
    private fun JSONObject.optStringOrNull(key: String) = if (isNull(key)) null else getString(key)
}
