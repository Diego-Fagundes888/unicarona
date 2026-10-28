import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,rm,symlink} from 'node:fs/promises';
import {join,resolve,sep} from 'node:path';
import {tmpdir} from 'node:os';
import {resolveEnvironmentPath,resolvePrivateRoot,readPrivateEnvironment} from './private-environment.mjs';

test('environment resolver fails closed for interrupted or redirected private configuration',async()=>{
 const scratch=await mkdtemp(join(tmpdir(),'unicarona-resolver-'));
 const originalLocal=process.env.LOCALAPPDATA;
 try{
  process.env.LOCALAPPDATA=join(scratch,'local');
  const workspace=join(scratch,'workspace'),privateRoot=join(process.env.LOCALAPPDATA,'UniCarona/Private');
  await mkdir(join(workspace,'.local'),{recursive:true});
  await mkdir(join(privateRoot,'config'),{recursive:true});
  assert.equal(resolvePrivateRoot(workspace),null);
  assert.equal(resolveEnvironmentPath(workspace),join(workspace,'.env'));
  const pointer='UNICARONA_PRIVATE_ROOT='+privateRoot.replaceAll('\\','/')+'\n';
  await writeFile(join(workspace,'.env'),pointer);
  assert.throws(()=>resolvePrivateRoot(workspace),/marker exists without/);
  const manifest={version:1,privateRoot,workspaceRoot:workspace};
  const publish=async value=>writeFile(join(workspace,'.local/private-runtime.json'),JSON.stringify(value));
  await publish({...manifest,version:2});assert.throws(()=>resolvePrivateRoot(workspace),/Unexpected/);
  await publish({...manifest,workspaceRoot:scratch});assert.throws(()=>resolvePrivateRoot(workspace),/Unexpected/);
  await publish({...manifest,privateRoot:scratch});assert.throws(()=>resolvePrivateRoot(workspace),/Unexpected/);
  await publish(manifest);assert.throws(()=>resolveEnvironmentPath(workspace),/missing or linked/);
  await writeFile(join(workspace,'.env'),pointer+'SYNTHETIC_SETTING=wrong-place\n');
  assert.throws(()=>resolvePrivateRoot(workspace),/must contain only/);
  await writeFile(join(workspace,'.env'),pointer);
  await writeFile(join(privateRoot,'config/app.env'),'SYNTHETIC_SETTING=example\n# ignored\n');
  assert.equal(resolveEnvironmentPath(workspace),join(privateRoot,'config/app.env'));
  assert.deepEqual(readPrivateEnvironment(workspace),{SYNTHETIC_SETTING:'example'});
  assert.throws(()=>resolveEnvironmentPath(workspace,'unexpected'),/Unknown/);
  const config=join(privateRoot,'config');
  // Only synthetic files, and only within the verified scratch directory, are removed.
  assert.ok(resolve(config).startsWith(resolve(scratch)+sep));
  await rm(config,{recursive:true});
  await mkdir(join(scratch,'redirected-config'));
  await symlink(join(scratch,'redirected-config'),config,process.platform==='win32'?'junction':'dir');
  await writeFile(join(scratch,'redirected-config/app.env'),'SYNTHETIC_SETTING=redirected\n');
  assert.throws(()=>resolveEnvironmentPath(workspace),/missing or linked/);
 }finally{
  if(originalLocal===undefined)delete process.env.LOCALAPPDATA;else process.env.LOCALAPPDATA=originalLocal;
  assert.ok(resolve(scratch).startsWith(resolve(tmpdir())+sep));
  await rm(scratch,{recursive:true,force:true});
 }
});
