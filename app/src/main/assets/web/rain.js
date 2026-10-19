/* Matrix yağmuru — ULL-Bot'takinin aynısı (siyah-beyaz, az kırmızı).
 *
 * Her sütunun kendi hızı var; başı parlak, arkası kanvasa her karede çizilen
 * yarı saydam siyahla sönerek iz bırakıyor. Telefonda pil için kare hızı
 * biraz düşük ve sekme arka plandayken duruyor.
 */
(function () {
  "use strict";

  const KATAKANA = "ｦｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ";
  const KANJI = "日月火水木金土雨夢光闇電脳空心";
  const DIGITS = "0123456789";
  const GLYPHS = KATAKANA + KATAKANA + KANJI + DIGITS;

  const BASE_FPS = 20;
  const BG_SOLID = "#050505";
  const BG_FADE = "rgba(5, 5, 5, 0.085)"; // iz uzunluğunu bu alfa belirliyor
  const BODY = "#6e6e6e";
  const HEAD = "#ffffff";
  const RED_BODY = "#c41e2e";
  const RED_HEAD = "#ff8a94";

  const cfg = {
    enabled: true,
    speed: 1,       // çarpan
    density: 0.9,   // sütunların ne kadarı aktif (0-1)
    size: 16,       // hücre boyu (px)
    redRatio: 0.06, // kırmızı sütun oranı; VPN açıkken artıyor
  };

  const canvas = document.getElementById("rain");
  const ctx = canvas ? canvas.getContext("2d") : null;
  const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

  let w = 0, h = 0, rows = 0, drops = [];
  let running = false, last = 0;

  const pick = () => GLYPHS[(Math.random() * GLYPHS.length) | 0];

  function newDrop(initial) {
    return {
      // İlk açılışta ekran boş görünmesin diye bazıları ortadan başlıyor.
      y: initial ? Math.random() * rows * 1.2 - rows * 0.6 : -Math.random() * rows * 0.5,
      speed: 0.25 + Math.random() * 0.55, // satır / kare
      row: -1,
      dim: Math.random() < 0.35,          // bazı sütunlar daha sönük → derinlik
      red: Math.random() < cfg.redRatio,
      on: Math.random() < cfg.density,
    };
  }

  function resize() {
    if (!ctx) return;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    w = window.innerWidth;
    h = window.innerHeight;
    canvas.width = Math.floor(w * dpr);
    canvas.height = Math.floor(h * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.fillStyle = BG_SOLID;
    ctx.fillRect(0, 0, w, h);
    ctx.font = `${cfg.size - 2}px "MS Gothic", "Noto Sans JP", "Noto Sans CJK JP", monospace`;
    ctx.textBaseline = "top";
    ctx.textAlign = "center";
    rows = Math.ceil(h / cfg.size);
    drops = Array.from({ length: Math.ceil(w / cfg.size) }, () => newDrop(true));
  }

  function cell(i, r, color, alpha) {
    const S = cfg.size;
    ctx.fillStyle = BG_SOLID;
    ctx.fillRect(i * S, r * S, S, S);
    ctx.fillStyle = color;
    ctx.globalAlpha = alpha;
    ctx.fillText(pick(), i * S + S / 2, r * S);
    ctx.globalAlpha = 1;
  }

  function step() {
    ctx.fillStyle = BG_FADE;
    ctx.fillRect(0, 0, w, h);

    for (let i = 0; i < drops.length; i++) {
      const d = drops[i];
      if (!d.on) {
        if (Math.random() < 0.002) drops[i] = newDrop(false);
        continue;
      }
      d.y += d.speed * cfg.speed;
      const row = Math.floor(d.y);
      if (row === d.row) continue;

      // Bir önceki baş artık gövdenin parçası: parlak harfi sil, gövde rengiyle yaz.
      if (d.row >= 0) cell(i, d.row, d.red ? RED_BODY : BODY, d.dim ? 0.45 : 0.9);
      d.row = row;
      if (row >= 0) {
        ctx.fillStyle = d.red ? (d.dim ? RED_BODY : RED_HEAD) : (d.dim ? BODY : HEAD);
        ctx.globalAlpha = d.dim ? 0.6 : 1;
        ctx.fillText(pick(), i * cfg.size + cfg.size / 2, row * cfg.size);
        ctx.globalAlpha = 1;
      }
      if (row * cfg.size > h && Math.random() > 0.96) drops[i] = newDrop(false);
    }

    // Arada bir gövdedeki rastgele bir harf değişiyor (filmdeki titreşim).
    for (let k = 0; k < 3; k++) {
      const i = (Math.random() * drops.length) | 0;
      const d = drops[i];
      if (!d || !d.on || d.row < 2) continue;
      const r = d.row - 1 - ((Math.random() * Math.min(d.row, 14)) | 0);
      if (r >= 0) cell(i, r, d.red ? RED_BODY : BODY, 0.5);
    }
  }

  function loop(t) {
    if (!running) return;
    const fps = reduceMotion.matches ? 4 : BASE_FPS;
    if (t - last >= 1000 / fps) {
      last = t;
      step();
    }
    requestAnimationFrame(loop);
  }

  function start() {
    if (running || !ctx || !cfg.enabled || document.hidden) return;
    running = true;
    requestAnimationFrame(loop);
  }

  function configure(next) {
    const prevRed = cfg.redRatio;
    Object.assign(cfg, next);
    if (!ctx) return;
    document.documentElement.classList.toggle("no-rain", !cfg.enabled);
    if (!cfg.enabled) { running = false; return; }
    if (!drops.length) resize();
    else if (cfg.redRatio !== prevRed) drops.forEach((d) => { d.red = Math.random() < cfg.redRatio; });
    start();
  }

  let resizeTimer = 0;
  window.addEventListener("resize", () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => cfg.enabled && resize(), 120);
  });
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) running = false;
    else start();
  });

  window.Rain = { configure };
})();
