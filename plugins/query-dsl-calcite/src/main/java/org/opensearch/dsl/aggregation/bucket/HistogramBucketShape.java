/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation.bucket;

import org.opensearch.dsl.aggregation.GroupingInfo;
import org.opensearch.dsl.aggregation.HistogramGrouping;
import org.opensearch.dsl.result.BucketEntry;
import org.opensearch.search.DocValueFormat;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.BucketOrder;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.InternalAggregations;
import org.opensearch.search.aggregations.bucket.histogram.Histogram;
import org.opensearch.search.aggregations.bucket.histogram.HistogramAggregationBuilder;
import org.opensearch.search.aggregations.bucket.histogram.InternalHistogram;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Bucket shape adapter for histogram aggregations.
 * Converts HistogramAggregationBuilder to InternalHistogram results.
 */
public class HistogramBucketShape implements BucketShape<HistogramAggregationBuilder> {

    @Override
    public Class<HistogramAggregationBuilder> getAggregationType() {
        return HistogramAggregationBuilder.class;
    }

    @Override
    public GroupingInfo getGrouping(HistogramAggregationBuilder agg) {
        return new HistogramGrouping(agg.field(), agg.interval(), agg.offset());
    }

    @Override
    public BucketOrder getOrder(HistogramAggregationBuilder agg) {
        return agg.order();
    }

    @Override
    public Collection<AggregationBuilder> getSubAggregations(HistogramAggregationBuilder agg) {
        return agg.getSubAggregations();
    }

    @Override
    public long getMinDocCount(HistogramAggregationBuilder agg) {
        return agg.minDocCount();
    }

    @Override
    public InternalAggregation toBucketAggregation(HistogramAggregationBuilder agg, List<BucketEntry> buckets) {
        List<InternalHistogram.Bucket> histogramBuckets = new ArrayList<>(buckets.size());
        for (BucketEntry entry : buckets) {
            double key = ((Number) entry.keys().get(0)).doubleValue();
            histogramBuckets.add(new InternalHistogram.Bucket(
                key, entry.docCount(), agg.keyed(), DocValueFormat.RAW, (InternalAggregations) entry.subAggs()
            ));
        }

        // InternalHistogram requires EmptyBucketInfo when minDocCount is 0, but DSL doesn't support
        // extended bounds. Use minDocCount of 1 to avoid this requirement. Actual filtering is done
        // in AggregationResponseBuilder before calling this method.
        long minDocCount = agg.minDocCount() == 0 ? 1 : agg.minDocCount();

        return new InternalHistogram(
            agg.getName(), histogramBuckets, agg.order(), minDocCount,
            null, DocValueFormat.RAW, agg.keyed(), Map.of()
        );
    }
}
