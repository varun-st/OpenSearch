/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation.bucket;

import org.opensearch.common.time.DateFormatter;
import org.opensearch.common.time.DateMathParser;
import org.opensearch.common.time.JavaDateMathParser;
import org.opensearch.dsl.aggregation.DateRangeGrouping;
import org.opensearch.dsl.aggregation.GroupingInfo;
import org.opensearch.dsl.aggregation.util.RangeUtils;
import org.opensearch.dsl.converter.ConversionException;
import org.opensearch.dsl.result.BucketEntry;
import org.opensearch.search.DocValueFormat;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.BucketOrder;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.bucket.range.DateRangeAggregationBuilder;
import org.opensearch.search.aggregations.bucket.range.InternalDateRange;
import org.opensearch.search.aggregations.bucket.range.RangeAggregator.Range;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Translates date_range bucket aggregation.
 */
public class DateRangeBucketTranslator implements BucketTranslator<DateRangeAggregationBuilder> {

    @Override
    public Class<DateRangeAggregationBuilder> getAggregationType() {
        return DateRangeAggregationBuilder.class;
    }

    @Override
    public GroupingInfo getGrouping(DateRangeAggregationBuilder agg) throws ConversionException {
        DateFormatter formatter = DateFormatter.forPattern("strict_date_optional_time||epoch_millis");
        DateMathParser dateMathParser = formatter.toDateMathParser();

        ZoneId timeZone = agg.timeZone() != null ? agg.timeZone() : ZoneId.of("UTC");

        return new DateRangeGrouping(
            agg.getName(), 
            agg.field(), 
            agg.ranges(),
            dateMathParser,
            System::currentTimeMillis,
            timeZone
        );
    }

    @Override
    public BucketOrder getOrder(DateRangeAggregationBuilder agg) {
        return null;
    }

    @Override
    public Collection<AggregationBuilder> getSubAggregations(DateRangeAggregationBuilder agg) {
        return agg.getSubAggregations();
    }

    @Override
    public InternalAggregation toBucketAggregation(DateRangeAggregationBuilder agg, List<BucketEntry> buckets) {
        List<InternalDateRange.Bucket> dateRangeBuckets = new ArrayList<>();

        for (BucketEntry entry : buckets) {
            String key = (String) entry.keys().get(0);
            if (key != null) {
                double from = Double.NEGATIVE_INFINITY;
                double to = Double.POSITIVE_INFINITY;

                for (Range range : agg.ranges()) {
                    String rangeKey = range.getKey() != null ? range.getKey() : RangeUtils.generateDateRangeKey(range);
                    if (rangeKey.equals(key)) {
                        from = range.getFrom();
                        to = range.getTo();
                        break;
                    }
                }

                dateRangeBuckets.add(new InternalDateRange.Bucket(
                    key, from, to, entry.docCount(), entry.subAggs(), agg.keyed(), DocValueFormat.RAW
                ));
            }
        }

        return new InternalDateRange(agg.getName(), dateRangeBuckets, DocValueFormat.RAW, agg.keyed(), agg.getMetadata());
    }
}
