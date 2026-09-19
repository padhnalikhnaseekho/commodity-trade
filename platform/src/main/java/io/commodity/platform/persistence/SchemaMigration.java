package io.commodity.platform.persistence;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;

/**
 * Migrates ONE service's schema from its own migration directory, when the bean is created.
 *
 * <p>WHY this exists instead of Spring Boot's Flyway auto-configuration: Boot runs a single Flyway with a single location. Here every service owns
 * its schema and its migrations (one directory, one schema, one history table each), and in a single JVM only the services whose profile is active
 * should touch the database at all. So each service module declares its own {@code SchemaMigration}, and a service that is not running never migrates
 * (nor even creates) its schema.
 *
 * <p>Ordering: the JPA EntityManagerFactory must not start (and validate its mappings) before every active service's migration has run. The app's
 * persistence configuration makes it depend on all beans of this type.
 */
public class SchemaMigration implements InitializingBean {

    private final DataSource dataSource;
    private final String schema;
    private final String location;

    /** @param location a Flyway location such as {@code classpath:db/migration/pricing} */
    public SchemaMigration(DataSource dataSource, String schema, String location) {
        this.dataSource = dataSource;
        this.schema = schema;
        this.location = location;
    }

    @Override
    public void afterPropertiesSet() {
        // schemas(...) makes the first (only) schema the default, which is where this service's flyway_schema_history table lives.
        Flyway.configure().dataSource(dataSource).schemas(schema).locations(location).load().migrate();
    }

    public String schema() {
        return schema;
    }
}
