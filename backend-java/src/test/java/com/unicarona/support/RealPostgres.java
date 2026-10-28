package com.unicarona.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.testcontainers.containers.PostgreSQLContainer;

/** PostgreSQL only. Local mode is explicit; missing Docker never silently skips tests. */
public final class RealPostgres implements AutoCloseable {
    private final PostgreSQLContainer<?> container;
    private final HikariDataSource dataSource;
    private final String apiConnectionUrl;
    public RealPostgres() {
        String local = System.getenv("TEST_JAVA_DATABASE_URL");
        HikariConfig config = new HikariConfig();
        if (local != null && !local.isBlank()) {
            URI uri = URI.create(local);
            if (!"/unicarona_java_test".equals(uri.getPath()) || !java.util.Set.of("localhost", "127.0.0.1").contains(uri.getHost())) {
                throw new IllegalStateException("Java tests require the dedicated local unicarona_java_test database");
            }
            String[] credentials = uri.getRawUserInfo().split(":", 2);
            config.setJdbcUrl("jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath());
            config.setUsername(URLDecoder.decode(credentials[0], StandardCharsets.UTF_8));
            config.setPassword(URLDecoder.decode(credentials[1], StandardCharsets.UTF_8));
            container = null; apiConnectionUrl = local;
        } else {
            container = new PostgreSQLContainer<>("postgres:18-bookworm").withDatabaseName("unicarona_java_test");
            container.start();
            config.setJdbcUrl(container.getJdbcUrl()); config.setUsername(container.getUsername()); config.setPassword(container.getPassword());
            apiConnectionUrl = "postgresql://" + container.getUsername() + ":" + container.getPassword() + "@" + container.getHost() + ":" + container.getMappedPort(5432) + "/unicarona_java_test";
        }
        config.setMaximumPoolSize(5); config.setMinimumIdle(0); config.setConnectionTimeout(3000);
        dataSource = new HikariDataSource(config);
    }
    public HikariDataSource dataSource() { return dataSource; }
    public String apiConnectionUrl() { return apiConnectionUrl; }
    public void resetSchema() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT current_database()")) {
                result.next();
                if (!"unicarona_java_test".equals(result.getString(1))) throw new IllegalStateException("Refusing test reset outside Java test database");
            }
            statement.execute("DROP SCHEMA public CASCADE"); statement.execute("CREATE SCHEMA public");
        }
    }
    /** Actual PostgreSQL archive tools, restricted to this disposable test database. No credential CLI arguments. */
    public void dumpDatabaseArchive(Path archive)throws Exception {physicalTool("pg_dump",archive,false);}
    public void restoreDatabaseArchive(Path archive)throws Exception {physicalTool("pg_restore",archive,true);}
    private void physicalTool(String tool,Path archive,boolean restore)throws Exception {
        try(var connection=dataSource.getConnection();var statement=connection.createStatement();var result=statement.executeQuery("SELECT current_database()")){
            result.next();if(!"unicarona_java_test".equals(result.getString(1)))throw new IllegalStateException("Refusing physical test operations outside the disposable Java database");
        }
        var options=new ArrayList<String>();
        if(restore)options.addAll(List.of("--clean","--if-exists","--exit-on-error","--no-owner","--no-privileges"));
        // Schema filtering would omit extension definitions needed by the route indexes (unaccent/pg_trgm).
        else options.addAll(List.of("--format=custom","--no-owner","--no-privileges"));
        if(container!=null){
            String remote="/tmp/unicarona-test-"+UUID.randomUUID()+".dump";
            try{
                if(restore)container.copyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(archive),remote);
                var command=new ArrayList<String>(List.of(tool));command.addAll(options);command.add("--username="+container.getUsername());command.add("--dbname=unicarona_java_test");
                if(restore)command.add(remote);else command.add("--file="+remote);
                if(container.execInContainer(command.toArray(String[]::new)).getExitCode()!=0)throw new IOException("PostgreSQL physical test tool failed inside its test container");
                if(!restore)container.copyFileFromContainer(remote,archive.toString());
            }finally{container.execInContainer("rm","--",remote);}
            return;
        }
        String tools=System.getenv("TEST_PG_TOOLS_PATH");
        if(tools==null||tools.isBlank())throw new IllegalStateException("Local physical restore tests require TEST_PG_TOOLS_PATH; tests are never silently skipped");
        var executable=Path.of(tools).resolve(tool+(System.getProperty("os.name").startsWith("Windows")?".exe":""));
        if(!Files.isRegularFile(executable))throw new IllegalStateException("Configured PostgreSQL test archive tool is unavailable");
        var uri=URI.create(apiConnectionUrl);String[] credentials=uri.getRawUserInfo().split(":",2);
        var command=new ArrayList<String>(List.of(executable.toString()));command.addAll(options);
        command.addAll(List.of("--host="+uri.getHost(),"--port="+uri.getPort(),"--username="+URLDecoder.decode(credentials[0],StandardCharsets.UTF_8),"--dbname=unicarona_java_test"));
        if(restore)command.add(archive.toString());else command.add("--file="+archive);
        Path output=Files.createTempFile(archive.getParent(),"pg-tool-",".private.log");
        var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().clear();
        for(String key:List.of("SystemRoot","WINDIR","TEMP","TMP","PATH")){String value=System.getenv(key);if(value!=null)builder.environment().put(key,value);}
        builder.environment().put("PGPASSWORD",URLDecoder.decode(credentials[1],StandardCharsets.UTF_8));builder.environment().put("PGCONNECT_TIMEOUT","5");
        try{
            var process=builder.start();
            if(!process.waitFor(45,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);throw new IOException("PostgreSQL physical test tool timed out");}
            if(process.exitValue()!=0){
                String diagnostic=Files.readString(output).replace(URLDecoder.decode(credentials[1],StandardCharsets.UTF_8),"[credential]")
                    .replaceAll("postgres(?:ql)?://\\S+","[connection]").replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}","[email]");
                throw new IOException("PostgreSQL physical test tool failed with exit code "+process.exitValue()+" ("+tool+"): "+diagnostic.substring(0,Math.min(diagnostic.length(),2000)));
            }
        }finally{Files.deleteIfExists(output);}
    }
    @Override public void close() { dataSource.close(); if (container != null) container.stop(); }
}
