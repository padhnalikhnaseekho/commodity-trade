package io.commodity.blotter.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.commodity.contracts.lookup.QuotaDirectory;
import io.commodity.contracts.lookup.QuotaView;
import java.time.Duration;
import java.util.Optional;

/**
 * Looks quotas up through the trade service's port, with an in-process cache.
 *
 * <p>WHY cache: the blotter needs a quota's desk, trade and commodity for every snapshot it projects, and those never change once a trade is captured, so
 * asking trade every time would be a pointless call on the hot path. LEGACY/TARGET: Caffeine in one process stands in for a distributed cache read-through
 * over the owning service, with event-driven invalidation. Only FOUND quotas are cached, so a quota the blotter asks about too early is retried, not remembered as missing.
 */
public class CachedQuotaLookup {

    private final QuotaDirectory delegate;
    private final Cache<String, QuotaView> cache = Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(Duration.ofMinutes(30)).build();

    public CachedQuotaLookup(QuotaDirectory delegate) {
        this.delegate = delegate;
    }

    public Optional<QuotaView> find(String quotaRef) {
        QuotaView cached = cache.getIfPresent(quotaRef);
        if (cached != null) return Optional.of(cached);
        Optional<QuotaView> found = delegate.find(quotaRef);
        found.ifPresent(q -> cache.put(quotaRef, q));
        return found;
    }
}
