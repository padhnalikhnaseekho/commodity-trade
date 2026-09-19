package io.commodity.blotter.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.commodity.blotter.service.BlotterProjector;
import io.commodity.contracts.events.PricingQuotaPublished;
import io.commodity.contracts.events.Topics;
import io.commodity.contracts.events.ValuationPublished;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The blotter's two inputs. Each event is parsed and handed to the projector. Failures follow the shared policy (retry, then {@code <topic>.dlq}), so a
 * malformed message never stalls a partition. No dedup is needed: the projector's writes are idempotent upserts.
 */
@Component
@ConditionalOnProperty("commodity.blotter.consumer.enabled")
class BlotterListeners {

    private final BlotterProjector projector;
    private final ObjectMapper json;

    BlotterListeners(BlotterProjector projector, ObjectMapper json) {
        this.projector = projector;
        this.json = json;
    }

    @KafkaListener(topics = Topics.PRICING_QUOTA_PUBLISHED, groupId = "${commodity.blotter.consumer.group:blotter}")
    void onPricing(ConsumerRecord<String, String> record) throws Exception {
        projector.onQuotaPublished(json.readValue(record.value(), PricingQuotaPublished.class));
    }

    @KafkaListener(topics = Topics.VALUATION_PUBLISHED, groupId = "${commodity.blotter.consumer.group:blotter}")
    void onValuation(ConsumerRecord<String, String> record) throws Exception {
        projector.onValuationPublished(json.readValue(record.value(), ValuationPublished.class));
    }
}
