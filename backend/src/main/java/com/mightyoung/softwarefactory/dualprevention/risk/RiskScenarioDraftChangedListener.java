package com.mightyoung.softwarefactory.dualprevention.risk;

import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Explicitly composed post-commit entry point; component scanning does not activate it.
 * The outer asynchronous publication-registry failure boundary is owned by composition.
 */
public class RiskScenarioDraftChangedListener {
    private final RiskChangeCaptureService capture;

    public RiskScenarioDraftChangedListener(RiskChangeCaptureService capture) {
        if (capture == null) throw unavailable();
        this.capture = capture;
    }

    @Async
    @TransactionalEventListener(id = "risk-scenario-draft-change", phase = TransactionPhase.AFTER_COMMIT)
    public void capture(RiskScenarioDraftChangedEvent event) {
        try {
            if (event == null) throw unavailable();
            capture.capture(event);
        } catch (RuntimeException | Error failure) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Risk change listener is unavailable");
    }
}
