package com.clickagg.flink.aggregation;

import com.clickagg.flink.model.AggregatedClick;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

/**
 * ProcessWindowFunction that stamps the AggregatedClick with window timing.
 *
 * Why this exists alongside ClickAggregateFunction:
 *   AggregateFunction runs incrementally (one call per event — memory efficient).
 *   But it has no access to the TimeWindow (start/end timestamps).
 *
 *   Flink's aggregate(aggFn, windowFn) overload combines both:
 *     1. AggregateFunction processes events incrementally → produces one AggregatedClick
 *     2. ProcessWindowFunction receives that single result + the TimeWindow context
 *        → stamps windowStartMs / windowEndMs and emits to downstream sinks
 *
 *   This is the standard Flink pattern for efficient windowed aggregation
 *   with access to window metadata. The alternative (pure ProcessWindowFunction)
 *   would buffer all events in state until window close — expensive at 10k clicks/sec.
 */
public class WindowMetadataFunction
        extends ProcessWindowFunction<AggregatedClick, AggregatedClick, String, TimeWindow> {

    @Override
    public void process(
            String key,
            Context context,
            Iterable<AggregatedClick> elements,
            Collector<AggregatedClick> out) {

        // aggregate() guarantees exactly one element from AggregateFunction per window
        AggregatedClick result = elements.iterator().next();
        result.setWindowStartMs(context.window().getStart());
        result.setWindowEndMs(context.window().getEnd());
        out.collect(result);
    }
}
