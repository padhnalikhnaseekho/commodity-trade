package io.commodity.trade.api;

import io.commodity.contracts.lookup.QuotaView;
import io.commodity.trade.service.TradeService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * REST surface of the trade service. Thin on purpose: translate HTTP to service calls and back, no rules here.
 * GET /api/quotas/{ref} is the endpoint behind the QuotaDirectory port that logistics consumes.
 */
@RestController
@RequestMapping("/api")
public class TradeController {

    private final TradeService service;

    public TradeController(TradeService service) {
        this.service = service;
    }

    @PostMapping("/trades")
    @ResponseStatus(HttpStatus.CREATED)
    public TradeResponses.Created create(@RequestBody CreateTradeRequest request) {
        var created = service.create(request);
        return new TradeResponses.Created(created.tradeRef(), created.quotaRefs());
    }

    @GetMapping("/trades/{tradeRef}")
    public TradeResponses.TradeView get(@PathVariable String tradeRef) {
        var t = service.getTrade(tradeRef);
        return new TradeResponses.TradeView(t.getTradeRef(), t.getBusinessLine().name(), t.getDeskId(), t.getSide().name(),
                t.getCounterparty(), t.getCommodity(), t.getTotalQty().toPlainString(), t.getUom(), t.getCreatedBrd());
    }

    @GetMapping("/trades/{tradeRef}/quotas")
    public List<TradeResponses.QuotaItem> quotas(@PathVariable String tradeRef) {
        return service.getQuotas(tradeRef).stream().map(q -> new TradeResponses.QuotaItem(q.getQuotaRef(), q.getSeq(),
                q.getPeriodicity().name(), q.getPeriodFrom(), q.getPeriodTo(), q.getQty().toPlainString())).toList();
    }

    @GetMapping("/quotas/{quotaRef}")
    public QuotaView quota(@PathVariable String quotaRef) {
        return service.getQuotaView(quotaRef);
    }
}
