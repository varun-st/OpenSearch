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
import java.util.List;

import static org.junit.Assert.*;

public class DateHistogramBucketShapeTest {

    private DateHistogramBucketShape shape;
    private DateHistogramAggregationBuilder agg;

    @Before
    public void setUp() {
        shape = new DateHistogramBucketShape();
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
    public void testGetOrder() {
        BucketOrder order = BucketOrder.key(true);
        agg.order(order);

        assertEquals(order, shape.getOrder(agg));
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
    public void testGetMinDocCount() {
        assertEquals(0L, shape.getMinDocCount(agg));

        agg.minDocCount(5);
        assertEquals(5L, shape.getMinDocCount(agg));
    }

    @Test
    public void testToBucketAggregationWithEmptyBuckets() {
        List<BucketEntry> buckets = new ArrayList<>();

        InternalAggregation result = shape.toBucketAggregation(agg, buckets);

        assertNotNull(result);
        assertTrue(result instanceof InternalDateHistogram);
        InternalDateHistogram histogram = (InternalDateHistogram) result;
        assertEquals("test_date_histogram", histogram.getName());
        assertEquals(0, histogram.getBuckets().size());
    }

    @Test
    public void testToBucketAggregation() {
        long timestamp = 1704067200000L;
        BucketEntry entry = new BucketEntry(List.of(timestamp), 10L, InternalAggregations.EMPTY);
        List<BucketEntry> buckets = List.of(entry);

        InternalAggregation result = shape.toBucketAggregation(agg, buckets);

        assertNotNull(result);
        assertTrue(result instanceof InternalDateHistogram);
        InternalDateHistogram histogram = (InternalDateHistogram) result;
        assertEquals(1, histogram.getBuckets().size());
        assertEquals(timestamp, ((InternalDateHistogram.Bucket) histogram.getBuckets().get(0)).getKey());
        assertEquals(10L, histogram.getBuckets().get(0).getDocCount());
    }

    @Test
    public void testToBucketAggregationWithMinDocCountZero() {
        agg.minDocCount(0);

        BucketEntry entry = new BucketEntry(List.of(1704067200000L), 10L, InternalAggregations.EMPTY);

        // This test verifies the minDocCount=0 workaround. Without it, InternalDateHistogram
        // constructor would throw exception requiring EmptyBucketInfo.
        InternalAggregation result = shape.toBucketAggregation(agg, List.of(entry));

        assertNotNull(result);
        assertTrue(result instanceof InternalDateHistogram);
    }
}
