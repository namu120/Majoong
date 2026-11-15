// 마중 동기화 서버 (참조 구현) — 외부 의존성 없음. Node 20+
// 실행: node server.mjs   (PORT, PUBLIC_URL 환경변수로 조정)
// 원칙: 차량 좌표는 pickup.car 에 덮어쓰기만 하고 이력으로 쌓지 않는다. 종료·취소·만료 시 지운다.
import http from 'node:http';
import { randomBytes } from 'node:crypto';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const DIR = path.dirname(fileURLToPath(import.meta.url));
const DATA_FILE = process.env.DATA_FILE ?? path.join(DIR, 'data.json');
const PICKUP_TTL_MS = Number(process.env.PICKUP_TTL_MS ?? 2 * 60 * 60 * 1000); // 미종료 픽업 만료
const INVITE_TTL_MS = 24 * 60 * 60 * 1000;
const LONGPOLL_MS = Number(process.env.LONGPOLL_MS ?? 25000);

const id = (n = 9) => randomBytes(n).toString('base64url');
const now = () => Date.now();

// ── 저장소 ─────────────────────────────────────────────
// 연결 관계와 만남 지점만 파일에 남긴다. 픽업(위치 포함)은 메모리에만 둔다.
const db = { cars: {}, members: {}, invites: {} };
if (DATA_FILE !== 'none' && existsSync(DATA_FILE)) Object.assign(db, JSON.parse(readFileSync(DATA_FILE, 'utf8')));
const persist = () => { if (DATA_FILE !== 'none') writeFileSync(DATA_FILE, JSON.stringify(db)); };
const pickups = {};            // pickupId -> pickup
const waiters = {};            // pickupId -> Set<fn>
const tokens = new Map();      // token -> {kind:'car'|'member', id}
for (const c of Object.values(db.cars)) tokens.set(c.token, { kind: 'car', id: c.carId });
for (const m of Object.values(db.members)) tokens.set(m.token, { kind: 'member', id: m.memberId });

const TERMINAL = ['ENDED', 'CANCELLED', 'EXPIRED'];
const ORDER = ['REQUESTED', 'SPOT_CONFIRMED', 'EN_ROUTE', 'NEAR', 'ENDED'];

class HttpError extends Error { constructor(status, code) { super(code); this.status = status; this.code = code; } }
const bad = (status, code) => { throw new HttpError(status, code); };

function touch(p) {
  p.rev += 1; p.updatedAt = now();
  for (const fn of waiters[p.pickupId] ?? []) fn();
}
function finish(p, status) {
  p.status = status; p.endedAt = now();
  p.car = null;                 // 위치 공유 차단 + 임시 데이터 삭제
  p.proposal = null;
  touch(p);
}
function expireOld() {
  for (const p of Object.values(pickups)) {
    if (!TERMINAL.includes(p.status) && now() - p.startedAt > PICKUP_TTL_MS) finish(p, 'EXPIRED');
    if (TERMINAL.includes(p.status) && now() - p.endedAt > 10 * 60 * 1000) { delete pickups[p.pickupId]; delete waiters[p.pickupId]; }
  }
}
setInterval(expireOld, 30000).unref();

const cleanSpot = (s) => {
  if (!s || typeof s.lat !== 'number' || typeof s.lng !== 'number' || !s.name) bad(400, 'bad_spot');
  return { spotId: s.spotId ?? id(6), name: String(s.name).slice(0, 40), lat: s.lat, lng: s.lng, memo: String(s.memo ?? '').slice(0, 60) };
};
const view = (p) => ({ ...p, memberName: db.members[p.memberId]?.name, carAlias: db.cars[p.carId]?.alias });
const activeOf = (pred) => Object.values(pickups).find((p) => pred(p) && !TERMINAL.includes(p.status));

// ── 라우트 ─────────────────────────────────────────────
const routes = [];
const route = (method, pattern, who, fn) => {
  const keys = [];
  const re = new RegExp('^' + pattern.replace(/:(\w+)/g, (_, k) => { keys.push(k); return '([^/]+)'; }) + '$');
  routes.push({ method, re, keys, who, fn });
};
function pickupFor(auth, pickupId) {
  const p = pickups[pickupId]; if (!p) bad(404, 'no_pickup');
  if ((auth.kind === 'car' && p.carId !== auth.id) || (auth.kind === 'member' && p.memberId !== auth.id)) bad(403, 'forbidden');
  return p;
}
const open = (p) => { if (TERMINAL.includes(p.status)) bad(409, 'pickup_closed'); return p; };

// 차량 등록 (앱 최초 실행 시 1회)
route('POST', '/api/cars', null, (_, b) => {
  const car = { carId: id(6), token: id(24), alias: String(b.alias ?? '우리 차').slice(0, 20), members: [] };
  db.cars[car.carId] = car; tokens.set(car.token, { kind: 'car', id: car.carId }); persist();
  return { carId: car.carId, carToken: car.token, alias: car.alias };
});
// 초대 QR 발급 (주차 중에만 호출)
route('POST', '/api/invites', 'car', (a) => {
  const token = id(12);
  db.invites[token] = { carId: a.id, expiresAt: now() + INVITE_TTL_MS }; persist();
  return { inviteToken: token, url: `${PUBLIC_URL}/?invite=${token}`, expiresAt: db.invites[token].expiresAt };
});
// 초대 수락 → 가족(탑승자) 등록. 위치 공유 안내 동의(consent)가 필수.
route('POST', '/api/invites/:token/accept', null, (_, b, { token }) => {
  const inv = db.invites[token];
  if (!inv || inv.expiresAt < now()) bad(410, 'invite_expired');
  if (b.consent !== true) bad(400, 'consent_required');
  const m = { memberId: id(6), token: id(24), carId: inv.carId, name: String(b.name ?? '').slice(0, 12) || '가족', spots: [], joinedAt: now() };
  db.members[m.memberId] = m; db.cars[inv.carId].members.push(m.memberId);
  tokens.set(m.token, { kind: 'member', id: m.memberId });
  delete db.invites[token]; persist();
  return { memberId: m.memberId, memberToken: m.token, name: m.name, carAlias: db.cars[inv.carId].alias };
});
// 차량: 연결된 가족 목록(+기본 만남 지점)과 진행 중 픽업
route('GET', '/api/cars/me', 'car', (a) => ({
  carId: a.id, alias: db.cars[a.id].alias,
  members: db.cars[a.id].members.map((mid) => db.members[mid]).filter(Boolean)
    .map((m) => ({ memberId: m.memberId, name: m.name, spots: m.spots })),
  activePickupId: activeOf((p) => p.carId === a.id)?.pickupId ?? null,
}));
route('DELETE', '/api/cars/me/members/:memberId', 'car', (a, _, { memberId }) => {
  const m = db.members[memberId]; if (!m || m.carId !== a.id) bad(404, 'no_member');
  const p = activeOf((x) => x.memberId === memberId); if (p) finish(p, 'CANCELLED');
  tokens.delete(m.token); delete db.members[memberId];
  db.cars[a.id].members = db.cars[a.id].members.filter((x) => x !== memberId); persist();
  return { ok: true };
});
// 가족: 내 정보, 저장 지점
route('GET', '/api/members/me', 'member', (a) => {
  const m = db.members[a.id];
  return { memberId: m.memberId, name: m.name, carAlias: db.cars[m.carId]?.alias, spots: m.spots,
    activePickupId: activeOf((p) => p.memberId === a.id)?.pickupId ?? null };
});
route('PUT', '/api/members/me/spots', 'member', (a, b) => {
  if (!Array.isArray(b.spots) || b.spots.length > 10) bad(400, 'bad_spots');
  const spots = b.spots.map((s) => ({ ...cleanSpot(s), isDefault: !!s.isDefault }));
  if (spots.length && !spots.some((s) => s.isDefault)) spots[0].isDefault = true;
  db.members[a.id].spots = spots; persist();
  return { spots };
});
route('DELETE', '/api/members/me', 'member', (a) => {
  const m = db.members[a.id];
  const p = activeOf((x) => x.memberId === a.id); if (p) finish(p, 'CANCELLED');
  tokens.delete(m.token); delete db.members[a.id];
  if (db.cars[m.carId]) db.cars[m.carId].members = db.cars[m.carId].members.filter((x) => x !== a.id);
  persist(); return { ok: true };
});

// ── 픽업 ───────────────────────────────────────────────
// 차량: 가족을 골라 픽업 시작. spotId 를 주면 그 저장 지점을 첫 제안(seq 1)으로 올린다.
route('POST', '/api/pickups', 'car', (a, b) => {
  const m = db.members[b.memberId]; if (!m || m.carId !== a.id) bad(404, 'no_member');
  if (activeOf((p) => p.carId === a.id)) bad(409, 'pickup_in_progress');   // 동시 픽업은 범위 밖
  const p = {
    pickupId: id(8), carId: a.id, memberId: m.memberId, status: 'REQUESTED', rev: 0,
    accepted: false, confirmedSpot: null, proposal: null, proposalSeq: 0, lastResult: null,
    member: { state: 'PREPARING', lateMinutes: null, reason: '', seq: 0 },
    car: null, startedAt: now(), updatedAt: now(), endedAt: null,
  };
  const spot = m.spots.find((s) => s.spotId === b.spotId);
  if (spot) p.proposal = { seq: ++p.proposalSeq, spot: cleanSpot(spot), reason: '', by: 'driver', at: now() };
  pickups[p.pickupId] = p; touch(p);
  return view(p);
});
// 조회. ?since=rev 를 주면 rev 가 바뀔 때까지(최대 LONGPOLL_MS) 기다렸다가 응답한다.
route('GET', '/api/pickups/:id', 'any', async (a, _, { id: pid }, q) => {
  const p = pickupFor(a, pid);
  const since = Number(q.get('since') ?? -1);
  if (p.rev <= since) {
    await new Promise((res) => {
      const set = (waiters[pid] ??= new Set());
      const done = () => { clearTimeout(t); set.delete(done); res(); };
      const t = setTimeout(done, LONGPOLL_MS); set.add(done);
    });
  }
  return { ...view(p), serverTime: now() };
});
// 가족: 픽업 수락. spot 을 함께 보내면 만남 지점 제안이 된다(없으면 차량이 올린 제안/확정 지점을 그대로 수락).
route('POST', '/api/pickups/:id/accept', 'member', (a, b, { id: pid }) => {
  const p = open(pickupFor(a, pid));
  p.accepted = true;
  if (b.spot) p.proposal = { seq: ++p.proposalSeq, spot: cleanSpot(b.spot), reason: String(b.reason ?? '').slice(0, 40), by: 'member', at: now() };
  touch(p); return view(p);
});
// 가족: 만남 지점 변경 요청. 새 요청이 오면 이전 대기 요청은 덮어쓴다(가장 최근 것만 유효).
route('POST', '/api/pickups/:id/proposals', 'member', (a, b, { id: pid }) => {
  const p = open(pickupFor(a, pid));
  p.proposal = { seq: ++p.proposalSeq, spot: cleanSpot(b.spot), reason: String(b.reason ?? '').slice(0, 40), by: 'member', at: now() };
  touch(p); return view(p);
});
// 차량: 제안 처리 결과 보고. result=confirmed 는 "내비 목적지 반영 성공 콜백을 받은 뒤"에만 보낸다.
// seq 가 현재 대기 중인 제안과 다르면 409 — 늦게 도착한 응답이 현재 목적지를 덮어쓰지 못한다.
route('POST', '/api/pickups/:id/proposals/:seq/resolve', 'car', (a, b, { id: pid, seq }) => {
  const p = open(pickupFor(a, pid));
  if (!p.proposal || p.proposal.seq !== Number(seq)) bad(409, 'stale_proposal');
  if (!['confirmed', 'declined', 'no_answer', 'route_failed'].includes(b.result)) bad(400, 'bad_result');
  if (b.result === 'confirmed') {
    p.confirmedSpot = p.proposal.spot;
    if (p.status === 'REQUESTED') p.status = 'SPOT_CONFIRMED';
    if (p.status === 'NEAR') p.status = 'EN_ROUTE';        // 지점이 바뀌면 근접 판정을 다시 한다
  }
  p.lastResult = { seq: p.proposal.seq, result: b.result, spotName: p.proposal.spot.name, at: now() };
  p.proposal = null;
  touch(p); return view(p);
});
// 차량: 상태 전이. 앞으로만 간다(같은 상태 재전송은 무시 → 출발 알림 중복 방지).
route('POST', '/api/pickups/:id/status', 'car', (a, b, { id: pid }) => {
  const p = open(pickupFor(a, pid));
  if (b.status === 'CANCELLED') { finish(p, 'CANCELLED'); return view(p); }
  if (b.status === 'ENDED') { p.endedBy = b.endedBy === 'suggested' ? 'suggested' : 'manual'; finish(p, 'ENDED'); return view(p); }
  if (!['EN_ROUTE', 'NEAR'].includes(b.status)) bad(400, 'bad_status');
  if (!p.confirmedSpot) bad(409, 'no_confirmed_spot');
  if (ORDER.indexOf(b.status) > ORDER.indexOf(p.status)) { p.status = b.status; touch(p); }
  return view(p);
});
// 가족: 픽업 취소(공유 종료)
route('POST', '/api/pickups/:id/cancel', 'member', (a, _, { id: pid }) => {
  const p = open(pickupFor(a, pid)); finish(p, 'CANCELLED'); return view(p);
});
// 차량: 최신 차량 상태(덮어쓰기). 이력 없음.
route('PUT', '/api/pickups/:id/car', 'car', (a, b, { id: pid }) => {
  const p = open(pickupFor(a, pid));
  if (typeof b.lat !== 'number' || typeof b.lng !== 'number') bad(400, 'bad_location');
  p.car = {
    lat: b.lat, lng: b.lng, heading: typeof b.heading === 'number' ? b.heading : null,
    hazard: b.hazard ?? null,            // true/false, 미지원이면 null → 웹에서 숨김
    turnSignal: b.turnSignal ?? null,    // 'LEFT' | 'RIGHT' | 'OFF' | null
    parked: b.parked ?? null,
    remainingM: b.remainingM ?? null, etaSec: b.etaSec ?? null, nextTurn: b.nextTurn ?? null,
    mock: !!b.mock,                      // 모의 좌표 여부 — 시연 화면에 그대로 표시
    ts: typeof b.ts === 'number' ? b.ts : now(), receivedAt: now(),
  };
  touch(p); return { rev: p.rev };
});
// 가족: 준비 상태. seq 가 올라갈 때마다 차량이 한 번씩만 읽어 준다.
route('PUT', '/api/pickups/:id/member', 'member', (a, b, { id: pid }) => {
  const p = open(pickupFor(a, pid));
  if (!['PREPARING', 'OUTSIDE', 'LATE'].includes(b.state)) bad(400, 'bad_state');
  p.member = {
    state: b.state,
    lateMinutes: b.state === 'LATE' ? Math.max(1, Math.min(120, Number(b.lateMinutes) || 10)) : null,
    reason: b.state === 'LATE' ? String(b.reason ?? '').slice(0, 20) : '',
    seq: p.member.seq + 1,
  };
  touch(p); return view(p);
});

// ── HTTP ───────────────────────────────────────────────
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const send = (status, body, type = 'application/json; charset=utf-8') => {
    res.writeHead(status, { 'content-type': type, 'cache-control': 'no-store',
      'access-control-allow-origin': '*', 'access-control-allow-headers': 'authorization,content-type',
      'access-control-allow-methods': 'GET,POST,PUT,DELETE,OPTIONS' });
    res.end(typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body));
  };
  try {
    if (req.method === 'OPTIONS') return send(204, '');
    if (req.method === 'GET' && !url.pathname.startsWith('/api/')) {
      const file = { '/': 'index.html', '/manifest.webmanifest': 'manifest.webmanifest', '/car': 'car.html', '/demo': 'demo.html' }[url.pathname];
      if (!file) return send(404, { error: 'not_found' });
      return send(200, readFileSync(path.join(DIR, 'public', file)), file.endsWith('html') ? 'text/html; charset=utf-8' : 'application/manifest+json');
    }
    const r = routes.find((x) => x.method === req.method && x.re.test(url.pathname));
    if (!r) return send(404, { error: 'not_found' });
    const m = url.pathname.match(r.re);
    const params = Object.fromEntries(r.keys.map((k, i) => [k, decodeURIComponent(m[i + 1])]));
    let auth = null;
    if (r.who) {
      auth = tokens.get((req.headers.authorization ?? '').replace(/^Bearer /, ''));
      if (!auth || (r.who !== 'any' && auth.kind !== r.who)) return send(401, { error: 'unauthorized' });
    }
    let body = {};
    if (req.method !== 'GET') {
      let raw = ''; for await (const c of req) { raw += c; if (raw.length > 20000) bad(413, 'too_large'); }
      if (raw) { try { body = JSON.parse(raw); } catch { bad(400, 'bad_json'); } }
    }
    expireOld();
    send(200, await r.fn(auth, body, params, url.searchParams));
  } catch (e) {
    if (e instanceof HttpError) send(e.status, { error: e.code });
    else { console.error(e); send(500, { error: 'server_error' }); }
  }
});
const PORT = Number(process.env.PORT ?? 8787);
const PUBLIC_URL = process.env.PUBLIC_URL ?? `http://localhost:${PORT}`;
server.listen(PORT, () => console.log(`마중 서버 ${PUBLIC_URL}`));
export { server };
