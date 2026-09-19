/*
  TAG ARENA - multiplayer tag game server
  Zero dependencies: raw Node http + a small RFC6455 WebSocket implementation.

  Run:  node server.js          (or double-click start.bat)
  Env:  PORT=3000               change the port
        PUBLIC_URL=host:port    bake a tunnel/public address into share/tag.html
*/

const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PORT = Number(process.env.PORT) || 3000;
const ROOT = __dirname;

/* ------------------------------------------------------------------ */
/*  Minimal WebSocket server (RFC 6455, text frames only)              */
/* ------------------------------------------------------------------ */

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const MAX_FRAME = 1 << 20; // 1 MB is far more than this game ever needs

class Conn {
  constructor(socket) {
    this.socket = socket;
    this.buf = Buffer.alloc(0);
    this.closed = false;
    this.frag = null;
    this.fragOp = 0;
    this.alive = true;
    this.onmessage = null;
    this.onclose = null;

    socket.setNoDelay(true);
    socket.on('data', (d) => {
      this.buf = this.buf.length ? Buffer.concat([this.buf, d]) : d;
      try { this.parse(); } catch (e) { this.destroy(); }
    });
    socket.on('close', () => this.destroy());
    socket.on('error', () => this.destroy());
  }

  parse() {
    for (;;) {
      const b = this.buf;
      if (b.length < 2) return;
      const fin = (b[0] & 0x80) !== 0;
      const opcode = b[0] & 0x0f;
      const masked = (b[1] & 0x80) !== 0;
      let len = b[1] & 0x7f;
      let off = 2;

      if (len === 126) {
        if (b.length < off + 2) return;
        len = b.readUInt16BE(off); off += 2;
      } else if (len === 127) {
        if (b.length < off + 8) return;
        const big = b.readBigUInt64BE(off);
        if (big > BigInt(MAX_FRAME)) { this.destroy(); return; }
        len = Number(big); off += 8;
      }
      if (len > MAX_FRAME) { this.destroy(); return; }

      let mask = null;
      if (masked) {
        if (b.length < off + 4) return;
        mask = b.subarray(off, off + 4); off += 4;
      }
      if (b.length < off + len) return;

      let payload = Buffer.from(b.subarray(off, off + len)); // copy before unmasking
      if (masked) for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
      this.buf = b.subarray(off + len);

      this.frame(fin, opcode, payload);
      if (this.closed) return;
    }
  }

  frame(fin, opcode, payload) {
    if (opcode === 0x8) { this.destroy(); return; }          // close
    if (opcode === 0x9) { this.raw(0xA, payload); return; }  // ping -> pong
    if (opcode === 0xA) { this.alive = true; return; }       // pong

    if (opcode === 0x0) {                                     // continuation
      if (!this.frag) return;
      this.frag = Buffer.concat([this.frag, payload]);
    } else if (opcode === 0x1 || opcode === 0x2) {
      if (!fin) { this.frag = payload; this.fragOp = opcode; return; }
      this.deliver(payload);
      return;
    } else return;

    if (fin) { const f = this.frag; this.frag = null; this.deliver(f); }
  }

  deliver(buf) {
    if (!this.onmessage) return;
    let msg;
    try { msg = JSON.parse(buf.toString('utf8')); } catch (e) { return; }
    if (msg && typeof msg === 'object') this.onmessage(msg);
  }

  raw(opcode, payload) {
    if (this.closed) return;
    const p = payload || Buffer.alloc(0);
    let head;
    if (p.length < 126) { head = Buffer.alloc(2); head[1] = p.length; }
    else if (p.length < 65536) { head = Buffer.alloc(4); head[1] = 126; head.writeUInt16BE(p.length, 2); }
    else { head = Buffer.alloc(10); head[1] = 127; head.writeBigUInt64BE(BigInt(p.length), 2); }
    head[0] = 0x80 | opcode;
    try { this.socket.write(Buffer.concat([head, p])); } catch (e) { this.destroy(); }
  }

  send(obj) { this.raw(0x1, Buffer.from(JSON.stringify(obj), 'utf8')); }

  ping() { this.alive = false; this.raw(0x9, Buffer.alloc(0)); }

  destroy() {
    if (this.closed) return;
    this.closed = true;
    try { this.socket.destroy(); } catch (e) {}
    if (this.onclose) this.onclose();
  }
}

/* ------------------------------------------------------------------ */
/*  Game constants (mirrored in client.html - keep in sync)            */
/* ------------------------------------------------------------------ */

/* ---- side-view platformer world ---------------------------------- */
const W = 2400, H = 1400;      // level size in world units
const R = 22;                  // player radius
const ACCEL = 3400;            // horizontal acceleration on the ground
const AIR_ACCEL = 2100;        // weaker steering while airborne
const MAX_SPEED = 400;
const IT_SPEED_BONUS = 1.10;
const GROUND_DRAG = 9.0;       // horizontal decay while standing
const AIR_DRAG = 1.3;          // much less in the air
const GRAVITY = 2900;
const JUMP_V = 1180;           // ~240px apex, clears the platform gaps
const MAX_FALL = 1700;
const COYOTE = 0.10;           // grace period to still jump after walking off
const JUMP_BUFFER = 0.12;      // pressing jump slightly early still counts
const TICK = 1 / 60;
const SNAP_HZ = 30;            // more frequent updates = less to interpolate over
const FREEZE_AFTER_TAG = 0.8;
const TAG_COOLDOWN = 1.6;
const COUNTDOWN = 3;           // "3 - 2 - 1 - GO" before a round runs
const RESPAWN_FREEZE = 0.5;
const BOUNCE_V = 1750;         // ~530px of air, more than double a normal jump
const PORTAL_R = 46;
const PORTAL_CD = 1.0;         // stops instant ping-ponging between a pair

/* Portals are not part of the map any more. A pair tears open somewhere
   random, works for a few seconds, then collapses - so no escape route is
   ever permanent and nobody can camp one. */
const PORTAL_PAIRS_MAX = 2;
const PORTAL_WARM = 0.9;                   // swirling open, not usable yet
const PORTAL_LIFE_MIN = 9, PORTAL_LIFE_MAX = 16;
const PORTAL_FADE = 1.0;                   // collapsing, not usable any more
const PORTAL_GAP_MIN = 2.5, PORTAL_GAP_MAX = 6.0;
const PORTAL_MIN_SEP = 620;                // a pair has to be a real shortcut
const SPOT_LIFT = 74;                      // how high above a ledge things float

/* Pickups drop in at random too, and are gone the moment somebody runs
   through them. */
const PICKUP_R = 30;
const PICKUP_WARM = 0.35;
const PICKUP_MAX = 3;
const PICKUP_GAP_MIN = 3.5, PICKUP_GAP_MAX = 7.5;
const PICKUP_KINDS = ['boost', 'feather', 'shield', 'swap'];
const PICKUP_BAG = ['boost','boost','boost','feather','feather','shield','shield','swap'];
const BOOST_DUR = 6, FEATHER_DUR = 8, SHIELD_DUR = 4;

/* The last stretch of a round: whoever is IT gets faster, because being
   stuck as IT on the final whistle is what loses it for you. */
const PANIC_SECONDS = 20;
const PANIC_BONUS = 1.24;

/* Platforms are thin ledges you land on from above and can jump up through.
   `solid:true` blocks from every side (the ground, and the level's end walls). */
/* Every platform is solid now - you bonk your head instead of passing through.
   pads   = bounce you high off the top
   portals = step in one, come out its partner */
const PLAT_H = 26;
const LEVELS = [
  {
    plats: [
      { x: 0, y: 1330, w: 2400, h: 70 },        // ground
      { x: 90,   y: 1140, w: 430, h: PLAT_H },
      { x: 660,  y: 1045, w: 380, h: PLAT_H },
      { x: 1180, y: 1140, w: 430, h: PLAT_H },
      { x: 1760, y: 1035, w: 400, h: PLAT_H },
      { x: 190,  y: 890,  w: 320, h: PLAT_H },
      { x: 720,  y: 815,  w: 430, h: PLAT_H },
      { x: 1330, y: 875,  w: 350, h: PLAT_H },
      { x: 1870, y: 775,  w: 350, h: PLAT_H },
      { x: 430,  y: 640,  w: 350, h: PLAT_H },
      { x: 1000, y: 600,  w: 390, h: PLAT_H },
      { x: 1600, y: 560,  w: 330, h: PLAT_H },
      { x: 170,  y: 425,  w: 290, h: PLAT_H },
      { x: 780,  y: 380,  w: 350, h: PLAT_H },
      { x: 1340, y: 335,  w: 310, h: PLAT_H },
      { x: 1950, y: 420,  w: 310, h: PLAT_H },
    ],
    /* pads sit in columns with clear sky above - otherwise, now that
       platforms are solid, a bounce just cracks your head on the ledge */
    pads: [
      { x: 20,   y: 1300, w: 80, h: 30 },
      { x: 2300, y: 1300, w: 80, h: 30 },
    ],
  },
  {
    plats: [
      { x: 0, y: 1330, w: 2400, h: 70 },
      { x: 240,  y: 1150, w: 300, h: PLAT_H },
      { x: 700,  y: 1150, w: 300, h: PLAT_H },
      { x: 1160, y: 1150, w: 300, h: PLAT_H },
      { x: 1620, y: 1150, w: 300, h: PLAT_H },
      { x: 460,  y: 940,  w: 330, h: PLAT_H },
      { x: 960,  y: 940,  w: 330, h: PLAT_H },
      { x: 1460, y: 940,  w: 330, h: PLAT_H },
      { x: 120,  y: 730,  w: 300, h: PLAT_H },
      { x: 720,  y: 700,  w: 360, h: PLAT_H },
      { x: 1280, y: 730,  w: 360, h: PLAT_H },
      { x: 1900, y: 700,  w: 320, h: PLAT_H },
      { x: 380,  y: 490,  w: 340, h: PLAT_H },
      { x: 1060, y: 450,  w: 380, h: PLAT_H },
      { x: 1700, y: 490,  w: 340, h: PLAT_H },
      { x: 900,  y: 240,  w: 500, h: PLAT_H },
    ],
    pads: [
      { x: 20,   y: 1300, w: 80, h: 30 },
      { x: 2300, y: 1300, w: 80, h: 30 },
    ],
  },
  /* The pyramid. A stepped middle with a clear chimney straight up its
     centre - the middle pad fires you through it, and you have to steer
     onto a step on the way down or ride it all the way back to the floor. */
  {
    plats: [
      { x: 0, y: 1330, w: 2400, h: 70 },
      { x: 520,  y: 1170, w: 480, h: PLAT_H },
      { x: 1400, y: 1170, w: 480, h: PLAT_H },
      { x: 700,  y: 1000, w: 420, h: PLAT_H },
      { x: 1280, y: 1000, w: 420, h: PLAT_H },
      { x: 860,  y: 830,  w: 280, h: PLAT_H },
      { x: 1260, y: 830,  w: 280, h: PLAT_H },
      { x: 940,  y: 660,  w: 200, h: PLAT_H },
      { x: 1260, y: 660,  w: 200, h: PLAT_H },
      { x: 1080, y: 470,  w: 240, h: PLAT_H },   // summit, caps the chimney
      { x: 140,  y: 1120, w: 280, h: PLAT_H },
      { x: 120,  y: 880,  w: 300, h: PLAT_H },
      { x: 180,  y: 640,  w: 280, h: PLAT_H },
      { x: 120,  y: 400,  w: 300, h: PLAT_H },
      { x: 1980, y: 1120, w: 280, h: PLAT_H },
      { x: 1980, y: 880,  w: 300, h: PLAT_H },
      { x: 1940, y: 640,  w: 280, h: PLAT_H },
      { x: 1980, y: 400,  w: 300, h: PLAT_H },
      { x: 560,  y: 560,  w: 300, h: PLAT_H },
      { x: 1540, y: 560,  w: 300, h: PLAT_H },
    ],
    pads: [
      { x: 20,   y: 1300, w: 80, h: 30 },
      { x: 2300, y: 1300, w: 80, h: 30 },
      { x: 1160, y: 1300, w: 80, h: 30 },       // the chimney launcher
    ],
  },
];

const SPAWNS = [
  {x:200,y:1240}, {x:800,y:960}, {x:1350,y:1240}, {x:1900,y:950},
  {x:560,y:540},  {x:1150,y:500}, {x:300,y:330},  {x:1450,y:240},
];

const THEMES = ['park', 'beach', 'candy', 'rink', 'lava', 'space'];

/* Cheat codes. dur = how long the effect lasts, cd = cooldown before reuse.
   No cooldowns - a code works again the instant you retype it. You can type
   them mid-round or pick them off the in-game cheat menu; the menu is built
   from CHEAT_DEFS below and sent to clients, so the two cannot drift apart. */
const CHEATS = {
  TURBO:    { dur: 15, cd: 0 },
  NOCLIP:   { dur: 10, cd: 0 },
  MINI:     { dur: 20, cd: 0 },
  BIGFOOT:  { dur: 20, cd: 0 },
  IMMUNE:   { dur: 8,  cd: 0 },
  PARTY:    { dur: 12, cd: 0 },
  BLINK:    { dur: 0,  cd: 0 },
  FREEZE:   { dur: 0,  cd: 0 },
  /* --- platformer additions --- */
  MOONBOOT: { dur: 15, cd: 0 },   // low gravity, floaty huge jumps
  BUBBLE:   { dur: 12, cd: 0 },   // drift down slowly
  ROCKET:   { dur: 0,  cd: 0 },   // instant launch straight up
  BRICK:    { dur: 0,  cd: 0 },   // slam down fast
  FOG:      { dur: 8,  cd: 0 },   // nearly invisible to everyone else
  FLIP:     { dur: 0,  cd: 0 },   // swap places with a random player
  TETHER:   { dur: 0,  cd: 0 },   // IT only: yank the nearest player in
  MIRROR:   { dur: 4,  cd: 0 },   // IT only: reverse everyone else's steering
  MERCY:    { dur: 0,  cd: 0 },   // wipe 5s off your own IT time
  /* --- tied to the roaming portals and pickups --- */
  PORTAL:   { dur: 0,  cd: 0 },   // tear open a fresh pair on demand
  RAIN:     { dur: 0,  cd: 0 },   // dump three pickups onto the map
  SHUFFLE:  { dur: 0,  cd: 0 },   // everyone swaps places at once
};

/* What the cheat menu shows. `it` marks the ones only the chaser may use. */
const CHEAT_DEFS = [
  { code: 'TURBO',    grp: 'Speed',  desc: 'Sprint 45% faster.' },
  { code: 'MINI',     grp: 'Body',   desc: 'Shrink. A much smaller target.' },
  { code: 'BIGFOOT',  grp: 'Body',   desc: 'Grow huge. Long reach, easy to hit.' },
  { code: 'PARTY',    grp: 'Body',   desc: 'Rainbow body. Pure showing off.' },
  { code: 'MOONBOOT', grp: 'Air',    desc: 'Low gravity. Enormous floaty jumps.' },
  { code: 'BUBBLE',   grp: 'Air',    desc: 'Drift down slowly like a balloon.' },
  { code: 'NOCLIP',   grp: 'Air',    desc: 'Fly anywhere. Walls stop mattering.' },
  { code: 'ROCKET',   grp: 'Air',    desc: 'Blast straight up, right now.' },
  { code: 'BRICK',    grp: 'Air',    desc: 'Slam straight down, right now.' },
  { code: 'BLINK',    grp: 'Air',    desc: 'Teleport a short dash ahead.' },
  { code: 'IMMUNE',   grp: 'Escape', desc: 'Cannot be tagged at all.' },
  { code: 'FOG',      grp: 'Escape', desc: 'Go nearly invisible to everyone else.' },
  { code: 'MERCY',    grp: 'Escape', desc: 'Wipe 5 seconds off your own IT time.' },
  { code: 'FLIP',     grp: 'Chaos',  desc: 'Swap places with a random player.' },
  { code: 'SHUFFLE',  grp: 'Chaos',  desc: 'Teleport everyone somewhere new at once.' },
  { code: 'PORTAL',   grp: 'Chaos',  desc: 'Tear open a fresh pair of portals.' },
  { code: 'RAIN',     grp: 'Chaos',  desc: 'Drop three pickups onto the map.' },
  { code: 'FREEZE',   grp: 'Chaser', desc: 'Freeze everyone else where they stand.', it: true },
  { code: 'TETHER',   grp: 'Chaser', desc: 'Yank the nearest runner towards you.', it: true },
  { code: 'MIRROR',   grp: 'Chaser', desc: "Reverse everyone else's steering.", it: true },
];
const CHEAT_INFO = CHEAT_DEFS.map(c => ({ ...c, dur: CHEATS[c.code].dur }));
const CHEAT_IT = new Set(CHEAT_DEFS.filter(c => c.it).map(c => c.code));
const TURBO_MUL = 1.45, MINI_MUL = 0.6, BIG_MUL = 1.6;
const BLINK_DIST = 230, FREEZE_OTHERS = 2.5;
const MOON_G = 0.45;           // gravity multiplier under MOONBOOT
const BUBBLE_FALL = 190;       // terminal velocity while bubbled
const ROCKET_V = 2050;
const TETHER_PULL = 1050;
const MERCY_SECONDS = 5;
const COLORS = ['#ff4d6d','#ffb703','#43e97b','#4cc9f0','#b57bff','#ff7b3d','#f7f7f7','#00d9a3'];

/* Bots. react = seconds between decisions, so a low number is a sharper
   opponent; jumpSkill is how often it takes a jump it should take. */
const BOT_NAMES = ['Blip', 'Nova', 'Pixel', 'Zippy', 'Comet', 'Echo', 'Rusty'];
const BOT_DIFFS = {
  easy:   { react: 0.40, jumpSkill: 0.45, lead: 0.20, panic: 230 },
  normal: { react: 0.20, jumpSkill: 0.72, lead: 0.55, panic: 330 },
  hard:   { react: 0.09, jumpSkill: 0.93, lead: 0.85, panic: 440 },
};
const BOT_STUCK_S = 0.28;      // pressed into a ledge this long = jump out

/* ------------------------------------------------------------------ */
/*  Rooms                                                              */
/* ------------------------------------------------------------------ */

const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const rooms = new Map();
let nextPlayerId = 1;

function makeCode() {
  for (;;) {
    let c = '';
    for (let i = 0; i < 4; i++) c += CODE_ALPHABET[crypto.randomInt(CODE_ALPHABET.length)];
    if (!rooms.has(c)) return c;
  }
}

function cleanName(s) {
  s = String(s == null ? '' : s).replace(/[\u0000-\u001f\u007f]/g, '').trim();
  if (s.length > 14) s = s.slice(0, 14);
  return s || 'Player';
}

function createRoom(theme, duration) {
  const code = makeCode();
  const room = {
    code,
    players: new Map(),
    hostId: null,
    state: 'lobby',           // lobby | playing | ended
    theme: THEMES.includes(theme) ? theme : 'neon',
    duration: [60, 120, 180].includes(duration) ? duration : 120,
    obstacles: [],
    pads: [],
    portals: [],
    pickups: [],
    spots: [],               // where portals and pickups are allowed to appear
    gadgetSeq: 1,
    hueSeq: 0,
    portalWait: 0,
    pickupWait: 0,
    portalDirty: false,      // set when the wire copy needs resending
    pickupDirty: false,
    panic: false,            // final stretch: IT speeds up
    cheatsOn: true,
    level: 0,
    timeLeft: 0,
    itId: null,
    cooldown: 0,
    countdown: 0,
    timer: null,
    lastTick: 0,
    snapAcc: 0,
    events: [],
  };
  rooms.set(code, room);
  return room;
}

function destroyRoom(room) {
  if (room.timer) { clearInterval(room.timer); room.timer = null; }
  rooms.delete(room.code);
}

function playerList(room) {
  return [...room.players.values()].map(p => ({
    id: p.id, name: p.name, color: p.color, host: p.id === room.hostId, itTime: Math.round(p.itTime * 10) / 10,
    bot: !!p.bot, diff: p.botDiff || undefined,
  }));
}

/* bots sit in room.players but have no socket, so they are skipped here */
function broadcast(room, msg) {
  const s = JSON.stringify(msg);
  const buf = Buffer.from(s, 'utf8');
  for (const p of room.players.values()) if (p.conn) p.conn.raw(0x1, buf);
}

function humanCount(room) {
  let n = 0;
  for (const p of room.players.values()) if (!p.bot) n++;
  return n;
}

function sendLobby(room) {
  broadcast(room, {
    t: 'lobby', code: room.code, players: playerList(room),
    theme: room.theme, duration: room.duration, hostId: room.hostId, state: room.state,
    cheats: room.cheatsOn, cheatList: CHEAT_INFO,
  });
}

/* ------------------------------------------------------------------ */
/*  Physics                                                            */
/* ------------------------------------------------------------------ */

/* Solid boxes push out on every axis; thin ledges only catch a falling player
   whose feet were above the ledge top a moment ago. */
/* Solid on every side: land on top, bonk your head underneath, stop at edges. */
function resolvePlatforms(p, plats) {
  p.grounded = false;

  for (const o of plats) {
    const cx = Math.max(o.x, Math.min(p.x, o.x + o.w));
    const cy = Math.max(o.y, Math.min(p.y, o.y + o.h));
    const dx = p.x - cx, dy = p.y - cy;
    const d2 = dx * dx + dy * dy;
    if (d2 >= p.r * p.r) continue;

    if (d2 > 1e-6) {
      const d = Math.sqrt(d2), nx = dx / d, ny = dy / d;
      p.x += nx * (p.r - d);
      p.y += ny * (p.r - d);
      const dot = p.vx * nx + p.vy * ny;
      if (dot < 0) { p.vx -= dot * nx; p.vy -= dot * ny; }
      if (ny < -0.5) { p.grounded = true; p.vy = Math.min(0, p.vy); }
      if (ny > 0.5) p.vy = Math.max(0, p.vy);          // head bonk
    } else {
      const left = p.x - o.x, right = o.x + o.w - p.x;
      const top = p.y - o.y, bottom = o.y + o.h - p.y;
      const m = Math.min(left, right, top, bottom);
      if (m === left)       { p.x = o.x - p.r; p.vx = Math.min(0, p.vx); }
      else if (m === right) { p.x = o.x + o.w + p.r; p.vx = Math.max(0, p.vx); }
      else if (m === top)   { p.y = o.y - p.r; p.vy = Math.min(0, p.vy); p.grounded = true; }
      else                  { p.y = o.y + o.h + p.r; p.vy = Math.max(0, p.vy); }
    }
  }
}

/* jump pads, pickups and portals, checked after the player has been moved */
function resolveGadgets(room, p) {
  for (const b of room.pads) {
    if (p.x + p.r < b.x || p.x - p.r > b.x + b.w) continue;
    const foot = p.y + p.r;
    if (foot >= b.y - 6 && foot <= b.y + b.h + 10 && p.vy >= -50) {
      p.y = b.y - p.r;
      p.vy = -BOUNCE_V;
      p.grounded = false;
      room.events.push({ e: 'bounce', x: b.x + b.w / 2, y: b.y });
    }
  }

  /* pickups vanish for everyone the instant one player runs through them */
  for (let i = room.pickups.length - 1; i >= 0; i--) {
    const k = room.pickups[i];
    if (k.age < PICKUP_WARM) continue;
    if (Math.hypot(p.x - k.x, p.y - k.y) > p.r + PICKUP_R) continue;
    room.pickups.splice(i, 1);
    room.pickupDirty = true;
    applyPickup(room, p, k.kind);
  }

  p.portalCd = Math.max(0, p.portalCd - TICK);
  if (p.portalCd > 0) return;
  for (const g of room.portals) {
    if (g.age < PORTAL_WARM || g.age > g.life) continue;   // still opening, or collapsing
    if (Math.hypot(p.x - g.x, p.y - g.y) > PORTAL_R) continue;
    const partner = room.portals.find(o => o.pair === g.pair && o !== g);
    if (!partner) break;
    room.events.push({ e: 'warp', x: p.x, y: p.y });
    p.x = partner.x; p.y = partner.y;
    p.portalCd = PORTAL_CD;
    room.events.push({ e: 'warp', x: p.x, y: p.y });
    break;
  }
}

function updateEffects(p, dt) {
  for (const k of Object.keys(p.fx)) if (p.fx[k] > 0) p.fx[k] = Math.max(0, p.fx[k] - dt);
  for (const k of Object.keys(p.cd)) if (p.cd[k] > 0) p.cd[k] = Math.max(0, p.cd[k] - dt);
  p.r = R * (p.fx.mini > 0 ? MINI_MUL : p.fx.big > 0 ? BIG_MUL : 1);
}

function stepPlayer(p, dt, isIt, plats, panic) {
  if (p.freeze > 0) {
    p.freeze -= dt;
    p.vx = 0;
    if (p.fx.ghost > 0) p.vy = 0; else p.vy = Math.min(p.vy + GRAVITY * dt, MAX_FALL);
    p.y += p.vy * dt;
  } else {
    let dir = (p.in.r ? 1 : 0) - (p.in.l ? 1 : 0);
    if (p.fx.mirror > 0) dir = -dir;        // someone hit you with MIRROR
    if (dir !== 0) p.face = dir;

    /* NOCLIP turns into free flight - no gravity, steer in any direction */
    if (p.fx.ghost > 0) {
      const uy = (p.in.d ? 1 : 0) - (p.in.j ? 1 : 0);   /* jump key = fly up */
      p.vx += dir * ACCEL * dt;
      p.vy += uy * ACCEL * dt;
      const decay = Math.exp(-4 * dt);
      p.vx *= decay; p.vy *= decay;
    } else {
      const acc = p.grounded ? ACCEL : AIR_ACCEL;
      p.vx += dir * acc * dt;
      if (dir === 0 && p.grounded) p.vx *= Math.exp(-GROUND_DRAG * dt);
      else if (dir === 0) p.vx *= Math.exp(-AIR_DRAG * dt);

      p.coyote = p.grounded ? COYOTE : Math.max(0, p.coyote - dt);
      p.jumpBuf = Math.max(0, p.jumpBuf - dt);
      if (p.jumpBuf > 0 && p.coyote > 0) {
        p.vy = -JUMP_V;
        p.jumpBuf = 0; p.coyote = 0; p.grounded = false;
      }
      /* short hop: releasing jump early cuts the rise */
      if (p.vy < 0 && !p.in.j) p.vy *= Math.exp(-9 * dt);

      const g = GRAVITY * (p.fx.moon > 0 ? MOON_G : 1);
      p.vy = Math.min(p.vy + g * dt, MAX_FALL);
      if (p.fx.bubble > 0 && p.vy > BUBBLE_FALL) p.vy = BUBBLE_FALL;
    }

    const itMul = isIt ? (panic ? PANIC_BONUS : IT_SPEED_BONUS) : 1;
    const cap = MAX_SPEED * itMul * (p.fx.turbo > 0 ? TURBO_MUL : 1);
    if (p.vx > cap) p.vx = cap;
    if (p.vx < -cap) p.vx = -cap;

    p.x += p.vx * dt;
    p.y += p.vy * dt;
  }

  if (p.x < p.r) { p.x = p.r; p.vx = Math.max(0, p.vx); }
  if (p.x > W - p.r) { p.x = W - p.r; p.vx = Math.min(0, p.vx); }
  if (p.y < p.r) { p.y = p.r; p.vy = Math.max(0, p.vy); }

  if (p.fx.ghost > 0) {
    p.grounded = false;
    if (p.y > H - p.r) { p.y = H - p.r; p.vy = 0; }
  } else {
    resolvePlatforms(p, plats);
  }
}

/* ------------------------------------------------------------------ */
/*  Bot brains                                                         */
/* ------------------------------------------------------------------ */

/* Is there anything to land on at x, from footY down to maxDrop below it? */
function footingAt(room, x, footY, maxDrop) {
  for (const o of room.obstacles) {
    if (x < o.x || x > o.x + o.w) continue;
    if (o.y >= footY - 14 && o.y <= footY + maxDrop) return true;
  }
  return false;
}

/* A ledge ahead sitting above our feet but still inside jump range */
function ledgeAhead(room, x, footY) {
  for (const o of room.obstacles) {
    if (x < o.x - 12 || x > o.x + o.w + 12) continue;
    if (o.y < footY - 26 && o.y > footY - 215) return true;
  }
  return false;
}

/* Which way to the nearest ledge we could actually hop onto? Used to climb
   toward someone above us instead of standing under them. 0 = already there. */
function climbDir(room, b) {
  const foot = b.y + b.r;
  let best = null, bestD = Infinity;
  for (const o of room.obstacles) {
    if (o.y >= foot - 26 || o.y < foot - 215) continue;   // not a reachable step up
    const cx = Math.max(o.x, Math.min(b.x, o.x + o.w));
    const dist = Math.abs(cx - b.x);
    if (dist < bestD) { bestD = dist; best = cx; }
  }
  if (best === null || bestD < 30) return 0;
  return best > b.x ? 1 : -1;
}

/* A bot drives the same p.in flags a keyboard would, so it is subject to
   every rule real players are - gravity, freezes, cheats used on it. */
function botThink(room, b, dt) {
  const d = BOT_DIFFS[b.botDiff] || BOT_DIFFS.normal;
  const ai = b.ai;

  /* Wall-bump check runs every tick, not on the decision beat: a bot pressed
     into a ledge barely moves, and a jump is the only way out of that. */
  if (b.grounded && (b.in.l || b.in.r) && Math.abs(b.vx) < 40) ai.stuck += dt;
  else ai.stuck = 0;

  ai.next -= dt;
  if (ai.next > 0 && ai.stuck < BOT_STUCK_S) return;   // still inside its reaction gap
  ai.next = d.react * (0.7 + Math.random() * 0.6);     // jitter so bots desync

  const isIt = room.itId === b.id;
  let target = null, bestD = Infinity;
  for (const o of room.players.values()) {
    if (o.id === b.id) continue;
    const dist = Math.hypot(o.x - b.x, o.y - b.y);
    if (isIt) {
      if (o.fx.immune > 0) continue;        // no point chasing someone untaggable
      if (dist < bestD) { bestD = dist; target = o; }
    } else if (o.id === room.itId) {
      target = o; bestD = dist;
    }
  }

  const inp = { u: false, d: false, l: false, r: false, j: false };
  if (!target) { b.in = inp; return; }

  const dy = target.y - b.y;
  let dx = target.x - b.x;

  let dir = 0;

  if (isIt) {
    dx += target.vx * d.lead * 0.35;        // aim where they are going, not where they are
    dir = Math.abs(dx) > 8 ? (dx > 0 ? 1 : -1) : 0;

    /* They are up a level. Standing underneath them achieves nothing, so climb:
       walk to the nearest ledge within jump range and go up a floor. A jump
       only clears ~240px, so if nothing is reachable fall back to a bounce
       pad (~530px), which is the only way up a big step. */
    if (dy < -120 && b.grounded && Math.random() < d.jumpSkill) {
      let dirUp = climbDir(room, b);
      if (dirUp === 0 && dy < -170 && room.pads.length) {
        let pad = null, pd = Infinity;
        for (const q of room.pads) {
          const gap = Math.abs((q.x + q.w / 2) - b.x);
          if (gap < pd) { pd = gap; pad = q; }
        }
        if (pad && pd > 24) dirUp = (pad.x + pad.w / 2) > b.x ? 1 : -1;
      }
      if (dirUp !== 0) dir = dirUp;
    }

    /* they are above us: jump once we are roughly underneath */
    if (dy < -50 && Math.abs(dx) < 190 && b.grounded && Math.random() < d.jumpSkill) inp.j = true;
  } else {
    dir = dx > 0 ? -1 : 1;

    /* Every bot flees the same chaser, so left to itself the whole pack picks
       the same direction and travels as one lump. If someone is already
       running the way we picked, peel off - unless the chaser is right on us,
       when saving your own skin beats spreading out. */
    let crowded = 0;
    for (const o of room.players.values()) {
      if (o.id === b.id || o.id === room.itId) continue;
      if (Math.abs(o.y - b.y) > 110) continue;
      const gap = o.x - b.x;
      if (Math.abs(gap) < 190 && (gap > 0 ? 1 : -1) === dir) crowded++;
    }
    if (crowded && bestD > d.panic * 0.8) dir = -dir;

    /* a pickup on this floor, in the direction away from the chaser, is
       worth a small detour - it is usually the thing that saves them */
    if (room.pickups.length && Math.random() < d.jumpSkill) {
      for (const k of room.pickups) {
        if (Math.abs(k.y - b.y) > 130) continue;
        const gap = k.x - b.x;
        if (Math.abs(gap) > 420) continue;
        if ((gap > 0 ? 1 : -1) !== dir && bestD < d.panic) continue;   // not back into the chaser
        dir = gap > 0 ? 1 : -1;
        break;
      }
    }

    if (b.x < 260) dir = 1;
    else if (b.x > W - 260) dir = -1;

    /* chaser is close and level with us - hop to break their line */
    if (bestD < d.panic && Math.abs(dy) < 120 && b.grounded && Math.random() < d.jumpSkill * 0.8) inp.j = true;
    /* people do not run in a straight line on the floor; change level sometimes */
    if (b.grounded && Math.random() < ai.hop * 0.16) inp.j = true;
  }

  if (dir !== 0) inp[dir > 0 ? 'r' : 'l'] = true;

  /* Terrain. Without this a bot only ever runs along whatever it spawned on,
     walks off the end, and respawns - which is what made them clump. */
  if (b.grounded && dir !== 0) {
    const foot = b.y + b.r;
    const probe = b.x + dir * (b.r + 52);
    if (!footingAt(room, probe, foot, 120)) {
      /* edge ahead: hop the gap when there is a landing on the far side */
      if (footingAt(room, b.x + dir * 300, foot, 260)) inp.j = true;
      /* otherwise do not walk into the void - unless the target is down there */
      else if (dy < 60) { dir = -dir; inp.l = dir < 0; inp.r = dir > 0; }
    } else if (ledgeAhead(room, probe, foot) && Math.random() < d.jumpSkill) {
      inp.j = true;                       // step up onto it
    }
  }

  if (ai.stuck >= BOT_STUCK_S && b.grounded) { inp.j = true; ai.stuck = 0; }

  /* Jumping is edge-triggered, so a bot that just holds the key jumps once and
     never again. Hold through the rise (a released key cuts the hop short),
     then let go once grounded or falling so the next press registers. */
  if (inp.j) {
    if (!b.in.j) b.jumpBuf = JUMP_BUFFER;
    else if (b.grounded || b.vy >= 0) inp.j = false;
  }
  b.in = inp;
}

/* tell a player something was done TO them, without naming who did it */
function notify(target, code, why) {
  try { target.conn.send({ t: 'cheatres', code, ok: false, why }); } catch (e) {}
}

function applyCheat(room, p, code) {
  switch (code) {
    case 'TURBO':   p.fx.turbo = CHEATS.TURBO.dur; break;
    case 'NOCLIP':  p.fx.ghost = CHEATS.NOCLIP.dur; break;
    case 'MINI':    p.fx.mini = CHEATS.MINI.dur; p.fx.big = 0; break;
    case 'BIGFOOT': p.fx.big = CHEATS.BIGFOOT.dur; p.fx.mini = 0; break;
    case 'IMMUNE':  p.fx.immune = CHEATS.IMMUNE.dur; break;
    case 'PARTY':   p.fx.party = CHEATS.PARTY.dur; break;
    case 'BLINK': {
      /* dash the way you are facing, then settle onto whatever is underneath */
      p.x += p.face * BLINK_DIST;
      p.x = Math.max(p.r, Math.min(W - p.r, p.x));
      p.vy = Math.min(p.vy, 0);
      resolvePlatforms(p, room.obstacles);
      break;
    }
    case 'FREEZE': {
      if (room.itId !== p.id) return false;  // only the chaser may freeze the field
      for (const other of room.players.values()) {
        if (other.id !== p.id) other.freeze = Math.max(other.freeze, FREEZE_OTHERS);
      }
      break;
    }

    case 'MOONBOOT': p.fx.moon = CHEATS.MOONBOOT.dur; break;
    case 'BUBBLE':   p.fx.bubble = CHEATS.BUBBLE.dur; break;
    case 'FOG':      p.fx.fog = CHEATS.FOG.dur; break;

    case 'ROCKET':
      p.vy = -ROCKET_V;
      p.grounded = false;
      room.events.push({ e: 'bounce', x: p.x, y: p.y + p.r });
      break;

    case 'BRICK':
      p.vy = MAX_FALL;
      break;

    case 'FLIP': {
      const others = [...room.players.values()].filter(o => o.id !== p.id);
      if (!others.length) return false;
      const o = others[crypto.randomInt(others.length)];
      room.events.push({ e: 'warp', x: p.x, y: p.y });
      room.events.push({ e: 'warp', x: o.x, y: o.y });
      const tx = p.x, ty = p.y;
      p.x = o.x; p.y = o.y;
      o.x = tx;  o.y = ty;
      p.vy = 0; o.vy = 0;
      notify(o, 'FLIP', 'someone swapped places with you');
      break;
    }

    case 'TETHER': {
      if (room.itId !== p.id) return false;
      let best = null, bestD = Infinity;
      for (const o of room.players.values()) {
        if (o.id === p.id) continue;
        const d = Math.hypot(o.x - p.x, o.y - p.y);
        if (d < bestD) { bestD = d; best = o; }
      }
      if (!best) return false;
      const dx = p.x - best.x, dy = p.y - best.y;
      const m = Math.hypot(dx, dy) || 1;
      best.vx = (dx / m) * TETHER_PULL;
      best.vy = (dy / m) * TETHER_PULL * 0.6;
      room.events.push({ e: 'warp', x: best.x, y: best.y });
      notify(best, 'TETHER', 'the chaser yanked you in');
      break;
    }

    case 'MIRROR': {
      if (room.itId !== p.id) return false;
      for (const o of room.players.values()) {
        if (o.id === p.id) continue;
        o.fx.mirror = CHEATS.MIRROR.dur;
        notify(o, 'MIRROR', 'your steering is reversed');
      }
      break;
    }

    case 'MERCY':
      p.itTime = Math.max(0, p.itTime - MERCY_SECONDS);
      break;

    case 'PORTAL':
      if (!spawnPortalPair(room)) return false;
      break;

    case 'RAIN': {
      let n = 0;
      for (let i = 0; i < 3; i++) if (spawnPickup(room)) n++;
      if (!n) return false;
      break;
    }

    case 'SHUFFLE': {
      const all = [...room.players.values()];
      if (all.length < 2) return false;
      const pos = all.map(o => ({ x: o.x, y: o.y }));
      for (let i = pos.length - 1; i > 0; i--) {
        const j = crypto.randomInt(i + 1);
        [pos[i], pos[j]] = [pos[j], pos[i]];
      }
      all.forEach((o, i) => {
        room.events.push({ e: 'warp', x: o.x, y: o.y });
        o.x = pos[i].x; o.y = pos[i].y; o.vy = 0; o.portalCd = PORTAL_CD;
        room.events.push({ e: 'warp', x: o.x, y: o.y });
        if (o.id !== p.id) notify(o, 'SHUFFLE', 'the whole arena got shuffled');
      });
      break;
    }
  }
  return true;
}

/* One way in for both the typed codes and the cheat menu, so the two can
   never behave differently. Returns what to tell the player. */
function tryCheat(room, player, code) {
  if (!CHEATS[code]) return { ok: false, why: 'no such code' };
  if (!room.cheatsOn) return { ok: false, why: 'cheats are switched off in this room' };
  if ((player.cd[code] || 0) > 0) return { ok: false, why: 'recharging (' + Math.ceil(player.cd[code]) + 's)' };
  if (applyCheat(room, player, code) === false) {
    return { ok: false, why: CHEAT_IT.has(code) ? 'only works while you are IT' : 'nothing for it to work on' };
  }
  player.cd[code] = CHEATS[code].cd;
  broadcast(room, { t: 'cheatfx', id: player.id, code });
  return { ok: true, dur: CHEATS[code].dur };
}

/* ------------------------------------------------------------------ */
/*  Roaming portals and pickups                                        */
/* ------------------------------------------------------------------ */

/* Nothing is bolted to the map. A portal pair tears open somewhere random,
   works for a few seconds and collapses; pickups drop in the same way. Both
   only ever appear in clear air just above a ledge, which is where players
   actually run - a portal in the middle of the sky would never be used. */
function boxedIn(x, y, plats, r) {
  for (const o of plats) {
    const cx = Math.max(o.x, Math.min(x, o.x + o.w));
    const cy = Math.max(o.y, Math.min(y, o.y + o.h));
    if (Math.hypot(x - cx, y - cy) < r) return true;
  }
  return false;
}

function buildSpots(plats) {
  const spots = [];
  for (const o of plats) {
    if (o.w < 140) continue;
    for (let x = o.x + 70; x <= o.x + o.w - 70; x += 150) {
      const y = o.y - SPOT_LIFT;
      if (y < 110 || x < 70 || x > W - 70) continue;
      if (boxedIn(x, y, plats, PORTAL_R + 6)) continue;
      spots.push({ x, y });
    }
  }
  return spots;
}

/* n free spots, clear of whatever is already out and far enough apart */
function pickSpots(room, n, minApart) {
  const taken = room.portals.concat(room.pickups);
  const pool = room.spots.filter(s => !taken.some(g => Math.hypot(g.x - s.x, g.y - s.y) < 170));
  if (pool.length < n) return null;
  const out = [];
  for (let guard = 80; guard > 0 && out.length < n; guard--) {
    const s = pool[crypto.randomInt(pool.length)];
    if (out.includes(s)) continue;
    if (out.some(o => Math.hypot(o.x - s.x, o.y - s.y) < minApart)) continue;
    out.push(s);
  }
  return out.length === n ? out : null;
}

function spawnPortalPair(room) {
  const spot = pickSpots(room, 2, PORTAL_MIN_SEP);
  if (!spot) return false;
  const pair = room.gadgetSeq++;
  const life = PORTAL_LIFE_MIN + Math.random() * (PORTAL_LIFE_MAX - PORTAL_LIFE_MIN);
  const hue = room.hueSeq++ % 4;
  for (let i = 0; i < 2; i++) {
    room.portals.push({ id: room.gadgetSeq++, pair, hue, x: spot[i].x, y: spot[i].y, age: 0, life });
    room.events.push({ e: 'open', x: spot[i].x, y: spot[i].y, hue });
  }
  room.portalDirty = true;
  return true;
}

function spawnPickup(room) {
  const spot = pickSpots(room, 1, 0);
  if (!spot) return false;
  const kind = PICKUP_BAG[crypto.randomInt(PICKUP_BAG.length)];
  room.pickups.push({ id: room.gadgetSeq++, kind, x: spot[0].x, y: spot[0].y, age: 0 });
  room.pickupDirty = true;
  return true;
}

function applyPickup(room, p, kind) {
  let got = kind;
  if (kind === 'shield' && room.itId === p.id) got = 'boost';   // the chaser cannot be untaggable
  switch (got) {
    case 'boost':   p.fx.turbo  = Math.max(p.fx.turbo  || 0, BOOST_DUR); break;
    case 'feather': p.fx.moon   = Math.max(p.fx.moon   || 0, FEATHER_DUR); break;
    case 'shield':  p.fx.immune = Math.max(p.fx.immune || 0, SHIELD_DUR); break;
    case 'swap': {
      const others = [...room.players.values()].filter(o => o.id !== p.id);
      if (!others.length) { p.fx.turbo = Math.max(p.fx.turbo || 0, BOOST_DUR); got = 'boost'; break; }
      const o = others[crypto.randomInt(others.length)];
      room.events.push({ e: 'warp', x: p.x, y: p.y });
      room.events.push({ e: 'warp', x: o.x, y: o.y });
      const tx = p.x, ty = p.y;
      p.x = o.x; p.y = o.y; o.x = tx; o.y = ty;
      p.vy = 0; o.vy = 0;
      p.portalCd = PORTAL_CD; o.portalCd = PORTAL_CD;
      notify(o, 'SWAP', 'somebody swapped places with you');
      break;
    }
  }
  room.events.push({ e: 'grab', id: p.id, kind: got, x: p.x, y: p.y });
}

function updateGadgets(room, dt) {
  for (let i = room.portals.length - 1; i >= 0; i--) {
    const g = room.portals[i];
    g.age += dt;
    if (g.age >= g.life + PORTAL_FADE) { room.portals.splice(i, 1); room.portalDirty = true; }
  }
  room.portalWait -= dt;
  /* an arena with nothing to dive into is the dull version of this game,
     so a wiped-out map re-opens quickly instead of waiting out the gap */
  if (!room.portals.length && room.portalWait > 1.2) room.portalWait = 1.2;
  if (room.portalWait <= 0) {
    if (room.portals.length < PORTAL_PAIRS_MAX * 2) spawnPortalPair(room);
    room.portalWait = PORTAL_GAP_MIN + Math.random() * (PORTAL_GAP_MAX - PORTAL_GAP_MIN);
  }

  for (const k of room.pickups) k.age += dt;
  room.pickupWait -= dt;
  if (room.pickupWait <= 0) {
    if (room.pickups.length < PICKUP_MAX) spawnPickup(room);
    room.pickupWait = PICKUP_GAP_MIN + Math.random() * (PICKUP_GAP_MAX - PICKUP_GAP_MIN);
  }
}

/* compact wire forms - only resent when the set actually changes */
function portalWire(room) {
  return room.portals.map(g => [g.id, Math.round(g.x), Math.round(g.y), g.pair, g.hue,
                                Math.round(g.age * 10) / 10, Math.round(g.life * 10) / 10]);
}
function pickupWire(room) {
  return room.pickups.map(k => [k.id, Math.round(k.x), Math.round(k.y),
                                PICKUP_KINDS.indexOf(k.kind), Math.round(k.age * 10) / 10]);
}

/* ------------------------------------------------------------------ */
/*  Round flow                                                         */
/* ------------------------------------------------------------------ */

function startRound(room) {
  const players = [...room.players.values()];
  if (players.length < 2) return;

  room.level = crypto.randomInt(LEVELS.length);
  const level = LEVELS[room.level];
  room.obstacles = level.plats;
  room.pads = level.pads;
  room.spots = buildSpots(level.plats);
  room.portals = [];
  room.pickups = [];
  room.portalDirty = true;
  room.pickupDirty = true;
  room.gadgetSeq = 1;
  room.hueSeq = crypto.randomInt(4);
  room.portalWait = PORTAL_GAP_MIN;
  room.pickupWait = 2.5;
  room.panic = false;
  room.state = 'playing';
  room.timeLeft = room.duration;
  room.cooldown = TAG_COOLDOWN;
  room.countdown = COUNTDOWN;    // nobody moves until this hits zero
  room.events = [];

  const spots = SPAWNS.slice();
  for (let i = spots.length - 1; i > 0; i--) {
    const j = crypto.randomInt(i + 1);
    [spots[i], spots[j]] = [spots[j], spots[i]];
  }
  players.forEach((p, i) => {
    const s = spots[i % spots.length];
    p.x = s.x; p.y = s.y; p.vx = 0; p.vy = 0;
    p.itTime = 0; p.freeze = 0; p.r = R;
    p.fx = { turbo: 0, ghost: 0, immune: 0, mini: 0, big: 0, party: 0,
             moon: 0, bubble: 0, fog: 0, mirror: 0 };
    p.portalCd = 0;
    p.cd = {}; p.kbuf = '';
    p.in = { u: false, d: false, l: false, r: false, j: false };
    if (p.bot) p.ai = { next: 0, stuck: 0, hop: p.ai ? p.ai.hop : 0.9 };
  });

  room.itId = players[crypto.randomInt(players.length)].id;
  const it = room.players.get(room.itId);
  if (it) it.freeze = 1.2;

  /* one pair is already open when the whistle goes, so the first chase
     has somewhere to go */
  spawnPortalPair(room);

  broadcast(room, {
    t: 'start', arena: { w: W, h: H }, obstacles: room.obstacles,
    pads: room.pads, portals: portalWire(room), pickups: pickupWire(room),
    theme: room.theme, duration: room.duration, level: room.level,
    cheats: room.cheatsOn, cheatList: CHEAT_INFO,
    players: playerList(room), it: room.itId,
  });
  room.portalDirty = false;
  room.pickupDirty = false;

  room.lastTick = Date.now();
  room.snapAcc = 0;
  if (room.timer) clearInterval(room.timer);
  room.timer = setInterval(() => tick(room), 1000 / 60);
}

function endRound(room) {
  if (room.timer) { clearInterval(room.timer); room.timer = null; }
  room.state = 'ended';
  /* the real rule: whoever is still IT on the final whistle loses the round */
  const loser = room.itId;
  const results = [...room.players.values()]
    .map(p => ({ id: p.id, name: p.name, color: p.color, itTime: Math.round(p.itTime * 10) / 10, wasIt: p.id === loser }))
    .sort((a, b) => a.itTime - b.itTime);
  broadcast(room, { t: 'end', results, loser, hostId: room.hostId });
}

function tick(room) {
  const now = Date.now();
  let dt = (now - room.lastTick) / 1000;
  room.lastTick = now;
  if (dt > 0.25) dt = 0.25;             // after a stall, don't fast-forward

  /* freeze the field during "3 - 2 - 1 - GO", then start the clock */
  if (room.countdown > 0) {
    room.countdown -= dt;
    if (room.countdown > 0) {
      room.snapAcc += dt;
      if (room.snapAcc >= 1 / SNAP_HZ) { room.snapAcc = 0; sendSnapshot(room); }
      return;
    }
    room.countdown = 0;
  }

  updateGadgets(room, dt);

  let remaining = dt;
  while (remaining > 0) {
    const step = Math.min(TICK, remaining);
    remaining -= step;

    for (const p of room.players.values()) {
      if (p.bot) botThink(room, p, step);
      updateEffects(p, step);
      stepPlayer(p, step, p.id === room.itId, room.obstacles, room.panic);
      if (p.freeze <= 0 && p.fx.ghost <= 0) resolveGadgets(room, p);

      /* fell off the bottom of the level - drop them back in */
      if (p.y - p.r > H + 120) {
        const s = SPAWNS[crypto.randomInt(SPAWNS.length)];
        p.x = s.x; p.y = s.y; p.vx = 0; p.vy = 0;
        p.freeze = Math.max(p.freeze, RESPAWN_FREEZE);
        room.events.push({ e: 'respawn', id: p.id, x: p.x, y: p.y });
      }
    }

    const it = room.players.get(room.itId);
    if (it) {
      it.itTime += step;
      room.cooldown -= step;
      if (room.cooldown <= 0 && it.freeze <= 0) {
        for (const p of room.players.values()) {
          if (p.id === room.itId) continue;
          if (p.fx.immune > 0) continue;
          if (Math.hypot(p.x - it.x, p.y - it.y) < it.r + p.r) {
            room.itId = p.id;
            p.freeze = FREEZE_AFTER_TAG;
            room.cooldown = TAG_COOLDOWN;
            room.events.push({ e: 'tag', by: it.id, to: p.id, x: p.x, y: p.y });
            break;
          }
        }
      }
    }

    room.timeLeft -= step;
  }

  /* final stretch - the chaser speeds up, because being IT on the whistle
     is what loses it for you */
  if (!room.panic && room.timeLeft <= PANIC_SECONDS && room.timeLeft > 0) {
    room.panic = true;
    room.events.push({ e: 'panic' });
  }

  if (room.timeLeft <= 0) { endRound(room); return; }

  room.snapAcc += dt;
  if (room.snapAcc >= 1 / SNAP_HZ) {
    room.snapAcc = 0;
    sendSnapshot(room);
  }
}

function sendSnapshot(room) {
  const p = [];
  for (const pl of room.players.values()) {
    const flags = (pl.fx.ghost > 0 ? 1 : 0) | (pl.fx.immune > 0 ? 2 : 0) |
                  (pl.fx.turbo > 0 ? 4 : 0) | (pl.fx.party > 0 ? 8 : 0) |
                  (pl.grounded ? 16 : 0) | (pl.face < 0 ? 32 : 0) |
                  (pl.fx.fog > 0 ? 64 : 0) | (pl.fx.moon > 0 ? 128 : 0) |
                  (pl.fx.bubble > 0 ? 256 : 0) | (pl.fx.mirror > 0 ? 512 : 0);
    p.push([pl.id, Math.round(pl.x * 10) / 10, Math.round(pl.y * 10) / 10,
            pl.freeze > 0 ? 1 : 0, Math.round(pl.itTime * 10) / 10,
            Math.round(pl.r), flags, Math.round(pl.vy)]);
  }
  const msg = {
    t: 's',
    tl: Math.max(0, Math.round(room.timeLeft * 10) / 10),
    it: room.itId,
    cd: room.cooldown > 0 ? 1 : 0,
    cnt: room.countdown > 0 ? Math.ceil(room.countdown) : 0,
    pn: room.panic ? 1 : 0,
    p,
  };
  /* gadget lists ride along only when they change - the client ages them
     on its own clock in between */
  if (room.portalDirty) { msg.pt = portalWire(room); room.portalDirty = false; }
  if (room.pickupDirty) { msg.pk = pickupWire(room); room.pickupDirty = false; }
  if (room.events.length) { msg.ev = room.events; room.events = []; }
  broadcast(room, msg);
}

/* ------------------------------------------------------------------ */
/*  Connection handling                                                */
/* ------------------------------------------------------------------ */

function attach(conn) {
  let player = null;
  let room = null;

  const fail = (msg) => conn.send({ t: 'err', msg });

  conn.onmessage = (m) => {
    switch (m.t) {
      case 'create': {
        if (player) return;
        room = createRoom(m.theme, Number(m.duration));
        player = addPlayer(room, conn, m);
        room.hostId = player.id;
        conn.send({ t: 'you', id: player.id, code: room.code });
        sendLobby(room);
        break;
      }
      case 'join': {
        if (player) return;
        const code = String(m.code || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
        const r = rooms.get(code);
        if (!r) return fail('No room with that code.');
        if (r.players.size >= 8) return fail('That room is full (8 players max).');
        room = r;
        player = addPlayer(room, conn, m);
        conn.send({ t: 'you', id: player.id, code: room.code });
        if (room.state === 'playing') {
          // drop late joiners straight into the running round
          conn.send({
            t: 'start', arena: { w: W, h: H }, obstacles: room.obstacles,
            pads: room.pads, portals: portalWire(room), pickups: pickupWire(room),
            theme: room.theme, duration: room.duration, level: room.level,
            cheats: room.cheatsOn, cheatList: CHEAT_INFO,
            players: playerList(room), it: room.itId,
          });
        }
        sendLobby(room);
        break;
      }
      case 'input': {
        if (!player || !room || room.state !== 'playing') return;
        const wasJump = player.in.j;
        player.in = { u: !!m.u, d: !!m.d, l: !!m.l, r: !!m.r, j: !!m.j };
        /* buffer the press so an early tap still fires on landing */
        if (player.in.j && !wasJump) player.jumpBuf = JUMP_BUFFER;
        break;
      }
      case 'theme': {
        if (!player || !room || player.id !== room.hostId) return;
        if (THEMES.includes(m.theme)) room.theme = m.theme;
        if ([60, 120, 180].includes(Number(m.duration))) room.duration = Number(m.duration);
        if (typeof m.cheats === 'boolean') room.cheatsOn = m.cheats;
        sendLobby(room);
        break;
      }
      case 'addbot': {
        if (!player || !room || player.id !== room.hostId) return;
        if (room.state === 'playing') return fail('Add bots from the lobby.');
        if (room.players.size >= 8) return fail('That room is full (8 players max).');
        addBot(room, m.diff);
        sendLobby(room);
        break;
      }
      case 'removebot': {
        if (!player || !room || player.id !== room.hostId) return;
        if (room.state === 'playing') return;
        const bots = [...room.players.values()].filter(p => p.bot);
        if (!bots.length) return;
        room.players.delete(bots[bots.length - 1].id);
        sendLobby(room);
        break;
      }
      case 'botdiff': {
        if (!player || !room || player.id !== room.hostId) return;
        if (!BOT_DIFFS[m.diff]) return;
        for (const p of room.players.values()) if (p.bot) p.botDiff = m.diff;
        sendLobby(room);
        break;
      }
      case 'start': {
        if (!player || !room || player.id !== room.hostId) return;
        if (room.state === 'playing') return;
        if (room.players.size < 2) return fail('Need at least 2 players to start. Add a bot to play solo.');
        startRound(room);
        break;
      }
      case 'again': {
        if (!player || !room || player.id !== room.hostId) return;
        room.state = 'lobby';
        sendLobby(room);
        break;
      }
      /* Typed letters stream up one at a time and are matched HERE, so a
         code still counts even if you never open the menu. */
      case 'k': {
        if (!player || !room || room.state !== 'playing') return;
        const ch = String(m.c || '').toUpperCase();
        if (!/^[A-Z]$/.test(ch)) return;
        player.kbuf = (player.kbuf + ch).slice(-12);

        for (const code of Object.keys(CHEATS)) {
          if (!player.kbuf.endsWith(code)) continue;
          player.kbuf = '';
          const res = tryCheat(room, player, code);
          conn.send({ t: 'cheatres', code, ok: res.ok, why: res.why, dur: res.dur });
          return;
        }
        break;
      }
      /* the same codes, picked off the in-game cheat menu instead of typed */
      case 'cheat': {
        if (!player || !room || room.state !== 'playing') return;
        const code = String(m.code || '').toUpperCase();
        if (!CHEATS[code]) return;
        player.kbuf = '';
        const res = tryCheat(room, player, code);
        conn.send({ t: 'cheatres', code, ok: res.ok, why: res.why, dur: res.dur });
        break;
      }

      case 'ping': conn.send({ t: 'pong', ts: m.ts }); break;
    }
  };

  conn.onclose = () => {
    if (!room || !player) return;
    room.players.delete(player.id);
    /* bots cannot hold a room open on their own - the last human out kills it */
    if (humanCount(room) === 0) { destroyRoom(room); return; }

    if (room.hostId === player.id) {
      room.hostId = [...room.players.values()].find(p => !p.bot).id;
    }

    if (room.state === 'playing') {
      if (room.players.size < 2) { endRound(room); return; }
      if (room.itId === player.id) {
        // hand IT to a random survivor rather than letting the round stall
        const ids = [...room.players.keys()];
        room.itId = ids[crypto.randomInt(ids.length)];
        const it = room.players.get(room.itId);
        if (it) it.freeze = FREEZE_AFTER_TAG;
        room.cooldown = TAG_COOLDOWN;
      }
    }
    sendLobby(room);
  };
}

function newPlayer(id, conn, name, color) {
  return {
    id, conn, name, color,
    x: 0, y: 0, vx: 0, vy: 0, freeze: 0, itTime: 0, r: R,
    grounded: false, coyote: 0, jumpBuf: 0, face: 1, portalCd: 0,
    fx: { turbo: 0, ghost: 0, immune: 0, mini: 0, big: 0, party: 0,
          moon: 0, bubble: 0, fog: 0, mirror: 0 },
    cd: {}, kbuf: '',
    in: { u: false, d: false, l: false, r: false, j: false },
  };
}

function addPlayer(room, conn, m) {
  const color = COLORS.includes(m.color) ? m.color : COLORS[crypto.randomInt(COLORS.length)];
  const p = newPlayer(nextPlayerId++, conn, cleanName(m.name), color);
  room.players.set(p.id, p);
  return p;
}

function addBot(room, diff) {
  const taken = new Set([...room.players.values()].map(p => p.color));
  const color = COLORS.find(c => !taken.has(c)) || COLORS[crypto.randomInt(COLORS.length)];
  const names = new Set([...room.players.values()].map(p => p.name));
  const id = nextPlayerId++;
  const name = BOT_NAMES.find(n => !names.has(n)) || ('Bot ' + id);

  const p = newPlayer(id, null, name, color);
  p.bot = true;
  p.botDiff = BOT_DIFFS[diff] ? diff : 'normal';
  /* hop is how twitchy this one is - identical bots move as one blob */
  p.ai = { next: 0, stuck: 0, hop: 0.45 + Math.random() * 0.95 };
  room.players.set(id, p);
  return p;
}

/* ------------------------------------------------------------------ */
/*  HTTP                                                               */
/* ------------------------------------------------------------------ */

function lanIp() {
  const ifaces = os.networkInterfaces();
  const found = [];
  for (const name of Object.keys(ifaces)) {
    for (const i of ifaces[name] || []) {
      if (i.family !== 'IPv4' || i.internal) continue;
      if (i.address.startsWith('169.254.')) continue;
      found.push(i.address);
    }
  }
  found.sort((a, b) => (b.startsWith('192.168.') ? 1 : 0) - (a.startsWith('192.168.') ? 1 : 0));
  return found[0] || '127.0.0.1';
}

function clientHtml(serverUrl) {
  const src = fs.readFileSync(path.join(ROOT, 'client.html'), 'utf8');
  return src.replace('__SERVER_URL__', serverUrl);
}

const server = http.createServer((req, res) => {
  const url = (req.url || '/').split('?')[0];
  try {
    if (url === '/' || url === '/index.html' || url === '/play') {
      const body = clientHtml('');   // served from the server: auto-detect address
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(body);
      return;
    }
    if (url === '/tag.html' || url === '/share') {
      const body = clientHtml(shareTarget());
      res.writeHead(200, {
        'Content-Type': 'text/html; charset=utf-8',
        'Content-Disposition': 'attachment; filename="tag.html"',
      });
      res.end(body);
      return;
    }
    if (url === '/health') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true, rooms: rooms.size }));
      return;
    }
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not found');
  } catch (e) {
    res.writeHead(500, { 'Content-Type': 'text/plain' });
    res.end('Server error: ' + e.message);
  }
});

server.on('upgrade', (req, socket) => {
  const key = req.headers['sec-websocket-key'];
  if (!key) { socket.destroy(); return; }
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
    'Upgrade: websocket\r\n' +
    'Connection: Upgrade\r\n' +
    'Sec-WebSocket-Accept: ' + crypto.createHash('sha1').update(key + WS_GUID).digest('base64') + '\r\n\r\n'
  );
  attach(new Conn(socket));
});

function shareTarget() {
  if (process.env.PUBLIC_URL) {
    let u = process.env.PUBLIC_URL.trim();
    if (/^wss?:\/\//.test(u)) return u;
    if (u.startsWith('https://')) return 'wss://' + u.slice(8).replace(/\/$/, '');
    if (u.startsWith('http://')) return 'ws://' + u.slice(7).replace(/\/$/, '');
    return 'ws://' + u.replace(/\/$/, '');
  }
  return 'ws://' + lanIp() + ':' + PORT;
}

server.listen(PORT, '0.0.0.0', () => {
  const ip = lanIp();
  const shareDir = path.join(ROOT, 'share');
  let sharePath = '(could not write share file)';
  try {
    fs.mkdirSync(shareDir, { recursive: true });
    sharePath = path.join(shareDir, 'tag.html');
    fs.writeFileSync(sharePath, clientHtml(shareTarget()), 'utf8');
  } catch (e) { sharePath = 'error: ' + e.message; }

  const line = '='.repeat(58);
  console.log('\n' + line);
  console.log('  TAG ARENA is running');
  console.log(line);
  console.log('  You (this PC) :  http://localhost:' + PORT);
  console.log('  Same Wi-Fi    :  http://' + ip + ':' + PORT);
  console.log('  Send-a-file   :  ' + sharePath);
  console.log('                   (that one file is all a friend needs)');
  console.log(line);
  console.log('  Press Ctrl+C to stop the server.\n');
});

server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') {
    console.error('\n  Port ' + PORT + ' is already in use.');
    console.error('  Start it on another port, e.g.:  set PORT=3001 && node server.js\n');
  } else {
    console.error('Server error:', e);
  }
  process.exit(1);
});
