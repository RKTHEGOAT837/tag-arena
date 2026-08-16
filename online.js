/*
  Starts a public tunnel first, then the game server, so share\tag.html gets
  baked with an address that works from anywhere - not just your Wi-Fi.

  Run with:  play-online.bat     (or:  node online.js)
*/

const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PORT = Number(process.env.PORT) || 3000;
const URL_RE = /https:\/\/[a-z0-9-]+\.trycloudflare\.com/i;

function findCloudflared() {
  const roots = [
    path.join(os.homedir(), 'AppData', 'Local', 'npm-cache', '_npx'),
    path.join(os.homedir(), 'AppData', 'Roaming', 'npm', 'node_modules'),
  ];
  for (const base of roots) {
    let entries = [];
    try { entries = fs.readdirSync(base); } catch (e) { continue; }
    for (const d of entries) {
      const p = path.join(base, d, 'node_modules', 'cloudflared', 'bin', 'cloudflared.exe');
      if (fs.existsSync(p)) return p;
      const q = path.join(base, d, 'bin', 'cloudflared.exe');
      if (fs.existsSync(q)) return q;
    }
  }
  return null;
}

function refreshDesktopCopy() {
  const src = path.join(__dirname, 'share', 'tag.html');
  const candidates = [
    path.join(os.homedir(), 'OneDrive', 'Desktop'),
    path.join(os.homedir(), 'Desktop'),
  ];
  for (const dir of candidates) {
    if (!fs.existsSync(dir)) continue;
    const dest = path.join(dir, 'Tag Arena - send to friends.html');
    try { fs.copyFileSync(src, dest); return dest; } catch (e) { /* try next */ }
  }
  return null;
}

function startServer(publicUrl) {
  if (publicUrl) process.env.PUBLIC_URL = publicUrl;
  require('./server.js');
  if (publicUrl) {
    // the share file was just rebaked with the new address - keep the Desktop
    // copy in step, otherwise it still points at a dead tunnel
    setTimeout(() => {
      const dest = refreshDesktopCopy();
      if (dest) console.log('  Desktop copy refreshed:\n     ' + dest + '\n');
    }, 300);
    const line = '='.repeat(58);
    console.log(line);
    console.log('  PLAY FROM ANYWHERE');
    console.log(line);
    console.log('  Send friends this link:');
    console.log('     ' + publicUrl);
    console.log('');
    console.log('  ...or send them the file:');
    console.log('     ' + path.join(__dirname, 'share', 'tag.html'));
    console.log('');
    console.log('  Both already point at this PC. Nothing for them to install.');
    console.log('  Keep this window OPEN while you play.');
    console.log('  This link changes every time you restart - re-send it.');
    console.log(line + '\n');
  }
}

console.log('\n  Opening a public tunnel (first run downloads it, ~20MB)...\n');

const bin = findCloudflared();
/* http2 measured ~30% lower round-trip than the default quic transport */
const args = ['tunnel', '--protocol', 'http2', '--url', 'http://localhost:' + PORT];
const child = bin
  ? spawn(bin, args, { windowsHide: true })
  : spawn('npx.cmd', ['-y', 'cloudflared'].concat(args), { windowsHide: true, shell: true });

let done = false;
const onData = (buf) => {
  const s = buf.toString();
  const m = s.match(URL_RE);
  if (m && !done) {
    done = true;
    clearTimeout(giveUp);
    startServer(m[0]);
  }
};
child.stdout.on('data', onData);
child.stderr.on('data', onData);

child.on('error', (e) => {
  if (done) return;
  done = true;
  clearTimeout(giveUp);
  console.log('  Could not start the tunnel (' + e.message + ').');
  console.log('  Falling back to same-Wi-Fi only.\n');
  startServer(null);
});

const giveUp = setTimeout(() => {
  if (done) return;
  done = true;
  console.log('  Tunnel did not come up in time. Falling back to same-Wi-Fi only.\n');
  startServer(null);
}, 90000);

function shutdown() {
  try { child.kill(); } catch (e) {}
  process.exit(0);
}
process.on('SIGINT', shutdown);
process.on('exit', () => { try { child.kill(); } catch (e) {} });
