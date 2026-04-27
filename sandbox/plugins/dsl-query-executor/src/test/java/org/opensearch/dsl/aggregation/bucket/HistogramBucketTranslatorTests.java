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
import org.opensearch.dsl.aggregation.HistogramGrouping;
import org.opensearch.dsl.result.BucketEntry;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.BucketOrder;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.InternalAggregations;
import org.opensearch.search.aggregations.bucket.histogram.HistogramAggregationBuilder;
import org.opensearch.search.aggregations.bucket.histogram.InternalHistogram;
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

public class HistogramBucketTranslatorTests {

    private HistogramBucketTranslator shape;
    private HistogramAggregationBuilder agg;

    @Before
    public void setUp() {
        shape = new HistogramBucketTranslator();
        agg = new HistogramAggregationBuilder("test_histogram")
            .field("price")
            .interval(100);
    }

    @Test
    public void testGetAggregationType() {
        assertEquals(HistogramAggregationBuilder.class, shape.getAggregationType());
    }

    @Test
    public void testGetGrouping() {
        GroupingInfo grouping = shape.getGrouping(agg);

        assertNotNull(grouping);
        assertTrue(grouping instanceof HistogramGrouping);
        assertEquals(List.of("price"), grouping.getFieldNames());
    }

    @Test
    public void testGetBucketOrder() {
        BucketOrder order = BucketOrder.key(true);
        agg.order(order);

        assertEquals(order, shape.getBucketOrder(agg));
    }

    @Test
    public void testGetSubAggregations() {
        agg.subAggregation(new AvgAggregationBuilder("avg_price").field("price"));

        Collection<AggregationBuilder> subAggs = shape.getSubAggregations(agg);

        assertNotNull(subAggs);
        assertEquals(1, subAggs.size());
        assertEquals("avg_price", subAggs.iterator().next().getName());
    }

    @Test
    public void testToBucketAggregation_EmptyBuckets() {
        List<BucketEntry> buckets = new ArrayList<>();

        InternalAggregation result = shape.toBucketAggregation(agg, buckets);

        assertNotNull(result);
        assertTrue(result instanceof InternalHistogram);
        InternalHistogram histogram = (InternalHistogram) result;
        assertEquals("test_histogram", histogram.getName());
        assertEquals(0, histogram.getBuckets().size());
    }

    @Test
    public void testToBucketAggregation_SingleBucket() {
        BucketEntry entry = new BucketEntry(List.of(100.0), 10L, InternalAggregations.EMPTY);
        List<BucketEntry> buckets = List.of(entry);

        InternalAggregation result = shape.toBucketAggregation(agg, buckets);

        assertNotNull(result);
        assertTrue(result instanceof InternalHistogram);
        InternalHistogram histogram = (InternalHistogram) result;
        assertEquals(1, histogram.getBuckets().size());
        assertEquals(100.0, (Double) histogram.getBuckets().get(0).getKey(), 0.001);
        assertEquals(10L, histogram.getBuckets().get(0).getDocCount());
    }

    @Test
    public void testToBucketAggregation_IntegerKey() {
        BucketEntry entry = new BucketEntry(List.of(50), 3L, InternalAggregations.EMPTY);
        List<BucketEntry> buckets = List.of(entry);

        InternalAggregation result = shape.toBucketAggregation(agg, buckets);

        assertNotNull(result);
        InternalHistogram histogram = (InternalHistogram) result;
        assertEquals(50.0, (Double) histogram.getBuckets().get(0).getKey(), 0.001);
    }

    @Test
    public void testToBucketAggregation_MinDocCountZero() {
        agg.minDocCount(0);

        List<BucketEntry> buckets = List.of(
            new BucketEntry(List.of(100.0), 5L, InternalAggregations.EMPTY),
            new BucketEntry(List.of(300.0), 3L, InternalAggregations.EMPTY)
        );

        InternalAggregation result = shape.toBucketAggregation(agg, buckets);

        assertNotNull(result);
        InternalHistogram histogram = (InternalHistogram) result;

        assertEquals(2, histogram.getBuckets().size());
        assertEquals(100.0, (Double) histogram.getBuckets().get(0).getKey(), 0.001);
        assertEquals(5L, histogram.getBuckets().get(0).getDocCount());
        assertEquals(300.0, (Double) histogram.getBuckets().get(1).getKey(), 0.001);
        assertEquals(3L, histogram.getBuckets().get(1).getDocCount());
    }

    @Test
    public void testToBucketAggregation_MinDocCountZeroEmptyBuckets() {
        agg.minDocCount(0);

        List<BucketEntry> emptyBuckets = new ArrayList<>();

        InternalAggregation result = shape.toBucketAggregation(agg, emptyBuckets);

        assertNotNull(result);
        InternalHistogram histogram = (InternalHistogram) result;

        assertEquals(0, histogram.getBuckets().size());
    }

    @Test
    public void testToBucketAggregation_MinDocCountNonZero() {
        agg.minDocCount(1);
        List<BucketEntry> buckets = List.of(
            new BucketEntry(List.of(100.0), 5L, InternalAggregations.EMPTY)
        );

        InternalAggregation result = shape.toBucketAggregation(agg, buckets);
        assertNotNull(result);
        assertEquals(1, ((InternalHistogram) result).getBuckets().size());
    }

    @Test
    public void testToBucketAggregation_NonListIterable() {
        Set<BucketEntry> bucketSet = new HashSet<>(List.of(
            new BucketEntry(List.of(100.0), 5L, InternalAggregations.EMPTY)
        ));

        InternalAggregation result = shape.toBucketAggregation(agg, bucketSet);
        assertNotNull(result);
        assertEquals(1, ((InternalHistogram) result).getBuckets().size());
    }
}
