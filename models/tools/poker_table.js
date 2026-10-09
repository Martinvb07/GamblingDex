// Construye poker_table.bbmodel (mesa rectangular de poker) con la API de Blockbench
// (formato Generic / ModelEngine). Se ejecuta dentro de Blockbench (ver render.mjs).
// Pano de 96 x 48 (x -48..48, z -24..24) a 14 de alto; borde acolchado hasta x 54 / z 30.
// Las cartas, las fichas y el boton del dealer los pone el plugin encima (PokerVisuals).
(function () {
  newProject(Formats.free);
  Project.name = 'poker_table';
  Project.texture_width = 512; Project.texture_height = 256;

  const cv = document.createElement('canvas'); cv.width = 512; cv.height = 256;
  const g = cv.getContext('2d');
  const px = (x, y, c) => { g.fillStyle = c; g.fillRect(x, y, 1, 1); };
  const rect = (x, y, w, h, c) => { g.fillStyle = c; g.fillRect(x, y, w, h); };
  let seed = 3; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  const noise = (x0, y0, w, h, cols) => { for (let y = y0; y < y0 + h; y++) for (let x = x0; x < x0 + w; x++) px(x, y, cols[Math.floor(rnd() * cols.length)]); };

  // Pano (512x256 = 96x48 unidades, 5.33 px por unidad)
  const P = 512 / 96, OX = 48, OZ = 24;
  const FELT = ['#0f6a34', '#11713a', '#0d6331', '#127438'];
  for (let y = 0; y < 256; y++) for (let x = 0; x < 512; x++) {
    const ux = x / P - OX, uz = y / P - OZ;
    let c = FELT[Math.floor(rnd() * 4)];
    // linea de apuestas: rectangulo redondeado dorado punteado
    const ex = Math.max(Math.abs(ux) - 26, 0), ez = Math.max(Math.abs(uz) - 4, 0), dd = Math.hypot(ex, ez);
    if (dd > 13.4 && dd < 14 && (Math.floor((ux + uz) * 1.2) % 2 === 0)) c = '#d8b04a';
    if (Math.abs(ux) > 47.2 || Math.abs(uz) > 23.2) c = '#0a5428';
    px(x, y, c);
  }
  // Huecos de las 5 cartas comunitarias (contorno suave), en el centro, a lo largo de X
  for (let i = 0; i < 5; i++) {
    const cx = (i - 2) * 8.4, x1 = Math.round((cx - 3.4 + OX) * P), x2 = Math.round((cx + 3.4 + OX) * P);
    const z1 = Math.round((-4.8 + OZ) * P), z2 = Math.round((4.8 + OZ) * P);
    g.strokeStyle = '#2f8a50'; g.lineWidth = 1; g.strokeRect(x1 + 0.5, z1 + 0.5, x2 - x1, z2 - z1);
  }
  // Logo: palos + GAMBLINGDEX (hacia +Z, debajo de las cartas)
  const FONT = {
    G: ['111', '100', '101', '101', '111'], A: ['111', '101', '111', '101', '101'], M: ['10001', '11011', '10101', '10001', '10001'],
    B: ['110', '101', '110', '101', '110'], L: ['100', '100', '100', '100', '111'], I: ['111', '010', '010', '010', '111'],
    N: ['1001', '1101', '1011', '1001', '1001'], D: ['110', '101', '101', '101', '110'], E: ['111', '100', '110', '100', '111'],
    X: ['101', '101', '010', '101', '101'], P: ['111', '101', '111', '100', '100'], O: ['111', '101', '101', '101', '111'],
    K: ['101', '110', '100', '110', '101'], R: ['110', '101', '110', '101', '101'] };
  const text = (str, cx, cz, sc, col) => {
    const ws = [...str].map(ch => FONT[ch][0].length), w = ws.reduce((a, b) => a + b, 0) + ws.length - 1;
    let x0 = Math.round((cx + OX) * P - w * sc / 2); const y0 = Math.round((cz + OZ) * P - 5 * sc / 2);
    [...str].forEach((ch, k) => { FONT[ch].forEach((row, ry) => [...row].forEach((b, rx) => { if (b === '1') rect(x0 + rx * sc, y0 + ry * sc, sc, sc, col); })); x0 += (ws[k] + 1) * sc; });
  };
  text('GAMBLINGDEX', 0, 12, 3, '#d8b04a');
  text('POKER', 0, -12, 3, '#0a5428');
  const SUITS = [['.X.X.', 'XXXXX', 'XXXXX', '.XXX.', '..X..'], ['..X..', '.XXX.', 'XXXXX', '.XXX.', '..X..'],
    ['..X..', '.XXX.', 'XXXXX', 'X.X.X', '.XXX.'], ['..X..', '.XXX.', 'XXXXX', 'XXXXX', '.X.X.']];
  SUITS.forEach((rows, i) => {
    const col = i < 2 ? '#c01826' : '#1c1c20', x0 = Math.round((-9 + i * 6 + OX) * P - 5), y0 = Math.round((18.5 + OZ) * P - 5);
    rows.forEach((row, ry) => [...row].forEach((b, rx) => { if (b === 'X') rect(x0 + rx * 2, y0 + ry * 2, 2, 2, col); }));
  });

  noise(256, 0, 16, 16, ['#2a1a12', '#24160f', '#301e14', '#1e120c']);   // borde acolchado (cuero oscuro)
  noise(272, 0, 16, 16, ['#e8b420', '#f2c230', '#d9a514']);              // dorado
  noise(288, 0, 16, 16, ['#6b3d17', '#5e3514', '#764419', '#552f11']);   // madera
  noise(304, 0, 16, 16, ['#1a1a1f', '#202026', '#16161a']);              // base negra
  noise(320, 0, 16, 16, ['#0a5428', '#0b5a2b']);                         // pano oscuro (canto)

  const tex = new Texture({ name: 'poker_table.png' }).fromDataURL(cv.toDataURL()).add(false);
  const R = { pad: [256, 0], gold: [272, 0], wood: [288, 0], black: [304, 0], feltd: [320, 0] };
  const uvOf = (k) => { const r = R[k]; return [r[0] + 1, r[1] + 1, r[0] + 15, r[1] + 15]; };
  const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];
  function cube(name, from, to, parent, mat, over = {}, extra = {}) {
    const faces = {};
    FACES.forEach(f => { const m = over[f] === undefined ? mat : over[f]; if (m === null) return; faces[f] = { uv: Array.isArray(m) ? m : uvOf(m), texture: tex.uuid }; });
    const c = new Cube(Object.assign({ name, from, to, faces }, extra)); c.addTo(parent).init(); return c;
  }
  function group(name, origin, parent) { const gr = new Group({ name, origin }); gr.addTo(parent || undefined).init(); return gr; }
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

  const table = group('table', [0, 0, 0]);
  // Patas y base
  [[-40, -18], [40, -18], [-40, 18], [40, 18]].forEach(([x, z], i) => cube('leg_' + i, [x - 2.5, 0, z - 2.5], [x + 2.5, 11, z + 2.5], table, 'black'));
  cube('apron', [-50, 10, -26], [50, 13.6, 26], table, 'wood', { up: 'feltd' });
  // Pano (sin transparencias: es un rectangulo)
  cube('felt', [-48, 13.6, -24], [48, 14, 24], table, 'feltd', { up: [0, 0, 512, 256], down: null });
  // Borde acolchado: 4 lados + 4 esquinas achaflanadas (giradas 45 grados) + filete dorado
  cube('rail_n', [-48, 12, -30], [48, 16.2, -24], table, 'pad');
  cube('rail_s', [-48, 12, 24], [48, 16.2, 30], table, 'pad');
  cube('rail_w', [-54, 12, -24], [-48, 16.2, 24], table, 'pad');
  cube('rail_e', [48, 12, -24], [54, 16.2, 24], table, 'pad');
  [[-48, -24], [48, -24], [-48, 24], [48, 24]].forEach(([x, z], i) => cube('corner_' + i, [x - 4.2, 12, z - 4.2], [x + 4.2, 16.2, z + 4.2], table, 'pad', {},
    { rotation: [0, 45, 0], origin: [x, 12, z] }));
  cube('gold_n', [-48, 13.8, -24.4], [48, 14.6, -23.6], table, 'gold');
  cube('gold_s', [-48, 13.8, 23.6], [48, 14.6, 24.4], table, 'gold');
  cube('gold_w', [-48.4, 13.8, -24], [-47.6, 14.6, 24], table, 'gold');
  cube('gold_e', [47.6, 13.8, -24], [48.4, 14.6, 24], table, 'gold');

  // Fichas (como las del cajero y la ruleta): canto en (352+i*8, 0..8), cara en (352+i*8, 8..16)
  const chipCols = ['#c41c28', '#244ec4', '#22903a', '#1e1e22', '#e8be1e', '#6c28a0', '#eeeeec', '#e26e10'];
  const shade = (hex, f) => '#' + [1, 3, 5].map(i => Math.max(0, Math.min(255, Math.round(parseInt(hex.substr(i, 2), 16) * f))).toString(16).padStart(2, '0')).join('');
  const art = (x0, y0, rows, pal) => rows.forEach((r, y) => [...r].forEach((ch, x) => { if (pal[ch]) px(x0 + x, y0 + y, pal[ch]); }));
  chipCols.forEach((c, i) => {
    const x0 = 352 + i * 8, mark = i === 3 || i === 6 ? '#e8b434' : '#ffffff';
    rect(x0, 0, 8, 8, c); rect(x0, 0, 8, 1, shade(c, 0.6)); rect(x0, 7, 8, 1, shade(c, 0.6));
    for (let x = 1; x < 8; x += 4) rect(x0 + x, 1, 2, 6, mark);
    art(x0, 8, ['.oMooMo.', 'oCCCCCCo', 'MCLLLLCM', 'oCLLLLCo', 'oCLLLLCo', 'MCLLLLCM', 'oCCCCCCo', '.oMooMo.'],
      { o: shade(c, 0.75), C: c, M: mark, L: i === 6 ? '#f6e8c0' : shade(c, 1.35) });
  });
  tex.fromDataURL(cv.toDataURL());
  const CH = 0.9, D = 2.2, d = 1.3;
  const faceUV = (ci, fx1, fz1, fx2, fz2) => [352 + ci * 8 + fx1 * 8, 8 + fz1 * 8, 352 + ci * 8 + fx2 * 8, 8 + fz2 * 8];
  const chip = (name, x, z, y, ci, parent, rot) => {
    const side = [352 + ci * 8, 0, 352 + ci * 8 + 8, 8], t = (D - d) / (2 * D), u = 1 - t;
    [[D, d, faceUV(ci, 0, t, 1, u)], [d, D, faceUV(ci, t, 0, u, 1)]].forEach(([hx, hz, top], k) => {
      const c = cube(name + (k ? 'b' : 'a'), [x - hx, y, z - hz], [x + hx, y + CH, z + hz], parent, side, { up: top, down: top });
      if (rot) { c.rotation = [0, rot, 0]; c.origin = [x, y, z]; }
    });
  };
  // Bandeja del dealer (hacia -Z): 7 columnas de fichas, cada una de un color
  const deco = group('deco_chips', [0, 14, 0], table);
  cube('tray', [-17, 14, -23.5], [17, 15.2, -16], deco, 'black');
  [6, 0, 1, 2, 4, 5, 3].forEach((ci, i) => {
    const x = -13.8 + i * 4.6, h = [6, 8, 7, 9, 5, 7, 8][i];
    for (let k = 0; k < h; k++) chip('tray_' + i + '_' + k, x, -19.7, 15.2 + k * CH, ci, deco, k % 2 ? 22.5 : 0);
  });
  // Montones sueltos junto a la bandeja
  [[-21.5, -20.5, 0, 4], [-24.8, -17.2, 3, 3], [21.5, -20.5, 4, 5], [24.8, -17, 1, 2], [20.5, -15.8, 7, 3]].forEach(([x, z, ci, h], i) => {
    for (let k = 0; k < h; k++) chip('pile_' + i + '_' + k, x, z, 14 + k * CH, ci, deco, k % 2 ? 22.5 : 0);
  });

  const hitbox = group('hitbox', [0, 0, 0]);
  cube('hitbox', [-24, 0, -24], [24, 16, 24], hitbox, 'black');
  // Sin animaciones: el modelo es la mesa; lo que se mueve (cartas, fichas) lo pone el plugin.
  const idle = new Blockbench.Animation({ name: 'idle', loop: 'loop', length: 1 }).add();

  Canvas.updateAll();
  return 'ok';
})();
