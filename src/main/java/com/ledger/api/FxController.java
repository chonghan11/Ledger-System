package com.ledger.api;

import com.ledger.fx.FxRateService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/fx-rates")
public class FxController {

    public record RateView(String base, String quote, String rate, long rateScaled, Instant updatedAt) {
        static RateView of(FxRateService.Rate r) {
            return new RateView(r.base(), r.quote(), r.display(), r.rateScaled(), r.updatedAt());
        }
    }

    private final FxRateService fx;

    public FxController(FxRateService fx) {
        this.fx = fx;
    }

    @GetMapping
    public List<RateView> all() {
        return fx.all().stream().map(RateView::of).toList();
    }

    @GetMapping("/{base}/{quote}")
    public RateView get(@PathVariable String base, @PathVariable String quote) {
        return RateView.of(fx.rate(base.toUpperCase(java.util.Locale.ROOT), quote.toUpperCase(java.util.Locale.ROOT)));
    }

    /** Admin: set the rate for base->quote, given as an exact decimal string, e.g. {"rate": "1.345"}. */
    @PutMapping("/{base}/{quote}")
    public RateView put(@PathVariable String base, @PathVariable String quote, @Valid @RequestBody Requests.FxRate req) {
        return RateView.of(fx.upsert(base, quote, req.rate()));
    }
}
