package com.unicarona.support;

import com.unicarona.privacy.service.PrivacyOperationFiles;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Fresh native PG18 clusters only. Never connects to, resets or stops the existing local servers. */
public final class IsolatedPitrPostgres implements AutoCloseable {
    private final Path root,tools,source,restored,wrapper;private final int sourcePort,restorePort;private final String password;private final boolean windows=System.getProperty("os.name").startsWith("Windows");
    private boolean sourceStarted,restoreStarted;
    public IsolatedPitrPostgres(Path root)throws Exception {
        this.root=root.toAbsolutePath().normalize();PrivacyOperationFiles.ordinary(this.root);
        Path allowed=Path.of(System.getProperty("java.io.tmpdir")).toRealPath();if(!this.root.startsWith(allowed)||this.root.equals(allowed))throw new IOException("PITR rehearsal requires its disposable test directory.");
        try(var listing=Files.list(this.root)){if(listing.findAny().isPresent())throw new IOException("PITR rehearsal directory must be empty.");}
        String configured=System.getenv("TEST_PG_TOOLS_PATH");if(configured==null||configured.isBlank())throw new IOException("Native PostgreSQL tools are required for PITR rehearsal; no test is silently skipped.");tools=Path.of(configured).toAbsolutePath().normalize();PrivacyOperationFiles.ordinary(tools);
        for(String name:List.of("initdb","pg_ctl","pg_basebackup","pg_verifybackup"))if(!Files.isRegularFile(executable(name)))throw new IOException("Required native PostgreSQL tool is unavailable.");
        source=this.root.resolve("source");restored=this.root.resolve("restored");sourcePort=port();int other=port();while(other==sourcePort)other=port();restorePort=other;
        byte[] entropy=new byte[24];new SecureRandom().nextBytes(entropy);password=HexFormat.of().formatHex(entropy);Path pass=this.root.resolve("initial-password.private");Files.writeString(pass,password);
        try{run("initdb",List.of("--pgdata="+source,"--username=recovery_test","--auth-local=trust","--auth-host=scram-sha-256","--pwfile="+pass,"--encoding=UTF8","--no-locale"),Map.of(),60);}finally{Files.deleteIfExists(pass);}
        Files.writeString(this.root.resolve("scope"),"unicarona-pitr-synthetic-v1");
        Files.writeString(source.resolve("postgresql.auto.conf"),"\nlisten_addresses='127.0.0.1'\nport="+sourcePort+"\nwal_level='replica'\narchive_mode='on'\narchive_command=''\nmax_wal_senders=4\nfsync=on\nfull_page_writes=on\n",StandardOpenOption.APPEND);
        wrapper=this.root.resolve(windows?"pitr-command.ps1":"pitr-command.sh");Path java=Path.of(System.getProperty("java.home"),"bin",windows?"java.exe":"java");Path jar=Path.of("target/unicarona-backend-1.0.0-SNAPSHOT.jar").toAbsolutePath().normalize();if(!Files.isRegularFile(jar))throw new IOException("Packaged development jar is required for PITR command rehearsal.");
        if(windows)Files.writeString(wrapper,"param([string]$Action,[string]$Target,[string]$Name)\n& "+ps(java.toString())+" '-Dloader.main=com.unicarona.privacy.ops.PitrArchiveCommand' '-cp' "+ps(jar.toString())+" 'org.springframework.boot.loader.launch.PropertiesLauncher' $Action $Target $Name\nexit $LASTEXITCODE\n");
        else Files.writeString(wrapper,"#!/bin/sh\nexec "+sh(java.toString())+" '-Dloader.main=com.unicarona.privacy.ops.PitrArchiveCommand' -cp "+sh(jar.toString())+" org.springframework.boot.loader.launch.PropertiesLauncher \"$@\"\n");
    }
    private static String ps(String value){return "'"+value.replace("'","''")+"'";}
    private static String sh(String value){return "'"+value.replace("'","'\\''")+"'";}
    private Path executable(String name){return tools.resolve(name+(windows?".exe":""));}
    private static int port()throws IOException{try(var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){int port=socket.getLocalPort();if(Set.of(5432,55432,55433).contains(port))return port();return port;}}
    private String command(String action){
        if(windows){String powershell=Path.of(System.getenv("SystemRoot"),"System32/WindowsPowerShell/v1.0/powershell.exe").toString().replace('\\','/');if(powershell.contains(" "))throw new IllegalStateException("Unexpected Windows system directory.");return powershell+" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \""+wrapper.toString().replace('\\','/')+"\" "+action+" \"%p\" \"%f\"";}
        return "sh "+sh(wrapper.toString())+" "+action+" \"%p\" \"%f\"";
    }
    private String run(String name,List<String> args,Map<String,String> extra,int seconds)throws Exception {
        var command=new ArrayList<String>();command.add(executable(name).toString());command.addAll(args);Path output=root.resolve("tool-"+name+"-"+UUID.randomUUID()+".private.log");
        var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());builder.environment().clear();
        for(String key:List.of("SystemRoot","WINDIR","TEMP","TMP","PATH")){String value=System.getenv(key);if(value!=null)builder.environment().put(key,value);}builder.environment().put("PGPASSWORD",password);builder.environment().put("PGCONNECT_TIMEOUT","5");builder.environment().put("LC_ALL","C");builder.environment().putAll(extra);
        var process=builder.start();if(!process.waitFor(seconds,TimeUnit.SECONDS)){process.destroy();process.waitFor(5,TimeUnit.SECONDS);throw new IOException("Native PITR tool timed out: "+name);}
        String text=Files.readString(output).replace(password,"[credential]");if(process.exitValue()!=0)throw new IOException("Native PITR tool failed: "+name+". "+text.substring(Math.max(0,text.length()-1400)));return text;
    }
    public DriverManagerDataSource dataSource(boolean recovery){return new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:"+(recovery?restorePort:sourcePort)+"/unicarona_java_test?connectTimeout=2&socketTimeout=5","recovery_test",password);}
    public JdbcTemplate sql(boolean recovery){return new JdbcTemplate(dataSource(recovery));}
    public String url(boolean recovery){return "postgresql://recovery_test:"+password+"@127.0.0.1:"+(recovery?restorePort:sourcePort)+"/unicarona_java_test";}
    public void startSource(Map<String,String> environment)throws Exception {assertOwned(source,sourcePort,false);run("pg_ctl",List.of("-D",source.toString(),"-l",root.resolve("source.private.log").toString(),"-w","-t","45","start"),environment,55);sourceStarted=true;}
    public void createDatabase(){new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:"+sourcePort+"/postgres","recovery_test",password)).execute("CREATE DATABASE unicarona_java_test");}
    public void stopSource()throws Exception{if(!sourceStarted)return;assertOwned(source,sourcePort,true);run("pg_ctl",List.of("-D",source.toString(),"-m","fast","-w","-t","30","stop"),Map.of(),40);sourceStarted=false;}
    public void configureSourceArchive()throws Exception{Files.writeString(source.resolve("postgresql.auto.conf"),"\narchive_command='"+command("seal").replace("'","''")+"'\n",StandardOpenOption.APPEND);}
    public void baseBackup(Path destination)throws Exception {assertDestination(destination);run("pg_basebackup",List.of("--host=127.0.0.1","--port="+sourcePort,"--username=recovery_test","--no-password","--pgdata="+destination,"--format=plain","--wal-method=stream","--checkpoint=fast","--label=unicarona-synthetic-"+UUID.randomUUID()),Map.of(),60);verifyBase(destination);}
    public void verifyBase(Path directory)throws Exception {assertDestination(directory);run("pg_verifybackup",List.of("--exit-on-error",directory.toString()),Map.of(),45);}
    public Path restored(){return restored;}
    public Path source(){return source;}
    public void stopRestore()throws Exception {if(Files.exists(restored.resolve("postmaster.pid"))){assertOwned(restored,restorePort,true);run("pg_ctl",List.of("-D",restored.toString(),"-m","fast","-w","-t","30","stop"),Map.of(),40);}restoreStarted=false;}
    public void removeStoppedRestore()throws IOException {assertOwned(restored,restorePort,false);try(var paths=Files.walk(restored)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList()){PrivacyOperationFiles.ordinary(path);Files.delete(path);}}}
    public void startRestore(String target,String wal,Map<String,String> environment)throws Exception {
        assertOwned(restored,restorePort,false);if(!target.matches("unicarona-[a-f0-9-]{36}")||!wal.matches("[0-9A-F]{24}"))throw new IOException("Invalid synthetic recovery target.");
        verifyBase(restored);Files.writeString(restored.resolve("postgresql.auto.conf"),"\nlisten_addresses='127.0.0.1'\nport="+restorePort+"\narchive_mode='off'\nhot_standby=off\nrestore_command='"+command("fetch").replace("'","''")+"'\nrecovery_target_name='"+target+"'\nrecovery_target_timeline='"+Long.parseUnsignedLong(wal.substring(0,8),16)+"'\nrecovery_target_action='promote'\n",StandardOpenOption.APPEND);Files.writeString(restored.resolve("recovery.signal"),"");
        run("pg_ctl",List.of("-D",restored.toString(),"-l",root.resolve("restore.private.log").toString(),"-w","-t","45","start"),environment,55);restoreStarted=true;
        // pg_ctl can observe the running recovery process before SQL is available.
        // Readiness requires actual promotion at the configured target, not merely a live PID.
        long until=System.nanoTime()+Duration.ofSeconds(45).toNanos();
        while(System.nanoTime()<until){
            if(!Files.exists(restored.resolve("postmaster.pid")))throw new IOException("Native replay stopped before readiness. "+restoreDiagnostics());
            try{if(Boolean.TRUE.equals(sql(true).queryForObject("SELECT NOT pg_is_in_recovery()",Boolean.class)))return;}catch(org.springframework.dao.DataAccessException recovering){}
            Thread.sleep(100);
        }
        throw new IOException("Native replay did not reach its recovery target. "+restoreDiagnostics());
    }
    public String restoreDiagnostics()throws IOException {Path file=root.resolve("restore.private.log");if(!Files.exists(file))return "No restore log.";String text=Files.readString(file).replace(password,"[credential]");return text.substring(Math.max(0,text.length()-3500));}
    private void assertDestination(Path destination)throws IOException {Path actual=destination.toAbsolutePath().normalize();if(!actual.startsWith(root)||actual.equals(root))throw new IOException("PITR test destination escaped its disposable root.");if(Files.exists(actual,LinkOption.NOFOLLOW_LINKS))PrivacyOperationFiles.ordinary(actual);else PrivacyOperationFiles.ordinary(actual.getParent());}
    private void assertOwned(Path data,int port,boolean running)throws IOException {
        assertDestination(data);if(!Files.readString(data.resolve("PG_VERSION")).strip().equals("18")||!Files.readString(root.resolve("scope")).equals("unicarona-pitr-synthetic-v1"))throw new IOException("PITR scope marker does not match.");
        Path pid=data.resolve("postmaster.pid");if(running){var lines=Files.readAllLines(pid);if(lines.size()<4||!lines.get(3).strip().equals(Integer.toString(port)))throw new IOException("PITR test refuses an unrelated PostgreSQL process.");}
        else if(Files.exists(pid))throw new IOException("PITR test data directory is already running.");
    }
    public void removeOwnedBase(Path base)throws IOException {assertDestination(base);if(!Files.isRegularFile(base.resolve("backup_manifest"))||Files.exists(base.resolve("postmaster.pid")))throw new IOException("Refusing to retire unexpected physical staging.");try(var paths=Files.walk(base)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList()){PrivacyOperationFiles.ordinary(path);Files.delete(path);}}}
    @Override public void close()throws Exception {
        if(Files.exists(restored.resolve("postmaster.pid"))){assertOwned(restored,restorePort,true);run("pg_ctl",List.of("-D",restored.toString(),"-m","fast","-w","-t","30","stop"),Map.of(),40);restoreStarted=false;}
        if(Files.exists(source.resolve("postmaster.pid"))){assertOwned(source,sourcePort,true);run("pg_ctl",List.of("-D",source.toString(),"-m","fast","-w","-t","30","stop"),Map.of(),40);sourceStarted=false;}
    }
}
