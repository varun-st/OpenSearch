/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation.bucket;

import org.opensearch.dsl.aggregation.GroupingInfo;
import org.opensearch.dsl.aggregation.RangeGrouping;
import org.opensearch.dsl.aggregation.util.RangeUtils;
import org.opensearch.dsl.result.BucketEntry;
import org.opensearch.search.DocValueFormat;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.BucketOrder;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.InternalAggregations;
import org.opensearch.search.aggregations.bucket.range.InternalRange;
import org.opensearch.search.aggregations.bucket.range.RangeAggregationBuilder;
import org.opensearch.search.aggregations.bucket.range.RangeAggregator.Range;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Translates range bucket aggregation.
 */
public class RangeBucketTranslator implements BucketTranslator<RangeAggregationBuilder> {

    @Override
    public Class<RangeAggregationBuilder> getAggregationType() {
        return RangeAggregationBuilder.class;
    }

    @Override
    public GroupingInfo getGrouping(RangeAggregationBuilder agg) {
        return new RangeGrouping(agg.getName(), agg.field(), agg.ranges());
    }

    @Override
    public BucketOrder getOrder(RangeAggregationBuilder agg) {
        return null;
    }

    @Override
    public Collection<AggregationBuilder> getSubAggregations(RangeAggregationBuilder agg) {
        return agg.getSubAggregations();
    }

    @Override
    public InternalAggregation toBucketAggregation(RangeAggregationBuilder agg, List<BucketEntry> buckets) {
        List<InternalRange.Bucket> rangeBuckets = new ArrayList<>();
        
        for (BucketEntry entry : buckets) {
            String key = (String) entry.keys().get(0);
            if (key != null) {
                double from = Double.NEGATIVE_INFINITY;
                double to = Double.POSITIVE_INFINITY;
                
                for (Range range : agg.ranges()) {
                    String rangeKey = range.getKey() != null ? range.getKey() : RangeUtils.generateKey(range);
                    if (rangeKey.equals(key)) {
                        from = range.getFrom();
                        to = range.getTo();
                        break;
                    }
                }
                
                rangeBuckets.add(new InternalRange.Bucket(
                    key, from, to, entry.docCount(), entry.subAggs(), agg.keyed(), DocValueFormat.RAW
                ));
            }
        }
        
        return new InternalRange<>(agg.getName(), rangeBuckets, DocValueFormat.RAW, agg.keyed(), agg.getMetadata());
    }
}
