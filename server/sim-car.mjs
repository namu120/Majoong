// 차량 시뮬레이터 — 차량 앱이 완성되기 전에 서버·탑승자 웹을 혼자 시험하기 위한 도구.
// 차량 앱이 서버에 대해 해야 하는 일을 순서 그대로 보여 주므로, Kotlin SyncRepository 구현의 참고서로도 쓴다.
// 실행: node sim-car.mjs            (서버가 떠 있어야 함. BASE 환경변수로 주소 변경)
//   y / n + Enter : 변경 요청에 동의 / 거절 (아무것도 안 누르면 8초 뒤 무응답 처리)
//   h + Enter     : 비상등 토글      e + Enter : 탑승 종료
import readline from 'node:readline';

const BASE = process.env.BASE ?? 'http://localhost:8787';
let token = null;
const api = async (method, path, body) => {
  const res = await fetch(BASE + path, { method, headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const json = await res.json();
  if (!res.ok) throw Object.assign(new Error(json.error), { status: res.status });
  return json;
};
const ro = (w) => { const c = w.charCodeAt(w.length - 1); if (c < 0xac00 || c > 0xd7a3) return w + '(으)로'; const j = (c - 0xac00) % 28; return w + (j === 0 || j === 8 ? '로' : '으로'); };   // 한국어 조사 (으)로
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const say = (t) => console.log(`\x1b[36m[음성]\x1b[0m ${t}`);
const log = (t) => console.log(`\x1b[90m[차량]\x1b[0m ${t}`);
const dist = (a, b) => { const R = 6371000, r = Math.PI / 180, dLat = (b.lat - a.lat) * r, dLng = (b.lng - a.lng) * r;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin(dLng / 2) ** 2; return 2 * R * Math.asin(Math.sqrt(h)); };
const bearing = (a, b) => { const r = Math.PI / 180, y = Math.sin((b.lng - a.lng) * r) * Math.cos(b.lat * r);
  const x = Math.cos(a.lat * r) * Math.sin(b.lat * r) - Math.sin(a.lat * r) * Math.cos(b.lat * r) * Math.cos((b.lng - a.lng) * r); return (Math.atan2(y, x) / r + 360) % 360; };

const rl = readline.createInterface({ input: process.stdin });
let pendingAnswer = null, hazard = false, endRequested = false;
rl.on('line', (line) => {
  const k = line.trim().toLowerCase();
  if (k === 'h') { hazard = !hazard; log(`비상등 ${hazard ? '켜짐' : '꺼짐'}`); }
  else if (k === 'e') endRequested = true;
  else if (pendingAnswer && (k === 'y' || k === 'n')) pendingAnswer(k === 'y' ? 'yes' : 'no');
});
const listen = (ms) => new Promise((res) => { const t = setTimeout(() => { pendingAnswer = null; res('none'); }, ms); pendingAnswer = (a) => { clearTimeout(t); pendingAnswer = null; res(a); }; });

// 1) 차량 등록 + 초대
const car = await api('POST', '/api/cars', { alias: '회색 SUV' }); token = car.carToken;
const inv = await api('POST', '/api/invites');
console.log(`\n휴대폰(또는 브라우저)에서 이 주소를 여세요:\n  ${inv.url}\n`);

// 2) 가족이 연결될 때까지 대기
let member;
for (;;) { const me = await api('GET', '/api/cars/me'); if (me.members.length) { member = me.members[0]; break; } await sleep(1500); }
log(`${member.name} 연결됨`);

// 3) 픽업 시작 (저장된 기본 지점이 있으면 그 지점을 첫 제안으로)
const def = member.spots.find((s) => s.isDefault);
let p = await api('POST', '/api/pickups', { memberId: member.memberId, spotId: def?.spotId });
log(`픽업 시작. ${def ? `기본 지점 ${def.name}` : '탑승자가 지점을 고르길 기다리는 중'}`);

let pos = { lat: 37.4925, lng: 127.0205 }, heading = 0, lastMemberSeq = 0, memoRead = false;

// 4) 위치 전송 루프 (1초마다 최신 좌표 덮어쓰기)
(async () => {
  while (!['ENDED', 'CANCELLED', 'EXPIRED'].includes(p.status)) {
    const dest = p.confirmedSpot;
    if (dest && ['EN_ROUTE', 'NEAR'].includes(p.status)) {
      const d = dist(pos, dest);
      if (d > 8) { heading = bearing(pos, dest); const step = Math.min(12, d) / d; pos = { lat: pos.lat + (dest.lat - pos.lat) * step, lng: pos.lng + (dest.lng - pos.lng) * step }; }
      if (d < 150 && p.status === 'EN_ROUTE') { await api('POST', `/api/pickups/${p.pickupId}/status`, { status: 'NEAR' }).catch(() => {}); log('도착 근처'); }
      if (d < 150 && !memoRead && dest.memo) { memoRead = true; say(`${dest.name}, ${dest.memo}`); }
      await api('PUT', `/api/pickups/${p.pickupId}/car`, { ...pos, heading, hazard, remainingM: Math.round(d), etaSec: Math.round(d / 8), mock: true, ts: Date.now() }).catch(() => {});
    } else {
      await api('PUT', `/api/pickups/${p.pickupId}/car`, { ...pos, heading, hazard, mock: true, ts: Date.now() }).catch(() => {});
    }
    if (endRequested) { await api('POST', `/api/pickups/${p.pickupId}/status`, { status: 'ENDED' }).catch(() => {}); }
    await sleep(1000);
  }
})();

// 5) 상태 수신 루프 (롱폴링)
for (;;) {
  p = await api('GET', `/api/pickups/${p.pickupId}?since=${p.rev}`).catch(async () => { await sleep(2000); return p; });
  if (['ENDED', 'CANCELLED', 'EXPIRED'].includes(p.status)) { log(`픽업 ${p.status}`); break; }

  if (p.member.seq > lastMemberSeq) {   // 준비 상태는 seq 가 바뀔 때 한 번만 읽는다
    lastMemberSeq = p.member.seq;
    say({ PREPARING: `${member.name}, 준비 중입니다.`, OUTSIDE: `${member.name}, 나와 있어요.`, LATE: `${member.name}, ${p.member.lateMinutes}분 늦어요.${p.member.reason ? ' 사유, ' + p.member.reason : ''}` }[p.member.state]);
  }
  if (p.proposal) {
    const { seq, spot, reason } = p.proposal;
    let answer = 'yes';
    if (p.confirmedSpot) {              // 이동 중 변경은 운전자 동의가 필요
      say(`${reason ? reason.replace(/요$/, '서') + ' ' : ''}${ro(spot.name)} 변경 요청이 왔습니다. 안내할까요?  (y/n)`);
      answer = await listen(8000);
    }
    let result = answer === 'yes' ? 'confirmed' : answer === 'no' ? 'declined' : 'no_answer';
    if (answer === 'yes') { await sleep(600); /* 실제 앱: requestRoute → 성공 콜백 대기. 실패·시간 초과면 route_failed */ }
    try {
      p = await api('POST', `/api/pickups/${p.pickupId}/proposals/${seq}/resolve`, { result });
      memoRead = false;
      say(result === 'confirmed' ? `${ro(spot.name)} 안내를 시작합니다.` : `기존 장소로 계속 안내합니다.`);
      if (result === 'confirmed' && p.status === 'SPOT_CONFIRMED') { p = await api('POST', `/api/pickups/${p.pickupId}/status`, { status: 'EN_ROUTE' }); log('출발'); }
    } catch (e) { if (e.status === 409) log('그 사이 새 요청이 와서 이전 응답은 버림'); }
  }
}
process.exit(0);
