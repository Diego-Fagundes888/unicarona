// Local read-only migration proof. Never outputs rows, recipients, credentials or payloads.
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {createHash} from 'node:crypto';
import pg from 'pg';
import {readPrivateEnvironment,resolvePrivateRoot} from './private-environment.mjs';
const root=resolve('.'),privateRoot=resolvePrivateRoot(root)||resolve(process.env.LOCALAPPDATA,'UniCarona/Private');
const mode=process.argv[2]||'status';
if(!['status','before','clone','after'].includes(mode))throw new Error('Unknown migration proof mode.');
const original=readPrivateEnvironment(root,'sandbox'),url=new URL(original.DATABASE_URL);
if(!['127.0.0.1','localhost'].includes(url.hostname)||url.port!=='55432'||url.pathname!=='/unicarona_mercadopago_sandbox')throw new Error('Proof requires the existing isolated local sandbox.');
if(mode==='clone')url.port='55433';
const db=new pg.Client({connectionString:url.href});
const proof={at:new Date().toISOString(),mode,databases:[],readOnly:true};
await db.connect();let names;
try{names=(await db.query("SELECT datname FROM pg_database WHERE datname IN ('unicarona','unicarona_java_test','unicarona_mercadopago_sandbox') ORDER BY datname")).rows.map(r=>r.datname);}finally{await db.end();}
for(const name of names){
 const databaseUrl=new URL(url);databaseUrl.pathname='/'+name;
 const client=new pg.Client({connectionString:databaseUrl.href});await client.connect();
 try{
  await client.query('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY');
  const tables=(await client.query("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")).rows.map(r=>r.tablename);
  const fingerprint=createHash('sha256');let rows=0;
  for(const table of tables){const identifier='"'+table.replaceAll('"','""')+'"';
   const result=(await client.query(`SELECT count(*)::int AS count,md5(coalesce(string_agg(h,'' ORDER BY h),'')) AS hash FROM (SELECT md5(row_to_json(t)::text) AS h FROM public.${identifier} t) r`)).rows[0];
   rows+=result.count;fingerprint.update(table+':'+result.count+':'+result.hash+'\n');
  }
  const version=tables.includes('flyway_schema_history')?(await client.query('SELECT max(version::int) AS version FROM flyway_schema_history WHERE success')).rows[0].version:null;
  const ledger=tables.includes('ledger_entries')?(await client.query('SELECT coalesce(sum(cents),0)=0 AS balanced FROM ledger_entries')).rows[0].balanced:null;
  const inFlight=tables.includes('outbox_jobs')?(await client.query("SELECT count(*)::int AS count FROM outbox_jobs WHERE status='processing'")).rows[0].count:0;
  proof.databases.push({name,tables:tables.length,rows,version,ledgerBalanced:ledger,processingJobs:inFlight,sha256:fingerprint.digest('hex')});
  await client.query('COMMIT');
 }finally{await client.end();}
}
await mkdir(join(privateRoot,'archives/migration'),{recursive:true});
if(['clone','after'].includes(mode)){
 const before=JSON.parse(await readFile(join(privateRoot,'archives/migration/before.json'),'utf8'));
 if(JSON.stringify(before.databases)!==JSON.stringify(proof.databases))throw new Error('Restored database fingerprints differ from the stopped source snapshot.');
 proof.matchesSource=true;
}
if(mode==='before'&&proof.databases.some(d=>d.processingJobs!==0))throw new Error('Cannot take the migration checkpoint while a job is processing.');
await writeFile(join(privateRoot,'archives/migration',mode+'.json'),JSON.stringify(proof,null,2));
console.log(JSON.stringify({mode,databases:proof.databases.map(({name,tables,rows,version,ledgerBalanced,processingJobs})=>({name,tables,rows,version,ledgerBalanced,processingJobs})),matchesSource:proof.matchesSource||false,readOnly:true}));
