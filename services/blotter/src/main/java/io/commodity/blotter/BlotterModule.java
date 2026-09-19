package io.commodity.blotter;

import io.commodity.platform.persistence.SchemaMigration;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Profile;

/**
 * The single entry point of the blotter service inside a shared JVM, active only under the profile {@code blotter}.
 *
 * <p>Blotter service: a disposable read model (rows and valuations) built from pricing's quota snapshots and the gateway's valuation results, with an as-of
 * query and a live change stream. Owns schema {@code blotter}. See the other modules' entry points for why each service sits behind its own profile.
 */
@Configuration
@Profile("blotter")
@ComponentScan(basePackages = "io.commodity.blotter",
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = BlotterModule.class))
public class BlotterModule {

    /** Migrates schema blotter: the only schema this service touches. */
    @Bean
    SchemaMigration blotterMigration(DataSource dataSource) {
        return new SchemaMigration(dataSource, "blotter", "classpath:db/migration/blotter");
    }
}
