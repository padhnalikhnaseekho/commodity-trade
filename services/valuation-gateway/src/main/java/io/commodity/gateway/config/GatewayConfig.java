package io.commodity.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.commodity.contracts.lookup.QuotaDirectory;
import io.commodity.contracts.valuation.Lane;
import io.commodity.contracts.valuation.ValuationInputsProvider;
import io.commodity.gateway.domain.LaneLimiter;
import io.commodity.gateway.domain.LaneSettings;
import io.commodity.gateway.repository.RequestStore;
import io.commodity.gateway.service.Dispatcher;
import io.commodity.gateway.service.Watchdog;
import io.commodity.platform.error.ProblemAdvice;
import io.commodity.platform.eventing.DlqErrorHandler;
import io.commodity.platform.eventing.KafkaMessageSink;
import io.commodity.platform.eventing.PeriodicTask;
import io.commodity.platform.lookup.HttpQuotaDirectory;
import io.commodity.platform.lookup.HttpValuationInputsProvider;
import io.commodity.platform.outbox.OutboxPoller;
import io.commodity.platform.outbox.OutboxPublisher;
import io.commodity.platform.outbox.OutboxWriter;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * Wiring for the valuation gateway: lane limits, durable store, the dispatcher/watchdog/outbox workers, and the reply consumer policy.
 *
 * <p>Workers (dispatcher, watchdog, outbox publisher, reply listener) start only when {@code commodity.gateway.workers.enabled=true}, so a context
 * can start without a broker (tests, tooling); accepting and reading requests never needs Kafka.
 * Lane limits are configurable per lane: {@code commodity.gateway.lanes.<lane>.max-in-flight} and {@code .timeout-ms}.
 */
@Configuration
@Import(ProblemAdvice.class)
class GatewayConfig {

    private static final String SCHEMA = "gateway";

    @Bean
    Map<Lane, LaneSettings> laneSettings(Environment env) {
        Map<Lane, LaneSettings> settings = new EnumMap<>(Lane.class);
        LaneSettings.defaults().forEach((lane, d) -> {
            String prefix = "commodity.gateway.lanes." + lane.name().toLowerCase() + ".";
            settings.put(lane, new LaneSettings(env.getProperty(prefix + "max-in-flight", Integer.class, d.maxInFlight()),
                    Duration.ofMillis(env.getProperty(prefix + "timeout-ms", Long.class, d.timeout().toMillis()))));
        });
        return settings;
    }

    @Bean
    LaneLimiter laneLimiter(Map<Lane, LaneSettings> laneSettings) {
        return new LaneLimiter(laneSettings);
    }

    @Bean
    RequestStore requestStore(JdbcTemplate jdbc) {
        return new RequestStore(jdbc);
    }

    @Bean
    OutboxWriter gatewayOutboxWriter(JdbcTemplate jdbc) {
        return new OutboxWriter(jdbc, SCHEMA);
    }

    /**
     * On startup, seed each lane's in-flight count from the durable SENT rows. This is what makes a restart safe: requests that were in flight
     * when the previous instance died still occupy their slots until their replies arrive or the watchdog retries them.
     */
    @Bean
    SmartInitializingSingleton seedLaneLimiter(LaneLimiter limiter, RequestStore store) {
        return () -> { for (Lane lane : Lane.values()) limiter.initialize(lane, store.countSent(lane)); };
    }

    @Bean
    Dispatcher dispatcher(RequestStore store, LaneLimiter limiter, @Qualifier("gatewayOutboxWriter") OutboxWriter gatewayOutboxWriter, PlatformTransactionManager txm, ObjectMapper json) {
        return new Dispatcher(store, limiter, gatewayOutboxWriter, new TransactionTemplate(txm), json);
    }

    @Bean
    Watchdog watchdog(RequestStore store, LaneLimiter limiter, Map<Lane, LaneSettings> laneSettings) {
        return new Watchdog(store, limiter, laneSettings);
    }

    // ---- ports supplied over HTTP when a base URL is configured (tests and the demo wiring supply their own otherwise) -------------------

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

    @Bean
    @ConditionalOnProperty("commodity.pricing.base-url")
    ValuationInputsProvider valuationInputsProvider(RestClient.Builder builder, @Value("${commodity.pricing.base-url}") String baseUrl) {
        return new HttpValuationInputsProvider(builder, baseUrl);
    }

    // ---- workers ---------------------------------------------------------------------------------------------------------------------

    @Bean
    @ConditionalOnProperty("commodity.gateway.workers.enabled")
    PeriodicTask dispatchTask(Dispatcher dispatcher, @Value("${commodity.gateway.dispatch-interval-ms:100}") long ms) {
        return new PeriodicTask("gateway-dispatcher", ms, dispatcher::dispatchOnce);
    }

    /** The watchdog sweep: every 10 seconds by default (spec), tunable for tests. */
    @Bean
    @ConditionalOnProperty("commodity.gateway.workers.enabled")
    PeriodicTask watchdogTask(Watchdog watchdog, @Value("${commodity.gateway.watchdog-interval-ms:10000}") long ms) {
        return new PeriodicTask("gateway-watchdog", ms, watchdog::sweep);
    }

    @Bean
    @ConditionalOnProperty("commodity.gateway.workers.enabled")
    OutboxPoller gatewayOutboxPoller(JdbcTemplate jdbc, PlatformTransactionManager txm, KafkaTemplate<String, String> kafka,
                                     @Value("${commodity.outbox.poll-ms:100}") long pollMs, @Value("${commodity.outbox.batch-size:100}") int batch) {
        return new OutboxPoller(new OutboxPublisher(jdbc, new TransactionTemplate(txm), SCHEMA, new KafkaMessageSink(kafka), batch), pollMs);
    }

    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class) // see PricingConfig: one shared handler when several services run in one JVM
    @ConditionalOnProperty("commodity.gateway.workers.enabled")
    DefaultErrorHandler gatewayErrorHandler(KafkaTemplate<?, ?> kafka,
                                            @Value("${commodity.consumer.retry-interval-ms:${commodity.gateway.consumer.retry-interval-ms:1000}}") long intervalMs) {
        return DlqErrorHandler.create(kafka, intervalMs, 3);
    }
}
