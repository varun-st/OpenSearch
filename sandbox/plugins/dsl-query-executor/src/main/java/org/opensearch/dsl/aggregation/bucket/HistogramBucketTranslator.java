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
import org.opensearch.search.aggregations.bucket.histogram.HistogramAggregationBuilder;
import org.opensearch.search.aggregations.bucket.histogram.InternalHistogram;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Translates histogram bucket aggregation.
 */
public class HistogramBucketTranslator implements BucketTranslator<HistogramAggregationBuilder> {

    @Override
    public Class<HistogramAggregationBuilder> getAggregationType() {
        return HistogramAggregationBuilder.class;
    }

    @Override
    public GroupingInfo getGrouping(HistogramAggregationBuilder agg) {
        return new HistogramGrouping(agg.getName(), agg.field(), agg.interval(), agg.offset());
    }

    @Override
    public BucketOrder getBucketOrder(HistogramAggregationBuilder agg) {
        return agg.order();
    }

    @Override
    public Collection<AggregationBuilder> getSubAggregations(HistogramAggregationBuilder agg) {
        return agg.getSubAggregations();
    }

    @Override
    public InternalAggregation toBucketAggregation(HistogramAggregationBuilder agg, Iterable<BucketEntry> buckets) {
        List<BucketEntry> bucketList = buckets instanceof List ? (List<BucketEntry>) buckets : new ArrayList<>();
        if (!(buckets instanceof List)) {
            buckets.forEach(bucketList::add);
        }

        List<InternalHistogram.Bucket> histogramBuckets = convertBuckets(bucketList, agg);
        InternalHistogram.EmptyBucketInfo emptyBucketInfo = buildEmptyBucketInfo(agg, bucketList);

        return new InternalHistogram(
            agg.getName(),
            histogramBuckets,
            agg.order(),
            agg.minDocCount(),
            emptyBucketInfo,
            DocValueFormat.RAW,
            agg.keyed(),
            Map.of()
        );
    }

    private List<InternalHistogram.Bucket> convertBuckets(List<BucketEntry> buckets, HistogramAggregationBuilder agg) {
        List<InternalHistogram.Bucket> result = new ArrayList<>(buckets.size());
        for (BucketEntry entry : buckets) {
            double bucketKey = extractBucketKey(entry);
            result.add(new InternalHistogram.Bucket(
                bucketKey, entry.docCount(), agg.keyed(), DocValueFormat.RAW, entry.subAggs()
            ));
        }
        return result;
    }

    private InternalHistogram.EmptyBucketInfo buildEmptyBucketInfo(
            HistogramAggregationBuilder agg,
            List<BucketEntry> buckets) {
        if (agg.minDocCount() != 0) {
            return null;  // EmptyBucketInfo only needed for minDocCount=0 (gap filling)
        }

        // Use sentinel values for empty buckets to prevent gap filling when no data exists
        // round(POSITIVE_INFINITY) > NEGATIVE_INFINITY = false, no buckets created
        double minBound = buckets.isEmpty() ? Double.POSITIVE_INFINITY : extractBucketKey(buckets.get(0));
        double maxBound = buckets.isEmpty() ? Double.NEGATIVE_INFINITY : extractBucketKey(buckets.get(buckets.size() - 1));

        return new InternalHistogram.EmptyBucketInfo(
            agg.interval(),
            agg.offset(),
            minBound,
            maxBound,
            InternalAggregations.EMPTY
        );
    }

    private double extractBucketKey(BucketEntry entry) {
        // Histogram groups by a single numeric field, so keys() always contains exactly one element
        return ((Number) entry.keys().get(0)).doubleValue();
    }
}
