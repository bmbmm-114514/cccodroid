import fs from "node:fs";
import { spawn } from "node:child_process";
import { constants } from "node:os";
import { pathToFileURL } from "node:url";
import { verifiedAgy } from "./multi-binary.mjs";
import { personalEnv } from "./multi-agy-launch.mjs";
import { codexOptions } from "./multi-codex-launch.mjs";

async function optionsFor(agent) {
  if (agent === "codex") return codexOptions({ terminal: true });
  if (agent === "agy") return { command: await verifiedAgy(), args: [], cwd: process.cwd(), env: personalEnv() };
  throw new Error("未知的 Agent 命令");
}

export async function runTerminal(agent, argv, getOptions = optionsFor) {
  const { command, args, cwd, env } = await getOptions(agent);
  const child = spawn(command, [...args, ...argv], { cwd, env, stdio: "inherit" });
  // Keep the terminal's foreground process group and raw input intact. The kernel
  // already sends keyboard signals to the child when the terminal owns this group.
  const signals = ["SIGINT", "SIGQUIT", "SIGTERM", "SIGHUP"];
  const relay = signal => {
    if (!process.stdin.isTTY || (signal !== "SIGINT" && signal !== "SIGQUIT")) child.kill(signal);
  };
  for (const signal of signals) process.on(signal, relay);
  try {
    return await new Promise((resolve, reject) => {
      child.once("error", reject);
      child.once("exit", (code, signal) => resolve(code ?? (128 + (constants.signals[signal] || 0))));
    });
  } finally {
    for (const signal of signals) process.off(signal, relay);
  }
}

const entry = process.argv[1];
if (import.meta.main ?? (entry && fs.existsSync(entry) && import.meta.url === pathToFileURL(fs.realpathSync(entry)).href)) {
  try { process.exitCode = await runTerminal(process.argv[2], process.argv.slice(3)); }
  catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
