"use strict";

const fs = require("fs");
const path = require("path");
const root = path.resolve(__dirname, "../mobile-receiver-v1.8.4");
const html = fs.readFileSync(path.join(root, "index.html"), "utf8");
const app = fs.readFileSync(path.join(root, "app.js"), "utf8");
const ids = new Set([...html.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]));
const bindings = [...app.matchAll(/getElementById\("([^"]+)"\)/g)].map(match => match[1]);
const missing = bindings.filter(id => !ids.has(id));
if (missing.length) throw new Error(`Missing DOM IDs: ${missing.join(", ")}`);
if (!html.includes('id="shareButton"') || !html.includes("转发并删除")) {
  throw new Error("Share-and-delete control is missing");
}
for (const requiredSource of [
  "beginShare(completedFile.name",
  "await deleteOtherSessions(frame.sessionId)",
  "await clearStoredChunks()",
  "generation !== scanLoopGeneration",
  "acknowledgeNativeResult()",
  "await storeChunkBatch(pageBatch)",
  "if (activeChunkBatch) finalizeAfterChunkBatch = true"
]) {
  if (!app.includes(requiredSource)) throw new Error(`Missing lifecycle safeguard: ${requiredSource}`);
}
if (!app.includes('dataset.qtxVersion = "1.8.4"')) {
  throw new Error("V1.8.4 release version marker is missing");
}
for (const compatibilitySource of [
  "normalizeMetadata(JSON.parse",
  "meta.v !== 1 && meta.v !== 2",
  "return { ...meta, q: 1, g: 4 }"
]) {
  if (!app.includes(compatibilitySource)) throw new Error(`Missing V1 compatibility rule: ${compatibilitySource}`);
}
for (const diagnosticId of [
  "distributionToggle",
  "zxingErrorsToggle",
  "qualityToggle",
  "failureSamplesToggle",
  "exposureToggle"
]) {
  const togglePattern = new RegExp(`<input[^>]*type="checkbox"[^>]*id="${diagnosticId}"[^>]*>`);
  if (!togglePattern.test(html)) {
    throw new Error(`Missing manual diagnostic toggle: ${diagnosticId}`);
  }
}
for (const defaultOffSource of [
  "let durationDistributionEnabled = false",
  "let zxingErrorsEnabled = false",
  "let imageQualityEnabled = false",
  "let failureSamplingEnabled = false",
  "let exposureDiagnosticsEnabled = false"
]) {
  if (!app.includes(defaultOffSource)) throw new Error(`Diagnostic is not default-off: ${defaultOffSource}`);
}
for (const exposureSource of ["recordExposureSample(metrics)", "sensorFrameDurationMs", "formatShutter(current.exposureTimeMs)"]) {
  if (!app.includes(exposureSource)) throw new Error(`Missing exposure diagnostic path: ${exposureSource}`);
}
console.log(`DOM binding check passed: ${bindings.length} bindings`);
