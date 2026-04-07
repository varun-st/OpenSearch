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
import org.opensearch.dsl.aggregation.CompositeGrouping;
import org.opensearch.dsl.aggregation.GroupingInfo;
import org.opensearch.dsl.aggregation.HistogramGrouping;
import org.opensearch.dsl.aggregation.SimpleFieldGrouping;
import org.opensearch.dsl.converter.ConversionException;
import org.opensearch.dsl.result.BucketEntry;
import org.opensearch.search.aggregations.InternalAggregations;
import org.opensearch.search.aggregations.bucket.composite.CompositeAggregationBuilder;
import org.opensearch.search.aggregations.bucket.composite.DateHistogramValuesSourceBuilder;
import org.opensearch.search.aggregations.bucket.composite.HistogramValuesSourceBuilder;
import org.opensearch.search.aggregations.bucket.composite.InternalComposite;
import org.opensearch.search.aggregations.bucket.composite.TermsValuesSourceBuilder;

import java.util.List;

import static org.junit.Assert.*;

public class CompositeBucketTranslatorTests {

    private CompositeBucketTranslator translator;

    @Before
    public void setUp() {
        translator = new CompositeBucketTranslator();
    }

    @Test
    public void testGetAggregationType() {
        assertEquals(CompositeAggregationBuilder.class, translator.getAggregationType());
    }

    @Test
    public void testGetGroupingWithTermsSource() throws ConversionException {
        CompositeAggregationBuilder agg = new CompositeAggregationBuilder(
            "my_composite",
            List.of(new TermsValuesSourceBuilder("category").field("category"))
        );

        GroupingInfo grouping = translator.getGrouping(agg);

        assertTrue(grouping instanceof CompositeGrouping);
        CompositeGrouping composite = (CompositeGrouping) grouping;
        assertEquals(1, composite.getSourceGroupings().size());
        assertTrue(composite.getSourceGroupings().get(0) instanceof SimpleFieldGrouping);
    }

    @Test
    public void testGetGroupingWithMultipleSources() throws ConversionException {
        CompositeAggregationBuilder agg = new CompositeAggregationBuilder(
            "my_composite",
            List.of(
                new TermsValuesSourceBuilder("category").field("category"),
                new HistogramValuesSourceBuilder("price_bucket").field("price").interval(50)
            )
        );

        GroupingInfo grouping = translator.getGrouping(agg);

        assertTrue(grouping instanceof CompositeGrouping);
        CompositeGrouping composite = (CompositeGrouping) grouping;
        assertEquals(2, composite.getSourceGroupings().size());
        assertTrue(composite.getSourceGroupings().get(0) instanceof SimpleFieldGrouping);
        assertTrue(composite.getSourceGroupings().get(1) instanceof HistogramGrouping);
    }

    @Test
    public void testGetOrder() {
        CompositeAggregationBuilder agg = new CompositeAggregationBuilder(
            "my_composite",
            List.of(new TermsValuesSourceBuilder("category").field("category"))
        );

        assertNull(translator.getOrder(agg));
    }

    @Test
    public void testGetSubAggregations() {
        CompositeAggregationBuilder agg = new CompositeAggregationBuilder(
            "my_composite",
            List.of(new TermsValuesSourceBuilder("category").field("category"))
        );

        assertEquals(0, translator.getSubAggregations(agg).size());
    }

    @Test
    public void testToBucketAggregationWithEmptyBuckets() {
        CompositeAggregationBuilder agg = new CompositeAggregationBuilder(
            "my_composite",
            List.of(new TermsValuesSourceBuilder("category").field("category"))
        ).size(10);

        InternalComposite result = (InternalComposite) translator.toBucketAggregation(agg, List.of());

        assertEquals("my_composite", result.getName());
        assertEquals(0, result.getBuckets().size());
    }

    @Test
    public void testToBucketAggregation() {
        CompositeAggregationBuilder agg = new CompositeAggregationBuilder(
            "my_composite",
            List.of(new TermsValuesSourceBuilder("category").field("category"))
        ).size(10);

        BucketEntry entry = new BucketEntry(List.of("Electronics"), 100L, InternalAggregations.EMPTY);

        InternalComposite result = (InternalComposite) translator.toBucketAggregation(agg, List.of(entry));

        assertEquals("my_composite", result.getName());
        assertEquals(1, result.getBuckets().size());
        assertEquals(100L, result.getBuckets().get(0).getDocCount());
    }

    @Test
    public void testToBucketAggregationWithMultipleBuckets() {
        CompositeAggregationBuilder agg = new CompositeAggregationBuilder(
            "my_composite",
            List.of(
                new TermsValuesSourceBuilder("category").field("category"),
                new HistogramValuesSourceBuilder("price_bucket").field("price").interval(50)
            )
        ).size(10);

        BucketEntry entry1 = new BucketEntry(List.of("Electronics", 0.0), 50L, InternalAggregations.EMPTY);
        BucketEntry entry2 = new BucketEntry(List.of("Electronics", 50.0), 30L, InternalAggregations.EMPTY);
        BucketEntry entry3 = new BucketEntry(List.of("Books", 0.0), 20L, InternalAggregations.EMPTY);

        InternalComposite result = (InternalComposite) translator.toBucketAggregation(
            agg,
            List.of(entry1, entry2, entry3)
        );

        assertEquals("my_composite", result.getName());
        assertEquals(3, result.getBuckets().size());
        assertEquals(50L, result.getBuckets().get(0).getDocCount());
        assertEquals(30L, result.getBuckets().get(1).getDocCount());
        assertEquals(20L, result.getBuckets().get(2).getDocCount());
    }

    @Test
    public void testToBucketAggregationWithCustomSortOrder() {
        CompositeAggregationBuilder agg = new CompositeAggregationBuilder(
            "my_composite",
            List.of(
                new TermsValuesSourceBuilder("category").field("category").order(org.opensearch.search.sort.SortOrder.DESC)
            )
        ).size(10);

        BucketEntry entry = new BucketEntry(List.of("Electronics"), 100L, InternalAggregations.EMPTY);

        InternalComposite result = (InternalComposite) translator.toBucketAggregation(agg, List.of(entry));

        assertEquals("my_composite", result.getName());
        assertEquals(1, result.getBuckets().size());
        assertEquals(100L, result.getBuckets().get(0).getDocCount());
    }

    @Test
    public void testToBucketAggregationWithCustomMissingOrder() {
        CompositeAggregationBuilder agg = new CompositeAggregationBuilder(
            "my_composite",
            List.of(
                new TermsValuesSourceBuilder("category").field("category")
                    .missingOrder(org.opensearch.search.aggregations.bucket.missing.MissingOrder.FIRST)
            )
        ).size(10);

        BucketEntry entry = new BucketEntry(List.of("Electronics"), 100L, InternalAggregations.EMPTY);

        InternalComposite result = (InternalComposite) translator.toBucketAggregation(agg, List.of(entry));

        assertEquals("my_composite", result.getName());
        assertEquals(1, result.getBuckets().size());
        assertEquals(100L, result.getBuckets().get(0).getDocCount());
    }
}
