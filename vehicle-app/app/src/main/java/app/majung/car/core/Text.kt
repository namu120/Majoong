package app.majung.car.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ── 음성 응답 판별 ─────────────────────────────────────
// STT 는 텍스트만 준다. 긍정·부정의 짧은 응답만 받고, 애매하면 UNCLEAR → 기존 지점 유지.
enum class Answer { YES, NO, UNCLEAR }

object VoiceAnswer {
    // 부정을 먼저 본다: "안 돼", "아니 그냥 가" 같은 말에 긍정 단어가 섞여도 거절로 처리(안전한 쪽).
    private val no = listOf("아니", "아뇨", "안돼", "안되", "싫", "하지마", "됐어", "유지", "그대로", "그냥가", "취소", "노")
    private val yes = listOf("네", "예", "응", "어", "그래", "좋아", "오케이", "ok", "변경", "바꿔", "안내해", "해줘", "부탁", "맞아", "알겠")

    fun parse(raw: String): Answer {
        val t = raw.lowercase().filter { it.isLetterOrDigit() }
        if (t.isEmpty() || t.length > 12) return Answer.UNCLEAR          // 긴 문장은 응답으로 보지 않는다
        if (no.any { t.contains(it) }) return Answer.NO
        if (yes.any { t == it || t.startsWith(it) || t.endsWith(it) }) return Answer.YES
        return Answer.UNCLEAR
    }
}

// ── 안내 문구 ──────────────────────────────────────────
object Phrases {
    /** 조사 (으)로: 받침이 없거나 ㄹ 받침이면 "로" */
    fun ro(word: String): String {
        val c = word.lastOrNull() ?: return word
        if (c !in '가'..'힣') return "$word(으)로"
        val jong = (c - '가') % 28
        return word + if (jong == 0 || jong == 8) "로" else "으로"
    }

    fun askChange(p: Proposal): String {
        // "정문 앞이 혼잡해요" → "정문 앞이 혼잡해서 …". "~요"로 끝나지 않는 사유는 읽지 않는다(주행 중 문장은 짧게).
        val why = if (p.reason.endsWith("요")) p.reason.dropLast(1) + "서 " else ""
        return "${why}${ro(p.spot.name)} 변경 요청이 왔습니다. 안내할까요?"
    }
    const val ASK_AGAIN = "네 또는 아니요로 말씀해 주세요."
    fun routeStarted(spot: Spot) = "${ro(spot.name)} 안내를 시작합니다."
    const val KEEP = "기존 장소로 계속 안내합니다."
    const val ROUTE_FAILED_KEEP = "목적지를 바꾸지 못했습니다. 기존 장소로 계속 안내합니다."
    const val ROUTE_FAILED_FIRST = "목적지를 설정하지 못했습니다. 다시 시도해 주세요."
    fun member(name: String, m: MemberStatus) = when (m.state) {
        MemberState.PREPARING -> "$name, 준비 중입니다."
        MemberState.OUTSIDE -> "$name, 나와 있어요."
        MemberState.LATE -> "$name, ${m.lateMinutes ?: 10}분 늦어요." + if (m.reason.isBlank()) "" else " 사유, ${m.reason}."
    }
    fun memo(spot: Spot) = "${spot.name}, ${spot.memo}"
}

// ── 거리·방위 ──────────────────────────────────────────
object Geo {
    private const val R = 6_371_000.0
    private fun rad(d: Double) = Math.toRadians(d)

    /** 직선 거리(m). 도로 거리가 아니다 — 내비가 남은 거리를 주면 그 값을 우선한다. */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val h = sin(rad(lat2 - lat1) / 2).pow(2) + cos(rad(lat1)) * cos(rad(lat2)) * sin(rad(lng2 - lng1) / 2).pow(2)
        return 2 * R * asin(sqrt(h))
    }
    /** 북쪽 기준 시계 방향 방위각(0~360) */
    fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val y = sin(rad(lng2 - lng1)) * cos(rad(lat2))
        val x = cos(rad(lat1)) * sin(rad(lat2)) - sin(rad(lat1)) * cos(rad(lat2)) * cos(rad(lng2 - lng1))
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
    fun formatDistance(m: Int): Pair<String, String> =
        if (m >= 1000) String.format("%.1f", m / 1000.0) to "km" else ((m + 5) / 10 * 10).toString() to "m"
}
