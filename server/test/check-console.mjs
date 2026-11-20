import fs from "node:fs";
const s = fs.readFileSync(new URL("../src/membership.js", import.meta.url), "utf8");
const m = s.match(/CONSOLE_HTML\s*=\s*`([\s\S]*?)`;/);
if (!m) { console.log("NO CONSOLE_HTML"); process.exit(1); }
const html = eval("`" + m[1] + "`");
const sm = html.match(/<script>([\s\S]*?)<\/script>/);
fs.writeFileSync(process.env.TEMP + "/console-check.js", sm[1]);
console.log("html", html.length, "script", sm[1].length);
