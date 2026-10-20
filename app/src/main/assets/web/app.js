/* ULL-DPI arayüzü.
 *
 * `window.DPI` Kotlin tarafındaki köprü (MainActivity.Bridge). Tarayıcıda
 * açılırsa (geliştirme) sahte bir köprü devreye giriyor.
 */
(function () {
  "use strict";

  const $ = (s) => document.querySelector(s);
  const $$ = (s) => Array.from(document.querySelectorAll(s));
  const DPI = window.DPI || mockBridge();

  // --- profiller (GoodbyeDPI-Turkey .cmd dosyalarının karşılıkları) ----------

  const PRESETS = [
    {
      id: "turkey", name: "TURKEY",
      desc: "Varsayılan. Yandex DNS :1253 + TLS kayıt bölme + TCP parçalama (turkey_dnsredir · -5).",
      cfg: { dns: "yandex", tlsrec: true, split: true, splitAtSni: true, firstCut: 2, delayMs: 3, httpTricks: true },
    },
    {
      id: "alt1", name: "ALTERNATİF 1",
      desc: "Superonline vb. Sadece TCP parçalama, 1 baytlık ilk parça (alternative · --set-ttl).",
      cfg: { dns: "yandex", tlsrec: false, split: true, splitAtSni: true, firstCut: 1, delayMs: 5, httpTricks: true },
    },
    {
      id: "alt2", name: "ALTERNATİF 2",
      desc: "Sadece TLS kayıt bölme, Cloudflare DoH. Parçalamayı sevmeyen hatlar için.",
      cfg: { dns: "cloudflare", tlsrec: true, split: false, splitAtSni: true, firstCut: 2, delayMs: 0, httpTricks: true },
    },
    {
      id: "agresif", name: "AGRESİF",
      desc: "Her şey açık, parçalar arası 15 ms (alternative5 · -9). İlk bağlantı bir tık yavaş.",
      cfg: { dns: "yandex", tlsrec: true, split: true, splitAtSni: true, firstCut: 1, delayMs: 15, httpTricks: true },
    },
    {
      id: "dns", name: "SADECE DNS",
      desc: "Parçalama yok. Engel sadece DNS'teyse en hafifi.",
      cfg: { dns: "yandex", tlsrec: false, split: false, splitAtSni: false, firstCut: 2, delayMs: 0, httpTricks: false },
    },
  ];
  const METHOD_KEYS = ["dns", "tlsrec", "split", "splitAtSni", "firstCut", "delayMs", "httpTricks"];

  const DNS = [
    { id: "yandex", name: "YANDEX :1253", desc: "77.88.8.8 · standart dışı UDP portu (GoodbyeDPI-Turkey varsayılanı)." },
    { id: "cloudflare", name: "CLOUDFLARE DoH", desc: "https://1.1.1.1 · şifreli, IP üzerinden." },
    { id: "google", name: "GOOGLE DoH", desc: "https://8.8.8.8 · şifreli, IP üzerinden." },
    { id: "quad9", name: "QUAD9 DoH", desc: "https://9.9.9.9 · şifreli, IP üzerinden." },
  ];

  let cfg = load();
  let state = { running: false, starting: false };
  let lastLog = 0;
  let logLines = 0;
  let follow = true;

  // --- açılış ---------------------------------------------------------------

  (function drawBanner() {
    const F = {
      U: ["█  █", "█  █", "▀▀▀▀"],
      L: ["█   ", "█   ", "▀▀▀▀"],
      "-": ["    ", "▀▀▀ ", "    "],
      D: ["█▀▀▄", "█  █", "▀▀▀ "],
      P: ["█▀▀█", "█▀▀▀", "▀   "],
      I: ["▀█▀", " █ ", "▀▀▀"],
    };
    const word = "ULL-DPI".split("");
    const rows = [0, 1, 2].map((r) => word.map((c) => F[c][r]).join(" "));
    const el = $("#banner");
    rows.forEach((line) => {
      const r = document.createElement("div");
      r.className = "r";
      for (const ch of line) {
        const s = document.createElement("span");
        s.textContent = ch === " " ? " " : ch;
        r.appendChild(s);
      }
      el.appendChild(r);
    });
    const sub = document.createElement("div");
    sub.className = "sub";
    sub.textContent = "DPI BYPASS · NO ROOT";
    el.appendChild(sub);
  })();

  try { $("#ver").textContent = "v" + DPI.version(); } catch (e) { /* yok */ }

  // --- sekmeler ---------------------------------------------------------------

  function go(tab) {
    $$(".tabs button").forEach((b) => b.setAttribute("aria-selected", String(b.dataset.tab === tab)));
    $$(".view").forEach((v) => v.classList.toggle("is-active", v.dataset.view === tab));
    if (tab === "log") scrollLog(true);
  }
  $$(".tabs button").forEach((b) => b.addEventListener("click", () => go(b.dataset.tab)));
  $$("[data-go]").forEach((b) => b.addEventListener("click", () => go(b.dataset.go)));

  // Android geri tuşu: önce ana sekmeye dön.
  window.onBack = () => {
    const cur = $(".tabs [aria-selected='true']").dataset.tab;
    if (cur === "home") return false;
    go("home");
    return true;
  };

  // --- güç düğmesi --------------------------------------------------------------

  $("#power").addEventListener("click", () => {
    if (state.starting) return;
    if (state.running) {
      DPI.stop();
      toast("durduruluyor");
    } else {
      state.starting = true; // köprü cevap verene kadar da dönsün
      render();
      DPI.start();
    }
  });

  // --- spinner (ULL-Bot: ■ Cooking… ░▒▓█▓▒░░ 3s) -----------------------------

  const VERBS = [
    "Jacking in", "Splitting packets", "Dodging DPI", "Fragmenting", "Resolving",
    "Bending the spoon", "Following the white rabbit", "Tunneling", "Handshaking",
  ];
  const BOUNCE = ["▄", "■", "▀", "■"];
  const PULSE = "░▒▓█▓▒░░░░";
  let spin = null;

  function spinner(on) {
    const node = $("#spinner");
    if (!on) {
      if (spin) { clearInterval(spin.t1); clearInterval(spin.t2); spin = null; }
      node.textContent = "";
      return;
    }
    if (spin) return;
    node.innerHTML = '<span class="glyph"></span><span class="verb"></span><span class="bar"></span><span class="secs"></span>';
    const [glyph, verb, bar, secs] = node.children;
    const started = performance.now();
    let frame = 0;
    const nextVerb = () => { verb.textContent = VERBS[(Math.random() * VERBS.length) | 0] + "…"; };
    const tick = () => {
      glyph.textContent = BOUNCE[frame % BOUNCE.length];
      let s = "";
      for (let j = 0; j < 8; j++) s += PULSE[(((j - frame) % PULSE.length) + PULSE.length) % PULSE.length];
      bar.textContent = s;
      secs.textContent = Math.floor((performance.now() - started) / 1000) + "s";
      frame++;
    };
    nextVerb(); tick();
    spin = { t1: setInterval(tick, 110), t2: setInterval(nextVerb, 1800) };
  }

  // --- durum ------------------------------------------------------------------

  function poll() {
    try { state = JSON.parse(DPI.status()); } catch (e) { /* köprü meşgul */ }
    render();
    renderSpeed();
    pullLogs();
  }

  function render() {
    const on = state.running, wait = state.starting;
    const power = $("#power");
    power.classList.toggle("is-on", on);
    power.classList.toggle("is-wait", wait);
    $("#power-k").textContent = wait ? "[ . . . ]" : on ? "[ DURDUR ]" : "[ BAŞLAT ]";
    $("#power-s").textContent = wait ? "bağlanıyor" : on ? "aktif · " + uptime(state.since) : "dokun ▸ bağlan";

    const conn = $("#conn");
    conn.className = "conn" + (on ? " is-ok" : wait ? " is-wait" : state.error ? " is-bad" : "");
    conn.lastChild.textContent = on ? "AKTİF" : wait ? "BAĞLANIYOR" : state.error ? "HATA" : "KAPALI";

    const line = $("#status-line");
    line.className = "status-line" + (on ? " is-on" : !wait && state.error ? " is-bad" : "");
    if (on) line.innerHTML = "&gt; durum: <b>AKTİF</b> · proxy :" + state.port;
    else if (wait) line.innerHTML = "&gt; durum: <b>BAĞLANIYOR</b>";
    else if (state.error) line.textContent = "> hata: " + state.error;
    else line.innerHTML = "&gt; durum: <b>KAPALI</b>";
    spinner(wait);

    $("#st-conn").innerHTML = (state.active || 0) + "<small>/ " + (state.total || 0) + "</small>";
    $("#st-desync").textContent = num(state.desynced || 0);
    $("#st-dns").innerHTML = num(state.dns || 0) + (state.dnsCached ? "<small>önb. " + num(state.dnsCached) + "</small>" : "");
    $("#st-traffic").innerHTML = bytes((state.up || 0) + (state.down || 0)) +
      "<small>↑" + bytes(state.up || 0) + "</small>";

    // VPN açıkken yağmur biraz daha kırmızı.
    if (window.Rain && render.wasOn !== on) {
      render.wasOn = on;
      window.Rain.configure({ redRatio: on ? 0.22 : 0.06 });
    }
  }

  function uptime(since) {
    if (!since) return "";
    const s = Math.max(0, Math.floor((Date.now() - since) / 1000));
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
    return (h ? h + "s " : "") + String(m).padStart(2, "0") + ":" + String(x).padStart(2, "0");
  }
  const num = (n) => n >= 10000 ? (n / 1000).toFixed(1) + "k" : String(n);
  function bytes(n) {
    const u = ["B", "KB", "MB", "GB"];
    let i = 0;
    while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
    return (i ? n.toFixed(n < 10 ? 1 : 0) : n) + " " + u[i];
  }

  // --- hız (Ookla tarzı ibre) ----------------------------------------------------

  const G = { cx: 130, cy: 112, r: 94, a0: 150, sweep: 240 };
  const SCALE_S = [0, 1, 5, 10, 20, 30, 50, 75, 100];
  const SCALE_L = [0, 5, 10, 50, 100, 250, 500, 750, 1000];
  let scale = SCALE_S;
  let gTarget = 0, gShown = 0, gLabel = "";
  const svgNS = "http://www.w3.org/2000/svg";

  const mbps = (bits) => (bits || 0) / 1e6;
  const fmtM = (bits) => { const m = mbps(bits); return m < 10 ? m.toFixed(1) : m.toFixed(0); };
  const pt = (a, r) => [G.cx + r * Math.cos(a * Math.PI / 180), G.cy + r * Math.sin(a * Math.PI / 180)];
  function arc(a1, a2, r) {
    const [x1, y1] = pt(a1, r), [x2, y2] = pt(a2, r);
    return `M${x1.toFixed(1)} ${y1.toFixed(1)} A${r} ${r} 0 ${a2 - a1 > 180 ? 1 : 0} 1 ${x2.toFixed(1)} ${y2.toFixed(1)}`;
  }
  // Ookla gibi doğrusal olmayan ölçek: her aralık eşit açı.
  function angleOf(m) {
    const n = scale.length - 1;
    if (m >= scale[n]) return G.a0 + G.sweep;
    let i = 0;
    while (i < n - 1 && m >= scale[i + 1]) i++;
    const f = Math.max(0, (m - scale[i]) / (scale[i + 1] - scale[i]));
    return G.a0 + G.sweep * ((i + f) / n);
  }

  function drawTicks() {
    const g = $("#g-ticks");
    g.textContent = "";
    scale.forEach((v, i) => {
      const a = G.a0 + G.sweep * (i / (scale.length - 1));
      const [x1, y1] = pt(a, G.r - 9), [x2, y2] = pt(a, G.r - 15);
      const l = document.createElementNS(svgNS, "line");
      l.setAttribute("class", "g-tick");
      l.setAttribute("x1", x1); l.setAttribute("y1", y1); l.setAttribute("x2", x2); l.setAttribute("y2", y2);
      l.dataset.v = v;
      const [tx, ty] = pt(a, G.r - 26);
      const t = document.createElementNS(svgNS, "text");
      t.setAttribute("class", "g-num");
      t.setAttribute("x", tx); t.setAttribute("y", ty);
      t.textContent = v;
      t.dataset.v = v;
      g.append(l, t);
    });
  }
  $("#g-track").setAttribute("d", arc(G.a0, G.a0 + G.sweep, G.r));
  drawTicks();

  function gaugeFrame() {
    gShown += (gTarget - gShown) * 0.14;
    if (Math.abs(gTarget - gShown) < 0.005) gShown = gTarget;
    const a = angleOf(gShown);
    $("#g-fill").setAttribute("d", a > G.a0 + 0.5 ? arc(G.a0, a, G.r) : "");
    // İbre merkezden değil halkanın içinden başlıyor: ortadaki rakamı kapatmasın.
    const [sx, sy] = pt(a, 48), [nx, ny] = pt(a, G.r - 18);
    const n = $("#g-needle");
    n.setAttribute("x1", sx.toFixed(1)); n.setAttribute("y1", sy.toFixed(1));
    n.setAttribute("x2", nx.toFixed(1)); n.setAttribute("y2", ny.toFixed(1));
    $("#g-val").textContent = gShown < 10 ? gShown.toFixed(1) : gShown.toFixed(0);
    $$("#g-ticks [data-v]").forEach((el) => el.classList.toggle("on", Number(el.dataset.v) <= gShown + 1e-9));
    requestAnimationFrame(gaugeFrame);
  }
  requestAnimationFrame(gaugeFrame);

  function setGauge(bits, label, up) {
    gTarget = mbps(bits);
    const want = gTarget > 100 ? SCALE_L : (gTarget < 70 ? SCALE_S : scale);
    if (want !== scale) { scale = want; drawTicks(); }
    $("#g-lbl").textContent = label;
    $(".speed").classList.toggle("is-up", !!up);
  }

  function spark(hd, hu) {
    if (!hd || !hd.length) return;
    const max = Math.max(1e5, ...hd, ...hu);
    const path = (arr) => arr.map((v, i) =>
      (i ? "L" : "M") + (i * 400 / (arr.length - 1)).toFixed(1) + " " + (42 - (v / max) * 38).toFixed(1)).join(" ");
    $("#spark-d").setAttribute("d", path(hd));
    $("#spark-u").setAttribute("d", path(hu));
  }

  const PHASE = { ping: "PING", down: "İNDİRME", up: "YÜKLEME" };
  function renderSpeed() {
    const sp = state.speed || {};
    const t = state.test || { phase: "idle" };
    const testing = t.phase in PHASE;
    const recent = t.phase === "done" && Date.now() - t.at < 60000;
    const badge = $("#sp-mode");
    const btn = $("#sp-test");
    let ping = sp.ping, down = sp.down, up = sp.up;

    if (testing) {
      if (t.phase === "ping") setGauge(0, "PING ÖLÇÜLÜYOR…", false);
      else if (t.phase === "down") setGauge(t.down, "↓ İNDİRME · TEST", false);
      else setGauge(t.up, "↑ YÜKLEME · TEST", true);
      badge.className = "badge red";
      badge.textContent = "TEST · " + PHASE[t.phase] + " %" + Math.round((t.progress || 0) * 100);
      ping = t.ping; down = t.phase === "ping" ? null : t.down; up = t.phase === "up" ? t.up : null;
      btn.disabled = true;
      btn.textContent = "TEST…";
    } else {
      setGauge(sp.down, "↓ İNDİRME · CANLI", false);
      btn.disabled = false;
      btn.textContent = t.phase === "done" || t.phase === "error" ? "TEKRAR ▶" : "HIZ TESTİ ▶";
      if (recent) {
        badge.className = "badge solid";
        badge.textContent = "SON TEST";
        ping = t.ping; down = t.down; up = t.up;
      } else {
        badge.className = "badge live";
        badge.textContent = "CANLI";
      }
    }
    $("#sp-ping").textContent = ping >= 0 ? String(ping) : "—";
    $("#sp-down").textContent = down == null ? "—" : fmtM(down);
    $("#sp-up").textContent = up == null ? "—" : fmtM(up);
    spark(sp.histDown, sp.histUp);

    const last = $("#sp-last");
    if (t.phase === "error") last.textContent = "> test başarısız: " + (t.error || "bilinmiyor");
    else if (t.phase === "done") {
      const hm = new Date(t.at).toTimeString().slice(0, 5);
      last.innerHTML = "son test <b>" + hm + "</b> · ↓ <b>" + fmtM(t.down) + "</b> ↑ <b>" + fmtM(t.up) +
        "</b> Mbps · <b>" + t.ping + "</b> ms";
    }
  }

  $("#sp-test").addEventListener("click", () => {
    DPI.speedTest();
    toast("hız testi başladı · speed.cloudflare.com");
    setTimeout(poll, 150);
  });

  // --- log ----------------------------------------------------------------------

  function lineNode(l) {
    const d = document.createElement("div");
    d.className = "ln";
    const t = document.createElement("span"); t.className = "t"; t.textContent = l.t + " ";
    const g = document.createElement("span"); g.className = "g " + l.tag; g.textContent = l.tag;
    d.append(t, g, document.createTextNode(" " + l.msg));
    return d;
  }

  function pullLogs() {
    let items = [];
    try { items = JSON.parse(DPI.logs(lastLog)); } catch (e) { return; }
    if (!items.length) { if (!logLines) emptyLogs(); return; }
    const term = $("#term");
    if (!logLines) term.textContent = "";
    for (const l of items) { term.appendChild(lineNode(l)); logLines++; lastLog = l.id; }
    while (term.childNodes.length > 600) { term.removeChild(term.firstChild); }
    $("#log-count").textContent = logLines + " satır";
    const mini = $("#term-mini");
    mini.textContent = "";
    Array.from(term.childNodes).slice(-8).forEach((n) => mini.appendChild(n.cloneNode(true)));
    scrollLog();
  }

  function emptyLogs() {
    for (const id of ["#term", "#term-mini"]) {
      const el = $(id);
      if (!el.firstChild) el.innerHTML = '<div class="empty">henüz olay yok — BAŞLAT\'a dokun</div>';
    }
  }

  function scrollLog(force) {
    const term = $("#term");
    if (follow || force) term.scrollTop = term.scrollHeight;
  }

  $("#log-follow").addEventListener("click", (e) => {
    follow = !follow;
    e.currentTarget.setAttribute("aria-pressed", String(follow));
    e.currentTarget.textContent = follow ? "▼ TAKİP" : "‖ DURDU";
    scrollLog();
  });
  $("#log-clear").addEventListener("click", () => {
    DPI.clearLogs();
    $("#term").textContent = "";
    $("#term-mini").textContent = "";
    logLines = 0;
    $("#log-count").textContent = "0 satır";
    emptyLogs();
  });

  // --- ayarlar ----------------------------------------------------------------

  function load() {
    try { return JSON.parse(DPI.getConfig()); } catch (e) { return {}; }
  }

  let saveTimer = 0;
  function save(msg) {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => {
      const r = DPI.saveConfig(JSON.stringify(cfg));
      if (r === "ok") toast(msg || (state.running ? "kaydedildi · uygulandı" : "kaydedildi"));
      else toast("kaydedilemedi: " + r, true);
    }, 250);
  }

  function pickList(root, items, current, onPick) {
    root.textContent = "";
    for (const it of items) {
      const b = document.createElement("button");
      b.type = "button";
      b.setAttribute("role", "radio");
      b.setAttribute("aria-checked", String(it.id === current));
      b.innerHTML = '<span class="n"></span><span class="p"></span>';
      b.firstChild.textContent = it.name;
      b.lastChild.textContent = it.desc;
      b.addEventListener("click", () => onPick(it));
      root.appendChild(b);
    }
  }

  function presetName() {
    const p = PRESETS.find((x) => x.id === cfg.preset);
    return p ? p.name : "ÖZEL";
  }

  function renderSettings() {
    pickList($("#presets"), PRESETS, cfg.preset, (p) => {
      Object.assign(cfg, p.cfg, { preset: p.id });
      renderSettings();
      save(p.name + " seçildi");
    });
    pickList($("#dns"), DNS, cfg.dns, (d) => {
      cfg.dns = d.id;
      touched();
    });
    $("#custom-badge").hidden = PRESETS.some((p) => p.id === cfg.preset);

    $$(".switch[data-key]").forEach((s) => s.setAttribute("aria-checked", String(!!cfg[s.dataset.key])));
    $$("input[type=range][data-key]").forEach((r) => {
      r.value = cfg[r.dataset.key];
      $("#v-" + r.dataset.key).textContent = r.value;
    });
    $$("[data-needs]").forEach((el) => el.classList.toggle("is-off", !cfg[el.dataset.needs]));

    $$("#scope button").forEach((b) => b.setAttribute("aria-pressed", String((b.dataset.all === "1") === !!cfg.scopeAll)));
    $("#domains-wrap").hidden = !!cfg.scopeAll;
    if (document.activeElement !== $("#domains")) $("#domains").value = (cfg.domains || []).join("\n");

    $("#prof-name").textContent = presetName();
    $("#prof-sum").textContent = summary();
  }

  function summary() {
    const d = DNS.find((x) => x.id === cfg.dns);
    const m = [cfg.tlsrec && "tlsrec", cfg.split && "split:" + cfg.firstCut + (cfg.splitAtSni ? "+sni" : ""), cfg.httpTricks && "host"]
      .filter(Boolean);
    return "dns " + (d ? d.name.toLowerCase() : cfg.dns) + " · " + (m.length ? m.join(" + ") : "parçalama yok") +
      (cfg.delayMs ? " · " + cfg.delayMs + "ms" : "") + " · " + (cfg.scopeAll ? "tüm siteler" : (cfg.domains || []).length + " alan adı");
  }

  // Yöntem ayarı elle değiştiyse profil artık ÖZEL.
  function touched(key) {
    if (!key || METHOD_KEYS.includes(key)) {
      const p = PRESETS.find((x) => METHOD_KEYS.every((k) => x.cfg[k] === cfg[k]));
      cfg.preset = p ? p.id : "ozel";
    }
    renderSettings();
    save();
  }

  $$(".switch[data-key]").forEach((s) => s.addEventListener("click", () => {
    cfg[s.dataset.key] = !cfg[s.dataset.key];
    touched(s.dataset.key);
  }));
  $$("input[type=range][data-key]").forEach((r) => {
    r.addEventListener("input", () => { $("#v-" + r.dataset.key).textContent = r.value; });
    r.addEventListener("change", () => { cfg[r.dataset.key] = Number(r.value); touched(r.dataset.key); });
  });
  $$("#scope button").forEach((b) => b.addEventListener("click", () => {
    cfg.scopeAll = b.dataset.all === "1";
    touched("scopeAll");
  }));
  $("#domains").addEventListener("change", (e) => {
    cfg.domains = e.target.value.split(/[\s,]+/).map((x) => x.trim().toLowerCase()).filter(Boolean);
    touched("domains");
  });

  // --- görünüm (yalnızca bu cihazda, localStorage) ------------------------------

  const LOOK_KEY = "ull.dpi.look";
  let look = { rain: true, speed: 1 };
  try { Object.assign(look, JSON.parse(localStorage.getItem(LOOK_KEY) || "{}")); } catch (e) { /* yok */ }

  function applyLook() {
    $("#look-rain").setAttribute("aria-checked", String(look.rain));
    $("#look-speed").value = look.speed;
    $("#v-rainSpeed").textContent = Number(look.speed).toFixed(1) + "×";
    if (window.Rain) window.Rain.configure({ enabled: look.rain, speed: Number(look.speed) });
    try { localStorage.setItem(LOOK_KEY, JSON.stringify(look)); } catch (e) { /* yok */ }
  }
  $("#look-rain").addEventListener("click", () => { look.rain = !look.rain; applyLook(); });
  $("#look-speed").addEventListener("input", (e) => { look.speed = Number(e.target.value); applyLook(); });

  // --- bildirim -----------------------------------------------------------------

  let toastTimer = 0;
  function toast(text, bad) {
    const t = $("#toast");
    t.textContent = text;
    t.className = "toast is-on" + (bad ? " is-bad" : "");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.className = "toast"; }, 1800);
  }

  // --- tarayıcıda geliştirme için sahte köprü --------------------------------------

  function mockBridge() {
    let c = {
      preset: "turkey", dns: "yandex", tlsrec: true, split: true, splitAtSni: true, firstCut: 2, delayMs: 3,
      httpTricks: true, scopeAll: true, logDns: false, port: 18881,
      domains: ["discord.com", "discord.gg", "discordapp.com", "discordapp.net", "discord.media", "discordcdn.com"],
    };
    const s = { running: false, starting: false, error: null, since: 0, port: -1, active: 0, total: 0, desynced: 0, up: 0, down: 0, dns: 0, dnsCached: 0 };
    const sp = { down: 0, up: 0, ping: 24, histDown: Array(40).fill(0), histUp: Array(40).fill(0) };
    const test = { phase: "idle", ping: -1, down: 0, up: 0, progress: 0, error: null, at: 0 };
    s.speed = sp; s.test = test;
    setInterval(() => {
      const base = s.running ? 18e6 : 3e6;
      sp.down = Math.max(0, base * (0.4 + Math.random()));
      sp.up = Math.max(0, base * 0.12 * (0.3 + Math.random()));
      sp.ping = 18 + ((Math.random() * 14) | 0);
      sp.histDown.push(sp.down); sp.histDown.shift();
      sp.histUp.push(sp.up); sp.histUp.shift();
    }, 1000);
    const logs = [];
    let id = 0;
    const log = (tag, msg) => logs.push({ id: ++id, t: new Date().toTimeString().slice(0, 8), tag, msg });
    const hosts = ["discord.com", "gateway.discord.gg", "cdn.discordapp.com", "media.discordapp.net", "www.google.com", "i.ytimg.com"];
    setInterval(() => {
      if (!s.running) return;
      s.total += 1 + ((Math.random() * 3) | 0);
      s.active = 3 + ((Math.random() * 9) | 0);
      s.dns += (Math.random() * 4) | 0;
      s.dnsCached = Math.floor(s.dns * 0.6);
      s.up += (Math.random() * 40000) | 0;
      s.down += (Math.random() * 900000) | 0;
      if (Math.random() < 0.7) { s.desynced++; log("TLS", hosts[(Math.random() * hosts.length) | 0] + ":443 ▸ " + (3 + ((Math.random() * 2) | 0)) + " parça"); }
    }, 700);
    return {
      version: () => "0.1.0-dev",
      getConfig: () => JSON.stringify(c),
      saveConfig: (j) => { c = JSON.parse(j); return "ok"; },
      start() {
        s.starting = true;
        setTimeout(() => {
          s.starting = false; s.running = true; s.since = Date.now(); s.port = 18881;
          log("SYS", "proxy ▸ 127.0.0.1:18881");
          log("SYS", "AKTİF ▸ 77.88.8.8:1253 · tlsrec+split+host");
        }, 1600);
      },
      stop() { s.running = false; s.active = 0; s.port = -1; log("SYS", "durduruldu"); },
      status: () => JSON.stringify(s),
      logs: (since) => JSON.stringify(logs.filter((l) => l.id > since)),
      clearLogs: () => { logs.length = 0; },
      speedTest() {
        Object.assign(test, { phase: "ping", ping: -1, down: 0, up: 0, progress: 0 });
        const t0 = Date.now();
        const iv = setInterval(() => {
          const e = (Date.now() - t0) / 1000;
          if (e < 1.5) { test.phase = "ping"; test.progress = e / 1.5; test.ping = 17; }
          else if (e < 9.5) { test.phase = "down"; test.progress = (e - 1.5) / 8; test.down = 86e6 * (1 - Math.exp(-(e - 1.5))) * (0.95 + Math.random() * 0.1); }
          else if (e < 16.5) { test.phase = "up"; test.progress = (e - 9.5) / 7; test.up = 21e6 * (1 - Math.exp(-(e - 9.5))) * (0.95 + Math.random() * 0.1); }
          else { test.phase = "done"; test.at = Date.now(); clearInterval(iv); }
        }, 200);
      },
    };
  }

  // --- başla ----------------------------------------------------------------------

  if (!cfg.domains) cfg.domains = [];
  renderSettings();
  applyLook();
  poll();
  setInterval(poll, 1000);
  setInterval(() => { if (state.test && state.test.phase in PHASE) poll(); }, 250);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) poll(); });
})();
