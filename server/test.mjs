// 실행: node --test test.mjs
// 기획서 시나리오 A(학원 픽업)와 오류 처리 규칙을 서버 계약 수준에서 검증한다.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';

process.env.PORT = '8799';
process.env.DATA_FILE = 'none';
process.env.LONGPOLL_MS = '300';
const { server } = await import('./server.mjs');
const BASE = 'http://localhost:8799';

const call = async (method, path, token, body) => {
  const res = await fetch(BASE + path, { method, headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: body ? JSON.stringify(body) : undefined });
  return { status: res.status, body: await res.json() };
};
const A = { name: '수학학원 정문', lat: 37.4979, lng: 127.0276, memo: '' };
const B = { name: '수학학원 후문', lat: 37.4984, lng: 127.0281, memo: '회색 코트' };
let car, member, pid;

before(async () => {
  car = (await call('POST', '/api/cars', null, { alias: '회색 SUV', demoMember: false })).body;
  const inv = (await call('POST', '/api/invites', car.carToken)).body;
  assert.equal((await call('POST', `/api/invites/${inv.inviteToken}/accept`, null, { name: '첫째' })).status, 400, '동의 없이는 연결되지 않는다');
  member = (await call('POST', `/api/invites/${inv.inviteToken}/accept`, null, { name: '첫째', consent: true })).body;
  await call('PUT', '/api/members/me/spots', member.memberToken, { spots: [{ ...A, isDefault: true }, B] });
});
after(() => server.close());

test('초대 링크는 한 번만 쓸 수 있다', async () => {
  const inv = (await call('POST', '/api/invites', car.carToken)).body;
  await call('POST', `/api/invites/${inv.inviteToken}/accept`, null, { name: '둘째', consent: true });
  assert.equal((await call('POST', `/api/invites/${inv.inviteToken}/accept`, null, { name: 'x', consent: true })).status, 410);
});

test('시나리오 A: 기본 지점 안내 → 변경 요청 → 동의 → 확정 → 종료', async () => {
  const me = (await call('GET', '/api/cars/me', car.carToken)).body;
  const kid = me.members.find((m) => m.name === '첫째');
  const spotA = kid.spots.find((s) => s.isDefault);

  let p = (await call('POST', '/api/pickups', car.carToken, { memberId: kid.memberId, spotId: spotA.spotId })).body;
  pid = p.pickupId;
  assert.equal(p.status, 'REQUESTED');
  assert.equal(p.proposal.seq, 1);
  assert.equal(p.confirmedSpot, null, '내비 반영 전에는 확정되지 않는다');
  assert.equal((await call('POST', `/api/pickups/${pid}/status`, car.carToken, { status: 'EN_ROUTE' })).status, 409);

  p = (await call('POST', `/api/pickups/${pid}/proposals/1/resolve`, car.carToken, { result: 'confirmed' })).body;
  assert.equal(p.status, 'SPOT_CONFIRMED');
  assert.equal(p.confirmedSpot.name, A.name);

  p = (await call('POST', `/api/pickups/${pid}/accept`, member.memberToken, {})).body;
  assert.equal(p.accepted, true);

  await call('POST', `/api/pickups/${pid}/status`, car.carToken, { status: 'EN_ROUTE' });
  const before = (await call('GET', `/api/pickups/${pid}`, member.memberToken)).body.rev;
  p = (await call('POST', `/api/pickups/${pid}/status`, car.carToken, { status: 'EN_ROUTE' })).body;
  assert.equal(p.rev, before, '같은 상태 재전송은 변화를 만들지 않는다(출발 알림 1회)');

  await call('PUT', `/api/pickups/${pid}/car`, car.carToken, { lat: 37.49, lng: 127.02, heading: 45, hazard: false });

  // 변경 요청: 승인 전까지 확정 지점은 그대로
  p = (await call('POST', `/api/pickups/${pid}/proposals`, member.memberToken, { spot: B, reason: '정문 앞이 혼잡해요' })).body;
  assert.equal(p.proposal.seq, 2);
  assert.equal(p.confirmedSpot.name, A.name);

  // 연달아 다시 요청하면 최근 것만 유효, 이전 seq 에 대한 응답은 거부
  p = (await call('POST', `/api/pickups/${pid}/proposals`, member.memberToken, { spot: B, reason: '정문 앞이 혼잡해요' })).body;
  assert.equal(p.proposal.seq, 3);
  assert.equal((await call('POST', `/api/pickups/${pid}/proposals/2/resolve`, car.carToken, { result: 'confirmed' })).status, 409);
  assert.equal((await call('GET', `/api/pickups/${pid}`, car.carToken)).body.confirmedSpot.name, A.name);

  p = (await call('POST', `/api/pickups/${pid}/proposals/3/resolve`, car.carToken, { result: 'confirmed' })).body;
  assert.equal(p.confirmedSpot.name, B.name);
  assert.equal(p.proposal, null);
  assert.equal(p.lastResult.result, 'confirmed');

  p = (await call('PUT', `/api/pickups/${pid}/member`, member.memberToken, { state: 'OUTSIDE' })).body;
  assert.equal(p.member.seq, 1);
  p = (await call('PUT', `/api/pickups/${pid}/member`, member.memberToken, { state: 'LATE', lateMinutes: 20, reason: '재시험' })).body;
  assert.deepEqual([p.member.lateMinutes, p.member.reason, p.member.seq], [20, '재시험', 2]);

  await call('POST', `/api/pickups/${pid}/status`, car.carToken, { status: 'NEAR' });
  p = (await call('POST', `/api/pickups/${pid}/status`, car.carToken, { status: 'ENDED' })).body;
  assert.equal(p.status, 'ENDED');
  assert.equal(p.endedBy, 'manual');
  assert.equal(p.car, null, '종료하면 차량 위치를 지운다');
  assert.equal((await call('PUT', `/api/pickups/${pid}/car`, car.carToken, { lat: 1, lng: 1 })).status, 409, '종료 후에는 위치를 받지 않는다');
});

test('거절·무응답·경로 반영 실패는 기존 지점을 유지한다', async () => {
  const me = (await call('GET', '/api/cars/me', car.carToken)).body;
  const kid = me.members.find((m) => m.name === '첫째');
  let p = (await call('POST', '/api/pickups', car.carToken, { memberId: kid.memberId, spotId: kid.spots[0].spotId })).body;
  const id2 = p.pickupId;
  await call('POST', `/api/pickups/${id2}/proposals/1/resolve`, car.carToken, { result: 'confirmed' });
  let seq = 1;
  for (const result of ['declined', 'no_answer', 'route_failed']) {
    seq += 1;
    await call('POST', `/api/pickups/${id2}/proposals`, member.memberToken, { spot: B, reason: '' });
    p = (await call('POST', `/api/pickups/${id2}/proposals/${seq}/resolve`, car.carToken, { result })).body;
    assert.equal(p.confirmedSpot.name, A.name, result);
    assert.equal(p.lastResult.result, result);
  }
  assert.equal((await call('POST', '/api/pickups', car.carToken, { memberId: kid.memberId })).status, 409, '동시 픽업 불가');
  p = (await call('POST', `/api/pickups/${id2}/cancel`, member.memberToken)).body;
  assert.equal(p.status, 'CANCELLED');
});

test('저장 지점이 없는 첫 픽업: 탑승자가 수락하며 고른 지점이 제안이 된다', async () => {
  const inv = (await call('POST', '/api/invites', car.carToken)).body;
  const guest = (await call('POST', `/api/invites/${inv.inviteToken}/accept`, null, { name: '지인', consent: true })).body;
  let p = (await call('POST', '/api/pickups', car.carToken, { memberId: guest.memberId })).body;
  assert.equal(p.proposal, null);
  p = (await call('POST', `/api/pickups/${p.pickupId}/accept`, guest.memberToken, { spot: { name: '3번 출구 앞', lat: 37.5, lng: 127.03, memo: '회색 코트' } })).body;
  assert.equal(p.proposal.by, 'member');
  assert.equal(p.proposal.spot.memo, '회색 코트');
  assert.equal((await call('GET', `/api/pickups/${p.pickupId}`, member.memberToken)).status, 403, '다른 가족은 볼 수 없다');
  await call('POST', `/api/pickups/${p.pickupId}/status`, car.carToken, { status: 'CANCELLED' });
});

test('롱폴링: 변화가 없으면 기다렸다가, 변화가 생기면 바로 응답한다', async () => {
  const me = (await call('GET', '/api/cars/me', car.carToken)).body;
  const kid = me.members.find((m) => m.name === '첫째');
  const p = (await call('POST', '/api/pickups', car.carToken, { memberId: kid.memberId })).body;
  const t0 = Date.now();
  const idle = await call('GET', `/api/pickups/${p.pickupId}?since=${p.rev}`, car.carToken);
  assert.ok(Date.now() - t0 >= 250 && idle.body.rev === p.rev);
  const waiting = call('GET', `/api/pickups/${p.pickupId}?since=${p.rev}`, car.carToken);
  await call('PUT', `/api/pickups/${p.pickupId}/member`, member.memberToken, { state: 'OUTSIDE' });
  assert.equal((await waiting).body.member.state, 'OUTSIDE');
  await call('POST', `/api/pickups/${p.pickupId}/status`, car.carToken, { status: 'CANCELLED' });
});
