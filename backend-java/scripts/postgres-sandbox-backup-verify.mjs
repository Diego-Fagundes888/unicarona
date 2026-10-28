/** Restore rehearsal for the isolated local payment sandbox. Never prints rows or credentials. */
import {spawn} from 'node:child_process';
import {createHash,randomUUID} from 'node:crypto';
import {mkdir,readFile,writeFile,stat} from 'node:fs/promises';
import {join,resolve,sep} from 'node:path';
import {fileURLToPath} from 'node:url';
import pg from 'pg';
import {readPrivateEnvironment,resolvePrivateRoot} from './private-environment.mjs';

const root=fileURLToPath(new URL('../../',import.meta.url));
const protectedRoot=resolvePrivateRoot(root),privateRoot=protectedRoot||resolve(root,'.local');
const environment=readPrivateEnvironment(root,'sandbox');
const operational=readPrivateEnvironment(root,'app');
const uri=new URL(environment.DATABASE_URL);
if(environment.MERCADO_PAGO_ENVIRONMENT!=='test' || environment.PAYMENT_PROVIDER!=='mercadopago'
  || !['127.0.0.1','localhost'].includes(uri.hostname) || uri.pathname!=='/unicarona_mercadopago_sandbox')
  throw new Error('Restore rehearsal requires the dedicated loopback Mercado Pago test sandbox.');
const bin=process.env.POSTGRES_BIN || (protectedRoot?join(protectedRoot,'runtime/postgres/bin'):join(process.env.USERPROFILE || '', '.cache/unicarona-postgres/runtime/pgsql/bin'));
const extension=process.platform==='win32'?'.exe':'';
for(const name of ['pg_dump','pg_restore','createdb','dropdb'])await stat(join(bin,name+extension));
const copyName='unicarona_restore_check_'+randomUUID().replaceAll('-','').slice(0,12);
async function copyOperation(action,options={}){
  if(!protectedRoot||!operational.PRIVACY_OPERATIONS_ROOT)throw new Error('Initialize the protected retained-copy catalog before creating this backup.');
  return await new Promise((accept,reject)=>{
    const executable=join(process.env.SystemRoot||'C:/Windows','System32/WindowsPowerShell/v1.0/powershell.exe');
    const args=['-NoProfile','-ExecutionPolicy','Bypass','-File',join(protectedRoot,'runtime/scripts/private-copies-command.ps1'),'-Action',action];
    for(const [key,value] of Object.entries(options))args.push('-'+key,String(value));
    const child=spawn(executable,args,{windowsHide:true,stdio:['ignore','pipe','pipe']});let output='';
    child.stdout.on('data',data=>{output+=data;if(output.length>100000){child.kill();reject(new Error('Private-copy command output exceeded its limit.'));}});
    child.stderr.on('data',()=>{});child.on('error',()=>reject(new Error('Private-copy command could not start.')));
    child.on('close',code=>{try{const result=JSON.parse(output.replace(/^\uFEFF/,''));if(code!==0||result.ok!==true)throw new Error();accept(result);}catch{reject(new Error('Private-copy operation failed; preserve the pending resource for operator review.'));}});
  });
}
let reservation;
if(protectedRoot)reservation=await copyOperation('reserve',{ResourceName:randomUUID()});
const directory=reservation?resolve(reservation.directory):resolve(privateRoot,'mp-backup-proof',new Date().toISOString().replace(/[:.]/g,'-')+'_'+copyName.slice(-12));
if(!directory.startsWith(privateRoot+sep))throw new Error('Backup must remain inside the ignored private directory.');
if(reservation&&directory.toLowerCase()!==resolve(privateRoot,'backups',reservation.resourceName).toLowerCase())throw new Error('Unexpected protected backup destination.');
await mkdir(directory,{recursive:true});
const dump=join(directory,'sandbox.dump');
const childEnvironment={...process.env,PGHOST:uri.hostname,PGPORT:uri.port || '5432',PGUSER:decodeURIComponent(uri.username),
  PGPASSWORD:decodeURIComponent(uri.password),PGDATABASE:'postgres',PGCONNECT_TIMEOUT:'5'};
for(const name of ['PGSERVICE','PGSERVICEFILE','PGOPTIONS','PGHOSTADDR'])delete childEnvironment[name];
// The application role can dump its database; only the local operator creates/removes the rehearsal copy.
const operatorEnvironment={...childEnvironment,PGUSER:'postgres',PGPASSWORD:(await readFile(join(privateRoot,protectedRoot?'config/pg-operator.pass':'postgres-password'),'utf8')).trim()};
const sha=value=>createHash('sha256').update(value).digest('hex');
const quote=value=>'"'+value.replaceAll('"','""')+'"';
const source=new pg.Client({connectionString:uri.toString(),application_name:'unicarona-backup-proof'});
const restoredUri=new URL(uri);restoredUri.pathname='/'+copyName;
let restored,created=false;
const result={startedAt:new Date().toISOString(),sourceDatabase:uri.pathname.slice(1),restoreDatabase:copyName,
  snapshotConsistent:true,success:false,backupPath:dump,restoredDatabaseRemoved:false,catalogReserved:!!reservation,catalogFinalized:false};
async function command(name,args,operator=false) {
  return await new Promise((resolveCommand,reject)=>{
    const child=spawn(join(bin,name+extension),args,{env:operator?operatorEnvironment:childEnvironment,windowsHide:true,stdio:['ignore','pipe','pipe']});
    const chunks=[];let size=0;
    child.stdout.on('data',chunk=>{size+=chunk.length;if(size>10000000){child.kill();reject(new Error(name+' output exceeded rehearsal limit.'));}else chunks.push(chunk);});
    // Provider data/passwords must never enter command output or reports.
    child.stderr.on('data',()=>{});
    child.on('error',()=>reject(new Error(name+' could not start.')));
    child.on('close',code=>code===0?resolveCommand(Buffer.concat(chunks)):reject(new Error(name+' failed (exit '+code+').')));
  });
}
async function fingerprint(client) {
  await client.query("SET TIME ZONE 'UTC'");
  const tables=(await client.query("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")).rows;
  const fingerprints=[];
  for(const {tablename} of tables) {
    const rows=(await client.query('SELECT to_jsonb(t)::text AS value FROM public.'+quote(tablename)+' t ORDER BY to_jsonb(t)::text COLLATE "C"')).rows;
    const hash=createHash('sha256');for(const row of rows)hash.update(row.value).update('\n');
    fingerprints.push({table:tablename,rows:rows.length,sha256:hash.digest('hex')});
  }
  return fingerprints;
}
const normalizedSchema=bytes=>bytes.toString('utf8').split(/\r?\n/).filter(line=>!/^\\(?:un)?restrict /.test(line)).join('\n');
try {
  await source.connect();
  await source.query('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY');
  await source.query("SET statement_timeout='30s'");
  const restorePoint=(await source.query('SELECT transaction_timestamp() AS captured_at')).rows[0].captured_at.toISOString();
  const snapshot=(await source.query('SELECT pg_export_snapshot() AS snapshot')).rows[0].snapshot;
  await command('pg_dump',['--format=custom','--no-owner','--no-acl','--snapshot='+snapshot,'--file='+dump,uri.pathname.slice(1)]);
  const expected=await fingerprint(source);
  const expectedSchema=normalizedSchema(await command('pg_dump',['--schema-only','--no-owner','--no-acl','--snapshot='+snapshot,uri.pathname.slice(1)]));
  await command('createdb',['--maintenance-db=postgres','--template=template0','--owner='+childEnvironment.PGUSER,copyName],true);created=true;
  await command('pg_restore',['--exit-on-error','--no-owner','--no-acl','--role='+childEnvironment.PGUSER,'--dbname='+copyName,dump],true);
  restored=new pg.Client({connectionString:restoredUri.toString(),application_name:'unicarona-restore-proof'});await restored.connect();
  const actual=await fingerprint(restored);
  const actualSchema=normalizedSchema(await command('pg_dump',['--schema-only','--no-owner','--no-acl',copyName]));
  result.tables=expected;result.tablesMatch=JSON.stringify(expected)===JSON.stringify(actual);
  result.schemaSha256=sha(expectedSchema);result.schemaMatches=expectedSchema===actualSchema;
  result.dumpSha256=sha(await readFile(dump));result.dumpBytes=(await stat(dump)).size;
  result.migrationVersion=(await restored.query('SELECT max(version::integer) AS version FROM flyway_schema_history WHERE success')).rows[0].version;
  result.ledgerDifference=(await restored.query('SELECT coalesce(sum(cents),0)::text AS difference FROM ledger_entries')).rows[0].difference;
  if(!result.tablesMatch || !result.schemaMatches)throw new Error('Restored schema/data differ from the exported source snapshot.');
  if(reservation){await copyOperation('finalize',{RecordId:reservation.recordId,RestorePoint:restorePoint});result.catalogFinalized=true;}
  result.success=true;
} catch(error) {
  result.error=error.message.startsWith('Restored ') || /^(pg_dump|pg_restore|createdb|dropdb) /.test(error.message)
    ?error.message:'Backup/restore rehearsal failed; inspect configuration without exposing private data.';
  process.exitCode=1;
} finally {
  await restored?.end();
  await source.query('ROLLBACK').catch(()=>{});await source.end().catch(()=>{});
  if(created) {
    // This invocation owns only the random database it successfully created above.
    if(!/^unicarona_restore_check_[a-f0-9]{12}$/.test(copyName) || copyName===uri.pathname.slice(1))throw new Error('Unsafe restore cleanup target.');
    try{await command('dropdb',['--maintenance-db=postgres',copyName],true);result.restoredDatabaseRemoved=true;}
    catch{result.cleanupError='The isolated restore database could not be removed.';process.exitCode=1;}
  }
  result.finishedAt=new Date().toISOString();
  await writeFile(join(directory,'verification.private.json'),JSON.stringify(result,null,2));
  console.log(JSON.stringify({...result,tables:undefined,tableCount:result.tables?.length,totalRows:result.tables?.reduce((n,t)=>n+t.rows,0),reportPath:join(directory,'verification.private.json')}));
}
