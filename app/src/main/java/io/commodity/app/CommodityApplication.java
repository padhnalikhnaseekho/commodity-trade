package io.commodity.app;

import io.commodity.blotter.BlotterModule;
import io.commodity.gateway.GatewayModule;
import io.commodity.logistics.LogisticsModule;
import io.commodity.pricing.PricingModule;
import io.commodity.stubs.StubsModule;
import io.commodity.trade.TradeModule;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * The demo application: every service in one JVM, selected by profile.
 *
 * <p>It scans only this package (which holds nothing but wiring) and imports each service's module class. Each module class is behind its own profile,
 * so {@code --spring.profiles.active=pricing,stubs} runs only pricing and the stand-ins it needs, while the default (see application.yml) runs everything.
 * Boundaries are real in code (the build forbids service-to-service dependencies) and at run time (services call each other over HTTP and Kafka, even
 * inside this one process). Running them together is a packaging choice for the demo, not an architectural shortcut.
 */
@SpringBootApplication
@Import({TradeModule.class, LogisticsModule.class, PricingModule.class, GatewayModule.class, BlotterModule.class, StubsModule.class, PersistenceOrdering.class})
public class CommodityApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommodityApplication.class, args);
    }
}
