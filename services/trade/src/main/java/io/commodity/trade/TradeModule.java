package io.commodity.trade;

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
 * The single entry point of this service inside a shared JVM, active only under the profile {@code trade}.
 *
 * <p>Trade service: trade contracts and quota derivation. Owns schema `trade`.
 *
 * <p>WHY it exists: the app module runs every service in one JVM for the demo, but each service must stay independently deployable. The app scans
 * NOTHING from the services; it only activates profiles. Under this profile this class brings in the whole service (its controllers, services,
 * listeners, workers and repositories) and migrates only its own schema. Without the profile nothing of this service exists in the process: no beans,
 * no endpoints, no consumers, no tables. Splitting the service out later is therefore a change of active profile and base URLs, not a rewrite.
 *
 * <p>(Service modules' own tests do not use this class: they run as plain Spring Boot test applications scanning their own package.)
 */
@Configuration
@Profile("trade")
@ComponentScan(basePackages = "io.commodity.trade",
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = TradeModule.class))
@EnableJpaRepositories("io.commodity.trade.repository")
@EntityScan("io.commodity.trade.domain")
public class TradeModule {

    /** Migrates schema trade: the only schema this service touches. */
    @Bean
    SchemaMigration tradeMigration(DataSource dataSource) {
        return new SchemaMigration(dataSource, "trade", "classpath:db/migration/trade");
    }
}
