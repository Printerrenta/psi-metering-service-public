import {spawnSync} from 'node:child_process';
import {existsSync,readdirSync} from 'node:fs';
import {join} from 'node:path';
const env={...process.env};
if(!env.JAVA_HOME && process.platform==='win32') {
 const base=join(env.ProgramFiles||'C:/Program Files','JetBrains');
 if(existsSync(base))env.JAVA_HOME=readdirSync(base).sort().reverse().map(n=>join(base,n,'jbr')).find(p=>existsSync(join(p,'bin/java.exe')));
}
function maven(args){const win=process.platform==='win32';const r=spawnSync(win?(env.COMSPEC||'cmd.exe'):'sh',win?['/d','/s','/c','mvnw.cmd -B -ntp '+args.join(' ')]:['./mvnw','-B','-ntp',...args],{env,stdio:'inherit'});if(r.status!==0)process.exit(r.status||1);}
const modes={build:['verify'],test:['test'],integration:['-Pintegration','verify']};
if(!modes[process.argv[2]])throw new Error('Expected build, test or integration');
maven(modes[process.argv[2]]);
