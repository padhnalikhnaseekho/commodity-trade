package io.commodity.stubs.bdc;

import java.time.LocalDate;
import org.springframework.web.bind.annotation.*;

/** Demo controls for the stub: read a desk's BRD, roll it forward (used by the demo script to show as-of behaviour). */
@RestController
@RequestMapping("/api/desks")
public class BusinessDayStubController {

    public record DeskBrd(String deskId, LocalDate brd) {}

    private final InMemoryBusinessDayClock clock;

    public BusinessDayStubController(InMemoryBusinessDayClock clock) {
        this.clock = clock;
    }

    @GetMapping("/{deskId}/brd")
    public DeskBrd get(@PathVariable String deskId) {
        return new DeskBrd(deskId, clock.currentBrd(deskId));
    }

    @PostMapping("/{deskId}/roll")
    public DeskBrd roll(@PathVariable String deskId) {
        return new DeskBrd(deskId, clock.rollForward(deskId));
    }
}
