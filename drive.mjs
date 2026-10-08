// API 24 WebView (Chrome 69): does the splash wordmark lose a letter? Per CSS variant, reload the live page N times and
// grab real screen pixels (adb screencap) a few times per load. Variants are injected before the page's scripts run.
import { execFileSync } from "node:child_process";
import fs from "node:fs";
const OUT = process.env.OUT; const N = +(process.env.N || 5);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const V = {
  base: "",
  nowc: ".ol-splash-word>span,.ol-word-wave>span{will-change:auto!important}",
  noscale: "@keyframes ol-letter-pulse{0%,100%{transform:none}20%{transform:translateY(-8px)}40%{transform:none}}",
  noanim: ".ol-splash-word>span,.ol-word-wave>span{animation:none!important}",
};
const list = await (await fetch("http://127.0.0.1:9222/json")).json();
const t = list.find((x) => x.type === "page" && /openlounge\.app/.test(x.url)) || list.find((x) => x.type === "page");
const ws = new WebSocket(t.webSocketDebuggerUrl);
await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
let id = 0; const pend = {};
ws.onmessage = (m) => { const d = JSON.parse(m.data); if (d.id && pend[d.id]) { pend[d.id](d.result || d.error); delete pend[d.id]; } };
const send = (method, params = {}) => new Promise((res) => { const i = ++id; pend[i] = res; ws.send(JSON.stringify({ id: i, method, params })); });
await send("Page.enable");
let scriptId = null;
for (const [name, css] of Object.entries(V)) {
  if (scriptId) await send("Page.removeScriptToEvaluateOnNewDocument", { identifier: scriptId });
  scriptId = null;
  if (css) scriptId = (await send("Page.addScriptToEvaluateOnNewDocument", { source: `document.addEventListener('DOMContentLoaded',function(){var s=document.createElement('style');s.textContent=${JSON.stringify(css)};document.head.appendChild(s)});` })).identifier;
  for (let k = 0; k < N; k++) {
    await send("Page.reload", { ignoreCache: false });
    await sleep(28000);
    for (let f = 0; f < 4; f++) { fs.writeFileSync(`${OUT}/${name}-${k}-${f}.png`, execFileSync("adb", ["exec-out", "screencap", "-p"], { maxBuffer: 1 << 26 })); await sleep(900); }
    const r = await send("Runtime.evaluate", { expression: "JSON.stringify({ua:navigator.userAgent.match(/Chrome\\/[\\d.]+/)[0],attrs:[].map.call(document.documentElement.attributes,function(a){return a.name}).join(' '),letters:[].map.call(document.querySelectorAll('.ol-splash-word > span'),function(s){var c=getComputedStyle(s);return s.textContent+(+c.opacity).toFixed(2)+(c.willChange)}).join(',')})", returnByValue: true });
    fs.appendFileSync(`${OUT}/state.txt`, `${name} ${k} ${r.result && r.result.value}\n`);
  }
}
ws.close(); process.exit(0);
