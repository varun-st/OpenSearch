/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation.bucket;

import org.opensearch.common.Rounding;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.dsl.aggregation.DateHistogramGrouping;
import org.opensearch.dsl.aggregation.GroupingInfo;
import org.opensearch.dsl.result.BucketEntry;
import org.opensearch.search.DocValueFormat;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.BucketOrder;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.InternalAggregations;
import org.opensearch.search.aggregations.bucket.histogram.DateHistogramAggregationBuilder;
import org.opensearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.opensearch.search.aggregations.bucket.histogram.InternalDateHistogram;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Translates date_histogram bucket aggregation.
 */
public class DateHistogramBucketTranslator implements BucketTranslator<DateHistogramAggregationBuilder> {

    @Override
    public Class<DateHistogramAggregationBuilder> getAggregationType() {
        return DateHistogramAggregationBuilder.class;
    }

    @Override
    public GroupingInfo getGrouping(DateHistogramAggregationBuilder agg) {
        return new DateHistogramGrouping(agg.getName(), agg.field(), agg.getCalendarInterval(), agg.getFixedInterval(), agg.offset());
    }

    @Override
    public BucketOrder getBucketOrder(DateHistogramAggregationBuilder agg) {
        return agg.order();
    }

    @Override
    public Collection<AggregationBuilder> getSubAggregations(DateHistogramAggregationBuilder agg) {
        return agg.getSubAggregations();
    }

    @Override
    public InternalAggregation toBucketAggregation(DateHistogramAggregationBuilder agg, Iterable<BucketEntry> buckets) {
        List<BucketEntry> bucketList = buckets instanceof List ? (List<BucketEntry>) buckets : new ArrayList<>();
        if (!(buckets instanceof List)) {
            buckets.forEach(bucketList::add);
        }

        List<InternalDateHistogram.Bucket> dateHistogramBuckets = convertBuckets(bucketList, agg);
        InternalDateHistogram.EmptyBucketInfo emptyBucketInfo = buildEmptyBucketInfo(agg);

        return new InternalDateHistogram(
            agg.getName(),
            dateHistogramBuckets,
            agg.order(),
            agg.minDocCount(),
            agg.offset(),
            emptyBucketInfo,
            DocValueFormat.RAW,
            agg.keyed(),
            Map.of()
        );
    }

    private List<InternalDateHistogram.Bucket> convertBuckets(List<BucketEntry> buckets, DateHistogramAggregationBuilder agg) {
        List<InternalDateHistogram.Bucket> result = new ArrayList<>(buckets.size());
        for (BucketEntry entry : buckets) {
            long bucketKey = extractBucketKey(entry);
            result.add(new InternalDateHistogram.Bucket(
                bucketKey, entry.docCount(), agg.keyed(), DocValueFormat.RAW, entry.subAggs()
            ));
        }
        return result;
    }

    private long extractBucketKey(BucketEntry entry) {
        // Date histogram groups by a single timestamp field, so keys() always contains exactly one element
        return ((Number) entry.keys().get(0)).longValue();
    }

    private InternalDateHistogram.EmptyBucketInfo buildEmptyBucketInfo(DateHistogramAggregationBuilder agg) {
        if (agg.minDocCount() != 0) {
            return null;
        }

        Rounding rounding = createRounding(agg);
        return new InternalDateHistogram.EmptyBucketInfo(rounding, InternalAggregations.EMPTY);
    }

    private Rounding createRounding(DateHistogramAggregationBuilder agg) {
        Rounding.Builder roundingBuilder;

        if (agg.getCalendarInterval() != null) {
            Rounding.DateTimeUnit dateTimeUnit = mapToDateTimeUnit(agg.getCalendarInterval());
            roundingBuilder = new Rounding.Builder(dateTimeUnit);
        } else if (agg.getFixedInterval() != null) {
            TimeValue interval = TimeValue.parseTimeValue(
                agg.getFixedInterval().toString(),
                null,
                "DateHistogramBucketTranslator.createRounding"
            );
            roundingBuilder = new Rounding.Builder(interval);
        } else {
            throw new IllegalArgumentException("No interval specified for date_histogram");
        }

        return roundingBuilder.build();
    }

    private Rounding.DateTimeUnit mapToDateTimeUnit(DateHistogramInterval interval) {
        return switch (interval.toString()) {
            case "1y" -> Rounding.DateTimeUnit.YEAR_OF_CENTURY;
            case "1q" -> Rounding.DateTimeUnit.QUARTER_OF_YEAR;
            case "1M" -> Rounding.DateTimeUnit.MONTH_OF_YEAR;
            case "1w" -> Rounding.DateTimeUnit.WEEK_OF_WEEKYEAR;
            case "1d" -> Rounding.DateTimeUnit.DAY_OF_MONTH;
            case "1h" -> Rounding.DateTimeUnit.HOUR_OF_DAY;
            case "1m" -> Rounding.DateTimeUnit.MINUTES_OF_HOUR;
            case "1s" -> Rounding.DateTimeUnit.SECOND_OF_MINUTE;
            default -> throw new IllegalArgumentException("Unsupported calendar interval: " + interval);
        };
    }
}
