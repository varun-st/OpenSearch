/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl;

import org.opensearch.action.search.SearchResponse;
import org.opensearch.search.aggregations.AggregationBuilders;
import org.opensearch.search.aggregations.bucket.composite.CompositeAggregationBuilder;
import org.opensearch.search.aggregations.bucket.composite.HistogramValuesSourceBuilder;
import org.opensearch.search.aggregations.bucket.composite.InternalComposite;
import org.opensearch.search.aggregations.bucket.composite.TermsValuesSourceBuilder;
import org.opensearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.opensearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.opensearch.search.builder.SearchSourceBuilder;

import java.util.List;
import java.util.stream.Collectors;

import static org.opensearch.search.aggregations.AggregationBuilders.dateHistogram;

/**
 * Integration tests for DSL aggregation conversion.
 * Uses matchAllQuery; focus is on aggregation plan building.
 */
public class DslAggregationIT extends DslIntegTestBase {

    public void testMetricOnly() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(AggregationBuilders.avg("avg_price").field("price"))
        ));
    }

    public void testMultipleMetrics() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(AggregationBuilders.avg("avg_price").field("price"))
            .aggregation(AggregationBuilders.sum("total_price").field("price"))
            .aggregation(AggregationBuilders.min("min_price").field("price"))
            .aggregation(AggregationBuilders.max("max_price").field("price"))
        ));
    }

    public void testTermsBucket() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(new TermsAggregationBuilder("by_brand").field("brand"))
        ));
    }

    public void testTermsBucketWithMetric() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(new TermsAggregationBuilder("by_brand").field("brand")
                .subAggregation(AggregationBuilders.avg("avg_price").field("price")))
        ));
    }

    public void testNestedBuckets() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(new TermsAggregationBuilder("by_brand").field("brand")
                .subAggregation(AggregationBuilders.sum("total").field("price"))
                .subAggregation(new TermsAggregationBuilder("by_name").field("name")
                    .subAggregation(AggregationBuilders.avg("avg_price").field("price"))))
        ));
    }

    public void testAggsWithHits() {
        createTestIndex();
        // size > 0 with aggs produces both HITS + AGGREGATION plans
        assertOk(search(new SearchSourceBuilder()
            .size(10)
            .aggregation(AggregationBuilders.avg("avg_price").field("price"))
        ));
    }

    public void testHistogram() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(AggregationBuilders.histogram("price_histogram").field("price").interval(100))
        ));
    }

    public void testHistogramWithOffset() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(AggregationBuilders.histogram("price_histogram").field("price").interval(20).offset(5))
        ));
    }

    public void testHistogramWithMinDocCount() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(AggregationBuilders.histogram("price_histogram").field("price").interval(10).minDocCount(2))
        ));
    }

    public void testHistogramWithMetric() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(AggregationBuilders.histogram("price_histogram").field("price").interval(50)
                .subAggregation(AggregationBuilders.avg("avg_price").field("price")))
        ));
    }

    public void testDateHistogramCalendarInterval() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(dateHistogram("date_hist").field("timestamp").calendarInterval(DateHistogramInterval.MONTH))
        ));
    }

    public void testDateHistogramFixedInterval() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(dateHistogram("date_hist").field("timestamp").fixedInterval(DateHistogramInterval.hours(2)))
        ));
    }

    public void testDateHistogramWithMinDocCount() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(dateHistogram("date_hist").field("timestamp").fixedInterval(DateHistogramInterval.hours(1)).minDocCount(2))
        ));
    }

    public void testDateHistogramWithMetricSubAgg() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(dateHistogram("date_hist")
                .field("timestamp")
                .calendarInterval(DateHistogramInterval.DAY)
                .subAggregation(AggregationBuilders.avg("avg_price").field("price"))
            )
        ));
    }

    public void testTermsWithDateHistogramSubAgg() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(AggregationBuilders.terms("category_terms")
                .field("category")
                .subAggregation(dateHistogram("date_hist").field("timestamp").calendarInterval(DateHistogramInterval.DAY))
            )
        ));
    }

    public void testDateHistogramWithTermsSubAgg() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(dateHistogram("date_hist")
                .field("timestamp")
                .calendarInterval(DateHistogramInterval.DAY)
                .subAggregation(AggregationBuilders.terms("category_terms").field("category"))
            )
        ));
    }

    public void testNestedDateHistograms() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(dateHistogram("by_day")
                .field("timestamp")
                .calendarInterval(DateHistogramInterval.DAY)
                .subAggregation(dateHistogram("by_hour").field("timestamp").fixedInterval(DateHistogramInterval.hours(6)))
            )
        ));
    }

    public void testSiblingDateHistogramsDifferentIntervals() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(dateHistogram("by_day").field("timestamp").calendarInterval(DateHistogramInterval.DAY))
            .aggregation(dateHistogram("by_hour").field("timestamp").fixedInterval(DateHistogramInterval.hours(1)))
        ));
    }

    public void testRangeAggregation() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(AggregationBuilders.range("price_ranges")
                .field("price")
                .addRange(0, 50)
                .addRange(50, 100)
                .addRange(100, 200))
        ));
    }

    public void testRangeAggregationWithKeys() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(AggregationBuilders.range("price_ranges")
                .field("price")
                .addRange("cheap", 0, 50)
                .addRange("moderate", 50, 100)
                .addRange("expensive", 100, 200))
        ));
    }

    public void testRangeAggregationWithUnboundedRanges() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(AggregationBuilders.range("price_ranges")
                .field("price")
                .addUnboundedTo(50)
                .addRange(50, 100)
                .addUnboundedFrom(100))
        ));
    }

    public void testRangeAggregationWithMetric() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(AggregationBuilders.range("price_ranges")
                .field("price")
                .addRange(0, 100)
                .addRange(100, 200)
                .subAggregation(AggregationBuilders.avg("avg_rating").field("rating")))
        ));
    }

    public void testRangeAggregationKeyed() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(AggregationBuilders.range("price_ranges")
                .field("price")
                .addRange("low", 0, 50)
                .addRange("high", 50, 100)
                .keyed(true))
        ));
    }

    public void testNestedRangeAggregations() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(AggregationBuilders.range("price_ranges")
                .field("price")
                .addRange(0, 100)
                .addRange(100, 200)
                .subAggregation(AggregationBuilders.range("rating_ranges")
                    .field("rating")
                    .addRange(0, 3)
                    .addRange(3, 5)))
        ));
    }

    public void testDateRangeAggregation() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(AggregationBuilders.dateRange("date_ranges")
                .field("timestamp")
                .addRange(0, 1000000000000L)
                .addRange(1000000000000L, 2000000000000L))
        ));
    }

    public void testDateRangeAggregationWithKeys() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(AggregationBuilders.dateRange("date_ranges")
                .field("timestamp")
                .addRange("old", 0, 1000000000000L)
                .addRange("recent", 1000000000000L, 2000000000000L))
        ));
    }

    public void testDateRangeAggregationWithUnboundedRanges() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(AggregationBuilders.dateRange("date_ranges")
                .field("timestamp")
                .addUnboundedTo(1000000000000L)
                .addRange(1000000000000L, 2000000000000L)
                .addUnboundedFrom(2000000000000L))
        ));
    }

    public void testDateRangeAggregationKeyed() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(AggregationBuilders.dateRange("date_ranges")
                .field("timestamp")
                .addRange("before", 0, 1000000000000L)
                .addRange("after", 1000000000000L, 2000000000000L)
                .keyed(true))
        ));
    }

    public void testDateRangeAggregationWithDateMath() {
        createTestIndex();
        // Test with date math expressions
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(AggregationBuilders.dateRange("date_ranges")
                .field("timestamp")
                .addRange("now-7d/d", "now/d")
                .addRange("now/d", "now+1d/d"))
        ));
    }

    public void testDateRangeAggregationWithMixedDateFormats() {
        createTestIndex();
        // Test mixing date math and epoch milliseconds
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(AggregationBuilders.dateRange("date_ranges")
                .field("timestamp")
                .addRange("old", "0", "now-30d/d")
                .addRange("recent", "now-30d/d", "now/d"))
        ));
    }

    public void testCompositeAggregation() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(new CompositeAggregationBuilder("composite_agg",
                List.of(new TermsValuesSourceBuilder("category").field("category")))
            )
        ));
    }

    public void testCompositeAggregationWithMultipleSources() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(new CompositeAggregationBuilder("composite_agg",
                List.of(
                    new TermsValuesSourceBuilder("category").field("category"),
                    new HistogramValuesSourceBuilder("price_bucket").field("price").interval(100)
                ))
            )
        ));
    }

    public void testCompositeAggregationWithMetric() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(new CompositeAggregationBuilder("composite_agg",
                List.of(new TermsValuesSourceBuilder("category").field("category")))
                .subAggregation(AggregationBuilders.avg("avg_price").field("price"))
            )
        ));
    }

    public void testCompositeAggregationWithCustomOrder() {
        createTestIndex();
        assertOk(search(new SearchSourceBuilder()
            .size(0)
            .aggregation(new CompositeAggregationBuilder("composite_agg",
                List.of(new TermsValuesSourceBuilder("category").field("category")
                    .order(org.opensearch.search.sort.SortOrder.DESC)))
            )
        ));
    }

    public void testCompositeAggregationPagination() {
        createTestIndex();

        SearchResponse response1 = search(new SearchSourceBuilder()
            .size(0)
            .aggregation(new CompositeAggregationBuilder("composite_agg",
                List.of(new TermsValuesSourceBuilder("category").field("category")))
                .size(2)
            )
        );
        assertOk(response1);

        InternalComposite composite1 = response1.getAggregations().get("composite_agg");
        assertNotNull(composite1);
        assertEquals(2, composite1.getBuckets().size());
        assertNotNull(composite1.afterKey());

        SearchResponse response2 = search(new SearchSourceBuilder()
            .size(0)
            .aggregation(new CompositeAggregationBuilder("composite_agg",
                List.of(new TermsValuesSourceBuilder("category").field("category")))
                .size(2)
                .aggregateAfter(composite1.afterKey())
            )
        );
        assertOk(response2);

        InternalComposite composite2 = response2.getAggregations().get("composite_agg");
        assertNotNull(composite2);

        List<Object> keys1 = composite1.getBuckets().stream()
            .map(b -> b.getKey().get("category"))
            .collect(Collectors.toList());
        List<Object> keys2 = composite2.getBuckets().stream()
            .map(b -> b.getKey().get("category"))
            .collect(Collectors.toList());

        for (Object key : keys2) {
            assertFalse("Duplicate key found: " + key, keys1.contains(key));
        }
    }

    public void testCompositeAggregationMultiDimensionalPagination() {
        createTestIndex();

        SearchResponse response1 = search(new SearchSourceBuilder()
            .size(0)
            .aggregation(new CompositeAggregationBuilder("composite_agg",
                List.of(
                    new TermsValuesSourceBuilder("category").field("category"),
                    new HistogramValuesSourceBuilder("price_bucket").field("price").interval(100)
                ))
                .size(3)
            )
        );
        assertOk(response1);

        InternalComposite composite1 = response1.getAggregations().get("composite_agg");
        assertNotNull(composite1);
        assertNotNull(composite1.afterKey());
        assertTrue(composite1.afterKey().containsKey("category"));
        assertTrue(composite1.afterKey().containsKey("price_bucket"));

        SearchResponse response2 = search(new SearchSourceBuilder()
            .size(0)
            .aggregation(new CompositeAggregationBuilder("composite_agg",
                List.of(
                    new TermsValuesSourceBuilder("category").field("category"),
                    new HistogramValuesSourceBuilder("price_bucket").field("price").interval(100)
                ))
                .size(3)
                .aggregateAfter(composite1.afterKey())
            )
        );
        assertOk(response2);

        InternalComposite composite2 = response2.getAggregations().get("composite_agg");
        assertNotNull(composite2);

        List<String> keys1 = composite1.getBuckets().stream()
            .map(b -> b.getKey().get("category") + "|" + b.getKey().get("price_bucket"))
            .collect(Collectors.toList());
        List<String> keys2 = composite2.getBuckets().stream()
            .map(b -> b.getKey().get("category") + "|" + b.getKey().get("price_bucket"))
            .collect(Collectors.toList());

        for (String key : keys2) {
            assertFalse("Duplicate composite key found: " + key, keys1.contains(key));
        }
    }
}
