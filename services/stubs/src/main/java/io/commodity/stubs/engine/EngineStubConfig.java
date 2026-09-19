package io.commodity.stubs.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Wires the stub engine adapter. Active only when {@code commodity.engine.enabled=true}.
 * Settings: {@code commodity.engine.base-price} (default 100), {@code commodity.engine.delay-ms.<lane>} (defaults 100/200/500/500),
 * {@code commodity.engine.failure-rate} (0..1, default 0), {@code commodity.engine.fail-attempts} (default 1).
 */
@Configuration
@ConditionalOnProperty("commodity.engine.enabled")
public class EngineStubConfig {

    @Bean
    EngineSettings engineSettings(@Value("${commodity.engine.base-price:100}") BigDecimal basePrice,
                                  @Value("${commodity.engine.delay-ms.interactive:100}") long interactive,
                                  @Value("${commodity.engine.delay-ms.invoice:200}") long invoice,
                                  @Value("${commodity.engine.delay-ms.bulk:500}") long bulk,
                                  @Value("${commodity.engine.delay-ms.close:500}") long close,
                                  @Value("${commodity.engine.failure-rate:0}") double failureRate,
                                  @Value("${commodity.engine.fail-attempts:1}") int failAttempts) {
        return new EngineSettings(basePrice, Map.of("INTERACTIVE", interactive, "INVOICE", invoice, "BULK", bulk, "CLOSE", close), failureRate, failAttempts);
    }

    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService engineScheduler() {
        return Executors.newScheduledThreadPool(4, r -> new Thread(r, "stub-engine"));
    }

    @Bean
    StubEngineAdapter stubEngineAdapter(EngineSettings settings, KafkaTemplate<String, String> kafka, ObjectMapper json, ScheduledExecutorService engineScheduler) {
        return new StubEngineAdapter(settings, kafka, json, engineScheduler);
    }
}
