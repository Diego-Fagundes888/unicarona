import { run } from "./check-source.mjs";

// Preserve npm test -- -LocalDatabase and Maven selectors without a shell string.
run(process.execPath, ["scripts/check-source.mjs"]);
run(process.execPath, [
  "--test",
  "tests/privacy-ui.test.mjs",
  "tests/admin-privacy-ui.test.mjs",
  "tests/tooling.test.mjs",
]);
run(process.execPath, [
  "--test",
  "backend-java/scripts/private-environment.test.mjs",
]);
run("pwsh", [
  "-NoProfile",
  "-File",
  "backend-java/scripts/maven-local.ps1",
  ...process.argv.slice(2),
  "verify",
]);
