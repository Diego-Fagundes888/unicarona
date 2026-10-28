import {spawn} from 'node:child_process';
import {readFile,mkdir,rm} from 'node:fs/promises';
import {createInterface} from 'node:readline';
import {resolve,dirname,delimiter,join} from 'node:path';
import {randomUUID} from 'node:crypto';
import pg from 'pg';
import {resolvePrivateRoot} from '../scripts/private-environment.mjs';

// Synthetic fixtures only. No imports from backend/src, no TypeScript application or worker.
const seedPassword='Synthetic seed test only 2026';
const controls=new WeakMap<pg.Pool,(action:string,data?:object)=>Promise<any>>();
let active:((action:string,data?:object)=>Promise<any>)|undefined;
/** Allow an isolated build without replacing classes used by another Java verification. */
export async function javaClasspath() {
 if(process.env.JAVA_E2E_CLASSPATH)return process.env.JAVA_E2E_CLASSPATH;
 const root=resolve('.'),dependencies=(await readFile(resolve(root,'backend-java/target/e2e-classpath.txt'),'utf8')).trim();
 return [resolve(root,'backend-java/target/test-classes'),resolve(root,'backend-java/target/classes'),dependencies].join(delimiter);
}
export async function setup(overrides:{APP_ORIGIN?:string;PORT?:number;DEV_MAIL_PATH?:string;PAYMENT_PROVIDER_TIMEOUT_MS?:number}={}) {
 const root=resolve('.'),protectedRoot=resolvePrivateRoot(root),base=protectedRoot?join(protectedRoot,'data/java-e2e-runs'):resolve(root,'.local/java-e2e-runs'),privateRoot=join(base,randomUUID());
 await mkdir(privateRoot,{recursive:true,mode:0o700});
 const origin=overrides.APP_ORIGIN||'http://127.0.0.1:3001',mail=join(privateRoot,'mail'),storage=join(privateRoot,'storage');
 const executable=process.env.JAVA_E2E_EXECUTABLE||(protectedRoot?join(protectedRoot,'runtime/java/bin/java.exe'):'java');
 const classpath=await javaClasspath();
 const child=spawn(executable,['-cp',classpath,'com.unicarona.support.JavaE2EServer'],{cwd:root,env:{...process.env,JAVA_E2E_ORIGIN:origin,JAVA_E2E_STORAGE:storage,JAVA_E2E_MAIL:mail,JAVA_E2E_PAYMENT_TIMEOUT:String(overrides.PAYMENT_PROVIDER_TIMEOUT_MS||10000)},stdio:['pipe','pipe','ignore']});
 const pending=new Map<number,{resolve:(value:any)=>void;reject:(error:Error)=>void;timer:ReturnType<typeof setTimeout>}>();let next=0,closed=false;
 let readyResolve!:(value:any)=>void,readyReject!:(error:Error)=>void;
 const ready=new Promise<any>((accept,reject)=>{readyResolve=accept;readyReject=reject;});
 const startup=setTimeout(()=>{readyReject(new Error('Java E2E startup timed out'));child.kill();},90000);
 const lines=createInterface({input:child.stdout});
 lines.on('line',line=>{
  let message:any;try{message=JSON.parse(line);}catch{return;}
  if('ready' in message){clearTimeout(startup);message.ready?readyResolve(message):readyReject(new Error('Java E2E startup failed'));return;}
  const request=pending.get(message.id);if(!request)return;pending.delete(message.id);clearTimeout(request.timer);
  message.error?request.reject(new Error('Java E2E action failed: '+message.error)):request.resolve(message.result);
 });
 child.once('error',()=>readyReject(new Error('Java E2E process could not start')));
 child.once('exit',()=>{clearTimeout(startup);readyReject(new Error('Java E2E process exited before readiness'));for(const value of pending.values()){clearTimeout(value.timer);value.reject(new Error('Java E2E process exited'));}pending.clear();});
 const command=(action:string,data:object={})=>new Promise<any>((accept,reject)=>{
  if(child.exitCode!==null||closed){reject(new Error('Java E2E process is closed'));return;}
  const id=++next,timer=setTimeout(()=>{pending.delete(id);reject(new Error('Java E2E action timed out'));},20000);pending.set(id,{resolve:accept,reject,timer});
  child.stdin.write(JSON.stringify({id,action,...data})+'\n',error=>{if(error){clearTimeout(timer);pending.delete(id);reject(new Error('Java E2E input failed'));}});
 });
 let pool:pg.Pool|undefined;
 const close=async()=>{
  if(closed)return;
  // Release Node's PostgreSQL clients before Java stops its Testcontainers database.
  // Otherwise idle pool clients emit an uncaught connection error during teardown.
  if(pool)await pool.end();
  try{if(child.exitCode===null)await command('close');}catch{}closed=true;child.stdin.end();
  if(child.exitCode===null)await new Promise<void>(accept=>{const timer=setTimeout(()=>{child.kill();accept();},10000);child.once('exit',()=>{clearTimeout(timer);accept();});});
  lines.close();if(active===command)active=undefined;
  // Unique generated child directory only; never remove shared application storage/mail.
  if(dirname(privateRoot)!==base||privateRoot===base)throw new Error('Invalid Java E2E cleanup path');
  await rm(privateRoot,{recursive:true,force:true});
 };
 try{
  const started=await ready,url=new URL(started.databaseUrl);
  if(url.pathname!=='/unicarona_java_test'||!['127.0.0.1','localhost'].includes(url.hostname))throw new Error('Java E2E requires its isolated loopback database');
  pool=new pg.Pool({connectionString:started.databaseUrl,max:5});controls.set(pool,command);active=command;process.env.SEED_PASSWORD=seedPassword;
  return {pool,config:{PIN_ENCRYPTION_KEY:'ab'.repeat(32),PAYMENT_WEBHOOK_SECRET:'Synthetic-reference-payment-only-secret-2026',DEV_MAIL_PATH:mail},close,app:{listen:async(input:{host:string;port:number})=>{if('http://'+input.host+':'+input.port!==origin)throw new Error('E2E origin mismatch');}}};
 }catch(error){await close();throw error;}
}
export async function one(pool:pg.Pool,query:string,args:any[]=[]) {return (await pool.query(query,args)).rows[0]||null;}
function control(pool:pg.Pool,action:string,data?:object) {const command=controls.get(pool);if(!command)throw new Error('Unknown Java E2E pool');return command(action,data);}
function current(action:string,data?:object) {if(!active)throw new Error('Java E2E setup is required');return active(action,data);}
export function processOne(pool:pg.Pool,_config:unknown,failedDelivery?:unknown) {return control(pool,'processOne',{fail:!!failedDelivery});}
export function maintenance(pool:pg.Pool) {return control(pool,'maintenance');}
export function reconciliation(pool:pg.Pool) {return control(pool,'reconciliation');}
export function decrypt(ciphertext:string,_key:string) {return current('decrypt',{ciphertext});}
export function authLink(pool:pg.Pool,email:string,kind:'verify_email'|'reset_password') {return control(pool,'authLink',{email,kind});}
export function paymentFailure(fail:boolean) {return current('paymentFailure',{fail});}
export function notify(pool:pg.Pool,user:string,title:string,body:string,route:string,ref:string|null=null) {return control(pool,'notify',{user,title,body,route,ref});}
export type TransferEvent={eventId:string;transferId:string;reference:string;amountCents:number;status:'approved'|'failed'};
export function transferSignature(_secret:string,timestamp:string,event:TransferEvent) {return current('transferSignature',{timestamp,event});}
export function today() {return new Intl.DateTimeFormat('en-CA',{timeZone:'America/Sao_Paulo',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());}
