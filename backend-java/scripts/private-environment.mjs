import {existsSync,lstatSync,readFileSync,realpathSync} from 'node:fs';
import {join,resolve} from 'node:path';
import {homedir} from 'node:os';

export function resolvePrivateRoot(projectRoot=resolve('.')){
 const manifest=join(projectRoot,'.local/private-runtime.json');
 if(!existsSync(manifest)){
  if(existsSync(join(projectRoot,'.env'))&&/^UNICARONA_PRIVATE_ROOT=/m.test(readFileSync(join(projectRoot,'.env'),'utf8')))throw new Error('Private runtime marker exists without its manifest.');
  return null;
 }
 const value=JSON.parse(readFileSync(manifest,'utf8').replace(/^\uFEFF/,''));
 const expected=resolve(process.env.LOCALAPPDATA||join(homedir(),'AppData','Local'),'UniCarona/Private');
 const same=(a,b)=>resolve(a).toLowerCase()===resolve(b).toLowerCase();
 if(value.version!==1||!same(value.privateRoot,expected)||!same(value.workspaceRoot,projectRoot)||lstatSync(expected).isSymbolicLink()||!same(realpathSync(expected),expected))throw new Error('Unexpected private runtime manifest.');
 const pointer=join(projectRoot,'.env');
 if(existsSync(pointer)){
  const lines=readFileSync(pointer,'utf8').replace(/^\uFEFF/,'').split(/\r?\n/).filter(line=>line&&!/^\s*#/.test(line));
  if(lines.length!==1||lines[0]!=='UNICARONA_PRIVATE_ROOT='+expected.replaceAll('\\','/'))throw new Error('After migration the workspace .env must contain only its non-secret private-root pointer; edit protected config instead.');
 }
 return expected;
}
export function resolveEnvironmentPath(projectRoot=resolve('.'),kind='app'){
 if(!['app','sandbox','docker'].includes(kind))throw new Error('Unknown environment kind.');
 const root=resolvePrivateRoot(projectRoot);
 if(root){const config=join(root,'config'),path=join(config,({app:'app.env',sandbox:'mp-sandbox.env',docker:'docker.env'})[kind]);if(!existsSync(path)||lstatSync(config).isSymbolicLink()||lstatSync(path).isSymbolicLink())throw new Error('Private environment is missing or linked.');return path;}
 return join(projectRoot,({app:'.env',sandbox:'.local/mp-sandbox.env',docker:'.local/docker-java.env'})[kind]);
}
export function readPrivateEnvironment(projectRoot=resolve('.'),kind='app'){
 const path=resolveEnvironmentPath(projectRoot,kind);if(!existsSync(path))return {};
 return Object.fromEntries(readFileSync(path,'utf8').split(/\r?\n/).filter(l=>/^[A-Z][A-Z0-9_]*=/.test(l)).map(l=>{const at=l.indexOf('=');return [l.slice(0,at),l.slice(at+1)];}));
}
