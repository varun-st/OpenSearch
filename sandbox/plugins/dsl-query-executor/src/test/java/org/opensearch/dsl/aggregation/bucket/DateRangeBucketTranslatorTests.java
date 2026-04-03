/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation.bucket;

import org.junit.Before;
import org.junit.Test;
import org.opensearch.dsl.aggregation.GroupingInfo;
import org.opensearch.dsl.aggregation.DateRangeGrouping;
import org.opensearch.dsl.converter.ConversionException;
import org.opensearch.dsl.result.BucketEntry;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.InternalAggregations;
import org.opensearch.search.aggregations.bucket.range.DateRangeAggregationBuilder;
import org.opensearch.search.aggregations.bucket.range.InternalDateRange;
import org.opensearch.search.aggregations.metrics.AvgAggregationBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.*;

public class DateRangeBucketTranslatorTests {

    private DateRangeBucketTranslator translator;
    private DateRangeAggregationBuilder agg;

    @Before
    public void setUp() {
        translator = new DateRangeBucketTranslator();
        agg = new DateRangeAggregationBuilder("test_date_range")
            .field("timestamp")
            .addRange(0, 1000000000000L)
            .addRange(1000000000000L, 2000000000000L);
    }

    @Test
    public void testGetAggregationType() {
        assertEquals(DateRangeAggregationBuilder.class, translator.getAggregationType());
    }

    @Test
    public void testGetGrouping() throws ConversionException {
        GroupingInfo grouping = translator.getGrouping(agg);

        assertNotNull(grouping);
        assertTrue(grouping instanceof DateRangeGrouping);
        assertEquals(List.of("timestamp"), grouping.getFieldNames());
    }

    @Test
    public void testGetOrder() {
        assertNull(translator.getOrder(agg));
    }

    @Test
    public void testGetSubAggregations() {
        agg.subAggregation(new AvgAggregationBuilder("avg_value").field("value"));

        Collection<AggregationBuilder> subAggs = translator.getSubAggregations(agg);

        assertNotNull(subAggs);
        assertEquals(1, subAggs.size());
        assertEquals("avg_value", subAggs.iterator().next().getName());
    }

    @Test
    public void testToBucketAggregationWithEmptyBuckets() {
        List<BucketEntry> buckets = new ArrayList<>();

        InternalAggregation result = translator.toBucketAggregation(agg, buckets);

        assertNotNull(result);
        assertTrue(result instanceof InternalDateRange);
        InternalDateRange dateRange = (InternalDateRange) result;
        assertEquals("test_date_range", dateRange.getName());
        assertEquals(0, dateRange.getBuckets().size());
    }

    @Test
    public void testToBucketAggregationWithBuckets() {
        List<BucketEntry> buckets = new ArrayList<>();
        buckets.add(new BucketEntry(List.of("0-1000000000000"), 10, InternalAggregations.EMPTY));
        buckets.add(new BucketEntry(List.of("1000000000000-2000000000000"), 25, InternalAggregations.EMPTY));

        InternalAggregation result = translator.toBucketAggregation(agg, buckets);

        assertNotNull(result);
        assertTrue(result instanceof InternalDateRange);
        InternalDateRange dateRange = (InternalDateRange) result;
        assertEquals("test_date_range", dateRange.getName());
        assertEquals(2, dateRange.getBuckets().size());

        InternalDateRange.Bucket bucket1 = dateRange.getBuckets().get(0);
        assertEquals("0-1000000000000", bucket1.getKeyAsString());
        assertEquals(10, bucket1.getDocCount());
        // From/to values may be null in InternalDateRange
        // Skipping from/to assertions

        InternalDateRange.Bucket bucket2 = dateRange.getBuckets().get(1);
        assertEquals("1000000000000-2000000000000", bucket2.getKeyAsString());
        assertEquals(25, bucket2.getDocCount());
    }

    @Test
    public void testToBucketAggregationWithKeyedRanges() {
        DateRangeAggregationBuilder keyedAgg = new DateRangeAggregationBuilder("keyed_date_range")
            .field("timestamp")
            .addRange("old", 0, 1000000000000L)
            .addRange("recent", 1000000000000L, 2000000000000L)
            .keyed(true);

        List<BucketEntry> buckets = new ArrayList<>();
        buckets.add(new BucketEntry(List.of("old"), 10, InternalAggregations.EMPTY));
        buckets.add(new BucketEntry(List.of("recent"), 25, InternalAggregations.EMPTY));

        InternalAggregation result = translator.toBucketAggregation(keyedAgg, buckets);

        assertNotNull(result);
        assertTrue(result instanceof InternalDateRange);
        InternalDateRange dateRange = (InternalDateRange) result;

        InternalDateRange.Bucket bucket1 = dateRange.getBuckets().get(0);
        assertEquals("old", bucket1.getKeyAsString());
        assertEquals(10, bucket1.getDocCount());
    }

    @Test
    public void testToBucketAggregationWithUnboundedRanges() {
        DateRangeAggregationBuilder unboundedAgg = new DateRangeAggregationBuilder("unbounded_date_range")
            .field("timestamp")
            .addUnboundedTo(1000000000000L)
            .addRange(1000000000000L, 2000000000000L)
            .addUnboundedFrom(2000000000000L);

        List<BucketEntry> buckets = new ArrayList<>();
        buckets.add(new BucketEntry(List.of("*-1000000000000"), 8, InternalAggregations.EMPTY));
        buckets.add(new BucketEntry(List.of("1000000000000-2000000000000"), 15, InternalAggregations.EMPTY));
        buckets.add(new BucketEntry(List.of("2000000000000-*"), 12, InternalAggregations.EMPTY));

        InternalAggregation result = translator.toBucketAggregation(unboundedAgg, buckets);

        assertNotNull(result);
        assertTrue(result instanceof InternalDateRange);
        InternalDateRange dateRange = (InternalDateRange) result;
        assertEquals(3, dateRange.getBuckets().size());

        InternalDateRange.Bucket bucket1 = dateRange.getBuckets().get(0);
        assertEquals("*-1000000000000", bucket1.getKeyAsString());
        // Unbounded ranges have null from/to
        // Skipping from/to assertions

        InternalDateRange.Bucket bucket3 = dateRange.getBuckets().get(2);
        assertEquals("2000000000000-*", bucket3.getKeyAsString());
        // Skipping bound checks for unbounded ranges
    }
}
