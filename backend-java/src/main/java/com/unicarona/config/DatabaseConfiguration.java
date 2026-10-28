package com.unicarona.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class DatabaseConfiguration {
    @Bean
    DataSource dataSource(Environment environment) {
        String raw = environment.getRequiredProperty("DATABASE_URL");
        URI uri = URI.create(raw);
        if (!"postgresql".equals(uri.getScheme()) && !"postgres".equals(uri.getScheme())) {
            throw new IllegalStateException("DATABASE_URL must use PostgreSQL");
        }
        String[] credentials = uri.getRawUserInfo() == null ? new String[0] : uri.getRawUserInfo().split(":", 2);
        if (credentials.length != 2 || uri.getHost() == null || uri.getPath().length() < 2) {
            throw new IllegalStateException("Invalid DATABASE_URL configuration");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://" + uri.getHost() + ":" + (uri.getPort() < 0 ? 5432 : uri.getPort()) + uri.getRawPath()
            + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        config.setUsername(URLDecoder.decode(credentials[0], StandardCharsets.UTF_8));
        config.setPassword(URLDecoder.decode(credentials[1], StandardCharsets.UTF_8));
        config.setMaximumPoolSize(environment.getProperty("DB_POOL_MAX", Integer.class, 10));
        config.setConnectionTimeout(3000);
        config.setPoolName("unicarona-java");
        config.setConnectionInitSql("SET statement_timeout = " + environment.getProperty("DB_STATEMENT_TIMEOUT_MS", Integer.class, 5000));
        config.addDataSourceProperty("ApplicationName", "unicarona-java");
        return new HikariDataSource(config);
    }
}
