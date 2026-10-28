import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import sharp from 'sharp';
import {resolveEnvironmentPath} from './private-environment.mjs';
import {assertApiOperations} from '../../scripts/api-operations.mjs';

// Only the isolated Compose development stack and synthetic seed accounts.
const envPath=resolveEnvironmentPath(undefined,'docker');
const env=Object.fromEntries((await readFile(envPath,'utf8')).split(/\r?\n/).filter(Boolean).map(line=>{
  const split=line.indexOf('=');return [line.slice(0,split),line.slice(split+1)];
}));
const origin=`http://localhost:${env.JAVA_HTTP_PORT||3003}`;
assert.equal(new URL(env.JAVA_DATABASE_URL).hostname,'db');
assert.equal(new URL(env.JAVA_DATABASE_URL).pathname,'/unicarona');
let cookie='',csrf='';
async function call(path,options={}) {
  const headers={Origin:origin,...(cookie?{Cookie:cookie,'x-csrf-token':csrf}:{}),...options.headers};
  return fetch(origin+path,{...options,headers,signal:AbortSignal.timeout(15000)});
}
async function login(email) {
  const response=await call('/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email,password:env.SEED_PASSWORD})});
  assert.equal(response.status,200,'Synthetic login');
  const cookies=response.headers.getSetCookie().map(value=>value.split(';')[0]);
  cookie=cookies.join('; ');csrf=cookies.find(value=>value.startsWith('uc_csrf=')).slice('uc_csrf='.length);
  assert.ok((await response.json()).profile);
}
async function download(id) {
  const response=await call('/api/v1/files/'+id);assert.equal(response.status,200);
  assert.equal(response.headers.get('cache-control'),'private, no-store');
  return {bytes:Buffer.from(await response.arrayBuffer()),type:response.headers.get('content-type')};
}
const digest=bytes=>createHash('sha256').update(bytes).digest('hex');
assert.equal((await call('/health/ready')).status,200);
assert.equal((await call('/dashboard.html')).status,200);
const spec=await (await call('/api/docs/json')).json();
const configuration=await (await call('/api/v1/payments/config')).json();
const operationCount=assertApiOperations(spec,configuration.provider);
await login('ana@example.com');
const receiptPath='.local/docker-smoke-receipt.json';
if(process.argv.includes('--persistence')) {
  const receipt=JSON.parse(await readFile(receiptPath,'utf8'));
  for(const file of receipt.files) {
    const stored=await download(file.id);assert.equal(digest(stored.bytes),file.digest);assert.equal(stored.type,file.type);
  }
  console.log('PASS: database, sessions, private files and WebP persist after container recreation.');
} else {
  const png=await sharp({create:{width:720,height:480,channels:3,background:'#2d803b'}}).png().toBuffer();
  const pdf=Buffer.from('%PDF-1.7\nSynthetic Docker smoke document 2026\n%%EOF');
  const files=[];
  for(const [purpose,bytes,type] of [['photo',png,'image/png'],['identity',pdf,'application/pdf']]) {
    const form=new FormData();form.set('purpose',purpose);form.set('file',new Blob([bytes],{type}),purpose+'.fixture');
    const response=await call('/api/v1/files',{method:'POST',body:form});assert.equal(response.status,200,'Linux upload '+purpose);
    const file=await response.json(),stored=await download(file.id);
    if(purpose==='photo') {
      assert.equal(stored.type,'image/webp');assert.equal(stored.bytes.toString('ascii',0,4),'RIFF');
      assert.equal(stored.bytes.toString('ascii',8,12),'WEBP');
      const metadata=await sharp(stored.bytes).metadata();assert.equal(metadata.width,512);assert.equal(metadata.height,341);
    } else { assert.deepEqual(stored.bytes,pdf); }
    files.push({id:file.id,type:stored.type,digest:digest(stored.bytes)});
  }
  cookie='';csrf='';assert.equal((await call('/api/v1/files/'+files[0].id)).status,401);
  await login('lucas@example.com');assert.equal((await call('/api/v1/files/'+files[1].id)).status,404);
  await writeFile(receiptPath,JSON.stringify({files}),{mode:0o600});
  console.log(`PASS: container readiness, frontend, ${operationCount} OpenAPI operations (${configuration.provider}), login, CSRF, Linux native WebP, private documents and access isolation.`);
}
