import {readFile,writeFile,readdir,stat,mkdir} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {readPrivateEnvironment,resolvePrivateRoot} from './private-environment.mjs';
const secrets=[];
for(const kind of ['app','sandbox','docker']){
 for(const [key,raw] of Object.entries(readPrivateEnvironment(resolve('.'),kind))){
  const value=raw.replace(/^['"]|['"]$/g,'');
  if(/(SECRET|TOKEN|PASSWORD|PEPPER|ENCRYPTION_KEY|SMTP_PASS|API_KEY)$/.test(key)&&value.length>=12)secrets.push(value);
  if(/^PRIVACY_(BACKUP|JOURNAL|EVIDENCE)_KEYS$/.test(key)&&raw){
   let ring;try{ring=JSON.parse(raw);}catch{throw new Error('Invalid private signing key ring while scanning.');}
   for(const secret of Object.values(ring))if(typeof secret==='string'&&secret.length>=32)secrets.push(secret);
  }
 }
}
const files=[];
async function walk(dir,logsOnly=false){for(const entry of await readdir(dir,{withFileTypes:true})){const path=join(dir,entry.name);if(entry.isDirectory()){if(!['node_modules','java-tools','postgres-data','mp-runtime','mail','mp-backup-proof'].includes(entry.name))await walk(path,logsOnly);}else if(logsOnly?/\.(log|stdout|stderr)(\.log)?$/.test(entry.name):/\.(html|css|js|mjs|ts|mts|json|md|java|sql|ps1|properties|yaml|yml|example)$/.test(entry.name))files.push(path);}}
for(const dir of ['dist','docs','config','backend-java/src/main','backend-java/scripts'])await walk(dir);
await walk('.local',true);const privateRoot=resolvePrivateRoot(resolve('.'));if(privateRoot)await walk(join(privateRoot,'logs'),true);files.push('.env.example','backend-java/.env.example');
const findings=[];let checked=0,skippedLarge=0;
for(const path of files){if((await stat(path)).size>20_000_000){skippedLarge++;continue;}const content=await readFile(path,'utf8');checked++;if(secrets.some(secret=>content.includes(secret)))findings.push({path,kind:'configured-secret'});}
const report={at:new Date().toISOString(),checked,skippedLarge,secretFindings:findings.length,findings,scope:'Configured secret values in public source, config templates, documentation, runtime scripts and local operational logs. Private environment, journal/mail/evidence files and binaries excluded.'};
await mkdir('.impeccable/review/privacy',{recursive:true});await writeFile('.impeccable/review/privacy/security-audit.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report));if(findings.length)process.exitCode=1;
