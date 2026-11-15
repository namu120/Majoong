package app.majung.car.sdk

import android.os.Handler
import android.os.Looper
import app.majung.car.core.Geo

/**
 * 시연용 모의 위치. 버추얼 콘솔에는 위치·경로 모의 기능이 없어서, 에뮬레이터에서 FusedLocation 이 움직이지 않으면 이걸 쓴다.
 * gradle 속성 majung.mockLocation=true 일 때만 연결되며, 서버로 mock=true 가 함께 올라가 탑승자 화면에 "모의 위치"로 표시된다.
 */
class MockLocation(private val startLat: Double = 37.4925, private val startLng: Double = 127.0205) : LocationPort {
    private val main = Handler(Looper.getMainLooper())
    private var lat = startLat
    private var lng = startLng
    private var heading = 0.0
    private var target: Pair<Double, Double>? = null
    private var tick: Runnable? = null
    var moving = false                    // 출발(EN_ROUTE) 이후에만 움직인다

    fun setTarget(lat: Double, lng: Double) { target = lat to lng }

    override fun start(onFix: (Fix) -> Unit): Boolean {
        tick = object : Runnable {
            override fun run() {
                val t = target
                if (moving && t != null) {
                    val d = Geo.distanceM(lat, lng, t.first, t.second)
                    if (d > 8) {
                        heading = Geo.bearing(lat, lng, t.first, t.second)
                        val step = minOf(12.0, d) / d                       // 초속 12 m
                        lat += (t.first - lat) * step; lng += (t.second - lng) * step
                    }
                }
                onFix(Fix(lat, lng, heading, System.currentTimeMillis(), mock = true))
                main.postDelayed(this, 1000)
            }
        }.also { main.post(it) }
        return true
    }
    override fun stop() { tick?.let(main::removeCallbacks); tick = null }
}
