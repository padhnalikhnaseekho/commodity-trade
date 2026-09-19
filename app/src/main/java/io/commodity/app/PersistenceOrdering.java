package io.commodity.app;

import io.commodity.platform.persistence.SchemaMigration;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes JPA wait for the migrations. Hibernate validates every entity against its table when the EntityManagerFactory starts, so every ACTIVE service's
 * schema must be migrated first. Services that are not active contribute no migration and no entities, so nothing waits for them.
 */
@Configuration
class PersistenceOrdering {

    @Bean
    static BeanFactoryPostProcessor entityManagerFactoryWaitsForSchemaMigrations() {
        return new EntityManagerFactoryDependsOnPostProcessor(SchemaMigration.class);
    }
}
