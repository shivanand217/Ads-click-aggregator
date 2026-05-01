package com.clickagg.clickprocessor.service.decorator;

import com.clickagg.clickprocessor.model.ClickRequest;
import com.clickagg.clickprocessor.service.ClickProcessorService;

/**
 * Abstract base for the Decorator pattern.
 * Each concrete decorator enriches the ClickRequest and delegates to the next in chain.
 *
 * Chain (outer → inner):
 *   LoggingDecorator → GeoIpDecorator → AnonymizationDecorator → BaseClickProcessorService
 *
 * Wired in ClickProcessorBeanConfig — not by Spring component scanning,
 * so the chain order is explicit and reviewable in one place.
 */
public abstract class ClickProcessorDecorator implements ClickProcessorService {

    protected final ClickProcessorService delegate;

    protected ClickProcessorDecorator(ClickProcessorService delegate) {
        this.delegate = delegate;
    }

    @Override
    public abstract String processClick(ClickRequest request);
}
