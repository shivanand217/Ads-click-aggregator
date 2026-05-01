package com.clickagg.clickprocessor.service.decorator;

import com.clickagg.clickprocessor.model.ClickRequest;
import com.clickagg.clickprocessor.service.ClickProcessorService;
import lombok.extern.slf4j.Slf4j;

/**
 * Outermost decorator — logs timing and basic metadata for every click.
 * Wraps the full chain so timings capture enrichment + base processing.
 */
@Slf4j
public class LoggingClickProcessorDecorator extends ClickProcessorDecorator {

    public LoggingClickProcessorDecorator(ClickProcessorService delegate) {
        super(delegate);
    }

    @Override
    public String processClick(ClickRequest request) {
        long start = System.currentTimeMillis();
        log.debug("Click received: clickId={} adId={} sessionId={}",
                request.getClickId(), request.getAdId(), request.getSessionId());
        try {
            String redirectUrl = delegate.processClick(request);
            log.info("Click processed: clickId={} adId={} duration={}ms redirectUrl={}",
                    request.getClickId(), request.getAdId(),
                    System.currentTimeMillis() - start, redirectUrl);
            return redirectUrl;
        } catch (Exception e) {
            log.error("Click processing failed: clickId={} adId={} duration={}ms error={}",
                    request.getClickId(), request.getAdId(),
                    System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }
}
