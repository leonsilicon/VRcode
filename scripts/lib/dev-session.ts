/** Port checks for `scripts/_dev.ts`: refuse to start over a running stack, or stop it with --force. */
import { basename, join } from "node:path";
import { appExecutable, installedApp, LABEL } from "../run-mac-service";

/** mprocs's control server, the Tauri dev server, then vrcode-mac's bridge, speech and relay ports. */
export const DEV_SERVER_PORT = 7270;
export const TAURI_DEV_PORT = 7271;
export const SERVICE_PORTS = [7272, 7273, 7274];

type DevSessionOptions = {
  force: boolean;
  mprocsBin: string;
  serverPort: number;
  servicePorts: number[];
  /** Executable names that restart a helper killed on its own, so --force stops them instead. */
  supervisors?: string[];
  /** How long a SIGTERM gets before SIGKILL. VRcode Mac gives its helpers 3 s. */
  grace?: number;
};

export type DevSession = {
  /** The installed VRcode Mac (LaunchAgent) that --force stopped, for `launchctl kickstart` afterwards. */
  stoppedInstalledApp: boolean;
};

const installedExecutable = join(installedApp, appExecutable);

// Do not probe the control server by connecting: mprocs 0.9.6 panics on an empty request.
export async function listeners(port: number): Promise<number[]> {
  const result = Bun.spawnSync(["lsof", "-nP", "-t", `-iTCP:${port}`, "-sTCP:LISTEN"], {
    stdout: "pipe",
    stderr: "pipe",
    timeout: 15_000,
  });
  const stdout = result.stdout.toString().trim();
  const stderr = result.stderr.toString().trim();
  if (result.exitCode === 1 && !stdout && !stderr) return [];
  if (result.exitCode !== 0) {
    throw new Error(`Could not inspect port ${port}: ${stderr || result.signalCode || result.exitCode}`);
  }
  const pids = stdout ? [...new Set(stdout.split(/\s+/).map(Number))] : [];
  if (pids.some((pid) => !Number.isSafeInteger(pid) || pid <= 0)) {
    throw new Error(`Could not inspect port ${port}: lsof returned an invalid PID`);
  }
  return pids;
}

function processInfo(pid: number): { ppid: number; command: string } | undefined {
  const result = Bun.spawnSync(["ps", "-o", "ppid=,comm=", "-p", String(pid)], { stdout: "pipe", stderr: "ignore" });
  const match = result.stdout.toString().trim().match(/^(\d+)\s+(.+)$/);
  return match ? { ppid: Number(match[1]), command: match[2] } : undefined;
}

type Holder = { pid: number; command: string; supervisor?: { pid: number; command: string } };

/**
 * Who holds a port. VRcode Mac restarts a helper that is killed on its own, so a helper's
 * supervising app is reported too; it is never this process or launchd.
 */
function holders(pids: number[], supervisors: string[]): Holder[] {
  return pids.map((pid) => {
    const info = processInfo(pid);
    const holder: Holder = { pid, command: info?.command ?? "unknown" };
    if (info && info.ppid > 1 && info.ppid !== process.pid) {
      const parent = processInfo(info.ppid);
      if (parent && supervisors.includes(basename(parent.command))) holder.supervisor = { pid: info.ppid, command: parent.command };
    }
    return holder;
  });
}

async function waitForRelease(port: number, timeout: number): Promise<boolean> {
  const deadline = Date.now() + timeout;
  do {
    if ((await listeners(port)).length === 0) return true;
    await Bun.sleep(100);
  } while (Date.now() < deadline);
  return false;
}

function signal(pid: number, name: NodeJS.Signals) {
  try {
    process.kill(pid, name);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ESRCH") throw error;
  }
}

async function freePort(port: number, supervisors: string[], grace: number, session: DevSession): Promise<void> {
  for (const name of ["SIGTERM", "SIGKILL"] as const) {
    const held = holders(await listeners(port), supervisors);
    // A supervised helper goes down with its app; SIGTERM to the helper alone would be undone.
    const targets = new Set(held.flatMap((holder) =>
      holder.supervisor ? (name === "SIGTERM" ? [holder.supervisor.pid] : [holder.supervisor.pid, holder.pid]) : [holder.pid]));
    for (const holder of held) {
      if (holder.supervisor?.command === installedExecutable) session.stoppedInstalledApp = true;
    }
    for (const pid of targets) signal(pid, name);
    if (await waitForRelease(port, name === "SIGTERM" ? grace : 2_000)) return;
  }
  throw new Error(`Port ${port} is still in use after --force. Inspect it with lsof -nP -iTCP:${port} -sTCP:LISTEN.`);
}

function occupied(port: number, held: Holder[]): string {
  const installed = held.find((holder) => holder.supervisor?.command === installedExecutable || holder.command === installedExecutable);
  if (installed) {
    return `Port ${port} is held by the installed VRcode Mac (${LABEL}). Re-run with --force to stop it for this session; the dev session starts it again when it ends.`;
  }
  const holder = held[0];
  const owner = holder.supervisor ? `${basename(holder.supervisor.command)} (pid ${holder.supervisor.pid})` : `${basename(holder.command)} (pid ${holder.pid})`;
  return `Port ${port} is already in use by ${owner}; another dev session or VRcode Mac may be running. Re-run with --force to quit it first.`;
}

export async function prepareDevSession({
  force,
  mprocsBin,
  serverPort,
  servicePorts,
  supervisors = ["vrcode-mac-app"],
  grace = 5_000,
}: DevSessionOptions): Promise<DevSession> {
  const session: DevSession = { stoppedInstalledApp: false };
  const server = `127.0.0.1:${serverPort}`;
  const ports = [serverPort, ...servicePorts];
  if (!force) {
    for (const port of ports) {
      const pids = await listeners(port);
      if (pids.length > 0) throw new Error(occupied(port, holders(pids, supervisors)));
    }
    return session;
  }

  if ((await listeners(serverPort)).length > 0) {
    // A graceful quit lets the mac process stop the app and its helpers before mprocs exits.
    console.warn(`[dev] --force: quitting the existing mprocs session on ${server}`);
    const quit = Bun.spawnSync([mprocsBin, "--ctl", "{c: quit}", "--server", server], {
      stdout: "ignore",
      stderr: "inherit",
      timeout: 5_000,
    });
    if (quit.exitCode !== 0) console.warn("[dev] mprocs quit failed; stopping the port holder directly.");
    await waitForRelease(serverPort, 15_000);
  }
  // Also reclaim helpers and dev servers left behind by a session that did not shut down cleanly.
  for (const port of ports) await freePort(port, supervisors, grace, session);
  if (session.stoppedInstalledApp) console.warn(`[dev] --force: stopped the installed VRcode Mac (${LABEL}) for this session.`);
  return session;
}

/** Starts the installed VRcode Mac again after the session that stopped it. */
export function restartInstalledApp() {
  const target = `gui/${process.getuid?.() ?? 501}/${LABEL}`;
  const result = Bun.spawnSync(["launchctl", "kickstart", target], { stdout: "ignore", stderr: "pipe", timeout: 10_000 });
  if (result.exitCode === 0) console.log(`[dev] Started the installed VRcode Mac again (${LABEL}).`);
  else console.warn(`[dev] Could not restart ${LABEL}: ${result.stderr.toString().trim()}. Run: launchctl kickstart ${target}`);
}
