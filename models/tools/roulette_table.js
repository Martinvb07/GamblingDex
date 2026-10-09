// Construye roulette_table.bbmodel (mesa de ruleta americana: rueda + pano con el tablero)
// con la API de Blockbench (formato Generic / ModelEngine). Se ejecuta dentro de Blockbench
// (ver render.mjs). La rueda (diametro 48) esta en el origen y el tablero sigue hacia +X.
// Animaciones: idle (el plato gira despacio) y ball_<N> (la bola gira, cae y se queda en
// el numero N; N = 0..36 o 00).
//
// Tablero (unidades del modelo, 16 = 1 bloque; lo usa StationModels para saber donde se
// clickeo): numeros x 36..108 (12 columnas de 6), z -12..12 (3 filas de 8; la fila de
// 1,4,7... en z 4..12); 0 / 00 en x 28..36; "2 a 1" en x 108..116; docenas en z 12..18 y
// apuestas de afuera en z 18..24.
(function () {
  newProject(Formats.free);
  Project.name = 'roulette_table';
  Project.texture_width = 512; Project.texture_height = 512;

  // Orden de la rueda (igual que WorldRouletteTables.WHEEL_ORDER; 37 = "00")
  const ORDER = [0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23, 10,
    37, 5, 24, 16, 33, 1, 20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26];
  const REDS = new Set([1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36]);
  const N = ORDER.length, STEP = 360 / N;

  // ---------- Textura 256x128 ----------
  const cv = document.createElement('canvas'); cv.width = 512; cv.height = 512;
  const g = cv.getContext('2d');
  const px = (x, y, c) => { g.fillStyle = c; g.fillRect(x, y, 1, 1); };
  const rect = (x, y, w, h, c) => { g.fillStyle = c; g.fillRect(x, y, w, h); };
  let seed = 5; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  const noise = (x0, y0, w, h, cols) => { for (let y = y0; y < y0 + h; y++) for (let x = x0; x < x0 + w; x++) px(x, y, cols[Math.floor(rnd() * cols.length)]); };

  // Plato (128x128, radio 17 unidades = 64 px): numeros afuera, casillas, cono de madera adentro
  const DIG = { '0': ['111', '101', '101', '101', '111'], '1': ['010', '110', '010', '010', '111'], '2': ['111', '001', '111', '100', '111'],
    '3': ['111', '001', '111', '001', '111'], '4': ['101', '101', '111', '001', '001'], '5': ['111', '100', '111', '001', '111'],
    '6': ['111', '100', '111', '101', '111'], '7': ['111', '001', '010', '010', '010'], '8': ['111', '101', '111', '101', '111'], '9': ['111', '101', '111', '001', '111'] };
  const colorOf = (n) => n === 0 || n === 37 ? ['#1d8a3a', '#14652a'] : REDS.has(n) ? ['#c01826', '#8c101b'] : ['#1c1c20', '#0e0e10'];
  const C = 64;
  for (let y = 0; y < 128; y++) for (let x = 0; x < 128; x++) {
    const dx = x + 0.5 - C, dz = y + 0.5 - C, r = Math.hypot(dx, dz) / 64 * 17; // en unidades
    if (r > 17) continue;
    // angulo como lo ve el modelo: u -> +x, v -> +z
    let a = (Math.atan2(dz, dx) * 180 / Math.PI + 360) % 360;
    const i = Math.round(a / STEP) % N, off = Math.abs(((a - i * STEP + 540) % 360) - 180);
    const [c1, c2] = colorOf(ORDER[i]);
    let col;
    if (r > 15.6) col = '#5a3414';                       // labio
    else if (r > 12.2) col = off > STEP / 2 - 0.9 ? '#d8b04a' : c1;   // banda de numeros (filetes dorados)
    else if (r > 11.8) col = '#d8b04a';
    else if (r > 9.0) col = off > STEP / 2 - 1.1 ? '#e8c060' : c2;    // casillas (con separadores)
    else if (r > 8.6) col = '#d8b04a';
    else {                                                             // cono de madera con rayos
      const spoke = (a % 90) < 3 || (a % 90) > 87;
      col = spoke ? '#d8b04a' : ((Math.floor(r * 2) % 2) ? '#7a4a1e' : '#6c4019');
    }
    px(x, y, col);
  }
  // Numeros (derechos, en el centro de cada casilla de la banda)
  ORDER.forEach((n, i) => {
    const s = n === 37 ? '00' : String(n), a = i * STEP * Math.PI / 180, rr = 13.9 / 17 * 64;
    const cx = C + Math.cos(a) * rr, cz = C + Math.sin(a) * rr, w = s.length * 4 - 1;
    [...s].forEach((ch, k) => DIG[ch].forEach((row, ry) => [...row].forEach((b, rx) => {
      if (b === '1') px(Math.round(cx - w / 2) + k * 4 + rx, Math.round(cz - 2.5) + ry, '#ffffff');
    })));
  });

  // Base del cuenco (64x64 en (128,64), radio 24): madera oscura
  for (let y = 0; y < 64; y++) for (let x = 0; x < 64; x++) {
    const r = Math.hypot(x + 0.5 - 32, y + 0.5 - 32);
    if (r <= 32) px(128 + x, 64 + y, r > 30 ? '#2c1808' : ((Math.floor(r / 2) % 2) ? '#4a2a10' : '#422510'));
  }
  noise(128, 0, 16, 16, ['#6b3d17', '#5e3514', '#764419', '#552f11']);  // madera del aro
  noise(144, 0, 16, 16, ['#e7d6b0', '#ddcaa0', '#efe0bd']);             // pista de la bola (crema)
  noise(160, 0, 16, 16, ['#c8ccd4', '#b4b8c2', '#e6e8ee', '#a8adb8']);  // cromo
  rect(176, 0, 16, 16, '#f8f8f8'); rect(178, 2, 6, 6, '#ffffff');      // bola
  noise(192, 0, 16, 16, ['#e8b420', '#f2c230', '#d9a514']);             // dorado
  noise(208, 0, 16, 16, ['#3a2210', '#331d0c', '#40260f']);             // madera oscura

  // ---------- Pano con el tablero: (0,256)-(512,450), mesa x -28..120, z -28..28 ----------
  const S = 512 / 148, TX = (x) => (x + 28) * S, TZ = (z) => 256 + (z + 28) * S;
  const fillR = (x1, z1, x2, z2, c) => rect(Math.round(TX(x1)), Math.round(TZ(z1)), Math.round(TX(x2)) - Math.round(TX(x1)), Math.round(TZ(z2)) - Math.round(TZ(z1)), c);
  noise(0, 256, 512, 194, ['#0f6a34', '#11713a', '#0d6331', '#127438']);
  // sitio de la rueda (madera oscura) y la rueda queda encima
  for (let y = 256; y < 450; y++) for (let x = 0; x < 200; x++) {
    const ux = x / S - 28, uz = (y - 256) / S - 28;
    if (Math.hypot(ux, uz) < 25.5) px(x, y, Math.hypot(ux, uz) > 24.6 ? '#d8b04a' : '#3a2210');
  }
  const FONT = Object.assign({}, DIG, {
    'P': ['111', '101', '111', '100', '100'], 'A': ['111', '101', '111', '101', '101'], 'R': ['110', '101', '110', '101', '101'],
    'I': ['111', '010', '010', '010', '111'], 'M': ['10001', '11011', '10101', '10001', '10001'], '-': ['000', '000', '111', '000', '000'],
    ':': ['0', '1', '0', '1', '0'], 'G': ['111', '100', '101', '101', '111'], 'B': ['110', '101', '110', '101', '110'],
    'L': ['100', '100', '100', '100', '111'], 'N': ['1001', '1101', '1011', '1001', '1001'], 'D': ['110', '101', '101', '101', '110'],
    'E': ['111', '100', '110', '100', '111'], 'X': ['101', '101', '010', '101', '101'] });
  const text = (str, cx, cz, sc, col) => {
    const ws = [...str].map(ch => FONT[ch][0].length);
    const w = ws.reduce((a, b) => a + b, 0) + (ws.length - 1);
    let x0 = Math.round(TX(cx) - w * sc / 2);
    const y0 = Math.round(TZ(cz) - 5 * sc / 2);
    [...str].forEach((ch, k) => {
      FONT[ch].forEach((row, ry) => [...row].forEach((b, rx) => { if (b === '1') rect(x0 + rx * sc, y0 + ry * sc, sc, sc, col); }));
      x0 += (ws[k] + 1) * sc;
    });
  };
  const box = (x1, z1, x2, z2, bg) => { fillR(x1, z1, x2, z2, '#f2f2ea'); fillR(x1 + 0.35, z1 + 0.35, x2 - 0.35, z2 - 0.35, bg); };
  // 0 y 00
  box(28, -12, 36, 0, '#1d8a3a'); text('00', 32, -6, 2, '#ffffff');
  box(28, 0, 36, 12, '#1d8a3a'); text('0', 32, 6, 2, '#ffffff');
  // numeros 1..36
  for (let n = 1; n <= 36; n++) {
    const col = Math.floor((n - 1) / 3), row = (n - 1) % 3;
    const x1 = 36 + col * 6, z1 = 4 - row * 8;
    box(x1, z1, x1 + 6, z1 + 8, REDS.has(n) ? '#c01826' : '#1c1c20');
    text(String(n), x1 + 3, z1 + 4, 2, '#ffffff');
  }
  // 2 a 1 (columnas)
  for (let row = 0; row < 3; row++) { const z1 = 4 - row * 8; box(108, z1, 116, z1 + 8, '#0f6a34'); text('2:1', 112, z1 + 4, 1, '#ffffff'); }
  // docenas
  ['1-12', '13-24', '25-36'].forEach((t, i) => { box(36 + i * 24, 12, 60 + i * 24, 18, '#0f6a34'); text(t, 48 + i * 24, 15, 1, '#ffffff'); });
  // apuestas de afuera
  ['1-18', 'PAR', 'R', 'N', 'IMPAR', '19-36'].forEach((t, i) => {
    const x1 = 36 + i * 12;
    box(x1, 18, x1 + 12, 24, '#0f6a34');
    if (t === 'R' || t === 'N') {           // rombos rojo / negro
      const cx = TX(x1 + 6), cz = TZ(21);
      for (let dy = -7; dy <= 7; dy++) { const w = 10 - Math.abs(dy) * 10 / 7; rect(Math.round(cx - w), Math.round(cz + dy), Math.round(w * 2), 1, t === 'R' ? '#c01826' : '#1c1c20'); }
    } else text(t, x1 + 6, 21, 1, '#ffffff');
  });
  // nombre en el pano
  text('GAMBLINGDEX', 58, -20, 2, '#d8b04a');

  // Fichas de decoracion (como las del cajero): canto en (256+i*8, 0..8), cara en (256+i*8, 8..16)
  const chipCols = ['#c41c28', '#244ec4', '#22903a', '#1e1e22', '#e8be1e', '#6c28a0', '#eeeeec', '#e26e10'];
  const shade = (hex, f) => '#' + [1, 3, 5].map(i => Math.max(0, Math.min(255, Math.round(parseInt(hex.substr(i, 2), 16) * f))).toString(16).padStart(2, '0')).join('');
  const art = (x0, y0, rows, pal) => rows.forEach((r, y) => [...r].forEach((ch, x) => { if (pal[ch]) px(x0 + x, y0 + y, pal[ch]); }));
  chipCols.forEach((c, i) => {
    const x0 = 256 + i * 8, mark = i === 3 || i === 6 ? '#e8b434' : '#ffffff';
    rect(x0, 0, 8, 8, c); rect(x0, 0, 8, 1, shade(c, 0.6)); rect(x0, 7, 8, 1, shade(c, 0.6));
    for (let x = 1; x < 8; x += 4) rect(x0 + x, 1, 2, 6, mark);
    art(x0, 8, ['.oMooMo.', 'oCCCCCCo', 'MCLLLLCM', 'oCLLLLCo', 'oCLLLLCo', 'MCLLLLCM', 'oCCCCCCo', '.oMooMo.'],
      { o: shade(c, 0.75), C: c, M: mark, L: i === 6 ? '#f6e8c0' : shade(c, 1.35) });
  });
  noise(320, 0, 16, 16, ['#2a1a0c', '#24160a', '#30200f']);   // bandeja del crupier

  const tex = new Texture({ name: 'roulette_table.png' }).fromDataURL(cv.toDataURL()).add(false);

  // ---------- Helpers ----------
  const R = { wood: [128, 0], track: [144, 0], chrome: [160, 0], ball: [176, 0], gold: [192, 0], dark: [208, 0], rack: [320, 0] };
  const uvOf = (k) => { const r = R[k]; return [r[0] + 1, r[1] + 1, r[0] + 15, r[1] + 15]; };
  const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];
  function cube(name, from, to, parent, mat, over = {}, extra = {}) {
    const faces = {};
    FACES.forEach(f => {
      const m = over[f] === undefined ? mat : over[f];
      if (m === null) return;
      faces[f] = { uv: Array.isArray(m) ? m : uvOf(m), texture: tex.uuid };
    });
    const c = new Cube(Object.assign({ name, from, to, faces }, extra));
    c.addTo(parent).init();
    return c;
  }
  function group(name, origin, parent) { const gr = new Group({ name, origin }); gr.addTo(parent || undefined).init(); return gr; }
  // Anillo de 16 piezas (cada 22.5 grados) entre rIn y rOut: cada pieza es un cubo sobre un eje
  // girado -22.5/0/22.5/45 grados (lo que permite Minecraft).
  function ring(name, rIn, rOut, y1, y2, parent, mat, over = {}) {
    const half = rOut * Math.tan(Math.PI / 16) + 0.05;
    for (let k = 0; k < 16; k++) {
      const ang = k * 22.5, base = Math.round(ang / 90) * 90 % 360, rot = ang - Math.round(ang / 90) * 90;
      let from, to;
      if (base === 0) { from = [rIn, y1, -half]; to = [rOut, y2, half]; }
      else if (base === 90) { from = [-half, y1, -rOut]; to = [half, y2, -rIn]; }
      else if (base === 180) { from = [-rOut, y1, -half]; to = [-rIn, y2, half]; }
      else { from = [-half, y1, rIn]; to = [half, y2, rOut]; }
      cube(name + '_' + k, from, to, parent, mat, over, rot ? { rotation: [0, rot, 0], origin: [0, 0, 0] } : {});
    }
  }

  // ---------- Modelo ----------
  const bowl = group('bowl', [0, 0, 0]);
  cube('base', [-24, 0, -24], [24, 1, 24], bowl, 'dark', { up: [128, 64, 192, 128], down: [128, 64, 192, 128], north: null, south: null, east: null, west: null });
  ring('rim', 21, 24, 0, 9, bowl, 'wood', { up: 'gold' });
  ring('rim_top', 21.6, 24.2, 9, 9.6, bowl, 'gold');
  ring('track', 17, 21.05, 1, 7, bowl, 'track');
  // diamantes deflectores en la pista (8)
  [0, 2, 4, 6, 8, 10, 12, 14].forEach(k => {
    const a = k * 22.5 * Math.PI / 180, cx = Math.cos(a) * 19, cz = Math.sin(a) * 19;
    cube('diamond_' + k, [cx - 0.6, 7, cz - 0.6], [cx + 0.6, 7.7, cz + 0.6], bowl, 'chrome');
  });

  const rotor = group('rotor', [0, 0, 0]);
  cube('plate', [-17, 1, -17], [17, 5.5, 17], rotor, 'wood', { up: [0, 0, 128, 128], north: null, south: null, east: null, west: null, down: null });
  ring('lip', 15.6, 17.1, 1, 6.2, rotor, 'wood', { up: 'gold' });
  cube('turret', [-1.6, 5.5, -1.6], [1.6, 9.5, 1.6], rotor, 'chrome');
  cube('turret_top', [-1, 9.5, -1], [1, 10.5, 1], rotor, 'gold');
  cube('arm_x', [-6, 9.6, -0.5], [6, 10.4, 0.5], rotor, 'chrome');
  cube('arm_z', [-0.5, 9.6, -6], [0.5, 10.4, 6], rotor, 'chrome');
  [[-6.4, 0], [6.4, 0], [0, -6.4], [0, 6.4]].forEach(([x, z], i) => cube('knob_' + i, [x - 0.7, 9.3, z - 0.7], [x + 0.7, 10.7, z + 0.7], rotor, 'gold'));

  // Bola: en reposo escondida dentro de la torreta; ball_spin gira, ball_r la lleva hacia afuera
  const ballSpin = group('ball_spin', [0, 0, 0], rotor);
  const ballR = group('ball_r', [0, 6.6, 0], ballSpin);
  cube('ball', [-0.7, 6, -0.7], [0.7, 7.4, 0.7], ballR, 'ball');

  // Subir la rueda: queda encajada en la mesa (base del cuenco a la altura del pano)
  const WY = 11;
  Cube.all.forEach(c => { c.from[1] += WY; c.to[1] += WY; if (c.origin) c.origin[1] += WY; });
  Group.all.forEach(gr => { gr.origin[1] += WY; });

  // Mesa
  const table = group('table', [0, 0, 0]);
  cube('body', [-30, 1, -30], [122, 12, 30], table, 'dark', { north: 'wood', south: 'wood', east: 'wood', west: 'wood' });
  cube('plinth', [-28, 0, -28], [120, 1, 28], table, 'dark');
  cube('felt', [-28, 12, -28], [120, 12.4, 28], table, 'dark', { up: [0, 256, 512, 450] });
  cube('rail_n', [-30, 12, -30], [122, 14.2, -28], table, 'wood', { up: 'gold' });
  cube('rail_s', [-30, 12, 28], [122, 14.2, 30], table, 'wood', { up: 'gold' });
  cube('rail_w', [-30, 12, -28], [-28, 14.2, 28], table, 'wood', { up: 'gold' });
  cube('rail_e', [120, 12, -28], [122, 14.2, 28], table, 'wood', { up: 'gold' });

  // Fichas de decoracion: bandeja del crupier (esquina lejana) y montones junto a la rueda
  const CH = 0.9, D = 2.2, d = 1.3;
  const faceUV = (ci, fx1, fz1, fx2, fz2) => [256 + ci * 8 + fx1 * 8, 8 + fz1 * 8, 256 + ci * 8 + fx2 * 8, 8 + fz2 * 8];
  const chip = (name, x, z, y, ci, parent, rot) => {
    const side = [256 + ci * 8, 0, 256 + ci * 8 + 8, 8], t = (D - d) / (2 * D), u = 1 - t;
    [[D, d, faceUV(ci, 0, t, 1, u)], [d, D, faceUV(ci, t, 0, u, 1)]].forEach(([hx, hz, top], k) => {
      const c = cube(name + (k ? 'b' : 'a'), [x - hx, y, z - hz], [x + hx, y + CH, z + hz], parent, side, { up: top, down: top });
      if (rot) { c.rotation = [0, rot, 0]; c.origin = [x, y, z]; }
    });
  };
  const deco = group('deco_chips', [0, 12.4, 0], table);
  cube('rack', [84, 12.4, -27], [118, 13.6, -17], deco, 'rack');
  [6, 0, 1, 2, 4, 5, 3].forEach((ci, i) => {
    const x = 87 + i * 4.6, h = [5, 7, 6, 8, 4, 6, 7][i];
    for (let k = 0; k < h; k++) chip('rack_' + i + '_' + k, x, -22, 13.6 + k * CH, ci, deco, k % 2 ? 22.5 : 0);
  });
  [[30, -22, 0, 4], [35, -24, 3, 3], [33, -18.5, 4, 5], [38.5, -20, 1, 2]].forEach(([x, z, ci, h], i) => {
    for (let k = 0; k < h; k++) chip('pile_' + i + '_' + k, x, z, 12.4 + k * CH, ci, deco, k % 2 ? 22.5 : 0);
  });

  const hitbox = group('hitbox', [0, 0, 0]);
  cube('hitbox', [-24, 0, -24], [24, 14, 24], hitbox, 'dark');

  // ---------- Animaciones ----------
  const kf = (anim, grp, channel, list, interp = 'linear') => {
    const a = anim.getBoneAnimator(grp);
    list.forEach(([t, x, y, z]) => a.addKeyframe({ channel, time: t, interpolation: interp, data_points: [{ x, y, z }] }));
  };
  const idle = new Blockbench.Animation({ name: 'idle', loop: 'loop', length: 16 }).add();
  kf(idle, rotor, 'rotation', [[0, 0, 0, 0], [8, 0, 180, 0], [16, 0, 360, 0]]);

  // ball_<N>: 8 s. La bola corre por la pista en contra del plato, frena, cae, rebota y se queda.
  const T = 7.0;
  ORDER.forEach((n, i) => {
    const name = 'ball_' + (n === 37 ? '00' : n);
    const an = new Blockbench.Animation({ name, loop: 'hold', length: 8 }).add();
    // Angulo final del grupo: la casilla i esta en +x girada i*STEP (hacia +z); ver el comentario en StationModels.
    const end = -(i * STEP) - 6 * 360;
    const rot = [];
    for (let t = 0; t <= T + 0.001; t += 0.25) {
      const f = t / T, e = 1 - (1 - f) * (1 - f) * (1 - f);    // frena de a poco
      rot.push([+t.toFixed(2), 0, +(end * e).toFixed(2), 0]);
    }
    rot.push([8, 0, end, 0]);
    kf(an, ballSpin, 'rotation', rot);
    // Radio (x) y altura (y) de la bola respecto al reposo
    kf(an, ballR, 'position', [[0, 19, 1, 0], [4.6, 19, 1, 0], [5.0, 17.5, 0.6, 0], [5.3, 13.6, -0.2, 0], [5.5, 15.2, 0.4, 0],
      [5.75, 12.4, -0.4, 0], [6.0, 13.8, 0, 0], [6.3, 11.2, -0.6, 0], [6.6, 11.6, -0.4, 0], [7.0, 10.6, -0.6, 0], [8, 10.6, -0.6, 0]], 'catmullrom');
  });

  Canvas.updateAll();
  return 'ok';
})();
