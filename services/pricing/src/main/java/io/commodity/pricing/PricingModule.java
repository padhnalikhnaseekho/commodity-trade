package io.commodity.pricing;

import io.commodity.platform.persistence.SchemaMigration;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Profile;

/**
 * The single entry point of this service inside a shared JVM, active only under the profile {@code pricing}.
 *
 * <p>Pricing service: pricing revisions with structural sharing, price fixation, approval, replay, consumes QAG revisions. Owns schema `pricing`.
 *
 * <p>WHY it exists: the app module runs every service in one JVM for the demo, but each service must stay independently deployable. The app scans
 * NOTHING from the services; it only activates profiles. Under this profile this class brings in the whole service (its controllers, services,
 * listeners, workers and repositories) and migrates only its own schema. Without the profile nothing of this service exists in the process: no beans,
 * no endpoints, no consumers, no tables. Splitting the service out later is therefore a change of active profile and base URLs, not a rewrite.
 *
 * <p>(Service modules' own tests do not use this class: they run as plain Spring Boot test applications scanning their own package.)
 */
@Configuration
@Profile("pricing")
@ComponentScan(basePackages = "io.commodity.pricing",
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = PricingModule.class))
@EnableJpaRepositories("io.commodity.pricing.repository")
@EntityScan("io.commodity.pricing.domain")
public class PricingModule {

    /** Migrates schema pricing: the only schema this service touches. */
    @Bean
    SchemaMigration pricingMigration(DataSource dataSource) {
        return new SchemaMigration(dataSource, "pricing", "classpath:db/migration/pricing");
    }
}
