// Construye blackjack_table.bbmodel (mesa rectangular de blackjack, como la de poker) con la
// API de Blockbench (formato Generic / ModelEngine). Se ejecuta dentro de Blockbench (ver render.mjs).
// El dealer esta en el origen y la mesa queda delante de el (hacia -Z, el frente del modelo).
// Pano de 96 x 48 (x -48..48, z -54..-6) a 14 de alto; borde acolchado hasta x 54 / z -60 y
// borde de madera del lado del dealer (z -6..-3). Las cartas y fichas las pone el plugin
// encima (BlackjackVisuals).
(function () {
  newProject(Formats.free);
  Project.name = 'blackjack_table';
  Project.texture_width = 512; Project.texture_height = 256;

  const cv = document.createElement('canvas'); cv.width = 512; cv.height = 256;
  const g = cv.getContext('2d');
  const px = (x, y, c) => { g.fillStyle = c; g.fillRect(x, y, 1, 1); };
  const rect = (x, y, w, h, c) => { g.fillStyle = c; g.fillRect(x, y, w, h); };
  let seed = 9; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  const noise = (x0, y0, w, h, cols) => { for (let y = y0; y < y0 + h; y++) for (let x = x0; x < x0 + w; x++) px(x, y, cols[Math.floor(rnd() * cols.length)]); };

  // ---------- Pano: (0,0)-(512,256) = x -48..48, z -54..-6 (96 x 48), 5.33 px por unidad ----------
  // Arriba de la textura = z -54 (lado de los jugadores); abajo = z -6 (dealer).
  const P = 512 / 96, CZ = -6;
  const U = (x) => (x + 48) * P, V = (z) => (z + 54) * P;
  const FELT = ['#0f6a34', '#11713a', '#0d6331', '#127438'];
  for (let y = 0; y < 256; y++) for (let x = 0; x < 512; x++) {
    const ux = x / P - 48, uz = y / P - 54;
    let c = FELT[Math.floor(rnd() * 4)];
    // linea dorada punteada (rectangulo redondeado) como en la mesa de poker
    const ex = Math.max(Math.abs(ux) - 26, 0), ez = Math.max(Math.abs(uz + 33.5) - 1, 0), dd = Math.hypot(ex, ez);
    if (dd > 13.4 && dd < 14 && (Math.floor((ux + uz) * 1.2) % 2 === 0)) c = '#d8b04a';
    if (Math.abs(ux) > 47.2 || uz < -53.2 || uz > -6.8) c = '#0a5428';
    px(x, y, c);
  }
  // Textos: se leen desde el lado de los jugadores (girados 180 grados en la textura)
  const FONT = {
    A: ['111', '101', '111', '101', '101'], B: ['110', '101', '110', '101', '110'], C: ['111', '100', '100', '100', '111'],
    D: ['110', '101', '101', '101', '110'], E: ['111', '100', '110', '100', '111'], G: ['111', '100', '101', '101', '111'],
    I: ['111', '010', '010', '010', '111'], J: ['001', '001', '001', '101', '111'], K: ['101', '110', '100', '110', '101'],
    L: ['100', '100', '100', '100', '111'], M: ['10001', '11011', '10101', '10001', '10001'], N: ['1001', '1101', '1011', '1001', '1001'],
    O: ['111', '101', '101', '101', '111'], P: ['111', '101', '111', '100', '100'], R: ['110', '101', '110', '101', '101'],
    S: ['111', '100', '111', '001', '111'], T: ['111', '010', '010', '010', '010'], U: ['101', '101', '101', '101', '111'],
    X: ['101', '101', '010', '101', '101'], Y: ['101', '101', '010', '010', '010'], ' ': ['0', '0', '0', '0', '0'],
    '1': ['010', '110', '010', '010', '111'], '2': ['111', '001', '111', '100', '111'], '3': ['111', '001', '111', '001', '111'],
    '6': ['111', '100', '111', '101', '111'], '7': ['111', '001', '010', '010', '010'] };
  const text = (str, cx, cz, sc, col) => {
    const ws = [...str].map(ch => FONT[ch][0].length), w = ws.reduce((a, b) => a + b, 0) + ws.length - 1;
    // girado 180: se dibuja de derecha a izquierda y de abajo hacia arriba
    let x0 = Math.round(U(cx) + w * sc / 2) - sc; const y0 = Math.round(V(cz) + 5 * sc / 2) - sc;
    [...str].forEach((ch, k) => {
      FONT[ch].forEach((row, ry) => [...row].forEach((b, rx) => { if (b === '1') rect(x0 - rx * sc, y0 - ry * sc, sc, sc, col); }));
      x0 -= (ws[k] + 1) * sc;
    });
  };
  text('EL SEGURO PAGA 2 A 1', 0, -26.6, 1, '#e8e8e0');
  text('BLACKJACK PAGA 3 A 2', 0, -31, 2, '#d8b04a');
  text('EL CRUPIER PIDE CON 16 Y SE PLANTA CON 17', 0, -35.6, 1, '#e8e8e0');
  text('GAMBLINGDEX', 0, -42, 2, '#0a5428');

  noise(0, 0, 0, 0, ['#000']);
  const M = 0; // materiales en una fila aparte de la textura del pano no caben: van en una segunda textura
  const tex = new Texture({ name: 'blackjack_felt.png' }).fromDataURL(cv.toDataURL()).add(false);

  // Segunda textura (materiales y fichas) 128x32
  const cv2 = document.createElement('canvas'); cv2.width = 128; cv2.height = 32;
  const g2 = cv2.getContext('2d');
  const px2 = (x, y, c) => { g2.fillStyle = c; g2.fillRect(x, y, 1, 1); };
  const rect2 = (x, y, w, h, c) => { g2.fillStyle = c; g2.fillRect(x, y, w, h); };
  const noise2 = (x0, y0, w, h, cols) => { for (let y = y0; y < y0 + h; y++) for (let x = x0; x < x0 + w; x++) px2(x, y, cols[Math.floor(rnd() * cols.length)]); };
  noise2(0, 0, 16, 16, ['#2a1a12', '#24160f', '#301e14', '#1e120c']);   // borde acolchado
  noise2(16, 0, 16, 16, ['#e8b420', '#f2c230', '#d9a514']);              // dorado
  noise2(32, 0, 16, 16, ['#6b3d17', '#5e3514', '#764419', '#552f11']);   // madera
  noise2(48, 0, 16, 16, ['#1a1a1f', '#202026', '#16161a']);              // negro
  noise2(0, 16, 16, 16, ['#0a5428', '#0b5a2b']);                         // pano oscuro
  noise2(16, 16, 16, 16, ['#8c101b', '#a01420', '#7a0e18']);             // zapato rojo
  rect2(32, 16, 16, 16, '#f4f2ea'); rect2(32, 16, 16, 2, '#c8c4b4');     // cartas (canto)
  const chipCols = ['#c41c28', '#244ec4', '#22903a', '#1e1e22', '#e8be1e', '#6c28a0', '#eeeeec', '#e26e10'];
  const shade = (hex, f) => '#' + [1, 3, 5].map(i => Math.max(0, Math.min(255, Math.round(parseInt(hex.substr(i, 2), 16) * f))).toString(16).padStart(2, '0')).join('');
  const art2 = (x0, y0, rows, pal) => rows.forEach((r, y) => [...r].forEach((ch, x) => { if (pal[ch]) px2(x0 + x, y0 + y, pal[ch]); }));
  chipCols.forEach((c, i) => {
    const x0 = 64 + i * 8, mark = i === 3 || i === 6 ? '#e8b434' : '#ffffff';
    rect2(x0, 0, 8, 8, c); rect2(x0, 0, 8, 1, shade(c, 0.6)); rect2(x0, 7, 8, 1, shade(c, 0.6));
    for (let x = 1; x < 8; x += 4) rect2(x0 + x, 1, 2, 6, mark);
    art2(x0, 8, ['.oMooMo.', 'oCCCCCCo', 'MCLLLLCM', 'oCLLLLCo', 'oCLLLLCo', 'MCLLLLCM', 'oCCCCCCo', '.oMooMo.'],
      { o: shade(c, 0.75), C: c, M: mark, L: i === 6 ? '#f6e8c0' : shade(c, 1.35) });
  });
  const tex2 = new Texture({ name: 'blackjack_parts.png' }).fromDataURL(cv2.toDataURL()).add(false);
  // UV de la segunda textura en unidades del proyecto (512x256): x4 en X, x8 en Y
  const T2 = (x1, y1, x2, y2) => [x1 * 4, y1 * 8, x2 * 4, y2 * 8];
  const R2 = { pad: [0, 0], gold: [16, 0], wood: [32, 0], black: [48, 0], feltd: [0, 16], shoe: [16, 16], cards: [32, 16] };
  const uv2 = (k) => { const r = R2[k]; return T2(r[0] + 1, r[1] + 1, r[0] + 15, r[1] + 15); };

  const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];
  function cube(name, from, to, parent, mat, over = {}, extra = {}) {
    const faces = {};
    FACES.forEach(f => {
      const m = over[f] === undefined ? mat : over[f];
      if (m === null) return;
      if (m && m.felt) faces[f] = { uv: m.felt, texture: tex.uuid };
      else faces[f] = { uv: Array.isArray(m) ? m : uv2(m), texture: tex2.uuid };
    });
    const c = new Cube(Object.assign({ name, from, to, faces }, extra)); c.addTo(parent).init(); return c;
  }
  function group(name, origin, parent) { const gr = new Group({ name, origin }); gr.addTo(parent || undefined).init(); return gr; }
  const table = group('table', [0, 0, 0]);
  // Patas y cuerpo
  [[-40, -14], [40, -14], [-40, -46], [40, -46]].forEach(([x, z], i) => cube('leg_' + i, [x - 2.5, 0, z - 2.5], [x + 2.5, 11, z + 2.5], table, 'black'));
  cube('apron', [-50, 10, -56], [50, 13.6, -6], table, 'wood', { up: 'feltd' });
  // Pano (rectangulo entero)
  cube('felt', [-48, 13.6, -54], [48, 14, CZ], table, 'feltd', { up: { felt: [0, 0, 512, 256] }, down: null });
  // Borde acolchado en los 3 lados de los jugadores + esquinas achaflanadas + filete dorado
  cube('rail_n', [-48, 12, -60], [48, 16.2, -54], table, 'pad');
  cube('rail_w', [-54, 12, -54], [-48, 16.2, CZ], table, 'pad');
  cube('rail_e', [48, 12, -54], [54, 16.2, CZ], table, 'pad');
  [[-48, -54], [48, -54]].forEach(([x, z], i) => cube('corner_' + i, [x - 4.2, 12, z - 4.2], [x + 4.2, 16.2, z + 4.2], table, 'pad', {},
    { rotation: [0, 45, 0], origin: [x, 12, z] }));
  cube('gold_n', [-48, 13.8, -54.4], [48, 14.6, -53.6], table, 'gold');
  cube('gold_w', [-48.4, 13.8, -54], [-47.6, 14.6, CZ], table, 'gold');
  cube('gold_e', [47.6, 13.8, -54], [48.4, 14.6, CZ], table, 'gold');
  // Lado del dealer: borde de madera con filete dorado
  cube('dealer_edge', [-54, 12, CZ], [54, 14.8, CZ + 3], table, 'wood', { up: 'gold' });

  // Bandeja de fichas del dealer (centro del borde recto) con fichas bonitas
  const deco = group('deco', [0, 14, 0], table);
  cube('tray', [-16, 14, -13], [16, 15.2, -6.4], deco, 'black');
  const CH = 0.9, D = 2.2, d = 1.3;
  const chipSide = (ci) => T2(64 + ci * 8, 0, 72 + ci * 8, 8);
  const chipTop = (ci, fx1, fz1, fx2, fz2) => T2(64 + ci * 8 + fx1 * 8, 8 + fz1 * 8, 64 + ci * 8 + fx2 * 8, 8 + fz2 * 8);
  const chip = (name, x, z, y, ci, parent, rot) => {
    const t = (D - d) / (2 * D), u = 1 - t;
    [[D, d, chipTop(ci, 0, t, 1, u)], [d, D, chipTop(ci, t, 0, u, 1)]].forEach(([hx, hz, top], k) => {
      const c = cube(name + (k ? 'b' : 'a'), [x - hx, y, z - hz], [x + hx, y + CH, z + hz], parent, chipSide(ci), { up: top, down: top });
      if (rot) { c.rotation = [0, rot, 0]; c.origin = [x, y, z]; }
    });
  };
  [6, 0, 1, 2, 4, 5, 3].forEach((ci, i) => {
    const x = -13.2 + i * 4.4, h = [6, 8, 7, 9, 5, 7, 8][i];
    for (let k = 0; k < h; k++) chip('tray_' + i + '_' + k, x, -9.7, 15.2 + k * CH, ci, deco, k % 2 ? 22.5 : 0);
  });
  // Zapato de cartas (a la izquierda del dealer, lado +X) y bandeja de descartes (lado -X)
  cube('shoe', [22, 14, -16], [32, 19, -9], deco, 'shoe', { up: 'black' });
  cube('shoe_slot', [23, 14.5, -16.4], [31, 17.5, -15.9], deco, 'black');
  cube('shoe_cards', [23, 15, -15.95], [31, 18.6, -10], deco, 'cards');
  cube('discard', [-32, 14, -15], [-22, 16, -9], deco, 'black');
  cube('discard_cards', [-31, 16, -14], [-23, 16.8, -10], deco, 'cards');

  const hitbox = group('hitbox', [0, 0, 0]);
  cube('hitbox', [-24, 0, -54], [24, 16, -6], hitbox, 'black');
  new Blockbench.Animation({ name: 'idle', loop: 'loop', length: 1 }).add();

  Canvas.updateAll();
  return 'ok';
})();
