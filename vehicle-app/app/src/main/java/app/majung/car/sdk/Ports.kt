package app.majung.car.sdk

import app.majung.car.core.Spot
import app.majung.car.core.TurnSignal

// 앱 로직은 이 인터페이스만 안다. Pleos SDK 호출은 PleosPorts.kt 한 파일에 모여 있어,
// M1 검증에서 시그니처가 문서와 다르면 그 파일만 고치면 된다.

interface NaviPort {
    fun start(onReady: (Boolean) -> Unit)
    /** 목적지 설정·변경. 성공 콜백을 받으면 onResult(true), onError·시간 초과면 false. 한 번만 호출된다. */
    fun requestRoute(spot: Spot, timeoutMs: Long, onResult: (Boolean) -> Unit)
    fun cancelRoute()
    /** 내비가 도착을 알릴 때 */
    var onArrived: (() -> Unit)?
    /** 남은 거리(m)·시간(초)·다음 회전. 내비가 주지 않으면 null (M1 검증 항목) */
    var onProgress: ((remainingM: Int?, etaSec: Int?, nextTurn: String?) -> Unit)?
    fun stop()
}

data class Fix(val lat: Double, val lng: Double, val heading: Double?, val ts: Long, val mock: Boolean = false)

interface LocationPort {
    /** 지원되지 않으면 false 를 돌려주고 아무것도 하지 않는다 */
    fun start(onFix: (Fix) -> Unit): Boolean
    fun stop()
}

interface VehiclePort {
    /** 각 신호는 지원될 때만 호출된다. 한 번도 호출되지 않은 신호는 "미지원"으로 취급한다. */
    fun start(onHazard: (Boolean) -> Unit, onTurnSignal: (TurnSignal) -> Unit, onParkBrake: (Boolean) -> Unit, onBoardingHint: () -> Unit)
    fun stop()
}

interface VoicePort {
    fun start()
    /** 순서대로 읽는다. 다 읽으면 onDone. 실패해도 onDone 은 불린다(흐름이 멈추지 않게). */
    fun speak(text: String, onDone: () -> Unit = {})
    /** 한 번 듣는다. 최종 인식 결과는 onText, 오류·무음·시간 초과는 onFail. 둘 중 하나만 한 번 호출된다. */
    fun listen(timeoutMs: Long, onText: (String) -> Unit, onFail: () -> Unit)
    fun stopListening()
    /** 아직 읽지 않은 안내를 버리고 읽던 것을 멈춘다(운전자가 이미 답한 질문이 뒤늦게 나오지 않게). */
    fun flush()
    fun stop()
}
