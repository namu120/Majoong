package app.majung.car.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 실행: ./gradlew :app:testDebugUnitTest
// SDK·에뮬레이터 없이 돌아간다. 요청서 10장의 완료 기준 중 "차량 로직" 항목이 이 테스트다.
class PickupMachineTest {
    private val a = Spot("a", "수학학원 정문", 37.4979, 127.0276)
    private val b = Spot("b", "수학학원 후문", 37.4984, 127.0281, memo = "회색 코트")
    private fun pickup(rev: Int, status: PickupStatus, confirmed: Spot?, proposal: Proposal? = null, member: MemberStatus = MemberStatus(MemberState.PREPARING, null, "", 0)) =
        Pickup("p1", rev, status, "첫째", true, confirmed, proposal, member)

    private fun run(s: MachineState, vararg events: Event): Pair<MachineState, List<Effect>> {
        var st = s; val all = mutableListOf<Effect>()
        for (e in events) { val (n, fx) = PickupMachine.reduce(st, e); st = n; all += fx }
        return st to all
    }
    private fun enRoute(): MachineState =
        run(MachineState(), Event.Snapshot(pickup(1, PickupStatus.REQUESTED, null, Proposal(1, a, "", false))), Event.RouteOk,
            Event.Snapshot(pickup(3, PickupStatus.EN_ROUTE, a))).first

    @Test fun `첫 목적지는 묻지 않고 바로 반영하고, 성공 뒤에만 확정을 보고한다`() {
        val (s1, fx1) = run(MachineState(), Event.Snapshot(pickup(1, PickupStatus.REQUESTED, null, Proposal(1, a, "", false))))
        assertEquals(listOf<Effect>(Effect.RequestRoute(a, PickupMachine.ROUTE_TIMEOUT_MS)), fx1)
        assertTrue(fx1.none { it is Effect.Resolve })
        val (_, fx2) = run(s1, Event.RouteOk)
        assertEquals(Effect.Resolve(1, ProposalResult.CONFIRMED), fx2[0])
        assertTrue(Effect.PostStatus(PickupStatus.EN_ROUTE) in fx2)
    }

    @Test fun `주차 브레이크가 걸려 있으면 풀릴 때 출발을 알린다`() {
        val (s1, fx1) = run(MachineState(), Event.ParkBrake(true), Event.Snapshot(pickup(1, PickupStatus.REQUESTED, null, Proposal(1, a, "", false))), Event.RouteOk)
        assertTrue(fx1.none { it is Effect.PostStatus })
        val (_, fx2) = run(s1, Event.Snapshot(pickup(2, PickupStatus.SPOT_CONFIRMED, a)), Event.ParkBrake(false))
        assertEquals(listOf<Effect>(Effect.PostStatus(PickupStatus.EN_ROUTE)), fx2)
    }

    @Test fun `변경 요청 - 질문, 듣기, 동의, 경로 성공 순서로만 확정된다`() {
        val prop = Proposal(2, b, "정문 앞이 혼잡해요", true)
        val (s1, fx1) = run(enRoute(), Event.Snapshot(pickup(4, PickupStatus.EN_ROUTE, a, prop)))
        assertEquals(Effect.Speak("정문 앞이 혼잡해서 수학학원 후문으로 변경 요청이 왔습니다. 안내할까요?", "ask:2"), fx1.single())
        val (s2, fx2) = run(s1, Event.TtsDone("ask:2"))
        assertEquals(Effect.Listen(PickupMachine.LISTEN_MS), fx2.single())
        val (s3, fx3) = run(s2, Event.Heard("네"))
        assertTrue(Effect.RequestRoute(b, PickupMachine.ROUTE_TIMEOUT_MS) in fx3)
        assertTrue(fx3.none { it is Effect.Resolve })
        val (s4, fx4) = run(s3, Event.RouteOk)
        assertEquals(Effect.Resolve(2, ProposalResult.CONFIRMED), fx4[0])
        assertEquals(Phase.Idle, s4.phase)
        // Resolve 가 서버에 닿기 전에 같은 제안이 담긴 스냅숏이 와도 다시 묻지 않는다
        val (_, fx5) = run(s4, Event.Snapshot(pickup(5, PickupStatus.EN_ROUTE, a, prop)))
        assertTrue(fx5.isEmpty())
    }

    @Test fun `거절, 무응답, 경로 실패는 기존 지점을 유지한다`() {
        val prop = Proposal(2, b, "", true)
        val asking = run(enRoute(), Event.Snapshot(pickup(4, PickupStatus.EN_ROUTE, a, prop)), Event.TtsDone("ask:2")).first
        assertTrue(Effect.Resolve(2, ProposalResult.DECLINED) in run(asking, Event.Heard("아니요")).second)
        // 불명확하면 한 번만 되묻고, 다시 실패하면 무응답
        val (again, fxA) = run(asking, Event.Heard("음 글쎄 잠깐만 기다려봐 지금은 좀"))
        assertEquals(Effect.Speak(Phrases.ASK_AGAIN, "ask:2"), fxA.single())
        val (_, fxB) = run(again, Event.TtsDone("ask:2"), Event.ListenFailed)
        assertTrue(Effect.Resolve(2, ProposalResult.NO_ANSWER) in fxB)
        val (_, fxC) = run(asking, Event.Tapped(true), Event.RouteFailed)
        assertTrue(Effect.Resolve(2, ProposalResult.ROUTE_FAILED) in fxC)
        assertTrue(fxC.none { it == Effect.Resolve(2, ProposalResult.CONFIRMED) })
    }

    @Test fun `새 요청이 오면 이전 요청 처리는 버리고, 늦게 온 경로 콜백은 무시한다`() {
        val routing = run(enRoute(), Event.Snapshot(pickup(4, PickupStatus.EN_ROUTE, a, Proposal(2, b, "", true))), Event.Tapped(true)).first
        val c = Spot("c", "건너편 편의점 앞", 37.4990, 127.0290)
        val (s, fx) = run(routing, Event.Snapshot(pickup(5, PickupStatus.EN_ROUTE, a, Proposal(3, c, "", true))))
        assertEquals(Phase.Asking(3), s.phase)
        assertTrue(Effect.StopListen in fx)
        assertTrue(run(s, Event.RouteOk).second.isEmpty())           // seq 2 의 성공 콜백 → 무시
        assertTrue(run(enRoute(), Event.RouteOk).second.isEmpty())   // 운전자가 내비를 직접 조작한 경우도 무시
    }

    @Test fun `준비 상태는 seq 가 바뀔 때 한 번만 읽는다`() {
        val late = MemberStatus(MemberState.LATE, 20, "재시험", 1)
        val (s, fx) = run(enRoute(), Event.Snapshot(pickup(4, PickupStatus.EN_ROUTE, a, member = late)))
        assertEquals(Effect.Speak("첫째, 20분 늦어요. 사유, 재시험."), fx.single())
        assertTrue(s.toView() is DrivingView.Late)
        assertTrue(run(s, Event.Snapshot(pickup(5, PickupStatus.EN_ROUTE, a, member = late))).second.isEmpty())
        assertTrue(run(s, Event.AckLate).first.toView() is DrivingView.Progress)
    }

    @Test fun `150m 안으로 들어오면 도착 근처를 알리고 메모를 한 번 읽는다`() {
        val s0 = run(MachineState(), Event.Snapshot(pickup(1, PickupStatus.REQUESTED, null, Proposal(1, b, "", true))), Event.RouteOk,
            Event.Snapshot(pickup(3, PickupStatus.EN_ROUTE, b))).first
        assertTrue(run(s0, Event.Location(37.4900, 127.0200)).second.isEmpty())
        val (s1, fx) = run(s0, Event.Location(37.4983, 127.0280))
        assertEquals(listOf(Effect.PostStatus(PickupStatus.NEAR), Effect.Speak("수학학원 후문, 회색 코트")), fx)
        assertTrue(run(s1, Event.Snapshot(pickup(4, PickupStatus.NEAR, b)), Event.Location(37.4984, 127.0281)).second.isEmpty())
    }

    @Test fun `종료, 취소, 만료 스냅숏은 상태를 비운다`() {
        val (s, _) = run(enRoute(), Event.Snapshot(pickup(9, PickupStatus.ENDED, a)))
        assertEquals(null, s.pickup)
        assertEquals(null, s.toView())
        assertTrue(Effect.CancelRoute in run(enRoute(), Event.Snapshot(pickup(9, PickupStatus.CANCELLED, a))).second)
    }

    @Test fun `짧은 긍정과 부정만 응답으로 받는다`() {
        listOf("네", "예", "응", "그래", "좋아", "네 변경해 주세요", "바꿔줘").forEach { assertEquals(it, Answer.YES, VoiceAnswer.parse(it)) }
        listOf("아니요", "아니", "안 돼", "그대로 가", "유지", "됐어").forEach { assertEquals(it, Answer.NO, VoiceAnswer.parse(it)) }
        listOf("", "오늘 날씨가 참 좋네요 그렇죠", "잠깐만").forEach { assertEquals(it, Answer.UNCLEAR, VoiceAnswer.parse(it)) }
    }

    @Test fun `조사 로 으로`() {
        assertEquals("수학학원 후문으로", Phrases.ro("수학학원 후문"))
        assertEquals("3번 출구로", Phrases.ro("3번 출구"))
        assertEquals("영어학원 앞으로", Phrases.ro("영어학원 앞"))
        assertEquals("서울로", Phrases.ro("서울"))
    }
}
