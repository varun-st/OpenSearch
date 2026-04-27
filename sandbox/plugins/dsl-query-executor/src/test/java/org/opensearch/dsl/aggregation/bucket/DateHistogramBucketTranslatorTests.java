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
import org.opensearch.dsl.aggregation.DateHistogramGrouping;
import org.opensearch.dsl.aggregation.GroupingInfo;
import org.opensearch.dsl.result.BucketEntry;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.BucketOrder;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.InternalAggregations;
import org.opensearch.search.aggregations.bucket.histogram.DateHistogramAggregationBuilder;
import org.opensearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.opensearch.search.aggregations.bucket.histogram.InternalDateHistogram;
import org.opensearch.search.aggregations.metrics.AvgAggregationBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class DateHistogramBucketTranslatorTests {

    private DateHistogramBucketTranslator shape;
    private DateHistogramAggregationBuilder agg;

    @Before
    public void setUp() {
        shape = new DateHistogramBucketTranslator();
        agg = new DateHistogramAggregationBuilder("test_date_histogram")
            .field("timestamp")
            .calendarInterval(DateHistogramInterval.MONTH);
    }

    @Test
    public void testGetAggregationType() {
        assertEquals(DateHistogramAggregationBuilder.class, shape.getAggregationType());
    }

    @Test
    public void testGetGrouping() {
        GroupingInfo grouping = shape.getGrouping(agg);

        assertNotNull(grouping);
        assertTrue(grouping instanceof DateHistogramGrouping);
        assertEquals(List.of("timestamp"), grouping.getFieldNames());
    }

    @Test
    public void testGetBucketOrder() {
        BucketOrder order = BucketOrder.key(true);
        agg.order(order);

        assertEquals(order, shape.getBucketOrder(agg));
    }

    @Test
    public void testGetSubAggregations() {
        agg.subAggregation(new AvgAggregationBuilder("avg_value").field("value"));

        Collection<AggregationBuilder> subAggs = shape.getSubAggregations(agg);

        assertNotNull(subAggs);
        assertEquals(1, subAggs.size());
        assertEquals("avg_value", subAggs.iterator().next().getName());
    }

    @Test
    public void testToBucketAggregation_EmptyBuckets() {
        List<BucketEntry> buckets = new ArrayList<>();

        InternalAggregation result = shape.toBucketAggregation(agg, buckets);

        assertNotNull(result);
        assertTrue(result instanceof InternalDateHistogram);
        InternalDateHistogram histogram = (InternalDateHistogram) result;
        assertEquals("test_date_histogram", histogram.getName());
        assertEquals(0, histogram.getBuckets().size());
    }

    @Test
    public void testToBucketAggregation_SingleBucket() {
        long timestamp = 1704067200000L;
        BucketEntry entry = new BucketEntry(List.of(timestamp), 10L, InternalAggregations.EMPTY);
        List<BucketEntry> buckets = List.of(entry);

        InternalAggregation result = shape.toBucketAggregation(agg, buckets);

        assertNotNull(result);
        assertTrue(result instanceof InternalDateHistogram);
        InternalDateHistogram histogram = (InternalDateHistogram) result;
        assertEquals(1, histogram.getBuckets().size());
        assertEquals(10L, histogram.getBuckets().get(0).getDocCount());
    }

    @Test
    public void testToBucketAggregation_MinDocCountZero() {
        agg.calendarInterval(DateHistogramInterval.DAY);
        agg.minDocCount(0);

        List<BucketEntry> buckets = List.of(
            new BucketEntry(List.of(1704067200000L), 5L, InternalAggregations.EMPTY),  // 2024-01-01
            new BucketEntry(List.of(1704240000000L), 3L, InternalAggregations.EMPTY)   // 2024-01-03
        );

        InternalAggregation result = shape.toBucketAggregation(agg, buckets);

        assertNotNull(result);
        InternalDateHistogram histogram = (InternalDateHistogram) result;

        assertEquals(2, histogram.getBuckets().size());
        assertEquals(5L, histogram.getBuckets().get(0).getDocCount());
        assertEquals(3L, histogram.getBuckets().get(1).getDocCount());
    }

    @Test
    public void testToBucketAggregation_WithOffset() {
        agg.minDocCount(0);
        agg.offset(3600000L); // 1 hour offset

        List<BucketEntry> buckets = List.of(
            new BucketEntry(List.of(1704067200000L), 5L, InternalAggregations.EMPTY)
        );

        InternalAggregation result = shape.toBucketAggregation(agg, buckets);

        assertNotNull(result);
        InternalDateHistogram histogram = (InternalDateHistogram) result;

        assertEquals(1, histogram.getBuckets().size());
        assertEquals(5L, histogram.getBuckets().get(0).getDocCount());
    }

    @Test
    public void testToBucketAggregation_FixedInterval() {
        agg = new DateHistogramAggregationBuilder("test_date_histogram")
            .field("timestamp")
            .fixedInterval(DateHistogramInterval.hours(2));

        InternalAggregation result = shape.toBucketAggregation(agg, List.of());
        assertNotNull(result);
    }

    @Test
    public void testToBucketAggregation_MinDocCountNonZero() {
        agg.minDocCount(1);
        List<BucketEntry> buckets = List.of(
            new BucketEntry(List.of(1704067200000L), 5L, InternalAggregations.EMPTY)
        );

        InternalAggregation result = shape.toBucketAggregation(agg, buckets);
        assertNotNull(result);
        assertEquals(1, ((InternalDateHistogram) result).getBuckets().size());
    }

    @Test
    public void testToBucketAggregation_NonListIterable() {
        Set<BucketEntry> bucketSet = new HashSet<>(List.of(
            new BucketEntry(List.of(1704067200000L), 5L, InternalAggregations.EMPTY)
        ));

        InternalAggregation result = shape.toBucketAggregation(agg, bucketSet);
        assertNotNull(result);
        assertEquals(1, ((InternalDateHistogram) result).getBuckets().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testToBucketAggregation_NoIntervalSpecified() {
        agg = new DateHistogramAggregationBuilder("test_date_histogram").field("timestamp");
        shape.toBucketAggregation(agg, List.of());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testToBucketAggregation_UnsupportedCalendarInterval() {
        agg.calendarInterval(new DateHistogramInterval("1x"));
        shape.toBucketAggregation(agg, List.of());
    }
}
