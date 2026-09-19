package io.commodity.blotter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.commodity.blotter.repository.BlotterStore;
import io.commodity.blotter.service.CachedQuotaLookup;
import io.commodity.blotter.service.RowBroadcaster;
import io.commodity.contracts.lookup.QuotaDirectory;
import io.commodity.platform.error.ProblemAdvice;
import io.commodity.platform.eventing.DlqErrorHandler;
import io.commodity.platform.eventing.PeriodicTask;
import io.commodity.platform.lookup.HttpQuotaDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.web.client.RestClient;

/** Wiring for the blotter: the read-model store, the change broadcaster and its flush timer, the cached quota lookup, and the consumer failure policy. */
@Configuration
@Import(ProblemAdvice.class)
class BlotterConfig {

    @Bean
    BlotterStore blotterStore(JdbcTemplate jdbc) {
        return new BlotterStore(jdbc);
    }

    @Bean
    RowBroadcaster rowBroadcaster(ObjectMapper json) {
        return new RowBroadcaster(json);
    }

    /** Pushes coalesced row changes to open blotters every 250 ms (the design's per-row coalescing window). */
    @Bean
    PeriodicTask blotterFlushTask(RowBroadcaster broadcaster, @Value("${commodity.blotter.flush-ms:250}") long flushMs) {
        return new PeriodicTask("blotter-sse-flush", flushMs, broadcaster::flush);
    }

    @Bean
    CachedQuotaLookup cachedQuotaLookup(QuotaDirectory quotaDirectory) {
        return new CachedQuotaLookup(quotaDirectory);
    }

    /** See the other services' configs: the identical adapter is defined once when several services share a JVM. */
    @Bean
    @ConditionalOnMissingBean(QuotaDirectory.class)
    @ConditionalOnProperty("commodity.trade.base-url")
    QuotaDirectory quotaDirectory(RestClient.Builder builder, @Value("${commodity.trade.base-url}") String baseUrl) {
        return new HttpQuotaDirectory(builder, baseUrl);
    }

    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    @ConditionalOnProperty("commodity.blotter.consumer.enabled")
    DefaultErrorHandler blotterErrorHandler(KafkaTemplate<?, ?> kafka,
                                            @Value("${commodity.consumer.retry-interval-ms:${commodity.blotter.consumer.retry-interval-ms:1000}}") long intervalMs) {
        return DlqErrorHandler.create(kafka, intervalMs, 3);
    }
}
