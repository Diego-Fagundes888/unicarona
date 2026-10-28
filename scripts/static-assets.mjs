import { createHash } from "node:crypto";
import { readFile, readdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = fileURLToPath(new URL("../dist/", import.meta.url));

/** Version only local resources already carrying a cache identifier. */
export async function staticAssets({ write = false } = {}) {
  const stale = [];
  for (const name of await readdir(root)) {
    if (!name.endsWith(".html")) continue;
    const file = resolve(root, name);
    const source = await readFile(file, "utf8");
    let updated = source;
    const references = [
      ...source.matchAll(/(?:src|href)="([^"?#]+)\?v=([a-f0-9]+)"/g),
    ];
    for (const [, asset, previous] of references) {
      if (/^(?:[a-z]+:|\/\/)/i.test(asset)) continue;
      const bytes = await readFile(resolve(dirname(file), asset));
      const version = createHash("sha256")
        .update(bytes)
        .digest("hex")
        .slice(0, 12);
      if (version === previous) continue;
      stale.push(`${name}: ${asset}`);
      updated = updated.replaceAll(
        `"${asset}?v=${previous}"`,
        `"${asset}?v=${version}"`,
      );
    }
    if (write && updated !== source) await writeFile(file, updated);
  }
  return stale;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
) {
  const write = process.argv.includes("--write");
  const stale = await staticAssets({ write });
  if (stale.length && !write) {
    console.error("Recursos com versão desatualizada:\n" + stale.join("\n"));
    process.exitCode = 1;
  } else {
    console.log(
      write
        ? `${stale.length} referências de cache atualizadas.`
        : "Versões de recursos estáticos conferidas.",
    );
  }
}
