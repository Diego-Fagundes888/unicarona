export function resolvePrivateRoot(projectRoot?: string): string | null;
export function resolveEnvironmentPath(projectRoot?: string, kind?: 'app'|'sandbox'|'docker'): string;
export function readPrivateEnvironment(projectRoot?: string, kind?: 'app'|'sandbox'|'docker'): Record<string,string>;
