package app.majung.car.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.majung.car.HomeState
import app.majung.car.core.DrivingView
import app.majung.car.core.Geo
import app.majung.car.core.Member
import app.majung.car.core.Tone
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

// ── 디자인 토큰 (시안의 Pleos Connect 토큰과 같은 값) ──────────────────────────
object C {
    val bg = Color(0xFFF7F8FA); val surface = Color.White; val high = Color(0xFFEDEEF2)
    val text = Color(0xFF131417); val text2 = Color(0xD6131417); val text3 = Color(0xA3131417)
    val divider = Color(0x1A131417)
    val blue = Color(0xFF0064FF); val green = Color(0xFF08824A)
    val filled = Color(0xFF44464E); val basic = Color(0x0D131417)
}

/**
 * 시안은 840×1240 아트보드 기준 px 로 그려져 있다. 실제 창 크기·밀도는 에뮬레이터에서 확인 전이므로,
 * 창 너비를 840 으로 나눈 값을 1 단위(u)로 삼아 시안 수치를 그대로 쓴다. 사용자가 창 크기를 바꿔도 비율이 유지된다.
 */
class Units(private val unit: Dp) {
    val Int.u: Dp get() = unit * this
    val Int.t: TextUnit get() = (unit.value * this).sp     // 글자도 창 크기에 비례 (시스템 글꼴 배율은 의도적으로 따르지 않음)
}
val LocalUnits = compositionLocalOf { Units(1.dp) }

@Composable
fun Scaled(content: @Composable Units.() -> Unit) = BoxWithConstraints(Modifier.fillMaxSize().background(C.bg)) {
    val units = Units(maxWidth / 840f)
    CompositionLocalProvider(LocalUnits provides units) { units.content() }
}

@Composable
private fun Units.Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().shadow(12.u, RoundedCornerShape(48.u), ambientColor = Color(0x14000000), spotColor = Color(0x14000000))
        .clip(RoundedCornerShape(48.u)).background(C.surface), content = content)

/** 터치 대상은 시안 기준 최소 96u 높이. primary = 옅은 회색, filled = 진한 회색(시안의 "네", "안내 시작", "탑승 종료") */
@Composable
private fun Units.Btn(label: String, filled: Boolean, modifier: Modifier = Modifier, large: Boolean = true, onClick: () -> Unit) =
    Box(modifier.heightIn(min = if (large) 120.u else 88.u).clip(RoundedCornerShape(if (large) 32.u else 24.u))
        .background(if (filled) C.filled else C.basic).clickable(onClick = onClick).padding(horizontal = 40.u), contentAlignment = Alignment.Center) {
        Text(label, fontSize = if (large) 36.t else 30.t, fontWeight = FontWeight.Bold, color = if (filled) Color.White else C.text, maxLines = 1)
    }

// ── ① 주차 · 출발 전 ────────────────────────────────────────────────────────
@Composable
fun ParkedHome(home: HomeState, onPick: (Member) -> Unit, onNewInvite: () -> Unit) = Scaled {
    Column(Modifier.fillMaxSize().padding(start = 40.u, end = 40.u, top = 24.u, bottom = 40.u), verticalArrangement = Arrangement.spacedBy(32.u)) {
        Row(Modifier.height(104.u), verticalAlignment = Alignment.CenterVertically) {
            Text("마중", fontSize = 40.t, fontWeight = FontWeight.ExtraBold, color = C.text, modifier = Modifier.weight(1f))
            Text(if (home.offline) "연결 끊김" else "주차 중", fontSize = 24.t, color = C.text3)
        }
        if (home.members.isNotEmpty()) Card {
            Column(Modifier.padding(horizontal = 48.u, vertical = 16.u)) {
                home.members.forEachIndexed { i, m ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(2.u).background(C.divider))
                    Row(Modifier.padding(vertical = 32.u), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.u)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.u)) {
                            Text(m.name, fontSize = 36.t, fontWeight = FontWeight.Bold, color = C.text)
                            val def = m.spots.firstOrNull { it.isDefault } ?: m.spots.firstOrNull()
                            Text(if (def != null) "만남 지점 · ${def.name}" else "만남 지점 · 픽업 때 정해요", fontSize = 26.t, color = C.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Btn("픽업", filled = false, large = false) { onPick(m) }
                    }
                }
            }
        }
        Card(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().padding(48.u), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(32.u, Alignment.CenterVertically)) {
                Box(Modifier.size(280.u).clip(RoundedCornerShape(32.u)).background(C.high).clickable(onClick = onNewInvite), contentAlignment = Alignment.Center) {
                    val url = home.inviteUrl
                    if (url != null) Qr(url, Modifier.size(232.u)) else Text("눌러서 QR 만들기", fontSize = 24.t, color = C.text3)
                }
                Text("가족 추가", fontSize = 36.t, fontWeight = FontWeight.Bold, color = C.text)
                Text("가족 휴대폰으로 QR을 찍으면 바로 등록돼요.", fontSize = 28.t, color = C.text2)
            }
        }
    }
}

@Composable
private fun Qr(text: String, modifier: Modifier) {
    val matrix = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0) }
    Canvas(modifier) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width)
            if (matrix[x, y]) drawRect(C.text, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
    }
}

// ── C 주차 · 픽업 확인 ──────────────────────────────────────────────────────
/** spotId == null 은 "탑승자가 고르게 하기"(저장 지점이 없는 첫 픽업의 기본값) */
@Composable
fun PickupConfirm(member: Member, onBack: () -> Unit, onStart: (spotId: String?) -> Unit) = Scaled {
    var selected by remember { mutableStateOf((member.spots.firstOrNull { it.isDefault } ?: member.spots.firstOrNull())?.spotId) }
    Column(Modifier.fillMaxSize().padding(start = 40.u, end = 40.u, top = 24.u, bottom = 40.u), verticalArrangement = Arrangement.spacedBy(32.u)) {
        Row(Modifier.height(104.u), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.u)) {
            Btn("‹", filled = false, large = false, onClick = onBack)
            Text("${member.name} 픽업", fontSize = 40.t, fontWeight = FontWeight.ExtraBold, color = C.text)
        }
        Card {
            Column(Modifier.padding(horizontal = 48.u, vertical = 40.u), verticalArrangement = Arrangement.spacedBy(8.u)) {
                Text("만남 지점", fontSize = 26.t, color = C.text3)
                val options = member.spots.map { it.spotId to (it.name + if (it.isDefault) " · 기본" else "") } + (null to "${member.name}에게 물어보기")
                options.forEachIndexed { i, (id, label) ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(2.u).background(C.divider))
                    Row(Modifier.fillMaxWidth().heightIn(min = 96.u).clickable { selected = id }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.u)) {
                        Box(Modifier.size(44.u).clip(CircleShape).background(if (selected == id) C.filled else C.high), contentAlignment = Alignment.Center) {
                            if (selected == id) Box(Modifier.size(18.u).clip(CircleShape).background(Color.White))
                        }
                        Text(label, fontSize = 32.t, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Btn("안내 시작", filled = true, modifier = Modifier.fillMaxWidth()) { onStart(selected) }
    }
}

// ── ②③④⑤, D 주행 중 ───────────────────────────────────────────────────────
/**
 * 주행 중 화면은 전부 같은 뼈대다: 위 400u 에 이름과 112u 큰 글씨 한 줄, 아래에 60u 숫자 한 줄과 30u 보조 두 줄.
 * touchAllowed=false(주행 규제 중 터치 불가로 확인된 경우)이면 버튼을 그리지 않고 음성만으로 진행한다.
 */
@Composable
fun Driving(view: DrivingView, home: HomeState, headingToSpot: Float?, touchAllowed: Boolean,
            onYes: () -> Unit, onNo: () -> Unit, onAck: () -> Unit, onEnd: () -> Unit, onCancel: () -> Unit) = Scaled {
    Column(Modifier.fillMaxSize().padding(start = 40.u, end = 40.u, top = 32.u, bottom = 40.u), verticalArrangement = Arrangement.spacedBy(32.u)) {
        Row(Modifier.height(48.u), verticalAlignment = Alignment.CenterVertically) {
            Text("마중", fontSize = 32.t, fontWeight = FontWeight.Bold, color = C.text3, modifier = Modifier.weight(1f))
            if (home.offline) Text("연결 지연 · 기존 장소로 안내 중", fontSize = 24.t, color = C.text3)
        }
        val (headline, tone) = when (view) {
            is DrivingView.Progress -> view.headline to view.tone
            is DrivingView.Late -> "${view.minutes}분 늦어요" to Tone.PLAIN
            is DrivingView.Change -> "변경 요청" to Tone.BLUE
            is DrivingView.Waiting -> view.message to Tone.PLAIN
        }
        val band = when (tone) { Tone.PLAIN -> Color.Transparent; Tone.BLUE -> C.blue; Tone.GREEN -> C.green }
        val onBand = tone != Tone.PLAIN
        Card(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().height(400.u).background(band).padding(start = 48.u, end = 48.u, bottom = 32.u), verticalArrangement = Arrangement.spacedBy(4.u, Alignment.Bottom)) {
                Text(view.name, fontSize = 40.t, fontWeight = FontWeight.Bold, color = if (onBand) Color(0xD6FFFFFF) else C.text2)
                Text(headline, fontSize = 112.t, lineHeight = 134.t, fontWeight = FontWeight.Bold, letterSpacing = (-3).t, color = if (onBand) Color.White else C.text, maxLines = 1, softWrap = false)
            }
            if (!onBand) Box(Modifier.padding(horizontal = 48.u).fillMaxWidth().height(2.u).background(C.divider))
            Column(Modifier.padding(start = 48.u, end = 48.u, top = 32.u), verticalArrangement = Arrangement.spacedBy(24.u)) {
                when (view) {
                    is DrivingView.Progress -> {
                        Distance(view.distanceM, headingToSpot.takeIf { view.showArrow })
                        Line(view.spotName)
                        home.takeIf { it.boardingHint }?.let { Line("탑승한 것 같아요 · 픽업을 종료할까요?") }
                    }
                    is DrivingView.Late -> {
                        Distance(view.distanceM, null)
                        Line("${view.spotName} · 그대로")
                        if (view.reason.isNotBlank()) Line("사유 · ${view.reason}")
                    }
                    is DrivingView.Change -> {
                        Text(view.newSpot, fontSize = 60.t, lineHeight = 80.t, fontWeight = FontWeight.ExtraBold, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Line("기존 : ${view.oldSpot}")
                        if (view.reason.isNotBlank()) Line("사유 : ${view.reason}")
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(32.u)).background(C.bg).padding(horizontal = 32.u, vertical = 24.u), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.u)) {
                            Box(Modifier.size(72.u).clip(CircleShape).background(if (view.listening) C.blue else C.high))
                            Column(verticalArrangement = Arrangement.spacedBy(4.u)) {
                                Text(when { view.routing -> "목적지 변경 중"; view.listening -> "듣는 중"; else -> "안내 중" }, fontSize = 26.t, color = C.text3)
                                Text("${app.majung.car.core.Phrases.ro(view.newSpot)} 안내할까요?", fontSize = 32.t, fontWeight = FontWeight.Bold, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    is DrivingView.Waiting -> Line("${view.name}의 휴대폰에서 만남 장소를 확인하고 있어요")
                }
            }
        }
        // 주행 중 조작은 한 화면에 한 번의 탭으로 끝나는 큰 버튼만 둔다.
        when {
            view is DrivingView.Change && touchAllowed && !view.routing -> Row(horizontalArrangement = Arrangement.spacedBy(24.u)) {
                Btn("아니요", filled = false, modifier = Modifier.weight(1f), onClick = onNo)
                Btn("네", filled = true, modifier = Modifier.weight(1f), onClick = onYes)
            }
            view is DrivingView.Late && touchAllowed -> Btn("확인", filled = true, modifier = Modifier.fillMaxWidth(), onClick = onAck)
            view is DrivingView.Progress && view.canEnd && touchAllowed -> Btn("탑승 종료", filled = true, modifier = Modifier.fillMaxWidth(), onClick = onEnd)
            !home.restricted -> Btn("픽업 취소", filled = false, modifier = Modifier.fillMaxWidth(), onClick = onCancel)      // 주차 상태에서만
        }
    }
}

@Composable
private fun Units.Distance(meters: Int?, arrowDeg: Float?) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.u)) {
    if (meters == null) Text("위치 확인 중", fontSize = 36.t, fontWeight = FontWeight.Bold, color = C.text3)
    if (meters != null && arrowDeg != null) Box(Modifier.size(72.u).clip(CircleShape).background(C.blue).rotate(arrowDeg), contentAlignment = Alignment.Center) {
        Text("↑", fontSize = 44.t, fontWeight = FontWeight.Bold, color = Color.White)      // 시안의 화살표 아이콘(벡터)으로 교체
    }
    val (num, unit) = Geo.formatDistance(meters ?: 0)
    if (meters != null) Row(verticalAlignment = Alignment.Bottom) {
        Text(num, fontSize = 60.t, lineHeight = 80.t, fontWeight = FontWeight.ExtraBold, color = C.text)
        Spacer(Modifier.width(8.u))
        Text(unit, fontSize = 36.t, fontWeight = FontWeight.Bold, color = C.text, modifier = Modifier.padding(bottom = 10.u))
    }
}

@Composable
private fun Units.Line(text: String) = Text(text, fontSize = 30.t, lineHeight = 38.t, color = C.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
