import { readdir } from "node:fs/promises";
import { join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
async function scripts(directory) {
  const entries = await readdir(join(root, directory), { withFileTypes: true });
  const files = await Promise.all(
    entries.map((entry) => {
      const relative = join(directory, entry.name);
      if (entry.isDirectory())
        return entry.name === "vendor" ? [] : scripts(relative);
      return /\.(?:js|mjs|cjs)$/.test(entry.name) ? [relative] : [];
    }),
  );
  return files.flat();
}

export function run(command, args) {
  const result = spawnSync(command, args, {
    cwd: root,
    stdio: "inherit",
    shell: false,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const files = (
    await Promise.all(
      [
        "dist",
        "scripts",
        "tests",
        "backend-java/scripts",
      ].map(scripts),
    )
  ).flat();
  for (const file of files) run(process.execPath, ["--check", file]);
  run(process.execPath, [
    "node_modules/typescript/bin/tsc",
    "-p",
    "tsconfig.tools.json",
    "--noEmit",
    "--incremental",
    "false",
    "--noUnusedLocals",
    "--noUnusedParameters",
  ]);
  run(process.execPath, ["scripts/static-assets.mjs"]);
  console.log(
    `${files.length} scripts conferidos; TypeScript e cache estático aprovados.`,
  );
}
