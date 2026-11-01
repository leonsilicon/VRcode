import { resolve } from "node:path";

export const repoRoot = resolve(import.meta.dir, "../..");

export async function run(
  command: string[],
  options: { timeout?: number; input?: Uint8Array; cwd?: string; inherit?: boolean } = {},
) {
  const child = Bun.spawn(command, {
    cwd: options.cwd ?? repoRoot,
    stdin: options.input ?? "ignore",
    stdout: options.inherit ? "inherit" : "pipe",
    stderr: options.inherit ? "inherit" : "pipe",
  });
  let expired = false;
  const timer = setTimeout(() => { expired = true; child.kill("SIGKILL"); }, options.timeout ?? 10_000);
  try {
    const [code, stdout, stderr] = await Promise.all([
      child.exited,
      options.inherit ? "" : new Response(child.stdout as ReadableStream).text(),
      options.inherit ? "" : new Response(child.stderr as ReadableStream).text(),
    ]);
    if (expired) throw new Error(`${command[0]} timed out`);
    if (code !== 0) throw new Error((stderr || stdout).trim() || `${command[0]} exited ${code}`);
    return stdout.trim();
  } finally {
    clearTimeout(timer);
  }
}
