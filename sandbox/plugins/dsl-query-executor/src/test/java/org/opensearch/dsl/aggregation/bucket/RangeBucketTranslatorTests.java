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
import org.opensearch.dsl.aggregation.RangeGrouping;
import org.opensearch.dsl.converter.ConversionException;
import org.opensearch.dsl.result.BucketEntry;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.InternalAggregations;
import org.opensearch.search.aggregations.bucket.range.InternalRange;
import org.opensearch.search.aggregations.bucket.range.RangeAggregationBuilder;
import org.opensearch.search.aggregations.metrics.AvgAggregationBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class RangeBucketTranslatorTests {

    private RangeBucketTranslator translator;
    private RangeAggregationBuilder agg;

    @Before
    public void setUp() {
        translator = new RangeBucketTranslator();
        agg = new RangeAggregationBuilder("test_range")
            .field("price")
            .addRange(0, 50)
            .addRange(50, 100)
            .addRange(100, 200);
    }

    @Test
    public void testGetAggregationType() {
        assertEquals(RangeAggregationBuilder.class, translator.getAggregationType());
    }

    @Test
    public void testGetGrouping() throws ConversionException {
        GroupingInfo grouping = translator.getGrouping(agg);

        assertNotNull(grouping);
        assertTrue(grouping instanceof RangeGrouping);
        assertEquals(List.of("price"), grouping.getFieldNames());
    }

    @Test
    public void testGetOrder() {
        assertNull(translator.getOrder(agg));
    }

    @Test
    public void testGetSubAggregations() {
        agg.subAggregation(new AvgAggregationBuilder("avg_rating").field("rating"));

        Collection<AggregationBuilder> subAggs = translator.getSubAggregations(agg);

        assertNotNull(subAggs);
        assertEquals(1, subAggs.size());
        assertEquals("avg_rating", subAggs.iterator().next().getName());
    }

    @Test
    public void testToBucketAggregationWithEmptyBuckets() {
        List<BucketEntry> buckets = new ArrayList<>();

        InternalAggregation result = translator.toBucketAggregation(agg, buckets);

        assertNotNull(result);
        assertTrue(result instanceof InternalRange);
        @SuppressWarnings("unchecked")
        InternalRange<?, ?> range = (InternalRange<?, ?>) result;
        assertEquals("test_range", range.getName());
        assertEquals(0, range.getBuckets().size());
    }

    @Test
    public void testToBucketAggregationWithBuckets() {
        List<BucketEntry> buckets = new ArrayList<>();
        buckets.add(new BucketEntry(List.of("0.0-50.0"), 10, InternalAggregations.EMPTY));
        buckets.add(new BucketEntry(List.of("50.0-100.0"), 25, InternalAggregations.EMPTY));
        buckets.add(new BucketEntry(List.of("100.0-200.0"), 5, InternalAggregations.EMPTY));

        InternalAggregation result = translator.toBucketAggregation(agg, buckets);

        assertNotNull(result);
        assertTrue(result instanceof InternalRange);
        InternalRange<?, ?> range = (InternalRange<?, ?>) result;
        assertEquals("test_range", range.getName());
        assertEquals(3, range.getBuckets().size());

        InternalRange.Bucket bucket1 = (InternalRange.Bucket) range.getBuckets().get(0);
        assertEquals("0.0-50.0", bucket1.getKeyAsString());
        assertEquals(10, bucket1.getDocCount());
        assertEquals(0.0, ((Number) bucket1.getFrom()).doubleValue(), 0.001);
        assertEquals(50.0, ((Number) bucket1.getTo()).doubleValue(), 0.001);

        InternalRange.Bucket bucket2 = (InternalRange.Bucket) range.getBuckets().get(1);
        assertEquals("50.0-100.0", bucket2.getKeyAsString());
        assertEquals(25, bucket2.getDocCount());

        InternalRange.Bucket bucket3 = (InternalRange.Bucket) range.getBuckets().get(2);
        assertEquals("100.0-200.0", bucket3.getKeyAsString());
        assertEquals(5, bucket3.getDocCount());
    }

    @Test
    public void testToBucketAggregationWithKeyedRanges() {
        RangeAggregationBuilder keyedAgg = new RangeAggregationBuilder("keyed_range")
            .field("price")
            .addRange("cheap", 0, 50)
            .addRange("moderate", 50, 100)
            .addRange("expensive", 100, 200)
            .keyed(true);

        List<BucketEntry> buckets = new ArrayList<>();
        buckets.add(new BucketEntry(List.of("cheap"), 10, InternalAggregations.EMPTY));
        buckets.add(new BucketEntry(List.of("moderate"), 25, InternalAggregations.EMPTY));
        buckets.add(new BucketEntry(List.of("expensive"), 5, InternalAggregations.EMPTY));

        InternalAggregation result = translator.toBucketAggregation(keyedAgg, buckets);

        assertNotNull(result);
        assertTrue(result instanceof InternalRange);
        InternalRange<?, ?> range = (InternalRange<?, ?>) result;
        // keyed flag is internal

        InternalRange.Bucket bucket1 = (InternalRange.Bucket) range.getBuckets().get(0);
        assertEquals("cheap", bucket1.getKeyAsString());
        assertEquals(10, bucket1.getDocCount());
    }

    @Test
    public void testToBucketAggregationWithUnboundedRanges() {
        RangeAggregationBuilder unboundedAgg = new RangeAggregationBuilder("unbounded_range")
            .field("price")
            .addUnboundedTo(50)
            .addRange(50, 100)
            .addUnboundedFrom(100);

        List<BucketEntry> buckets = new ArrayList<>();
        buckets.add(new BucketEntry(List.of("*-50.0"), 8, InternalAggregations.EMPTY));
        buckets.add(new BucketEntry(List.of("50.0-100.0"), 15, InternalAggregations.EMPTY));
        buckets.add(new BucketEntry(List.of("100.0-*"), 12, InternalAggregations.EMPTY));

        InternalAggregation result = translator.toBucketAggregation(unboundedAgg, buckets);

        assertNotNull(result);
        assertTrue(result instanceof InternalRange);
        InternalRange<?, ?> range = (InternalRange<?, ?>) result;
        assertEquals(3, range.getBuckets().size());

        InternalRange.Bucket bucket1 = (InternalRange.Bucket) range.getBuckets().get(0);
        assertEquals("*-50.0", bucket1.getKeyAsString());
        assertTrue(Double.isInfinite(((Number) bucket1.getFrom()).doubleValue()));
        assertEquals(50.0, ((Number) bucket1.getTo()).doubleValue(), 0.001);

        InternalRange.Bucket bucket3 = (InternalRange.Bucket) range.getBuckets().get(2);
        assertEquals("100.0-*", bucket3.getKeyAsString());
        assertEquals(100.0, ((Number) bucket3.getFrom()).doubleValue(), 0.001);
        assertTrue(Double.isInfinite(((Number) bucket3.getTo()).doubleValue()));
    }

    @Test
    public void testToBucketAggregationSkipsNullKeys() {
        List<BucketEntry> buckets = new ArrayList<>();
        buckets.add(new BucketEntry(List.of("0.0-50.0"), 10, InternalAggregations.EMPTY));
        buckets.add(new BucketEntry(Collections.singletonList(null), 5, InternalAggregations.EMPTY));
        buckets.add(new BucketEntry(List.of("50.0-100.0"), 15, InternalAggregations.EMPTY));

        InternalAggregation result = translator.toBucketAggregation(agg, buckets);

        assertNotNull(result);
        assertTrue(result instanceof InternalRange);
        InternalRange<?, ?> range = (InternalRange<?, ?>) result;
        assertEquals(2, range.getBuckets().size());
    }
}
