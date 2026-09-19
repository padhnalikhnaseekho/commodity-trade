package io.commodity.pricing.config;

import io.commodity.contracts.lookup.QuotaDirectory;
import io.commodity.contracts.valuation.ValuationSubmitter;
import io.commodity.platform.error.ProblemAdvice;
import io.commodity.platform.eventing.DedupStore;
import io.commodity.platform.eventing.DlqErrorHandler;
import io.commodity.platform.eventing.PeriodicTask;
import io.commodity.platform.lookup.HttpQuotaDirectory;
import io.commodity.platform.lookup.HttpValuationSubmitter;
import io.commodity.pricing.repository.RevisionStore;
import io.commodity.pricing.service.CopyAllRevisionWriter;
import io.commodity.pricing.service.RevisionWriter;
import io.commodity.pricing.service.StructuralSharingRevisionWriter;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.web.client.RestClient;

/**
 * Wiring for pricing: the revision strategy switch, dedup, quota lookup, and the consumer's retry and dead-letter policy.
 *
 * <p>The strategy switch is a config value, not code: {@code commodity.pricing.revision-strategy} = {@code structural-sharing}
 * (default) or {@code copy-all}. Both writers implement one interface and readers cannot tell them apart, so the optimisation can
 * be turned off in production without a release if it ever misbehaves.
 */
@Configuration
@Import(ProblemAdvice.class)
class PricingConfig {

    @Bean
    RevisionStore revisionStore(JdbcTemplate jdbc) {
        return new RevisionStore(jdbc);
    }

    @Bean
    @ConditionalOnProperty(name = "commodity.pricing.revision-strategy", havingValue = "structural-sharing", matchIfMissing = true)
    RevisionWriter structuralSharingWriter(RevisionStore store) {
        return new StructuralSharingRevisionWriter(store);
    }

    @Bean
    @ConditionalOnProperty(name = "commodity.pricing.revision-strategy", havingValue = "copy-all")
    RevisionWriter copyAllWriter(RevisionStore store) {
        return new CopyAllRevisionWriter(store);
    }

    @Bean
    DedupStore pricingDedupStore(JdbcTemplate jdbc) {
        return new DedupStore(jdbc, "pricing");
    }

    /** Quotas come from the trade service over HTTP. Active only when its base URL is configured. */
    @Bean
    @ConditionalOnProperty("commodity.trade.base-url")
    QuotaDirectory quotaDirectory(RestClient.Builder builder, @Value("${commodity.trade.base-url}") String baseUrl) {
        return new HttpQuotaDirectory(builder, baseUrl);
    }

    /** The valuation gateway over HTTP, used by replay. Active only when its base URL is configured. */
    @Bean
    @ConditionalOnProperty("commodity.gateway.base-url")
    ValuationSubmitter valuationSubmitter(RestClient.Builder builder, @Value("${commodity.gateway.base-url}") String baseUrl) {
        return new HttpValuationSubmitter(builder, baseUrl);
    }

    /**
     * Consumer failure policy: retry a failing record three times, then publish it to {@code <topic>.dlq} and continue.
     * WHY: a bad record must not block its partition (everything behind it would stall). The DLQ keeps it for inspection and replay.
     * TARGET: non-blocking retries on delay topics (5s, 30s, 5m) so even the retries do not hold the partition.
     */
    @Bean
    @ConditionalOnProperty("commodity.pricing.consumer.enabled")
    DefaultErrorHandler pricingErrorHandler(KafkaTemplate<?, ?> kafka, @Value("${commodity.pricing.consumer.retry-interval-ms:1000}") long intervalMs) {
        return DlqErrorHandler.create(kafka, intervalMs, 3);
    }

    /** Trims dedup markers older than the TTL. The TTL must comfortably exceed the broker's retention-plus-retry window. */
    @Bean
    @ConditionalOnProperty("commodity.pricing.consumer.enabled")
    PeriodicTask pricingDedupSweeper(DedupStore dedup, @Value("${commodity.pricing.consumer.dedup-ttl-hours:168}") long ttlHours) {
        return new PeriodicTask("pricing-dedup-sweeper", Duration.ofMinutes(10).toMillis(), () -> dedup.sweep(Duration.ofHours(ttlHours)));
    }
}
