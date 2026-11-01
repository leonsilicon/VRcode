import { expect, test } from "bun:test";
import { mkdtempSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
import mprocsBin from "mprocs";
import { listeners, prepareDevSession } from "../lib/dev-session";
import { stage } from "../dev/mac";

const launcher = join(import.meta.dir, "../_dev.ts");

function unusedPort(): number {
  const server = Bun.listen({ hostname: "127.0.0.1", port: 0, socket: { data() {} } });
  const port = server.port;
  server.stop(true);
  return port;
}

async function startListener(hostname = "127.0.0.1", ignoreTerm = false) {
  const child = Bun.spawn(
    [
      process.execPath,
      "-e",
      `${ignoreTerm ? 'process.on("SIGTERM", () => {});' : ""}
      const server = Bun.serve({ hostname: ${JSON.stringify(hostname)}, port: 0, fetch: () => new Response("ready") });
      console.log(server.port);`,
    ],
    { stdout: "pipe", stderr: "inherit" },
  );
  const reader = child.stdout.getReader();
  const { value } = await reader.read();
  reader.releaseLock();
  const port = Number(new TextDecoder().decode(value).trim());
  if (!port) {
    child.kill();
    throw new Error("Test listener did not publish a port");
  }
  return { child, port };
}

/** Like VRcode Mac: restarts its listening helper when the helper dies, and stops it on SIGTERM. */
async function startSupervisor(port: number) {
  const helper = `Bun.serve({ hostname: "127.0.0.1", port: ${port}, fetch: () => new Response("helper") });`;
  const child = Bun.spawn([
    process.execPath,
    "-e",
    `let helper, stopping = false;
    const start = () => {
      helper = Bun.spawn([process.execPath, "-e", ${JSON.stringify(helper)}]);
      helper.exited.then(() => { if (!stopping) setTimeout(start, 100); });
    };
    process.on("SIGTERM", () => { stopping = true; helper.kill(); helper.exited.then(() => process.exit(0)); });
    start();`,
  ], { stdout: "inherit", stderr: "inherit" });
  const deadline = Date.now() + 5_000;
  while ((await listeners(port)).length === 0) {
    if (Date.now() > deadline) throw new Error("Supervised helper did not start listening");
    await Bun.sleep(50);
  }
  return child;
}

test("normal startup refuses occupied ports and preserves the listener", async () => {
  const { child, port } = await startListener();
  try {
    await expect(
      prepareDevSession({ force: false, mprocsBin, serverPort: unusedPort(), servicePorts: [port] }),
    ).rejects.toThrow("Re-run with --force");
    expect(child.exitCode).toBeNull();
    expect(await (await fetch(`http://127.0.0.1:${port}`)).text()).toBe("ready");
  } finally {
    child.kill("SIGKILL");
    await child.exited;
  }
});

test("force frees an orphaned listener and leaves other listeners running", async () => {
  const orphan = await startListener();
  const other = await startListener();
  try {
    const session = await prepareDevSession({ force: true, mprocsBin, serverPort: unusedPort(), servicePorts: [orphan.port], grace: 1_000 });
    expect(session.stoppedInstalledApp).toBe(false);
    await orphan.child.exited;
    const replacement = Bun.listen({ hostname: "127.0.0.1", port: orphan.port, socket: { data() {} } });
    replacement.stop(true);
    expect(other.child.exitCode).toBeNull();
  } finally {
    orphan.child.kill("SIGKILL");
    other.child.kill("SIGKILL");
    await Promise.all([orphan.child.exited, other.child.exited]);
  }
});

test("force handles IPv6 listeners that ignore SIGTERM", async () => {
  const { child, port } = await startListener("::1", true);
  try {
    await prepareDevSession({ force: true, mprocsBin, serverPort: unusedPort(), servicePorts: [port], grace: 1_000 });
    await child.exited;
    expect(child.signalCode).toBe("SIGKILL");
    const replacement = Bun.listen({ hostname: "::1", port, socket: { data() {} } });
    replacement.stop(true);
  } finally {
    child.kill("SIGKILL");
    await child.exited;
  }
}, 10_000);

test("force stops the app supervising a helper instead of the helper it would restart", async () => {
  const port = unusedPort();
  const supervisor = await startSupervisor(port);
  try {
    // Signalling only the helper frees the port until the supervisor restarts it.
    await prepareDevSession({ force: true, mprocsBin, serverPort: unusedPort(), servicePorts: [port], supervisors: [], grace: 500 });
    await Bun.sleep(1_000);
    expect(supervisor.exitCode).toBeNull();
    expect((await listeners(port)).length).toBeGreaterThan(0);

    await prepareDevSession({ force: true, mprocsBin, serverPort: unusedPort(), servicePorts: [port], supervisors: [basename(process.execPath)], grace: 3_000 });
    expect(await supervisor.exited).toBe(0);
    await Bun.sleep(1_000);
    expect(await listeners(port)).toEqual([]);
  } finally {
    supervisor.kill("SIGKILL");
    await supervisor.exited;
  }
}, 20_000);

test("force quits a real mprocs session through its control server", async () => {
  const directory = mkdtempSync(join(tmpdir(), "vrcode-dev-session-test-"));
  const config = join(directory, "mprocs.yaml");
  await Bun.write(config, "procs: {}\n");
  const serverPort = unusedPort();
  const child = Bun.spawn([mprocsBin, "--config", config, "--server", `127.0.0.1:${serverPort}`], {
    cwd: directory,
    terminal: { cols: 80, rows: 24, data() {} },
  });
  try {
    const deadline = Date.now() + 5_000;
    while ((await listeners(serverPort)).length === 0 && child.exitCode === null && Date.now() < deadline) await Bun.sleep(50);
    expect((await listeners(serverPort)).length).toBeGreaterThan(0);
    await expect(prepareDevSession({ force: false, mprocsBin, serverPort, servicePorts: [] })).rejects.toThrow("Re-run with --force");
    expect(child.exitCode).toBeNull();
    await prepareDevSession({ force: true, mprocsBin, serverPort, servicePorts: [] });
    expect(await child.exited).toBe(0);
  } finally {
    child.terminal?.close();
    if (child.exitCode === null) child.kill("SIGKILL");
    await child.exited;
    rmSync(directory, { recursive: true, force: true });
  }
}, 25_000);

test("force with help is accepted without stopping the running development stack", () => {
  const result = Bun.spawnSync([process.execPath, launcher, "--force", "--help"], { stdout: "pipe", stderr: "pipe", timeout: 10_000 });
  expect(result.exitCode).toBe(0);
  expect(result.stdout.toString()).toContain("Usage: vpx _dev");
  expect(result.stdout.toString()).toContain("Usage: mprocs");
  expect(result.stderr.toString()).not.toContain("--force:");
});

test("sidecars are replaced atomically and only when their bytes change", async () => {
  const directory = mkdtempSync(join(tmpdir(), "vrcode-stage-test-"));
  try {
    const source = join(directory, "built"), destination = join(directory, "binaries/helper-triple");
    await Bun.write(source, "first");
    expect(await stage(source, destination)).toBe(true);
    expect(statSync(destination).mode & 0o777).toBe(0o755);
    const staged = statSync(destination).mtimeMs;
    await Bun.sleep(20);
    expect(await stage(source, destination)).toBe(false);
    expect(statSync(destination).mtimeMs).toBe(staged);
    await Bun.write(source, "other");
    expect(await stage(source, destination)).toBe(true);
    expect(await Bun.file(destination).text()).toBe("other");
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
