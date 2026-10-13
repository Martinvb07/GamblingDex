// Construye crash_machine.bbmodel (máquina de Crash) con la API de Blockbench
// (formato Generic / ModelEngine). Se ejecuta dentro de Blockbench (ver render.mjs).
// Frente del modelo = -Z (north).
//
// Pantalla con un marcador digital arriba (x + 4 cifras, con punto en dos posiciones) y abajo
// el gráfico por el que sube el cohete. Animaciones:
//   idle               luces (en bucle)
//   countdown          apuestas abiertas: el cohete tiembla en la plataforma y las luces parpadean
//   fly                despega y sube por la curva dejando estela (40 s a la velocidad por defecto,
//                      el plugin ajusta la velocidad a growth_per_second); se queda arriba
//   flame              llama del cohete (en bucle mientras vuela)
//   explode            el cohete revienta donde esté (se queda escondido hasta la ronda siguiente)
//   cashout            caen monedas a la bandeja y destello verde (alguien retiró)
//   d<P>_<N>           la cifra P (0..3, de izquierda a derecha) muestra el número N (se queda)
//   point_a / point_b  punto decimal entre las cifras 1-2 (x1.85, x12.40) o 2-3 (x123.4)
//   lcd_red            el marcador se pone rojo (explotó)
(function () {
  newProject(Formats.free);
  Project.name = 'crash_machine';
  Project.texture_width = 128; Project.texture_height = 64;

  // ---------- Textura 128x64 (pixel art) ----------
  const cv = document.createElement('canvas'); cv.width = 128; cv.height = 64;
  const g = cv.getContext('2d');
  const px = (x, y, c) => { g.fillStyle = c; g.fillRect(x, y, 1, 1); };
  const rect = (x, y, w, h, c) => { g.fillStyle = c; g.fillRect(x, y, w, h); };
  let seed = 67; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  const noise = (x0, y0, w, h, cols) => { for (let y = y0; y < y0 + h; y++) for (let x = x0; x < x0 + w; x++) px(x, y, cols[Math.floor(rnd() * cols.length)]); };
  const art = (x0, y0, rows, pal) => rows.forEach((r, y) => [...r].forEach((ch, x) => { if (pal[ch]) px(x0 + x, y0 + y, pal[ch]); }));

  noise(0, 0, 16, 16, ['#14182a', '#181c30', '#121626', '#1c2036']);       // R0 cuerpo azul noche
  noise(16, 0, 16, 16, ['#e83a2a', '#d8301e', '#f4503a', '#c82818']);      // R1 rojo
  noise(32, 0, 16, 16, ['#08080e', '#0c0c14', '#06060a']);                  // R2 interior
  noise(48, 0, 16, 16, ['#c8ccd4', '#b4b8c2', '#dfe2e8', '#a8adb8']);      // R3 cromo
  rect(0, 16, 16, 16, '#eaffee'); rect(2, 18, 12, 12, '#50ff80');          // R4 bombillo verde
  noise(16, 16, 16, 16, ['#1a1a24', '#20202c', '#16161e']);                 // R5 bandeja
  noise(32, 16, 16, 16, ['#2a2a34', '#30303c', '#26262e']);                 // R6 base oscura
  rect(48, 16, 16, 16, '#06100a');                                          // R7 fondo del marcador (LCD)

  // Cifras 0-9 (5x7) en celdas de 6x8 desde (64,0), verdes sobre el LCD; fila (64,8) rojas
  const DIG = {
    0: ['.###.', '#...#', '#..##', '#.#.#', '##..#', '#...#', '.###.'], 1: ['..#..', '.##..', '..#..', '..#..', '..#..', '..#..', '.###.'],
    2: ['.###.', '#...#', '....#', '...#.', '..#..', '.#...', '#####'], 3: ['####.', '....#', '...#.', '..##.', '....#', '#...#', '.###.'],
    4: ['...#.', '..##.', '.#.#.', '#..#.', '#####', '...#.', '...#.'], 5: ['#####', '#....', '####.', '....#', '....#', '#...#', '.###.'],
    6: ['..##.', '.#...', '#....', '####.', '#...#', '#...#', '.###.'], 7: ['#####', '....#', '...#.', '..#..', '.#...', '.#...', '.#...'],
    8: ['.###.', '#...#', '#...#', '.###.', '#...#', '#...#', '.###.'], 9: ['.###.', '#...#', '#...#', '.####', '....#', '...#.', '.##..'],
  };
  for (let n = 0; n < 10; n++) {
    art(64 + n * 6, 0, DIG[n], { '#': '#50ff80' }); // fondo transparente: se ve el LCD de detrás
  }
  rect(124, 5, 4, 3, '#50ff80');                                            // punto
  // "x" (64,16) 6x8
  art(64, 17, ['.....', '#...#', '.#.#.', '..#..', '.#.#.', '#...#'], { '#': '#50ff80' });
  rect(70, 16, 6, 8, '#5a0a08');                                            // LCD rojo de fondo
  // Cohete (80,16) 8x8 en diagonal hacia arriba a la derecha de quien mira
  art(80, 16, ['......WW', '.....WRW', '....WWW.', '...WBW..', '..WWW...', '.RWW....', 'OR.R....', 'YO......'],
    { W: '#f0f0f4', R: '#e83a2a', B: '#60c0ff', O: '#ff9020', Y: '#ffe040' });
  // Llama (88,16) y explosión (96,16) 8x8
  art(88, 16, ['..YY....', '.YOOY...', 'YORROY..', 'YORROY..', '.YOOY...', '..YY....', '........', '........'], { Y: '#ffe040', O: '#ff9020', R: '#e83a2a' });
  art(96, 16, ['Y..O..Y.', '.YOROY..', '.ORWRO..', 'ORWWWRO.', '.ORWRO..', '.YOROY..', 'Y..O..Y.', '........'],
    { Y: '#ffe040', O: '#ff9020', R: '#e83a2a', W: '#ffffff' });
  // Estela (104,16) 4x4 y plataforma (108,16)
  rect(104, 16, 4, 4, '#50ff80'); rect(105, 17, 2, 2, '#d0ffe0');
  rect(108, 16, 8, 4, '#808898'); rect(108, 16, 8, 1, '#c8ccd4');
  // Fondo del gráfico (64,32) 32x24: cuadrícula y ejes
  rect(64, 32, 32, 24, '#0a0e1c');
  for (let i = 0; i < 32; i += 4) rect(64 + i, 32, 1, 24, '#121a30');
  for (let i = 0; i < 24; i += 4) rect(64, 32 + i, 32, 1, '#121a30');
  rect(95, 32, 1, 24, '#3a4870'); rect(64, 55, 32, 1, '#3a4870');
  rect(116, 16, 6, 6, '#50ff80'); rect(122, 16, 6, 6, '#ff3020');          // destellos verde / rojo
  // Monedas
  art(96, 24, ['.oYYYYo.', 'oYWYYYYo', 'YWYOOYYY', 'YYOYYOYY', 'YYOYYOYY', 'YYYOOYYY', 'oYYYYYYo', '.oYYYYo.'],
    { o: '#9a6a00', Y: '#ffd83d', W: '#fff4b0', O: '#d9a514' });
  rect(104, 24, 8, 8, '#d9a514'); for (let x = 104; x < 112; x += 2) rect(x, 25, 1, 6, '#9a6a00');
  // Botones
  rect(112, 24, 8, 8, '#801010'); rect(113, 25, 6, 6, '#e83030');
  rect(120, 24, 8, 8, '#1e7a2e'); rect(121, 25, 6, 6, '#3fd060');
  rect(96, 32, 16, 8, '#145a22'); rect(97, 33, 14, 6, '#2fb046'); rect(97, 33, 14, 1, '#8cf0a0');
  art(102, 34, ['.##.', '##..', '.##.', '..##'], { '#': '#e8ffe8' });

  // Cartel "CRASH" 30x10 en (0,48)
  rect(0, 48, 30, 10, '#08080e');
  for (let x = 0; x < 30; x += 2) { px(x, 48, '#ff6040'); px(x + 1, 57, '#ff6040'); }
  const CRASH = [
    ['.RRR', 'RRR.', '.RR.', '.RRR', 'R..R'], ['R...', 'R..R', 'R..R', 'R...', 'R..R'], ['R...', 'RRR.', 'RRRR', '.RR.', 'RRRR'],
    ['R...', 'R.R.', 'R..R', '...R', 'R..R'], ['.RRR', 'R..R', 'R..R', 'RRR.', 'R..R'],
  ].map(r => r.join('.'));
  art(3, 50, CRASH, { R: '#ff5030' });

  const tex = new Texture({ name: 'crash_machine.png' }).fromDataURL(cv.toDataURL()).add(false);

  // ---------- Helpers ----------
  const R = { body: [0, 0], red: [16, 0], dark: [32, 0], chrome: [48, 0], bulb: [0, 16], tray: [16, 16], base: [32, 16], lcd: [48, 16] };
  const uvOf = (k) => { const r = R[k]; return [r[0] + 1, r[1] + 1, r[0] + 15, r[1] + 15]; };
  const T = {
    point: [124, 0, 128, 8], none: [126, 62, 128, 64], x: [64, 16, 70, 24], lcdRed: [71, 17, 75, 23],
    rocket: [80, 16, 88, 24], flame: [88, 16, 96, 22], burst: [96, 16, 104, 23], trail: [104, 16, 108, 20], pad: [108, 16, 116, 20],
    graph: [64, 32, 96, 56], green: [117, 17, 121, 21], redglow: [123, 17, 127, 21], coin: [96, 24, 104, 32], coinEdge: [104, 24, 112, 32],
    btnRed: [112, 24, 120, 32], btnGreen: [120, 24, 128, 32], cashKey: [96, 32, 112, 40], sign: [0, 48, 30, 58],
  };
  const digitUV = (n) => [64 + n * 6, 0, 69 + n * 6, 7];
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
  const coinCube = (name, x, z, y, parent, rot = 0) => {
    const D = 1.0, d = 0.6, H = 0.4;
    [[D, d], [d, D]].forEach(([hx, hz], k) => {
      const c = cube(name + (k ? 'b' : 'a'), [x - hx, y, z - hz], [x + hx, y + H, z + hz], parent, 'coinEdge', { up: 'coin', down: 'coin' });
      if (rot) { c.rotation = [0, rot, 0]; c.origin = [x, y, z]; }
    });
  };

  // ---------- Mueble ----------
  const body = group('body', [0, 0, 0]);
  cube('base', [-7.5, 0, -5.5], [7.5, 2, 5.5], body, 'base');
  cube('lower', [-7, 2, -5], [7, 3.5, 5], body, 'body');
  cube('tray_left', [-7, 3.5, -5], [-5, 7.5, 5], body, 'body');
  cube('tray_right', [5, 3.5, -5], [7, 7.5, 5], body, 'body');
  cube('tray_back', [-5, 3.5, -1], [5, 7.5, 5], body, 'dark');
  cube('tray_floor', [-5, 3.5, -5.3], [5, 4, -1], body, 'tray', { north: 'red' });
  cube('tray_roof', [-7, 7.5, -5.2], [7, 8.5, 5], body, 'red');
  cube('desk', [-7, 8.5, -5], [7, 11, 5], body, 'body');
  cube('desk_shelf', [-7.25, 10.5, -6.8], [7.25, 11.5, -4.5], body, 'body', { north: 'red' });
  cube('btn_cash', [-6.2, 11.5, -6.4], [-0.6, 12.1, -4.9], body, 'btnGreen', { up: 'cashKey' });
  cube('btn_minus', [4.8, 11.5, -6.3], [6.2, 12, -5], body, 'btnRed');
  cube('btn_plus', [2.8, 11.5, -6.3], [4.2, 12, -5], body, 'btnGreen');
  cube('upper', [-7.25, 11.5, -4.5], [7.25, 27, 5], body, 'body');
  cube('edge_l', [-7.5, 2, -5.2], [-7, 27, -4.7], body, 'red');
  cube('edge_r', [7, 2, -5.2], [7.5, 27, -4.7], body, 'red');
  cube('side_l', [-7.35, 14, -2.5], [-7.25, 22, 2.5], body, 'red', { west: 'rocket' });
  cube('side_r', [7.25, 14, -2.5], [7.35, 22, 2.5], body, 'red', { east: 'rocket' });

  const screen = group('screen', [0, 19.4, -5], body);
  cube('screen_frame', [-6.8, 12.3, -4.9], [6.8, 26.5, -4.5], screen, 'red');
  cube('screen_bg', [-6.4, 12.7, -5], [6.4, 26.1, -4.9], screen, 'dark', { north: 'graph' });
  const flashG = group('flash_green', [0, 19.4, -5], screen);
  cube('flash_g', [-6.4, 12.7, -4.97], [6.4, 26.1, -4.92], flashG, 'green');
  const flashR = group('flash_red', [0, 19.4, -5], screen);
  cube('flash_r', [-6.4, 12.7, -4.97], [6.4, 26.1, -4.92], flashR, 'redglow');

  // Marcador: LCD con "x" y 4 cifras. Las cifras (10 por posición) y los puntos están detrás del
  // LCD; d<P>_<N> trae al frente la cifra N de la posición P.
  const LCD_Y1 = 22.6, LCD_Y2 = 25.9, DY = 24.25, DH = 1.25, DW = 0.62;
  cube('lcd', [-6.1, LCD_Y1, -5.12], [6.1, LCD_Y2, -5.0], screen, 'lcd');
  const lcdRed = group('lcd_red', [0, DY, -5], screen);
  cube('lcd_red', [-6.1, LCD_Y1, -4.99], [6.1, LCD_Y2, -4.95], lcdRed, 'lcd', { north: 'lcdRed' });
  cube('lcd_x', [4.4 - DW, DY - DH * 0.8, -5.16], [4.4 + DW, DY + DH * 0.5, -5.12], screen, 'none', { north: 'x' });
  const DIGIT_X = [2.65, 1.0, -0.65, -2.3, -3.95].slice(0, 4);
  const digits = [];
  DIGIT_X.forEach((x, p) => {
    const set = [];
    for (let n = 0; n < 10; n++) {
      const dg = group('d' + p + '_' + n, [x, DY, -5], screen);
      cube('d' + p + '_' + n, [x - DW, DY - DH, -4.98], [x + DW, DY + DH, -4.94], dg, 'none', { north: digitUV(n) });
      set.push(dg);
    }
    digits.push(set);
  });
  const pointA = group('point_a', [(DIGIT_X[1] + DIGIT_X[2]) / 2, DY, -5], screen);
  cube('point_a', [(DIGIT_X[1] + DIGIT_X[2]) / 2 - 0.2, DY - DH, -4.98], [(DIGIT_X[1] + DIGIT_X[2]) / 2 + 0.2, DY + DH, -4.94], pointA, 'none', { north: 'point' });
  const pointB = group('point_b', [(DIGIT_X[2] + DIGIT_X[3]) / 2, DY, -5], screen);
  cube('point_b', [(DIGIT_X[2] + DIGIT_X[3]) / 2 - 0.2, DY - DH, -4.98], [(DIGIT_X[2] + DIGIT_X[3]) / 2 + 0.2, DY + DH, -4.94], pointB, 'none', { north: 'point' });

  // Gráfico: plataforma, cohete y estela. Curva desde abajo a la izquierda de quien mira (+X)
  // hasta arriba a la derecha (-X).
  const PATH = [[0, 5.0, 13.9], [5, 4.0, 14.3], [10, 2.8, 15.0], [15, 1.5, 16.0], [20, 0.1, 17.3], [25, -1.4, 18.7], [30, -2.9, 20.0],
    [35, -4.3, 21.2], [40, -5.2, 21.8]];
  cube('pad', [4.2, 13.0, -5.2], [5.8, 13.4, -5.0], screen, 'chrome', { north: 'pad' });
  const RX = 5.0, RY = 13.9;
  const rocket = group('rocket', [RX, RY, -5.3], screen);
  const rocketBody = group('rocket_body', [RX, RY, -5.3], rocket);
  cube('rocket', [RX - 0.8, RY - 0.5, -5.4], [RX + 0.8, RY + 1.1, -5.2], rocketBody, 'none', { north: 'rocket', south: 'rocket' });
  const flame = group('flame', [RX + 0.6, RY - 0.4, -5.3], rocketBody);
  cube('flame', [RX + 0.25, RY - 0.95, -5.35], [RX + 0.95, RY - 0.25, -5.25], flame, 'none', { north: 'flame', south: 'flame' });
  const burst = group('burst', [RX, RY + 0.3, -5.3], rocket);
  cube('burst', [RX - 0.05, RY + 0.25, -5.45], [RX + 0.05, RY + 0.35, -5.4], burst, 'none', { north: 'burst', south: 'burst' });
  // Estela: puntos escondidos detrás del gráfico que aparecen al pasar el cohete
  const pathAt = (t) => {
    for (let i = 1; i < PATH.length; i++) if (t <= PATH[i][0]) {
      const [t0, x0, y0] = PATH[i - 1], [t1, x1, y1] = PATH[i], f = (t - t0) / (t1 - t0);
      return [x0 + (x1 - x0) * f, y0 + (y1 - y0) * f];
    }
    return PATH[PATH.length - 1].slice(1);
  };
  const trail = [];
  for (let i = 1; i <= 15; i++) {
    const t = i * 2.5, [x, y] = pathAt(t);
    const tg = group('trail_' + i, [x, y, -5], screen);
    cube('trail_' + i, [x - 0.18, y - 0.18, -4.98], [x + 0.18, y + 0.18, -4.94], tg, 'dark', { north: 'trail' });
    trail.push({ tg, t });
  }

  // Monedas que caen al retirar alguien
  const coins = group('coins', [0, 9, -3.4], body);
  [[-1.6, -3.0, 0], [0.4, -3.6, 25], [2.2, -2.8, 50], [-0.4, -2.4, 70], [1.2, -3.2, 15]].forEach(([x, z, rot], k) =>
    coinCube('coin_' + k, x, z, 9 + k * 0.42, coins, rot));

  // Cartel
  const sign = group('sign', [0, 27, 0]);
  cube('crown', [-7.5, 27, -5.5], [7.5, 32, 5.5], sign, 'body', { north: 'sign', south: 'sign' });
  cube('crown_trim', [-7.7, 26.6, -5.7], [7.7, 27.4, 5.7], sign, 'red');
  cube('crown_top', [-7.7, 32, -5.7], [7.7, 32.8, 5.7], sign, 'red');
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
  const flashKeys = (n, step) => { const o = []; for (let i = 0; i <= n; i++) o.push([i * step, 0, 0, i % 2 === 0 && i < n - 1 ? -0.15 : 0]); return o; };

  const idle = new Blockbench.Animation({ name: 'idle', loop: 'loop', length: 1.2 }).add();
  kf(idle, la, 'scale', [[0, 1, 1, 1], [0.6, 1.25, 1.25, 1.25], [1.2, 1, 1, 1]], 'catmullrom');
  kf(idle, lb, 'scale', [[0, 1.25, 1.25, 1.25], [0.6, 1, 1, 1], [1.2, 1.25, 1.25, 1.25]], 'catmullrom');

  const cd = new Blockbench.Animation({ name: 'countdown', loop: 'loop', length: 1.0 }).add();
  kf(cd, rocket, 'position', [[0, 0, 0, 0], [0.05, 0.06, 0.04, 0], [0.1, -0.06, 0, 0], [0.15, 0.04, 0.03, 0], [0.2, 0, 0, 0], [1.0, 0, 0, 0]]);
  const bcd = blink(1.0, 0.25, 1.35);
  kf(cd, la, 'scale', bcd, 'step'); kf(cd, lb, 'scale', invert(bcd, 1.35), 'step');

  const FLY = 40;
  const fly = new Blockbench.Animation({ name: 'fly', loop: 'hold', length: FLY }).add();
  kf(fly, rocket, 'position', PATH.map(([t, x, y]) => [t, x - RX, y - RY, 0]), 'catmullrom');
  trail.forEach(({ tg, t }) => kf(fly, tg, 'position', [[0, 0, 0, 0], [t, 0, 0, -0.12], [FLY, 0, 0, -0.12]], 'step'));

  const fl = new Blockbench.Animation({ name: 'flame', loop: 'loop', length: 0.3 }).add();
  kf(fl, flame, 'scale', [[0, 1, 1, 1], [0.1, 1.35, 1.35, 1], [0.2, 0.8, 0.8, 1], [0.3, 1, 1, 1]], 'step');

  const ex = new Blockbench.Animation({ name: 'explode', loop: 'hold', length: 1.0 }).add();
  kf(ex, rocketBody, 'scale', [[0, 1, 1, 1], [0.02, 0.01, 0.01, 0.01], [1.0, 0.01, 0.01, 0.01]], 'step');
  kf(ex, burst, 'scale', [[0, 1, 1, 1], [0.15, 22, 22, 1], [0.5, 28, 28, 1], [0.8, 0.01, 0.01, 0.01], [1.0, 0.01, 0.01, 0.01]]);
  kf(ex, flashR, 'position', flashKeys(10, 0.1), 'step');
  kf(ex, body, 'position', [[0, 0, 0, 0], [0.05, 0.5, 0, 0], [0.1, -0.5, 0.2, 0], [0.15, 0.4, 0, 0], [0.2, -0.3, 0, 0], [0.25, 0, 0, 0]]);

  const lr = new Blockbench.Animation({ name: 'lcd_red', loop: 'hold', length: 0.2 }).add();
  kf(lr, lcdRed, 'position', [[0, 0, 0, -0.15], [0.2, 0, 0, -0.15]], 'step');

  const cash = new Blockbench.Animation({ name: 'cashout', loop: 'once', length: 2.6 }).add();
  kf(cash, coins, 'position', [[0, 0, 0, 0], [0.15, 0, 0, 0], [0.55, 0, -5, 0], [0.65, 0, -4.7, 0], [0.75, 0, -5, 0], [2.2, 0, -5, 0]]);
  kf(cash, coins, 'scale', [[0, 1, 1, 1], [2.2, 1, 1, 1], [2.5, 0.01, 0.01, 0.01]]);
  kf(cash, flashG, 'position', flashKeys(6, 0.12), 'step');

  // Cifras y puntos: al frente del LCD (por delante también del fondo rojo de lcd_red).
  digits.forEach((set, p) => set.forEach((dg, n) => {
    const an = new Blockbench.Animation({ name: 'd' + p + '_' + n, loop: 'hold', length: 0.1 }).add();
    kf(an, dg, 'position', [[0, 0, 0, -0.25], [0.1, 0, 0, -0.25]], 'step');
  }));
  [['point_a', pointA], ['point_b', pointB]].forEach(([name, grp]) => {
    const an = new Blockbench.Animation({ name, loop: 'hold', length: 0.1 }).add();
    kf(an, grp, 'position', [[0, 0, 0, -0.25], [0.1, 0, 0, -0.25]], 'step');
  });

  Canvas.updateAll();
  return 'ok';
})();
