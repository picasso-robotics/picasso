// 칸과 간선을 빌드에서 읽는다. **간선은 전부 그린다** — 바닥의 계약 어휘 둘로 가는 것만 수로 적는다.
// 문법은 engineering-diagram-svg(기준본 hero.svg)를 따른다: 토큰 8색, 직각 경로, 반전 칸은 핵심만.
// 모듈이 전부 이 저장소의 것이라 색으로는 못 가른다. 그래서 **역할은 영역 테두리로** 묶는다.
// ★간선마다 경로를 아래 ROUTES 에 손으로 정해 둔다. 빌드에 경로 없는 간선이 생기면 **멈춘다** —
//   자동 배치로 넘기면 그림이 조용히 엉키고, 멈추면 사람이 자리를 정한다.
// 문구는 components.labels.json 에 따로 둔다(배치와 문구를 따로 고친다).
import { writeFileSync, readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const W = 840, H = 560;
const INK = '#262626', GREY = '#6f6f6f', HAIR = '#bdbdbd', GOV = '#2b3f6b', TINT = '#e8ebf3', TEXT2 = '#cfd6e6';
const SANS = "'Helvetica Neue', Helvetica, Arial, 'Apple SD Gothic Neo', 'Malgun Gothic', 'Noto Sans KR', 'Noto Sans CJK KR', sans-serif";
const MONO = "Menlo, Consolas, 'DejaVu Sans Mono', monospace";
const T = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'components.labels.json'), 'utf8'));

// ── 빌드에서 읽는다
const ROOT = process.argv[4] || '.';
const SHIP = new Set(['api', 'implementation', 'compileOnly', 'runtimeOnly']);
const mods = [...new Set([...readFileSync(join(ROOT, 'settings.gradle.kts'), 'utf8')
  .split('rootProject.name')[1].matchAll(/"([a-z0-9-]+)"/g)].map(m => m[1]))].sort();
const graph = {};
for (const m of mods) {
  const f = join(ROOT, m, 'build.gradle.kts');
  if (!existsSync(f)) continue;
  graph[m] = [...new Set([...readFileSync(f, 'utf8')
    .matchAll(/^\s*(\w+)\s*\(\s*(?:testFixtures\s*\(\s*)?project\("[:]([a-z0-9-]+)"\)/gm)]
    .filter(x => SHIP.has(x[1])).map(x => x[2]))].sort();
}
const ROOTS = ['contracts', 'profile-model'];
const usersOf = n => Object.entries(graph).filter(([, d]) => d.includes(n)).map(([m]) => m);
const rootEdges = Object.values(graph).flat().filter(d => ROOTS.includes(d)).length;

// ── 칸. 열은 x 32 부터 160 간격, 폭 128(사이 통로 32). 행 A 112, B 216, C 352, 바닥 456
const col = i => 32 + 160 * i;
const BOX = {
  'host-kit': [col(0), 112, 'host-kit', T.COMP_ROLE_HOSTKIT],
  harness: [col(1), 112, 'harness', T.COMP_ROLE_HARNESS],
  orbit: [col(3), 112, 'orbit', T.COMP_ROLE_ORBIT],
  picasso: [col(0), 216, 'picasso', T.COMP_ROLE_PICASSO],
  mimic: [col(1), 216, 'mimic', T.COMP_ROLE_MIMIC],
  'adapter-host': [col(2), 216, 'adapter-host', T.COMP_ROLE_HOST],
  vendor3: [col(3), 216, 'g1, spot, digit', T.COMP_ROLE_VENDOR3],
  registry: [col(4), 216, 'registry', T.COMP_ROLE_REGISTRY],
  client: [col(0), 352, 'client', T.COMP_ROLE_CLIENT],
  capability: [col(1), 352, 'capability', T.COMP_ROLE_CAPABILITY],
  uplink: [col(2), 352, 'uplink', T.COMP_ROLE_UPLINK],
  'adapter-core': [col(3), 352, 'adapter-core', T.COMP_ROLE_CORE],
  gate: [col(4), 352, 'gate', T.COMP_ROLE_GATE],
};
// 역할 영역. 칸에서 8 떨어져 두르고, 영역 사이 통로(폭 16)의 한가운데로 선이 지난다.
const ZONES = [
  [24, 84, 144, 328, T.COMP_ZONE_CONSUMER],
  [184, 84, 304, 192, T.COMP_ZONE_RUNTIME],
  // 위에서 내려오는 화살표가 이 영역의 윗변을 많이 지나므로 라벨은 칸 아래에 둔다.
  [184, 324, 304, 112, T.COMP_ZONE_LIB, 'bottom'],
  [504, 84, 144, 328, T.COMP_ZONE_ADAPTERS],
  [664, 188, 144, 224, T.COMP_ZONE_OPS],
];
// 표시 칸 하나가 모듈 여럿을 뜻할 수 있다. 주석은 실제 모듈 이름으로 낸다.
const CELL = {
  'adapter-unitree-g1': 'vendor3', 'adapter-boston-dynamics-spot': 'vendor3', 'adapter-agility-digit': 'vendor3',
  'adapter-boston-dynamics-orbit': 'orbit',
};
const cell = m => CELL[m] || m;

// ── 경로. 표시 칸 쌍마다 하나. 행 사이 가로 구간은 영역 밖(y 284~316)에 둔다
const ROUTES = {
  'host-kit>picasso': 'M96,164 V214',
  'picasso>client': 'M96,268 V350',
  'picasso>capability': 'M128,268 V292 H224 V350',
  'mimic>capability': 'M256,268 V350',
  'mimic>uplink': 'M320,252 H332 V400 H350',
  'adapter-host>capability': 'M384,268 V308 H296 V350',
  'adapter-host>uplink': 'M416,268 V350',
  'adapter-host>adapter-core': 'M456,268 V300 H548 V350',
  'vendor3>adapter-core': 'M600,268 V350',
  'registry>gate': 'M736,268 V350',
  'harness>mimic': 'M256,164 V214',
  // 왼쪽 열 맨 위에 host-kit 이 서서 바깥으로 돌던 길을 통로(x 172, 영역 테두리 168 바로 안쪽)로 옮겼다. harness>capability 의 세로 구간(x 176)과 한 번 직각으로 엇갈린다.
  'harness>client': 'M192,160 H172 V378 H162',
  // 통로(x 168~184)의 한가운데로 내려간다. picasso>capability 의 가로 구간(y 292)과 한 번 직각으로 엇갈린다.
  'harness>capability': 'M192,148 H176 V392 H190',
  'harness>uplink': 'M320,148 H340 V384 H350',
  'orbit>adapter-host': 'M512,132 H432 V214',
  'orbit>uplink': 'M512,156 H496 V392 H482',
  'orbit>adapter-core': 'M640,140 H656 V378 H642',
};

const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const text = (x, y, s, { size = 11, fill = null, weight = null, mono = false, anchor = null } = {}) =>
  `<text x="${x}" y="${y}" font-size="${size}"${weight ? ` font-weight="${weight}"` : ''}` +
  `${fill ? ` fill="${fill}"` : ''}${anchor ? ` text-anchor="${anchor}"` : ''}` +
  `${mono ? ` font-family="${MONO}"` : ''}>${esc(s)}</text>`;

const o = [];
o.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">`);
o.push('  <defs>');
o.push(`    <marker id="data" markerUnits="userSpaceOnUse" markerWidth="10" markerHeight="10" refX="8" refY="5" orient="auto">`);
o.push(`      <path d="M1,1 L8,5 L1,9" fill="none" stroke="${INK}" stroke-width="1.2"/>`);
o.push('    </marker>');
o.push('  </defs>');
// 시험이 읽는다. 바닥 둘로 가는 간선은 수로 적으므로 여기 안 싣는다.
const drawn = new Set();
for (const [m, deps] of Object.entries(graph)) {
  for (const d of deps) {
    if (ROOTS.includes(d)) continue;
    const key = `${cell(m)}>${cell(d)}`;
    if (!ROUTES[key]) throw new Error(`경로가 없는 간선: ${m} -> ${d} (${key}). ROUTES 에 자리를 정하라`);
    drawn.add(key);
    o.push(`  <!-- edge: ${m} -> ${d} -->`);
  }
}
for (const k of Object.keys(ROUTES)) if (!drawn.has(k)) throw new Error(`빌드에 없는 경로: ${k}`);
// 바닥 둘로 가는 간선은 그림에 안 그리고 수도 화면에 적지 않는다. 시험이 대조할 수만 주석으로 싣는다.
o.push(`  <!-- hidden-edges: ${rootEdges} -->`);

o.push(`  <g font-family="${SANS}" fill="${INK}">`);
o.push('    ' + text(20, 36, T.COMP_TITLE, { size: 17, weight: 700 }));
o.push('    ' + text(20, 56, T.COMP_SUB.replace(/모듈 \d+ 개/, `모듈 ${Object.keys(graph).length} 개`), { size: 12, fill: GREY }));

o.push('\n    <!-- role zones -->');
for (const [x, y, w, h, label, at] of ZONES) {
  o.push(`    <rect x="${x}" y="${y}" width="${w}" height="${h}" fill="none" stroke="${HAIR}"/>`);
  o.push('    ' + text(x + 12, at === 'bottom' ? y + h - 8 : y + 16, label, { fill: GREY }));
}

o.push('\n    <!-- modules -->');
for (const [x, y, name, role] of Object.values(BOX)) {
  o.push(`    <rect x="${x}" y="${y}" width="128" height="52" fill="${TINT}" stroke="${GOV}" stroke-width="1.5"/>`);
  o.push('    ' + text(x + 10, y + 23, name, { size: 12.5, weight: 700, mono: true }));
  o.push('    ' + text(x + 10, y + 39, role, { fill: GREY }));
}

o.push('\n    <!-- foundation: contract vocabularies -->');
[[32, 'contracts', T.COMP_ROLE_CONTRACTS, T.COMP_USERS_CONTRACTS],
 [432, 'profile-model', T.COMP_ROLE_PROFILEMODEL, T.COMP_USERS_PROFILEMODEL]].forEach(([x, name, role, users]) => {
  o.push(`    <rect x="${x}" y="456" width="376" height="52" fill="${GOV}" stroke="${GOV}" stroke-width="1.5"/>`);
  o.push(`    <rect x="${x + 4}" y="460" width="368" height="44" fill="none" stroke="#fff" stroke-width="0.75" stroke-opacity="0.6"/>`);
  o.push('    ' + text(x + 16, 479, name, { size: 12.5, weight: 700, fill: '#fff', mono: true }));
  o.push('    ' + text(x + 16, 495, role, { fill: TEXT2 }));
  o.push('    ' + text(x + 360, 479, users.replace(/^\d+/, String(usersOf(name).length)), { fill: TEXT2, anchor: 'end' }));
});

o.push('\n    <!-- edges: shipping dependencies -->');
o.push(`    <g fill="none" stroke="${INK}" stroke-width="1.2">`);
for (const d of Object.values(ROUTES)) o.push(`      <path d="${d}" marker-end="url(#data)"/>`);
o.push('    </g>');

o.push('\n    <!-- key -->');
o.push(`    <g font-size="10.5" fill="${GREY}">`);
o.push(`      <path d="M20,536 H44" fill="none" stroke="${INK}" stroke-width="1.2"/>`);
o.push('      ' + text(50, 540, T.COMP_KEY_ARROW));
o.push(`      <rect x="160" y="530" width="14" height="10" fill="${TINT}" stroke="${GOV}" stroke-width="1.5"/>`);
o.push('      ' + text(180, 540, T.KEY_PROJECT));
o.push(`      <rect x="268" y="530" width="14" height="10" fill="${GOV}" stroke="${GOV}"/>`);
o.push('      ' + text(288, 540, T.KEY_CORE));
o.push(`      <rect x="368" y="530" width="14" height="10" fill="none" stroke="${HAIR}"/>`);
o.push('      ' + text(388, 540, T.KEY_ZONE));
o.push('    </g>');
o.push('  </g>');
o.push('</svg>');

writeFileSync(process.argv[2], o.join('\n') + '\n');
console.log(`${W}x${H} · 간선 ${drawn.size} 경로 · contracts ${usersOf('contracts').length} · profile-model ${usersOf('profile-model').length} · 수로 적은 간선 ${rootEdges}`);
