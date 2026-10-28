// Local, disposable demonstration using the existing Java test harness.
// No fixture controls are included in the production jar.
import {spawn} from 'node:child_process';
import {mkdtemp,mkdir,writeFile} from 'node:fs/promises';
import {createServer} from 'node:net';
import {join,resolve} from 'node:path';
import {randomBytes} from 'node:crypto';
import {openSync,closeSync} from 'node:fs';
import {resolvePrivateRoot} from '../backend-java/scripts/private-environment.mjs';

const retained=['PATH','Path','HOME','JAVA_HOME','JAVA_E2E_EXECUTABLE','PG_BIN','DOCKER_HOST','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH','SystemRoot','WINDIR','OS','ComSpec','PATHEXT','PROCESSOR_ARCHITECTURE','TEMP','TMP','LOCALAPPDATA','USERPROFILE'];
const clean=Object.fromEntries(retained.filter(key=>process.env[key]).map(key=>[key,process.env[key]]));
for(const key of Object.keys(process.env))delete process.env[key];
Object.assign(process.env,clean);
const privateRoot=resolvePrivateRoot(),base=privateRoot?join(privateRoot,'data/portfolio-demo'):resolve('.local/demo');
await mkdir(base,{recursive:true});
const directory=await mkdtemp(join(base,'run-')),log=openSync(join(directory,'demo.private.log'),'w');
const windows=process.platform==='win32',tools=process.env.PG_BIN;
const executable=name=>join(tools,name+(windows?'.exe':''));
const run=(file,args,extra={})=>new Promise((accept,reject)=>{
 const child=spawn(file,args,{env:{...process.env,...extra},stdio:['ignore',log,log],windowsHide:true});
 child.once('error',reject);child.once('exit',code=>code===0?accept():reject(new Error('Demo tool failed: '+file.split(/[\\/]/).at(-1))));
});
async function freePort(){const server=createServer();await new Promise((accept,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',accept);});const port=server.address().port;await new Promise(accept=>server.close(accept));return port;}
const data=join(directory,'postgres');let started=false,environment;
try{
 if(tools){
  const password=randomBytes(32).toString('hex'),port=await freePort(),pwfile=join(directory,'initial-password.private');
  await writeFile(pwfile,password,{mode:0o600});
  await run(executable('initdb'),['-D',data,'-U','portfolio_demo','--pwfile='+pwfile,'--auth=scram-sha-256','--no-locale','--encoding=UTF8']);
  await run(executable('pg_ctl'),['-D',data,'-o','-p '+port+' -h 127.0.0.1','-l',join(directory,'postgres.private.log'),'start','-w','-t','45']);started=true;
  await run(executable('createdb'),['-h','127.0.0.1','-p',String(port),'-U','portfolio_demo','unicarona_java_test'],{PGPASSWORD:password});
  await run(executable('psql'),['-h','127.0.0.1','-p',String(port),'-U','portfolio_demo','-d','unicarona_java_test','-v','ON_ERROR_STOP=1','-c',"SET statement_timeout='120s'; CREATE EXTENSION pgcrypto; CREATE EXTENSION pg_trgm;"],{PGPASSWORD:password});
  process.env.TEST_JAVA_DATABASE_URL=`postgresql://portfolio_demo:${password}@127.0.0.1:${port}/unicarona_java_test`;
 }
 const origin='http://127.0.0.1:'+await freePort();
 const {setup}=await import('../backend-java/e2e/helpers.ts');
 environment=await setup({APP_ORIGIN:origin});
 console.log('Demonstração local: '+origin+'\nDados fictícios; pagamentos e e-mails simulados.\nLogin: ana@example.com\nSenha exclusiva dos testes: Synthetic seed test only 2026');
 if(process.argv.includes('--check')){
  const home=await fetch(origin),docs=await fetch(origin+'/api/docs/json');
  if(!home.ok||!docs.ok)throw new Error('Demo HTTP smoke check failed');
  const login=await fetch(origin+'/api/v1/auth/login',{method:'POST',headers:{Origin:origin,'Content-Type':'application/json'},body:JSON.stringify({email:'ana@example.com',password:'Synthetic seed test only 2026'})});
  if(!login.ok)throw new Error('Demo login smoke check failed');
  console.log('Página inicial, OpenAPI e login Java verificados.');
 }else await new Promise(accept=>{process.once('SIGINT',accept);process.once('SIGTERM',accept);});
}finally{
 if(environment)await environment.close();
 if(started)await run(executable('pg_ctl'),['-D',data,'-m','fast','stop','-w','-t','30']);
 closeSync(log);
}
