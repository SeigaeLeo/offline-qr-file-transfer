"use strict";

const fs = require("fs");
const path = require("path");
const root = path.resolve(__dirname, "../mobile-receiver-v1.6.2");
const html = fs.readFileSync(path.join(root, "index.html"), "utf8");
const app = fs.readFileSync(path.join(root, "app.js"), "utf8");
const ids = new Set([...html.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]));
const bindings = [...app.matchAll(/getElementById\("([^"]+)"\)/g)].map(match => match[1]);
const missing = bindings.filter(id => !ids.has(id));
if (missing.length) throw new Error(`Missing DOM IDs: ${missing.join(", ")}`);
console.log(`DOM binding check passed: ${bindings.length} bindings`);
