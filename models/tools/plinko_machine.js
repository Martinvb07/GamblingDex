// Construye plinko_machine.bbmodel (máquina de Plinko) con la API de Blockbench
// (formato Generic / ModelEngine). Se ejecuta dentro de Blockbench (ver render.mjs).
// Frente del modelo = -Z (north).
//
// Tablero de 8 filas de clavos y 9 casillas (las del menú, de izquierda a derecha). Hay 3 bolas
// (a, b, c) para que caigan varias a la vez. Cada bola cuelga de 8 huesos anidados, uno por fila:
// ball_<b>_r<R> se corre medio paso a la izquierda o a la derecha cuando la bola toca el clavo de
// la fila R. Al soltar, el plugin reproduce a la vez:
//   drop_<b>               la bola sale del embudo, baja rebotando fila a fila y se hunde en la casilla
//   drop_<b>_r<R>_<L|R>    hacia dónde rebota en la fila R (una por fila, según el camino real)
// y como los movimientos de los huesos se suman, la bola hace exactamente el camino del menú.
// Otras: idle, hit_<K> (se ilumina la casilla K, 0 = izquierda) y bigwin.
(function () {
  newProject(Formats.free);
  Project.name = 'plinko_machine';
  Project.texture_width = 128; Project.texture_height = 64;

  // ---------- Textura 128x64 (pixel art) ----------
  const cv = document.createElement('canvas'); cv.width = 128; cv.height = 64;
  const g = cv.getContext('2d');
  const px = (x, y, c) => { g.fillStyle = c; g.fillRect(x, y, 1, 1); };
  const rect = (x, y, w, h, c) => { g.fillStyle = c; g.fillRect(x, y, w, h); };
  let seed = 41; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  const noise = (x0, y0, w, h, cols) => { for (let y = y0; y < y0 + h; y++) for (let x = x0; x < x0 + w; x++) px(x, y, cols[Math.floor(rnd() * cols.length)]); };
  const art = (x0, y0, rows, pal) => rows.forEach((r, y) => [...r].forEach((ch, x) => { if (pal[ch]) px(x0 + x, y0 + y, pal[ch]); }));

  noise(0, 0, 16, 16, ['#221a40', '#261e48', '#1e1638', '#2a2250']);       // R0 cuerpo morado noche
  noise(16, 0, 16, 16, ['#ee9a1e', '#e08c14', '#f8aa30', '#d48010']);      // R1 naranja
  noise(32, 0, 16, 16, ['#0c0a16', '#100e1c', '#0a0812']);                  // R2 interior
  noise(48, 0, 16, 16, ['#c8ccd4', '#b4b8c2', '#dfe2e8', '#a8adb8']);      // R3 cromo
  rect(0, 16, 16, 16, '#fff4dc'); rect(2, 18, 12, 12, '#ffc040');          // R4 bombillo
  noise(16, 16, 16, 16, ['#3a1060', '#42146a', '#341058']);                 // R5 fieltro morado
  noise(32, 16, 16, 16, ['#2a2a34', '#30303c', '#26262e']);                 // R6 base oscura
  noise(48, 16, 16, 16, ['#100c24', '#140f2c', '#0e0a20']);                 // R7 fondo de la pantalla

  // Clavo (64,0) 8x8: blanco con brillo
  rect(64, 0, 8, 8, '#b8bccc'); rect(65, 1, 6, 6, '#f4f6ff'); rect(65, 1, 2, 2, '#ffffff');
  // Bola (72,0) 8x8: rosa con brillo
  rect(72, 0, 8, 8, '#c8206a'); rect(73, 1, 6, 6, '#ff4c9a'); rect(73, 1, 2, 2, '#ffd0e6');
  // Casillas 8x8 (80..120, 0): roja, naranja, amarilla, verde y sus versiones encendidas (fila 8)
  const BUCKETS = [['#c41c28', '#ff6060'], ['#e07010', '#ffb050'], ['#e0b818', '#fff080'], ['#30a040', '#90ff90']];
  BUCKETS.forEach(([c, lit], i) => {
    rect(80 + i * 8, 0, 8, 8, c); rect(80 + i * 8, 0, 8, 1, '#ffffff40'); rect(81 + i * 8, 1, 6, 1, lit);
    rect(80 + i * 8, 8, 8, 8, lit); rect(81 + i * 8, 9, 6, 6, '#ffffff');
  });
  rect(112, 0, 8, 8, '#b85c10');   // destello naranja (premio grande)
  // Botón grande "soltar" (64,16) 16x8 y botones chicos (80,16)
  rect(64, 16, 16, 8, '#a05a08'); rect(65, 17, 14, 6, '#f8a020'); rect(65, 17, 14, 1, '#ffe0a0');
  art(70, 18, ['.##.', '#..#', '#..#', '.##.'], { '#': '#fff4dc' }); // bola
  rect(80, 16, 8, 8, '#801010'); rect(81, 17, 6, 6, '#e83030');
  rect(88, 16, 8, 8, '#1e7a2e'); rect(89, 17, 6, 6, '#3fd060');
  // Triángulo de clavos para los costados (96,16) 16x16
  rect(96, 16, 16, 16, '#1e1638');
  for (let r = 0; r < 5; r++) for (let j = 0; j <= r; j++) px(96 + 8 - r * 1.5 + j * 3 | 0, 18 + r * 3, '#f4f6ff');

  // Cartel "PLINKO" 30x10 en (0,48)
  rect(0, 48, 30, 10, '#0c0a16');
  for (let x = 0; x < 30; x += 2) { px(x, 48, '#ffc040'); px(x + 1, 57, '#ffc040'); }
  const PLINKO = [
    ['RRR', 'R..', 'RRR', 'R..R', 'R..R', '.RR.'], ['R.R', 'R..', '.R.', 'RR.R', 'R.R.', 'R..R'], ['RRR', 'R..', '.R.', 'R.RR', 'RR..', 'R..R'],
    ['R..', 'R..', '.R.', 'R..R', 'R.R.', 'R..R'], ['R..', 'RRR', 'RRR', 'R..R', 'R..R', '.RR.'],
  ].map(r => r.join('.'));
  art(2, 50, PLINKO, { R: '#ffa424' });
  // Rejilla de la pantalla (32,48)
  rect(32, 48, 16, 16, '#100c24');
  for (let i = 0; i < 16; i += 4) { rect(32 + i, 48, 1, 16, '#181232'); rect(32, 48 + i, 16, 1, '#181232'); }

  const tex = new Texture({ name: 'plinko_machine.png' }).fromDataURL(cv.toDataURL()).add(false);

  // ---------- Helpers ----------
  const R = { body: [0, 0], orange: [16, 0], dark: [32, 0], chrome: [48, 0], bulb: [0, 16], felt: [16, 16], base: [32, 16], screen: [48, 16] };
  const uvOf = (k) => { const r = R[k]; return [r[0] + 1, r[1] + 1, r[0] + 15, r[1] + 15]; };
  const T = {
    peg: [64, 0, 72, 8], ball: [72, 0, 80, 8], flash: [113, 1, 119, 7], dropKey: [64, 16, 80, 24],
    btnRed: [80, 16, 88, 24], btnGreen: [88, 16, 96, 24], sideArt: [96, 16, 112, 32],
    sign: [0, 48, 30, 58], grid: [32, 48, 48, 64],
  };
  const bucketUV = (i, lit) => [80 + i * 8, lit ? 8 : 0, 88 + i * 8, lit ? 16 : 8];
  const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];
  function cube(name, from, to, parent, mat, over = {}) {
    const faces = {};
    FACES.forEach(f => {
      const m = over[f] || mat;
      faces[f] = { uv: Array.isArray(m) ? m : (T[m] || uvOf(m)), texture: tex.uuid };
    });
    const c = new Cube({ name, from, to, faces });
    c.addTo(parent).init();
    return c;
  }
  function group(name, origin, parent) { const gr = new Group({ name, origin }); gr.addTo(parent || undefined).init(); return gr; }

  // ---------- Mueble ----------
  const body = group('body', [0, 0, 0]);
  cube('base', [-7.5, 0, -5.5], [7.5, 2, 5.5], body, 'base');
  cube('lower', [-7, 2, -5], [7, 8.5, 5], body, 'body');
  cube('lower_panel', [-5.5, 3, -5.2], [5.5, 7.5, -5], body, 'felt');
  cube('lower_trim', [-5.8, 2.7, -5.25], [5.8, 3, -5], body, 'orange');
  cube('lower_trim2', [-5.8, 7.5, -5.25], [5.8, 7.8, -5], body, 'orange');
  cube('desk', [-7, 8.5, -5], [7, 11, 5], body, 'body');
  cube('desk_shelf', [-7.25, 10.5, -6.8], [7.25, 11.5, -4.5], body, 'body', { north: 'orange' });
  cube('btn_drop', [-6.2, 11.5, -6.4], [-0.6, 12.1, -4.9], body, 'btnGreen', { up: 'dropKey' });
  cube('btn_minus', [4.8, 11.5, -6.3], [6.2, 12, -5], body, 'btnRed');
  cube('btn_plus', [2.8, 11.5, -6.3], [4.2, 12, -5], body, 'btnGreen');
  cube('upper', [-7.25, 11.5, -4.5], [7.25, 27, 5], body, 'body');
  cube('edge_l', [-7.5, 2, -5.2], [-7, 27, -4.7], body, 'orange');
  cube('edge_r', [7, 2, -5.2], [7.5, 27, -4.7], body, 'orange');
  cube('side_art_l', [-7.35, 14, -3], [-7.25, 24, 3], body, 'orange', { west: 'sideArt' });
  cube('side_art_r', [7.25, 14, -3], [7.35, 24, 3], body, 'orange', { east: 'sideArt' });

  const screen = group('screen', [0, 19.4, -5], body);
  cube('screen_frame', [-6.8, 12.3, -4.9], [6.8, 26.5, -4.5], screen, 'orange');
  cube('screen_bg', [-6.4, 12.7, -5], [6.4, 26.1, -4.9], screen, 'screen', { north: 'grid' });
  const flash = group('flash', [0, 19.4, -5], screen);
  cube('flash', [-6.4, 12.7, -4.97], [6.4, 26.1, -4.92], flash, 'flash');
  cube('funnel_l', [0.5, 25.3, -5.4], [1.6, 26.1, -5], screen, 'chrome');
  cube('funnel_r', [-1.6, 25.3, -5.4], [-0.5, 26.1, -5], screen, 'chrome');

  // Clavos: fila r (0..7) tiene r+3 clavos separados PITCH; la bola toca el de arriba al centro
  const PITCH = 1.4, ROW_H = 1.2, PEG_Y0 = 23.9, PEG = 0.22, BALL = 0.42;
  const pegY = (r) => PEG_Y0 - r * ROW_H;
  for (let r = 0; r < 8; r++) for (let j = 0; j < r + 3; j++) {
    const x = (j - (r + 2) / 2) * PITCH, y = pegY(r);
    cube('peg_' + r + '_' + j, [x - PEG, y - PEG, -5.4], [x + PEG, y + PEG, -5], screen, 'peg');
  }
  // Casillas: K = 0 a la izquierda de quien mira (+X). Colores de los multiplicadores por defecto.
  const BUCKET_COLOR = [0, 1, 2, 2, 3, 2, 2, 1, 0];
  const BUCKET_Y = 13.0, slots = [];
  for (let k = 0; k < 9; k++) {
    const x = (4 - k) * PITCH;
    const sg = group('slot_' + k, [x, BUCKET_Y, -5], screen);
    cube('slot_' + k, [x - 0.62, BUCKET_Y, -5.3], [x + 0.62, BUCKET_Y + 1.0, -5], sg, 'dark', { north: bucketUV(BUCKET_COLOR[k], false), up: bucketUV(BUCKET_COLOR[k], false) });
    const lit = group('slot_lit_' + k, [x, BUCKET_Y, -5], sg);
    cube('slot_lit_' + k, [x - 0.62, BUCKET_Y, -4.98], [x + 0.62, BUCKET_Y + 1.0, -4.93], lit, 'dark', { north: bucketUV(BUCKET_COLOR[k], true) });
    slots.push({ sg, lit });
    if (k < 8) cube('divider_' + k, [x - PITCH / 2 - 0.08, BUCKET_Y, -5.35], [x - PITCH / 2 + 0.08, BUCKET_Y + 1.3, -5], screen, 'chrome');
  }

  // Bolas: en reposo escondidas dentro del mueble, arriba al centro
  const BALL_REST_Y = 25.7, BALL_Z = -4.45, SHOW_Z = -0.95;
  const balls = {};
  ['a', 'b', 'c'].forEach(b => {
    let parent = screen;
    const levels = [];
    for (let r = 0; r < 8; r++) {
      const lv = group('ball_' + b + '_r' + r, [0, BALL_REST_Y, BALL_Z], parent);
      levels.push(lv); parent = lv;
    }
    const top = group('ball_' + b, [0, BALL_REST_Y, BALL_Z], screen);
    // la cadena de filas cuelga de ball_<b> (la caída vertical) para que se sumen los movimientos
    levels[0].addTo(top);
    cube('ball_' + b, [-BALL, BALL_REST_Y - BALL, BALL_Z - BALL], [BALL, BALL_REST_Y + BALL, BALL_Z + BALL], levels[7], 'ball');
    balls[b] = { top, levels };
  });

  // Cartel
  const sign = group('sign', [0, 27, 0]);
  cube('crown', [-7.5, 27, -5.5], [7.5, 32, 5.5], sign, 'body', { north: 'sign', south: 'sign' });
  cube('crown_trim', [-7.7, 26.6, -5.7], [7.7, 27.4, 5.7], sign, 'orange');
  cube('crown_top', [-7.7, 32, -5.7], [7.7, 32.8, 5.7], sign, 'orange');
  const la = group('lights_a', [0, 33, 0], sign), lb = group('lights_b', [0, 33, 0], sign);
  [-6, -3, 0, 3, 6].forEach((x, i) => cube('bulb_top_' + i, [x - 0.6, 32.8, -0.6], [x + 0.6, 34, 0.6], i % 2 ? lb : la, 'bulb'));
  for (let i = 0; i < 6; i++) {
    const y = 13.4 + i * 2.4;
    cube('bulb_l_' + i, [6.8, y - 0.35, -5.3], [7.35, y + 0.35, -4.7], i % 2 ? la : lb, 'bulb');
    cube('bulb_r_' + i, [-7.35, y - 0.35, -5.3], [-6.8, y + 0.35, -4.7], i % 2 ? lb : la, 'bulb');
  }

  const hitbox = group('hitbox', [0, 0, 0]);
  cube('hitbox', [-8, 0, -8], [8, 32, 8], hitbox, 'dark');

  // ---------- Animaciones ----------
  const kf = (anim, grp, channel, list, interp = 'linear') => {
    const a = anim.getBoneAnimator(grp);
    list.forEach(([t, x, y, z]) => a.addKeyframe({ channel, time: +t.toFixed(3), interpolation: interp, data_points: [{ x, y, z }] }));
  };
  const blink = (len, step, on) => { const o = []; for (let t = 0; t <= len + 0.0001; t += step) o.push([+t.toFixed(2), ...(Math.round(t / step) % 2 ? [on, on, on] : [1, 1, 1])]); return o; };
  const invert = (list, on) => list.map(([t, x]) => [t, ...(x > 1 ? [1, 1, 1] : [on, on, on])]);

  const idle = new Blockbench.Animation({ name: 'idle', loop: 'loop', length: 1.2 }).add();
  kf(idle, la, 'scale', [[0, 1, 1, 1], [0.6, 1.25, 1.25, 1.25], [1.2, 1, 1, 1]], 'catmullrom');
  kf(idle, lb, 'scale', [[0, 1.25, 1.25, 1.25], [0.6, 1, 1, 1], [1.2, 1.25, 1.25, 1.25]], 'catmullrom');

  // Tiempos (iguales al menú: un paso cada 3 ticks, cae en la casilla a los 27 ticks = 1.35 s)
  const STEP = 0.15, T0 = 0.15, LAND = T0 + 8 * STEP, LEN = 1.75;
  const tRow = (r) => T0 + r * STEP;
  const restY = BALL_REST_Y;
  ['a', 'b', 'c'].forEach(b => {
    const { top, levels } = balls[b];
    // Caída: sale del embudo, toca el clavo de cada fila con un saltito y se hunde en la casilla
    const drop = new Blockbench.Animation({ name: 'drop_' + b, loop: 'once', length: LEN }).add();
    const pos = [[0, 0, 0, 0], [0.01, 0, 25.6 - restY, SHOW_Z]];
    for (let r = 0; r < 8; r++) {
      const y = pegY(r) + PEG + BALL - restY;
      pos.push([tRow(r), 0, y, SHOW_Z]);
      pos.push([tRow(r) + 0.05, 0, y + 0.35, SHOW_Z]);
    }
    pos.push([LAND, 0, BUCKET_Y + 0.45 - restY, SHOW_Z]);
    pos.push([LAND + 0.25, 0, BUCKET_Y + 0.2 - restY, SHOW_Z]);
    pos.push([LEN - 0.01, 0, BUCKET_Y + 0.2 - restY, SHOW_Z]);
    pos.push([LEN, 0, 0, 0]);
    kf(drop, top, 'position', pos);
    kf(drop, top, 'scale', [[0, 1, 1, 1], [LAND + 0.15, 1, 1, 1], [LEN - 0.02, 0.4, 0.4, 0.4], [LEN, 1, 1, 1]]);
    // Rebote de cada fila: medio paso (PITCH/2) a la izquierda (+X) o a la derecha (-X)
    for (let r = 0; r < 8; r++) {
      [['L', 1], ['R', -1]].forEach(([d, s]) => {
        const an = new Blockbench.Animation({ name: 'drop_' + b + '_r' + r + '_' + d, loop: 'once', length: LEN }).add();
        const h = s * PITCH / 2;
        kf(an, levels[r], 'position', [[0, 0, 0, 0], [tRow(r), 0, 0, 0], [tRow(r) + 0.05, h * 0.35, 0, 0],
          [tRow(r) + STEP, h, 0, 0], [LEN - 0.01, h, 0, 0], [LEN, 0, 0, 0]]);
      });
    }
  });

  // hit_K: la casilla se ilumina y salta
  slots.forEach(({ sg, lit }, k) => {
    const an = new Blockbench.Animation({ name: 'hit_' + k, loop: 'once', length: 1.2 }).add();
    kf(an, sg, 'scale', [[0, 1, 1, 1], [0.08, 1.25, 1.35, 1], [0.25, 1, 1, 1]]);
    const on = []; for (let i = 0; i <= 10; i++) on.push([i * 0.1, 0, 0, i % 2 === 0 && i < 10 ? -0.4 : 0]);
    kf(an, lit, 'position', on, 'step');
  });

  // bigwin: la pantalla destella en naranja y las luces a tope
  const big = new Blockbench.Animation({ name: 'bigwin', loop: 'once', length: 2.4 }).add();
  const fl = []; for (let i = 0; i <= 12; i++) fl.push([i * 0.12, 0, 0, i % 2 === 0 && i < 11 ? -0.15 : 0]);
  kf(big, flash, 'position', fl, 'step');
  const bc = blink(2.4, 0.08, 1.45);
  kf(big, la, 'scale', bc, 'step'); kf(big, lb, 'scale', invert(bc, 1.45), 'step');

  Canvas.updateAll();
  return 'ok';
})();
