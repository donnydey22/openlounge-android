// FX-062 (Donny t3300u, Oct 24 2026 4:11 AM MST, call 574e953a): Keiko on 0.3.20 (36) could not accept the mic/camera
// prompts after accepting an iPhone video call; the page sat on "Connecting" until the 11 s first-connect budget.
// Run: node --test tests/
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const main = readFileSync('app/src/main/java/com/donnydey/openlounge/MainActivity.java', 'utf8');
const inc = readFileSync('app/src/main/java/com/donnydey/openlounge/IncomingCallActivity.java', 'utf8');
test('FX-062 WebView mic/camera requests are queued: one system dialog at a time, none dropped', () => {
  assert.match(main, /ArrayDeque<PermissionRequest> webPermQueue/);
  assert.match(main, /webPermQueue\.add\(request\);\s*nextWebPermission\(\);/);
  assert.match(main, /if \(pendingWebPerm != null\) return; \/\/ a system dialog is up/);
  assert.match(main, /handler\.post\(this::nextWebPermission\)/);
  assert.doesNotMatch(main, /pendingWebPerm = request;/);
});
test('FX-062 a denied camera (or mic) grants the rest: the call continues', () => {
  assert.match(main, /req\.grant\(ok\.toArray\(new String\[0\]\)\)/);
});
test('FX-062 accept never opens the call page behind the lock screen', () => {
  assert.match(inc, /@Override public void onDismissCancelled\(\) \{ \}/);
});
