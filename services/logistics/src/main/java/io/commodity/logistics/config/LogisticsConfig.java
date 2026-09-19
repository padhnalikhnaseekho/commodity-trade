package io.commodity.logistics.config;

import io.commodity.platform.error.ProblemAdvice;
import io.commodity.platform.eventing.KafkaMessageSink;
import io.commodity.platform.outbox.OutboxPoller;
import io.commodity.platform.outbox.OutboxPublisher;
import io.commodity.platform.outbox.OutboxWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
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

    @Bean
    @ConditionalOnProperty("commodity.outbox.enabled")
    OutboxPoller logisticsOutboxPoller(JdbcTemplate jdbc, PlatformTransactionManager txManager, KafkaTemplate<String, String> kafka,
                                       @Value("${commodity.outbox.poll-ms:500}") long pollMs,
                                       @Value("${commodity.outbox.batch-size:100}") int batchSize) {
        var publisher = new OutboxPublisher(jdbc, new TransactionTemplate(txManager), SCHEMA, new KafkaMessageSink(kafka), batchSize);
        return new OutboxPoller(publisher, pollMs);
    }
}
