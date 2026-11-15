package app.majung.car

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.majung.car.core.Event
import app.majung.car.core.Member
import app.majung.car.core.toView
import app.majung.car.ui.Driving
import app.majung.car.ui.ParkedHome
import app.majung.car.ui.PickupConfirm

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = (application as MajungApp).controller
        setContent {
            val state by c.state.collectAsStateWithLifecycle()
            val home by c.home.collectAsStateWithLifecycle()
            val arrow by c.arrow.collectAsStateWithLifecycle()
            var confirming by remember { mutableStateOf<Member?>(null) }

            val view = state.toView()
            val member = confirming
            when {
                // 픽업이 진행 중이면 주차·주행과 관계없이 같은 안내 화면
                view != null -> Driving(
                    view = view, home = home,
                    headingToSpot = arrow,
                    // TODO(M1): 주행 규제 중 터치가 허용되는지 확인되기 전까지는 버튼을 그대로 둔다(규제가 막으면 플랫폼이 차단).
                    touchAllowed = true,
                    onYes = { c.dispatch(Event.Tapped(true)) }, onNo = { c.dispatch(Event.Tapped(false)) },
                    onAck = { c.dispatch(Event.AckLate) },
                    onEnd = { c.dispatch(Event.End(suggested = home.boardingHint)) },
                    onCancel = { c.dispatch(Event.Cancel) },
                )
                // 주행 규제 중에는 가족 선택·QR 을 열지 않는다
                home.restricted -> ParkedHome(home.copy(members = emptyList(), inviteUrl = null), onPick = {}, onNewInvite = {})
                member != null -> PickupConfirm(member, onBack = { confirming = null }) { spotId -> c.startPickup(member.memberId, spotId); confirming = null }
                else -> ParkedHome(home, onPick = { confirming = it }, onNewInvite = { c.newInvite() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as MajungApp).controller.refreshHome()      // QR 로 새 가족이 연결됐을 수 있다
    }
}
