package io.commodity.trade.config;

import io.commodity.platform.error.ProblemAdvice;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Wires the shared problem+json error rendering into this service's web layer. */
@Configuration
@Import(ProblemAdvice.class)
class TradeConfig {}
