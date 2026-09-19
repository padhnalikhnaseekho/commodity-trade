package io.commodity.contracts.lookup;

import java.util.Optional;

/**
 * Port for reading quota data owned by the trade service.
 *
 * <p>WHY a port in :contracts: a service module may not depend on another service module, so logistics cannot call
 * trade classes. It depends on this interface; the wiring supplies an implementation (HTTP in logistics). That is the
 * rule "cross-service reads go through contract types over HTTP or Kafka, even inside one JVM" made concrete.
 */
public interface QuotaDirectory {
    Optional<QuotaView> find(String quotaRef);
}
