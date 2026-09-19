package io.commodity.stubs;

import io.commodity.contracts.lookup.FunctionalLineDirectory;
import io.commodity.stubs.bdc.BusinessDayStubController;
import io.commodity.stubs.bdc.InMemoryBusinessDayClock;
import io.commodity.stubs.engine.EngineStubConfig;
import io.commodity.stubs.srd.InMemoryFunctionalLineDirectory;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/**
 * The stand-ins for services that are built in P1 (Business Day Control, reference data, the valuation engine), active only under the profile
 * {@code stubs}. They implement the {@code contracts} PORTS in memory, so replacing one with the real service changes no caller.
 *
 * <p>HONEST LIMIT of splitting the deployment: these ports are in-process Java interfaces, not HTTP services yet. So any process that runs a service
 * needing the business-day clock or the functional-line reference data (trade, logistics, pricing, gateway) also runs the {@code stubs} profile. The
 * services that talk over HTTP and Kafka (trade, logistics, pricing, gateway, blotter) are what the profiles split cleanly.
 *
 * <p>The stub engine adapter is a separate switch ({@code commodity.engine.enabled}) inside this profile.
 */
@Configuration
@Profile("stubs")
@Import({EngineStubConfig.class, BusinessDayStubController.class})
public class StubsModule {

    /** One fixed business date per desk, rollable. The default date is a demo setting. */
    @Bean
    InMemoryBusinessDayClock businessDayClock(@Value("${commodity.stub.default-brd:2026-09-18}") LocalDate defaultBrd) {
        return new InMemoryBusinessDayClock(defaultBrd);
    }

    /** RM trades created before the cutover BRD value on the legacy engine, later ones on the modern one; a trade may override. */
    @Bean
    FunctionalLineDirectory functionalLineDirectory(@Value("${commodity.stub.rm-cutover-brd:2026-09-01}") LocalDate rmCutoverBrd) {
        return new InMemoryFunctionalLineDirectory(rmCutoverBrd);
    }
}
