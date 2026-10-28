import {readFile,writeFile} from 'node:fs/promises';import pg from 'pg';import {readPrivateEnvironment,resolvePrivateRoot} from './private-environment.mjs';import {join,resolve} from 'node:path';
const env=readPrivateEnvironment(resolve('.'),'sandbox'),privateRoot=resolvePrivateRoot(resolve('.'));
const url=new URL(env.DATABASE_URL);if(!['127.0.0.1','localhost'].includes(url.hostname)||url.pathname!='/unicarona_mercadopago_sandbox')throw new Error('Expected isolated local demonstration database');
const db=new pg.Client({connectionString:env.DATABASE_URL});await db.connect();
try{await db.query('BEGIN READ ONLY');const version=(await db.query("SELECT max(version::int) AS version FROM flyway_schema_history WHERE success")).rows[0].version;
 const oldNoClass=(await db.query("SELECT count(*)::int AS count FROM parcels p JOIN ride_reservations r ON r.id=p.reservation_id WHERE p.status='retained' AND r.availability='no_class'")).rows[0].count;
 const balanced=(await db.query("SELECT coalesce(sum(cents),0)=0 AS balanced FROM ledger_entries")).rows[0].balanced;
 const proof={at:new Date().toISOString(),database:'local-demonstration-sandbox',schemaVersion:version,retainedNoClassParcels:oldNoClass,ledgerBalanced:balanced,readOnly:true};await db.query('COMMIT');await writeFile(privateRoot?join(privateRoot,'logs/privacy-native-runtime-status.json'):'.local/privacy-native-runtime-status.json',JSON.stringify(proof,null,2));console.log(JSON.stringify(proof));
}finally{await db.end();}
