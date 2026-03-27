/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation.bucket;

import org.opensearch.dsl.aggregation.DateHistogramGrouping;
import org.opensearch.dsl.aggregation.GroupingInfo;
import org.opensearch.dsl.result.BucketEntry;
import org.opensearch.search.DocValueFormat;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.BucketOrder;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.InternalAggregations;
import org.opensearch.search.aggregations.bucket.histogram.DateHistogramAggregationBuilder;
import org.opensearch.search.aggregations.bucket.histogram.InternalDateHistogram;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class DateHistogramBucketShape implements BucketShape<DateHistogramAggregationBuilder> {

    @Override
    public Class<DateHistogramAggregationBuilder> getAggregationType() {
        return DateHistogramAggregationBuilder.class;
    }

    @Override
    public GroupingInfo getGrouping(DateHistogramAggregationBuilder agg) {
        return new DateHistogramGrouping(agg.field(), agg.getCalendarInterval(), agg.getFixedInterval());
    }

    @Override
    public BucketOrder getOrder(DateHistogramAggregationBuilder agg) {
        return agg.order();
    }

    @Override
    public Collection<AggregationBuilder> getSubAggregations(DateHistogramAggregationBuilder agg) {
        return agg.getSubAggregations();
    }

    @Override
    public long getMinDocCount(DateHistogramAggregationBuilder agg) {
        return agg.minDocCount();
    }

    @Override
    public InternalAggregation toBucketAggregation(DateHistogramAggregationBuilder agg, List<BucketEntry> buckets) {
        List<InternalDateHistogram.Bucket> dateHistogramBuckets = new ArrayList<>(buckets.size());
        for (BucketEntry entry : buckets) {
            long key = ((Number) entry.keys().get(0)).longValue();
            dateHistogramBuckets.add(new InternalDateHistogram.Bucket(
                key, entry.docCount(), agg.keyed(), DocValueFormat.RAW, (InternalAggregations) entry.subAggs()
            ));
        }

        // InternalDateHistogram requires EmptyBucketInfo when minDocCount is 0, but DSL doesn't support
        // extended bounds. Use minDocCount of 1 to avoid this requirement. Actual filtering is done
        // in AggregationResponseBuilder before calling this method.
        long minDocCount = agg.minDocCount() == 0 ? 1 : agg.minDocCount();

        return new InternalDateHistogram(
            agg.getName(), dateHistogramBuckets, agg.order(), minDocCount,
            0L, null, DocValueFormat.RAW, agg.keyed(), Map.of()
        );
    }
}
