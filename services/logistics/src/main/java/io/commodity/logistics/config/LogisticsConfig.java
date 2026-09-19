package io.commodity.logistics.config;

import io.commodity.contracts.lookup.FixationDirectory;
import io.commodity.contracts.lookup.QuotaDirectory;
import io.commodity.platform.error.ProblemAdvice;
import io.commodity.platform.eventing.KafkaMessageSink;
import io.commodity.platform.lookup.HttpFixationDirectory;
import io.commodity.platform.lookup.HttpQuotaDirectory;
import io.commodity.platform.outbox.OutboxPoller;
import io.commodity.platform.outbox.OutboxPublisher;
import io.commodity.platform.outbox.OutboxWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for logistics: the outbox writer (always), the outbox publisher (only when enabled) and shared error rendering.
 *
 * <p>The publisher is opt-in ({@code commodity.outbox.enabled=true}) so a service context starts without a Kafka broker
 * (tests, tooling). Writing the outbox row never needs Kafka; only shipping it does.
 */
@Configuration
@Import(ProblemAdvice.class)
class LogisticsConfig {

    private static final String SCHEMA = "logistics";

    @Bean
    OutboxWriter logisticsOutboxWriter(JdbcTemplate jdbc) {
        return new OutboxWriter(jdbc, SCHEMA);
    }

    /** Quotas come from the trade service over HTTP. Active only when its base URL is configured. */
    /**
     * The quota lookup adapter is identical in every service that needs it, so when several services share one JVM the first definition wins
     * (ConditionalOnMissingBean) instead of registering the same bean three times.
     */
    @Bean
    @ConditionalOnMissingBean(QuotaDirectory.class)
    @ConditionalOnProperty("commodity.trade.base-url")
    QuotaDirectory quotaDirectory(RestClient.Builder builder, @Value("${commodity.trade.base-url}") String baseUrl) {
        return new HttpQuotaDirectory(builder, baseUrl);
    }

    /** Fixation state comes from the pricing service over HTTP. Active only when its base URL is configured. */
    @Bean
    @ConditionalOnProperty("commodity.pricing.base-url")
    FixationDirectory fixationDirectory(RestClient.Builder builder, @Value("${commodity.pricing.base-url}") String baseUrl) {
        return new HttpFixationDirectory(builder, baseUrl);
    }

    @Bean
    @ConditionalOnProperty("commodity.outbox.enabled")
    OutboxPoller logisticsOutboxPoller(JdbcTemplate jdbc, PlatformTransactionManager txManager, KafkaTemplate<String, String> kafka,
                                       @Value("${commodity.outbox.poll-ms:500}") long pollMs,
                                       @Value("${commodity.outbox.batch-size:100}") int batchSize) {
        var publisher = new OutboxPublisher(jdbc, new TransactionTemplate(txManager), SCHEMA, new KafkaMessageSink(kafka), batchSize);
        return new OutboxPoller(publisher, pollMs);
    }
}
