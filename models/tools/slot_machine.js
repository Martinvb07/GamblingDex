// Construye slot_machine.bbmodel usando la API de Blockbench (formato Generic / ModelEngine).
// Se ejecuta dentro de Blockbench (ver render.mjs). Front del modelo = -Z (north).
(function () {
  newProject(Formats.free);
  Project.name = 'slot_machine';
  Project.texture_width = 128; Project.texture_height = 64;

  // ---------- Textura 64x64 (pixel art) ----------
  const cv = document.createElement('canvas'); cv.width = 128; cv.height = 64;
  const g = cv.getContext('2d');
  const px = (x, y, c) => { g.fillStyle = c; g.fillRect(x, y, 1, 1); };
  const rect = (x, y, w, h, c) => { g.fillStyle = c; g.fillRect(x, y, w, h); };
  let seed = 7; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  const noise = (x0, y0, w, h, cols) => { for (let y = y0; y < y0 + h; y++) for (let x = x0; x < x0 + w; x++) px(x, y, cols[Math.floor(rnd() * cols.length)]); };
  const art = (x0, y0, rows, pal) => rows.forEach((r, y) => [...r].forEach((ch, x) => { if (pal[ch]) px(x0 + x, y0 + y, pal[ch]); }));

  // Regiones 16x16 (fila 0 y 1)
  noise(0, 0, 16, 16, ['#8e1420', '#9c1a26', '#861220', '#a01e2a']);            // R0 cuerpo rojo
  noise(16, 0, 16, 16, ['#f2c230', '#e8b420', '#ffd84a', '#d9a514']);           // R1 dorado
  noise(32, 0, 16, 16, ['#14141c', '#1a1a24', '#101018']);                       // R2 negro/interior
  noise(48, 0, 16, 16, ['#c8ccd4', '#b4b8c2', '#dfe2e8', '#a8adb8']);           // R3 cromo
  rect(0, 16, 16, 16, '#d01818'); rect(3, 19, 4, 3, '#ff7070'); rect(4, 20, 1, 1, '#ffffff'); // R4 bola roja
  rect(16, 16, 16, 16, '#fff3a0'); rect(18, 18, 12, 12, '#ffe040');             // R5 bombillo
  noise(32, 16, 16, 16, ['#2a2a34', '#30303c', '#26262e']);                       // R6 base oscura
  rect(48, 16, 16, 16, '#1e7a2e'); rect(50, 18, 12, 12, '#2fb046');             // R7 boton verde

  // Simbolos de los rodillos = items del modulo de slots (SlotsController.REELS).
  // Celdas de 16x12 en x>=64 (4 columnas x 2 filas), arte de 12x12 centrado.
  const ITEMS = {
    diamond: ['....dddd....', '...dDDDDd...', '..dDLLDDDd..', '.dDLLDDDDDd.', 'dDDDDDDDDDDd', '.dDDDDDDDDd.',
              '..dDDDDDDd..', '...dDDDDd...', '....dDDd....', '.....dd.....', '............', '............'],
    emerald: ['.....gg.....', '....gGGg....', '...gGLGGg...', '..gGLGGGGg..', '.gGGGGGGGGg.', '.gGGGGGGGGg.',
              '..gGGGGGGg..', '...gGGGGg...', '....gGGg....', '.....gg.....', '............', '............'],
    gold:    ['............', '............', '...oooooo...', '..oYYYYYYo..', '.oYWYYYYYYo.', '.oYYYYYYYYo.',
              'oOOOOOOOOOOo', 'oooooooooooo', '............', '............', '............', '............'],
    iron:    ['............', '............', '...ssssss...', '..sIIIIIIs..', '.sIWIIIIIIs.', '.sIIIIIIIIs.',
              'sSSSSSSSSSSs', 'ssssssssssss', '............', '............', '............', '............'],
    amethyst:['.......pp...', '......pPPp..', '.....pPLPp..', '....pPLPPp..', '...pPPPPp...', '..pPPPPp....',
              '.pPPPPp.....', '.pPPPp......', '..ppp.......', '............', '............', '............'],
    star:    ['.....w......', '.....w......', '....wWw.....', 'w..wWYWw..w.', '.wwWYYYWww..', '..wWYYYWw...',
              '.wwWYYYWww..', 'w..wWYWw..w.', '....wWw.....', '.....w......', '.....w......', '............'],
  };
  const ipal = { d: '#1a7f8e', D: '#4fe3e3', L: '#d8ffff', g: '#0b6b2a', G: '#2fd16a', o: '#9a6a00', Y: '#ffd83d',
    O: '#d9a514', W: '#ffffff', s: '#5d5d66', I: '#d8d8de', S: '#a9a9b2', p: '#5a2a8c', P: '#b37de6', w: '#c9c9e8' };
  const order = ['diamond', 'gold', 'iron', 'emerald', 'star', 'gold', 'amethyst', 'iron'];
  const cell = (i) => [64 + (i % 4) * 16, (i >> 2) * 12];
  order.forEach((n, i) => {
    const [x, y] = cell(i);
    rect(x, y, 16, 12, '#f6f1e4'); rect(x, y + 11, 16, 1, '#ddd5c2');
    art(x + 2, y, ITEMS[n], ipal);
  });
  const sy = 0;

  // Cartel "SLOTS" (30x10) en (0,48)
  rect(0, 48, 30, 10, '#1a0a10');
  for (let x = 0; x < 30; x += 2) { px(x, 48, '#ffe040'); px(x + 1, 57, '#ffe040'); }
  art(4, 50, [
    'YYY.Y...YYY.YYY.YYY',
    'Y...Y...Y.Y..Y..Y..',
    'YYY.Y...Y.Y..Y..YYY',
    '..Y.Y...Y.Y..Y....Y',
    'YYY.YYY.YYY..Y..YYY',
  ].map(r => r.replace(/Y/g, 'X')), { X: '#ffd030' });
  // Boton amarillo y rojo pequeños (32,48)
  rect(32, 48, 8, 8, '#e0b010'); rect(33, 49, 6, 6, '#ffe040');
  rect(40, 48, 8, 8, '#a01010'); rect(41, 49, 6, 6, '#e83030');

  const tex = new Texture({ name: 'slot_machine.png' }).fromDataURL(cv.toDataURL()).add(false);

  // ---------- Helpers de geometria ----------
  const R = { body: [0, 0], gold: [16, 0], dark: [32, 0], chrome: [48, 0], knob: [0, 16], bulb: [16, 16], base: [32, 16], green: [48, 16], yel: [32, 48, 40, 56], red: [40, 48, 48, 56] };
  const uvOf = (k) => { const r = R[k]; return r.length === 4 ? r : [r[0] + 1, r[1] + 1, r[0] + 15, r[1] + 15]; };
  const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];
  function cube(name, from, to, parent, mat, over = {}, extra = {}) {
    const faces = {};
    FACES.forEach(f => {
      const m = over[f] || mat;
      faces[f] = m.uv ? Object.assign({ texture: tex.uuid }, m) : { uv: Array.isArray(m) ? m : uvOf(m), texture: tex.uuid };
    });
    const c = new Cube(Object.assign({ name, from, to, faces }, extra));
    c.addTo(parent).init();
    return c;
  }
  function group(name, origin, parent) {
    const gr = new Group({ name, origin }); gr.addTo(parent || undefined).init(); return gr;
  }

  // ---------- Modelo ----------
  const body = group('body', [0, 0, 0]);
  cube('base', [-7.5, 0, -5.5], [7.5, 2, 5.5], body, 'base');
  cube('lower', [-7, 2, -5], [7, 10, 5], body, 'body');
  cube('tray', [-3, 3, -6], [3, 4.5, -5], body, 'gold');
  cube('trim_low', [-7.2, 9.5, -5.2], [7.2, 10.5, 5.2], body, 'gold');
  cube('back_upper', [-7, 10, 2.5], [7, 26, 5], body, 'body');
  cube('window_bottom', [-7, 10, -5], [7, 13, 2.5], body, 'body');
  cube('window_top', [-7, 21, -5], [7, 26, 2.5], body, 'body');
  cube('side_l', [-7, 13, -5], [-6, 21, 2.5], body, 'body');
  cube('side_r', [6, 13, -5], [7, 21, 2.5], body, 'body');
  cube('div_1', [-2.1, 13, -4], [-1.9, 21, 2.5], body, 'gold');
  cube('div_2', [1.9, 13, -4], [2.1, 21, 2.5], body, 'gold');
  cube('window_back', [-6, 13, 1.5], [6, 21, 2.5], body, 'dark');
  cube('frame_top', [-6.5, 21, -5.3], [6.5, 21.6, -4.8], body, 'gold');
  cube('frame_bottom', [-6.5, 12.4, -5.3], [6.5, 13, -4.8], body, 'gold');
  cube('payline', [-6, 16.9, -5.25], [6, 17.1, -5.15], body, 'red');

  const panel = group('panel', [0, 10, -5]);
  cube('panel', [-7, 9.5, -7.5], [7, 11, -5], panel, 'dark', { up: 'body' });
  cube('btn_spin', [-5.5, 11, -7], [-2.5, 11.6, -5.8], panel, 'red');
  cube('btn_bet', [-1.5, 11, -7], [1.5, 11.6, -5.8], panel, 'yel');
  cube('btn_max', [2.5, 11, -7], [5.5, 11.6, -5.8], panel, 'green');

  // Rodillos: octagono exacto = cruz (2 cubos) + cruz girada 45 (2 cubos). 8 caras con simbolo.
  // Cara k (k=0..7) mira al frente cuando el rodillo gira k*45 grados en X.
  const symUV = (i) => { const [x, y] = cell(i); return [x, y, x + 16, y + 12]; };
  const RC = [0, 17, -1.5], rr = 3.5, ss = rr * Math.tan(Math.PI / 8);
  const reels = group('reels', RC);
  [[-5.8, -2.2], [-1.8, 1.8], [2.2, 5.8]].forEach(([x1, x2], ri) => {
    const reel = group('reel_' + (ri + 1), RC, reels);
    const sh = (ri * 3) % 8, sym8 = (k) => symUV((k + sh) % 8);
    const flip = (k) => ({ uv: sym8(k), rotation: 180 }); // up/south/down quedan al derecho al llegar al frente
    const sides = { east: 'gold', west: 'gold' };
    [0, 45].forEach((rot, j) => {
      // j=0: caras 0(north),2(up),4(south),6(down); j=1: caras 1,3,5,7 (giradas 45)
      const o = { origin: RC, rotation: [rot, 0, 0] };
      cube('ns' + j, [x1, RC[1] - ss, RC[2] - rr], [x2, RC[1] + ss, RC[2] + rr], reel, 'body',
        Object.assign({ north: sym8(j), south: flip(4 + j), up: 'body', down: 'body' }, sides), o);
      cube('ud' + j, [x1 + 0.005, RC[1] - rr, RC[2] - ss], [x2 - 0.005, RC[1] + rr, RC[2] + ss], reel, 'body',
        Object.assign({ up: flip(2 + j), down: flip(6 + j), north: 'body', south: 'body' }, sides), o);
    });
  });

  const marquee = group('marquee', [0, 26, 0]);
  cube('crown', [-7.5, 26, -5.5], [7.5, 31, 5.5], marquee, 'body', { north: [0, 48, 30, 58] });
  cube('crown_trim', [-7.7, 25.6, -5.7], [7.7, 26.4, 5.7], marquee, 'gold');
  cube('crown_top', [-7.7, 31, -5.7], [7.7, 31.8, 5.7], marquee, 'gold');
  const la = group('lights_a', [0, 32, -1], marquee), lb = group('lights_b', [0, 32, -1], marquee);
  [-6, -3, 0, 3, 6].forEach((x, i) => cube('bulb_' + i, [x - 0.6, 31.8, -1.6], [x + 0.6, 33, -0.4], i % 2 ? lb : la, 'bulb'));

  const lever = group('lever', [8, 15.5, 0]);
  cube('bracket', [7, 14, -1.5], [8.2, 17, 1.5], body, 'chrome');
  cube('arm', [7.9, 15.5, -0.4], [8.7, 25, 0.4], lever, 'chrome');
  cube('knob', [7.3, 24.5, -1], [9.3, 26.5, 1], lever, 'knob');

  const hitbox = group('hitbox', [0, 0, 0]);
  cube('hitbox', [-8, 0, -8], [8, 32, 8], hitbox, 'dark');

  // ---------- Animaciones ----------
  const kf = (anim, grp, channel, list, interp = 'linear') => {
    const a = anim.getBoneAnimator(grp);
    list.forEach(([t, x, y, z]) => a.addKeyframe({ channel, time: t, interpolation: interp, data_points: [{ x, y, z }] }));
  };
  const idle = new Blockbench.Animation({ name: 'idle', loop: 'loop', length: 1 }).add();
  kf(idle, la, 'scale', [[0, 1, 1, 1], [0.5, 1.3, 1.3, 1.3], [1, 1, 1, 1]], 'catmullrom');
  kf(idle, lb, 'scale', [[0, 1.3, 1.3, 1.3], [0.5, 1, 1, 1], [1, 1.3, 1.3, 1.3]], 'catmullrom');

  const spin = new Blockbench.Animation({ name: 'spin', loop: 'once', length: 3 }).add();
  kf(spin, lever, 'rotation', [[0, 0, 0, 0], [0.25, -55, 0, 0], [0.6, 0, 0, 0]]);
  const reelGroups = reels.children;
  reelGroups.forEach((rg, i) => {
    const stop = 1.6 + i * 0.5, total = -(1080 + i * 360); // negativo: los simbolos bajan, como una maquina real
    kf(spin, rg, 'rotation', [[0, 0, 0, 0], [0.3, 0, 0, 0], [stop - 0.35, total + 90, 0, 0], [stop - 0.1, total - 6, 0, 0], [stop, total, 0, 0]]);
  });
  kf(spin, la, 'scale', [[0, 1, 1, 1], [0.15, 1.3, 1.3, 1.3], [0.3, 1, 1, 1]], 'step');

  const win = new Blockbench.Animation({ name: 'win', loop: 'once', length: 2 }).add();
  const flash = []; for (let t = 0; t <= 2; t += 0.2) flash.push([+t.toFixed(2), ...(Math.round(t * 5) % 2 ? [1.5, 1.5, 1.5] : [1, 1, 1])]);
  kf(win, la, 'scale', flash, 'step');
  kf(win, lb, 'scale', flash.map(([t, s]) => [t, ...(s > 1 ? [1, 1, 1] : [1.5, 1.5, 1.5])]), 'step');
  kf(win, marquee, 'position', [[0, 0, 0, 0], [0.15, 0, 1.2, 0], [0.3, 0, 0, 0], [0.45, 0, 0.8, 0], [0.6, 0, 0, 0]], 'catmullrom');
  kf(win, marquee, 'scale', [[0, 1, 1, 1], [0.15, 1.06, 1.06, 1.06], [0.6, 1, 1, 1]], 'catmullrom');

  Canvas.updateAll();
  return 'ok';
})();
