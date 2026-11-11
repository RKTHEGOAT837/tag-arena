// Compiles and links every GLSL ES 3.00 program used by the Android stage (app/.../stage/Gl.kt)
// with a real GLES3-class compiler (WebGL2 in headless Chromium). Usage: node tools/check-shaders.mjs
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const { chromium } = require("playwright");

const src = readFileSync(new URL("../app/src/main/kotlin/com/aidj/app/stage/Gl.kt", import.meta.url), "utf8");
const shaders = {};
for (const m of src.matchAll(/const val (\w+) = """([\s\S]*?)"""/g)) shaders[m[1]] = m[2];
const programs = [["LIT_VS", "LIT_FS"], ["BEAM_VS", "BEAM_FS"], ["LIT_VS", "WALL_FS"], ["LIT_VS", "FLOOR_FS"], ["PARTICLE_VS", "PARTICLE_FS"]];

const browser = await chromium.launch({ args: ["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader"] });
const page = await browser.newPage();
const results = await page.evaluate(({ shaders, programs }) => {
  const gl = document.createElement("canvas").getContext("webgl2");
  if (!gl) return [{ name: "webgl2", ok: false, log: "WebGL2 unavailable" }];
  const out = [];
  for (const [vsName, fsName] of programs) {
    const compile = (type, s) => { const sh = gl.createShader(type); gl.shaderSource(sh, s.trim()); gl.compileShader(sh); return [sh, gl.getShaderParameter(sh, gl.COMPILE_STATUS), gl.getShaderInfoLog(sh)]; };
    const [vs, vok, vlog] = compile(gl.VERTEX_SHADER, shaders[vsName]);
    const [fs, fok, flog] = compile(gl.FRAGMENT_SHADER, shaders[fsName]);
    let lok = false, llog = "";
    if (vok && fok) {
      const p = gl.createProgram(); gl.attachShader(p, vs); gl.attachShader(p, fs);
      gl.bindAttribLocation(p, 0, "aPos"); gl.bindAttribLocation(p, 1, "aNormal");
      gl.linkProgram(p); lok = gl.getProgramParameter(p, gl.LINK_STATUS); llog = gl.getProgramInfoLog(p);
    }
    out.push({ name: `${vsName}+${fsName}`, ok: vok && fok && lok, log: [vlog, flog, llog].filter(Boolean).join(" | ") });
  }
  return out;
}, { shaders, programs });
await browser.close();
let bad = 0;
for (const r of results) { console.log(`${r.ok ? "OK  " : "FAIL"} ${r.name}${r.log ? "  " + r.log : ""}`); if (!r.ok) bad++; }
process.exit(bad ? 1 : 0);
