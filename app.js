'use strict';

const KN = 1.943844;          // m/s → knot
const DEG = Math.PI / 180;
const R_EARTH = 6371008.8;
const MAX_ACC = 30;           // 이보다 부정확한 GPS 위치는 버림 (m)
const STOP_SPEED = 0.05;      // 이보다 느리면 "거의 정지" (m/s ≈ 0.1 kn)

const DEFAULTS = { windowSec: 40, offset: -8.5 };
const settings = loadSettings();

const state = {
  fixes: [],          // {t, lat, lon, acc}
  lastFix: null,      // 마지막 GPS 수신 정보 (버린 것 포함)
  drift: null,        // estimateDrift() 결과
  heading: null,      // 휴대폰 정면 방위 (진북 기준, 스무딩)
  hs: 0, hc: 0,       // 방위 스무딩용 sin/cos 누적값
  headingAt: 0,
  compassAcc: null,   // iOS 나침반 정확도 (°)
  iosOff: null,       // iOS: 상대 alpha → 절대 방위 오프셋 (sin/cos)
  demo: false,
};

const $ = (id) => document.getElementById(id);
const norm360 = (a) => ((a % 360) + 360) % 360;
const norm180 = (a) => { a = norm360(a); return a > 180 ? a - 360 : a; };

function loadSettings() {
  try { return { ...DEFAULTS, ...JSON.parse(localStorage.getItem('shipnavi') || '{}') }; }
  catch { return { ...DEFAULTS }; }
}
function saveSettings() {
  try { localStorage.setItem('shipnavi', JSON.stringify(settings)); } catch {}
}

// ---------------------------------------------------------------- GPS

function startGps() {
  if (!('geolocation' in navigator)) { setChip('gps', 'bad', 'GPS 없음'); return; }
  navigator.geolocation.watchPosition(
    (p) => addFix(p.timestamp / 1000, p.coords),
    (err) => setChip('gps', 'bad', err.code === 1 ? 'GPS 권한 거부' : 'GPS 오류'),
    { enableHighAccuracy: true, maximumAge: 0, timeout: 30000 },
  );
}

function addFix(t, c) {
  state.lastFix = { at: performance.now(), acc: c.accuracy, speed: c.speed, heading: c.heading };
  if (c.accuracy > MAX_ACC) return;
  const fx = state.fixes;
  if (fx.length && t <= fx[fx.length - 1].t) return;   // 같은 위치 중복 수신
  fx.push({ t, lat: c.latitude, lon: c.longitude, acc: c.accuracy });
  while (t - fx[0].t > Math.max(settings.windowSec, 120)) fx.shift();
  state.drift = estimateDrift();
}

// 측정 구간 안의 GPS 위치들에 직선을 맞춰(가중 최소제곱) 속도 벡터를 구한다.
// 한두 점의 차이로 구하는 것보다 GPS 튐에 훨씬 강하다.
function estimateDrift() {
  const fx = state.fixes;
  const tEnd = fx[fx.length - 1].t;
  const pts = fx.filter((f) => tEnd - f.t <= settings.windowSec);
  const span = tEnd - pts[0].t;
  const minSpan = Math.min(10, settings.windowSec / 2);
  if (pts.length < 5 || span < minSpan) {
    return { warming: true, progress: Math.min(span / minSpan, pts.length / 5) };
  }

  const ref = pts[pts.length - 1];
  const kx = R_EARTH * DEG * Math.cos(ref.lat * DEG);
  const ky = R_EARTH * DEG;
  let W = 0, T = 0, X = 0, Y = 0;
  const rows = pts.map((f) => {
    const r = { t: f.t - tEnd, x: (f.lon - ref.lon) * kx, y: (f.lat - ref.lat) * ky, w: 1 / Math.max(f.acc, 3) ** 2 };
    W += r.w; T += r.w * r.t; X += r.w * r.x; Y += r.w * r.y;
    return r;
  });
  T /= W; X /= W; Y /= W;

  let Stt = 0, Stx = 0, Sty = 0;
  for (const r of rows) {
    const dt = r.t - T;
    Stt += r.w * dt * dt; Stx += r.w * dt * (r.x - X); Sty += r.w * dt * (r.y - Y);
  }
  const vx = Stx / Stt, vy = Sty / Stt;   // 동쪽, 북쪽 성분 (m/s)

  let Srr = 0;
  for (const r of rows) {
    const dt = r.t - T;
    const ex = r.x - X - vx * dt, ey = r.y - Y - vy * dt;
    Srr += r.w * (ex * ex + ey * ey);
  }
  const se = Math.sqrt(Srr / (2 * (rows.length - 2)) / Stt);   // 축당 속도 표준오차 (m/s)
  const speed = Math.hypot(vx, vy);
  return { speed, bearing: norm360(Math.atan2(vx, vy) / DEG), se, n: rows.length, span };
}

function quality(d) {
  if (!d || d.warming) return null;
  if (d.speed < STOP_SPEED) return 'stop';
  const ratio = d.speed / Math.max(d.se, 1e-6);
  return ratio >= 6 ? 'good' : ratio >= 3 ? 'ok' : 'low';
}

// ---------------------------------------------------------------- 나침반

// W3C DeviceOrientation 회전행렬로, 휴대폰 "위쪽 모서리 + 뒷면"이 향하는 방향의 방위를 구한다.
// 눕혀 들면 위쪽 모서리 방향, 세워 들면 카메라가 향하는 방향 = 내가 바라보는 방향.
function lookHeading(alpha, beta, gamma) {
  const cA = Math.cos(alpha * DEG), sA = Math.sin(alpha * DEG);
  const cB = Math.cos(beta * DEG), sB = Math.sin(beta * DEG);
  const cG = Math.cos(gamma * DEG), sG = Math.sin(gamma * DEG);
  const east = -sA * cB - (cA * sG + sA * sB * cG);
  const north = cA * cB - (sA * sG - cA * sB * cG);
  return norm360(Math.atan2(east, north) / DEG);
}

async function startCompass() {
  const DOE = window.DeviceOrientationEvent;
  if (!DOE) { setChip('compass', 'bad', '나침반 없음'); return; }

  if (typeof DOE.requestPermission === 'function') {
    // iOS: 사용자 터치 안에서 권한 요청 필요
    const res = await DOE.requestPermission().catch(() => 'denied');
    if (res !== 'granted') { setChip('compass', 'bad', '나침반 권한 거부'); return; }
    window.addEventListener('deviceorientation', onOrientIOS);
  } else if ('ondeviceorientationabsolute' in window) {
    window.addEventListener('deviceorientationabsolute', onOrientAbs);
  } else {
    window.addEventListener('deviceorientation', (e) => { if (e.absolute) onOrientAbs(e); });
  }
  setTimeout(() => { if (state.heading === null) setChip('compass', 'bad', '나침반 없음'); }, 3000);
}

function onOrientAbs(e) {
  if (e.alpha == null) return;
  pushHeading(lookHeading(e.alpha, e.beta, e.gamma));
}

// iOS는 alpha가 임의 기준이라, 휴대폰이 어느 정도 누워 있을 때 webkitCompassHeading(위쪽 모서리 방위)과
// 비교해 오프셋을 학습한 뒤, 세워 들었을 때도 회전행렬로 방향을 계산한다.
function onOrientIOS(e) {
  const wch = e.webkitCompassHeading;
  if (wch == null || e.alpha == null) return;
  state.compassAcc = e.webkitCompassAccuracy;
  if (Math.abs(e.beta) < 60) {
    const off = (wch + e.alpha) * DEG;
    if (!state.iosOff) state.iosOff = { s: Math.sin(off), c: Math.cos(off) };
    else {
      state.iosOff.s += 0.1 * (Math.sin(off) - state.iosOff.s);
      state.iosOff.c += 0.1 * (Math.cos(off) - state.iosOff.c);
    }
  }
  if (!state.iosOff) { pushHeading(wch); return; }
  const off = Math.atan2(state.iosOff.s, state.iosOff.c) / DEG;
  pushHeading(norm360(lookHeading(e.alpha, e.beta, e.gamma) + off));
}

function pushHeading(magHeading) {
  const h = norm360(magHeading + settings.offset) * DEG;
  if (state.heading === null) { state.hs = Math.sin(h); state.hc = Math.cos(h); }
  else {
    state.hs += 0.2 * (Math.sin(h) - state.hs);
    state.hc += 0.2 * (Math.cos(h) - state.hc);
  }
  state.heading = norm360(Math.atan2(state.hs, state.hc) / DEG);
  state.headingAt = performance.now();
}

// ---------------------------------------------------------------- 화면

const DIR16 = ['북', '북북동', '북동', '동북동', '동', '동남동', '남동', '남남동', '남', '남남서', '남서', '서남서', '서', '서북서', '북서', '북북서'];
const dirName = (b) => DIR16[Math.round(b / 22.5) % 16];

function buildDial() {
  const svg = 'http://www.w3.org/2000/svg';
  const el = (tag, attrs, parent) => {
    const n = document.createElementNS(svg, tag);
    for (const k in attrs) n.setAttribute(k, attrs[k]);
    parent.appendChild(n);
    return n;
  };

  const ticks = $('ticks');
  for (let d = 0; d < 360; d += 10) {
    const major = d % 30 === 0;
    const r1 = 136, r2 = major ? 118 : 126;
    const s = Math.sin(d * DEG), c = -Math.cos(d * DEG);
    el('line', { x1: s * r1, y1: c * r1, x2: s * r2, y2: c * r2, class: major ? 'major' : '' }, ticks);
    if (major && d % 90) {
      el('text', { x: s * 106, y: c * 106, class: 'deg', transform: `rotate(${d} ${s * 106} ${c * 106})` }, ticks).textContent = d;
    }
  }
  ['N', 'E', 'S', 'W'].forEach((t, i) => {
    const d = i * 90, s = Math.sin(d * DEG), c = -Math.cos(d * DEG);
    el('text', { x: s * 100, y: c * 100, class: i ? '' : 'n', transform: `rotate(${d} ${s * 100} ${c * 100})` }, ticks).textContent = t;
  });

  const clock = $('clock');
  for (let h = 1; h <= 12; h++) {
    if (h === 12) continue;   // 12시 자리는 정면 표시 삼각형
    const d = h * 30, s = Math.sin(d * DEG), c = -Math.cos(d * DEG);
    el('text', { x: s * 154, y: c * 154 }, clock).textContent = h;
  }
}

function setArrow(lengthPx, coneDeg) {
  const L = lengthPx;
  $('shaft').setAttribute('y2', -L + 6);
  $('head').setAttribute('points', `0,${-L - 18} -15,${-L + 8} 15,${-L + 8}`);
  const t = Math.min(coneDeg, 89) * DEG, r = L + 18;
  $('cone').setAttribute('d', coneDeg > 1
    ? `M0,0 L${-r * Math.sin(t)},${-r * Math.cos(t)} A${r},${r} 0 0 1 ${r * Math.sin(t)},${-r * Math.cos(t)} Z`
    : '');
}

function setChip(name, cls, text) {
  const c = $('chip-' + name);
  c.className = 'chip ' + (cls || '');
  c.textContent = text;
}

// 배가 rel 방향(내 정면 기준)으로 밀릴 때, 채비가 어떻게 되는지 (바다 쪽을 보고 서 있다고 가정)
function hintFor(rel) {
  const a = Math.abs(rel);
  if (a <= 45) return '배가 <b>내 앞(바다) 쪽</b>으로 밀려요 → 채비가 <b>배 밑으로</b> 들어가기 쉬워요';
  if (a >= 135) return '배가 <b>내 뒤쪽</b>으로 밀려요 → 채비가 <b>앞으로 뻗어</b> 나가요';
  return rel > 0
    ? '배가 <b>오른쪽</b>으로 밀려요 → 채비는 <b>왼쪽</b>으로 기울어요'
    : '배가 <b>왼쪽</b>으로 밀려요 → 채비는 <b>오른쪽</b>으로 기울어요';
}

let shown = { card: 0, arrow: 0 };
let lastText = 0;

function frame(now) {
  requestAnimationFrame(frame);
  const H = state.heading;
  const hasCompass = H !== null && now - state.headingAt < 3000;
  const d = state.drift;
  const q = quality(d);

  // 방위판은 -heading만큼 돌리고, 화살표는 (흐름 방위 - heading)만큼 돌린다.
  const cardTarget = hasCompass ? -H : 0;
  const arrowTarget = d && !d.warming ? d.bearing - (hasCompass ? H : 0) : shown.arrow;
  shown.card += norm180(cardTarget - shown.card) * 0.25;
  shown.arrow += norm180(arrowTarget - shown.arrow) * 0.15;
  $('card').setAttribute('transform', `rotate(${shown.card})`);
  $('arrow').setAttribute('transform', `rotate(${shown.arrow})`);
  $('front-mark').style.display = hasCompass ? '' : 'none';
  $('clock').style.display = hasCompass ? '' : 'none';

  const arrow = $('arrow');
  arrow.classList.toggle('hidden', !q || q === 'stop');
  arrow.classList.toggle('weak', q === 'low');
  if (q && q !== 'stop') {
    const kn = d.speed * KN;
    setArrow(40 + Math.min(kn / 2, 1) * 50, Math.atan2(2 * d.se, d.speed) / DEG);
  }

  if (now - lastText > 250) { lastText = now; updateText(hasCompass, d, q); }
}

function updateText(hasCompass, d, q) {
  const lf = state.lastFix;
  if (!lf) setChip('gps', '', state.demo ? '데모 GPS' : 'GPS 대기');
  else if (performance.now() - lf.at > 10000) setChip('gps', 'bad', 'GPS 끊김');
  else setChip('gps', lf.acc <= 10 ? 'good' : lf.acc <= MAX_ACC ? 'warn' : 'bad', `GPS ±${Math.round(lf.acc)}m`);

  if (hasCompass) {
    const acc = state.compassAcc;
    if (acc != null && (acc < 0 || acc > 25)) setChip('compass', 'warn', '나침반 보정 필요');
    else setChip('compass', 'good', `나침반 ${Math.round(state.heading)}°`);
  } else if (state.heading !== null) {
    setChip('compass', 'bad', '나침반 끊김');
  }

  if (!d || d.warming) {
    setChip('quality', '', '측정 중');
    $('spd').textContent = '--';
    $('spd-sub').innerHTML = '&nbsp;';
    $('rel').textContent = d ? `측정 중… ${Math.round(d.progress * 100)}%` : 'GPS 신호 기다리는 중…';
    $('abs').innerHTML = '&nbsp;';
    $('hint').innerHTML = '&nbsp;';
    return;
  }

  const kn = d.speed * KN;
  $('spd').textContent = kn < 10 ? kn.toFixed(1) : Math.round(kn);
  $('spd-sub').textContent = `분당 ${Math.round(d.speed * 60)}m · ${d.span.toFixed(0)}초 평균`;

  if (q === 'stop') {
    setChip('quality', 'good', '거의 정지');
    $('rel').textContent = '배가 거의 멈춰 있어요';
    $('abs').innerHTML = '&nbsp;';
    $('hint').innerHTML = '&nbsp;';
    return;
  }
  setChip('quality', q === 'good' ? 'good' : q === 'ok' ? 'warn' : 'bad',
    q === 'good' ? '신뢰도 높음' : q === 'ok' ? '신뢰도 보통' : '신뢰도 낮음');

  const absText = `흐름 방위 ${dirName(d.bearing)} ${String(Math.round(d.bearing)).padStart(3, '0')}°`;
  if (!hasCompass) {
    $('rel').textContent = absText;
    $('abs').textContent = '나침반이 없어 화면 위쪽 = 북쪽';
    $('hint').innerHTML = '&nbsp;';
    return;
  }

  const rel = norm180(d.bearing - state.heading);
  const a = Math.round(Math.abs(rel));
  const clock = Math.round(norm360(rel) / 30) % 12 || 12;
  const side = a <= 10 ? '정면' : a >= 170 ? '뒤쪽' : `${rel > 0 ? '오른쪽' : '왼쪽'} ${a}°`;
  $('rel').textContent = `${clock}시 방향 · ${side}`;
  $('abs').textContent = absText;
  $('hint').innerHTML = q === 'low' ? 'GPS 흔들림이 커서 방향이 부정확할 수 있어요' : hintFor(rel);
}

// ---------------------------------------------------------------- 기타

let wakeLock = null;
async function keepAwake() {
  try { wakeLock = await navigator.wakeLock?.request('screen'); } catch {}
}
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && wakeLock) keepAwake();
});

function toast(msg) {
  const t = $('toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { t.hidden = true; }, 3500);
}

function calibrate() {
  const lf = state.lastFix, d = state.drift;
  let course = null;
  if (lf && lf.speed > 1 && Number.isFinite(lf.heading)) course = lf.heading;
  else if (d && !d.warming && d.speed > 1) course = d.bearing;
  if (course === null) { toast('배가 2노트 이상으로 직진할 때 사용하세요'); return; }
  if (state.heading === null) { toast('나침반 값이 없어요'); return; }
  settings.offset = Math.round(norm180(settings.offset + norm180(course - state.heading)) * 10) / 10;
  saveSettings();
  $('set-offset').value = settings.offset;
  state.heading = null;   // 스무딩 초기화
  toast(`나침반 보정값을 ${settings.offset}°로 맞췄어요`);
}

function startDemo() {
  state.demo = true;
  const v = 0.8 / KN, brg = 60 * DEG;               // 0.8노트로 북동쪽(060°) 흐름
  const ky = R_EARTH * DEG, kx = ky * Math.cos(34.7 * DEG);
  let lat = 34.7, lon = 127.75;
  const gauss = () => Math.sqrt(-2 * Math.log(Math.random() || 1e-9)) * Math.cos(2 * Math.PI * Math.random());
  setInterval(() => {
    lat += v * Math.cos(brg) / ky;
    lon += v * Math.sin(brg) / kx;
    addFix(Date.now() / 1000, {
      latitude: lat + gauss() * 1.5 / ky, longitude: lon + gauss() * 1.5 / kx,
      accuracy: 4, speed: v, heading: 60,
    });
  }, 1000);

  const slider = $('demo-h');
  setInterval(() => {
    // 실제 나침반이 없을 때만 슬라이더 값 사용
    if (performance.now() - state.headingAt > 500 || state.demoHeading) {
      state.demoHeading = true;
      $('demo-ctrl').hidden = false;
      pushHeading(+slider.value - settings.offset);
    }
  }, 50);
  slider.addEventListener('input', () => { $('demo-h-val').textContent = slider.value + '°'; });
}

function initSettings() {
  const dlg = $('settings');
  $('btn-settings').onclick = () => {
    $('set-window').value = settings.windowSec;
    $('set-offset').value = settings.offset;
    $('set-demo').checked = state.demo;
    dlg.showModal();
  };
  $('set-window').onchange = (e) => {
    settings.windowSec = +e.target.value; saveSettings();
    if (state.fixes.length) state.drift = estimateDrift();
  };
  $('set-offset').onchange = (e) => {
    const v = parseFloat(e.target.value);
    if (Number.isFinite(v)) { settings.offset = norm180(v); saveSettings(); }
  };
  $('btn-calib').onclick = calibrate;
  $('btn-reset').onclick = () => {
    settings.offset = DEFAULTS.offset; saveSettings();
    $('set-offset').value = settings.offset;
    toast('보정값을 −8.5°로 되돌렸어요');
  };
  $('set-demo').onchange = (e) => {
    const url = new URL(location.href);
    if (e.target.checked) url.searchParams.set('demo', '1'); else url.searchParams.delete('demo');
    location.href = url.toString();
  };
}

$('btn-start').onclick = async () => {
  $('btn-start').disabled = true;
  await startCompass();
  if (new URLSearchParams(location.search).has('demo')) startDemo();
  else startGps();
  keepAwake();
  $('start').hidden = true;
};

if (!window.isSecureContext) {
  $('start-err').textContent = 'HTTPS 주소에서 열어야 GPS·나침반을 쓸 수 있어요.';
}

buildDial();
initSettings();
requestAnimationFrame(frame);

if ('serviceWorker' in navigator && window.isSecureContext) {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
