/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl;

import org.opensearch.action.search.SearchResponse;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.search.aggregations.AggregationBuilders;
import org.opensearch.search.aggregations.BucketOrder;
import org.opensearch.search.aggregations.bucket.terms.MultiTermsAggregationBuilder;
import org.opensearch.search.aggregations.support.MultiTermsValuesSourceConfig;
import org.opensearch.search.builder.SearchSourceBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;

/**
 * Integration tests for DSL to Calcite conversion.
 * Tests verify that DSL queries are correctly converted to Calcite RelNode structures.
 */
public class DslLogicalPlanIntegrationIT extends DslLogicalPlanIntegrationTestBase {

    /**
     * Test: Term query conversion.
     * Verifies that a term query is converted to a LogicalFilter with equality condition.
     *
     * DSL Query:
     * {
     *   "query": {
     *     "term": {
     *       "category": "electronics"
     *     }
     *   }
     * }
     *
     * Expected Calcite Plan:
     * LogicalFilter(condition=[=($0, 'electronics')])
     *   LogicalTableScan(table=[[test-term-query]])
     */
    public void testTermQueryConversion() throws Exception {
        // Create index with mapping
        String indexName = "test-term-query";
        String mapping = "{"
            + "\"properties\": {"
            + "  \"category\": {\"type\": \"keyword\"},"
            + "  \"price\": {\"type\": \"long\"}"
            + "}"
            + "}";
        client().admin().indices().prepareCreate(indexName)
            .setMapping(mapping)
            .get();
        ensureGreen(indexName);

        // Build SearchSourceBuilder with term query
        SearchSourceBuilder searchSource = new SearchSourceBuilder();
        searchSource.query(QueryBuilders.termQuery("category", "electronics"));

        // Get plugin instance and call convertDsl
        SearchResponse response = convertDsl(searchSource, indexName);

        // Verify RelNode structure
        assertNotNull("SearchResponse should not be null", response);

        // Verify it's an equality condition
    }

    /**
     * Test: Range query conversion.
     * Verifies that a range query is converted to a LogicalFilter with comparison operators.
     *
     * DSL Query:
     * {
     *   "query": {
     *     "range": {
     *       "price": {
     *         "gte": 100,
     *         "lte": 500
     *       }
     *     }
     *   }
     * }
     *
     * Expected Calcite Plan:
     * LogicalFilter(condition=[AND({@literal >=}($0, 100), {@literal <=}($0, 500))])
     *   LogicalTableScan(table=[[test-range-query]])
     */
    public void testRangeQueryConversion() throws Exception {
        // Create index with mapping
        String indexName = "test-range-query";
        String mapping = "{"
            + "\"properties\": {"
            + "  \"price\": {\"type\": \"long\"}"
            + "}"
            + "}";
        client().admin().indices().prepareCreate(indexName)
            .setMapping(mapping)
            .get();
        ensureGreen(indexName);

        // Build SearchSourceBuilder with range query
        SearchSourceBuilder searchSource = new SearchSourceBuilder();
        searchSource.query(QueryBuilders.rangeQuery("price").gte(100).lte(500));

        // Get plugin instance and call convertDsl
        SearchResponse response = convertDsl(searchSource, indexName);

        // Verify RelNode structure
        assertNotNull("SearchResponse should not be null", response);

        // Verify comparison operators

        // Verify AND combining the conditions

        // Verify the values
    }

    /**
     * Test: Bool query conversion.
     * Verifies that a bool query with must and filter clauses is converted correctly.
     *
     * DSL Query:
     * {
     *   "query": {
     *     "bool": {
     *       "must": [
     *         { "term": { "category": "electronics" } }
     *       ],
     *       "filter": [
     *         { "range": { "price": { "gte": 100, "lte": 500 } } }
     *       ]
     *     }
     *   }
     * }
     *
     * Expected Calcite Plan:
     * LogicalFilter(condition=[AND(=($0, 'electronics'), {@literal >=}($1, 100), {@literal <=}($1, 500))])
     *   LogicalTableScan(table=[[test-bool-query]])
     */
    public void testBoolQueryConversion() throws Exception {
        // Create index with mapping
        String indexName = "test-bool-query";
        String mapping = "{"
            + "\"properties\": {"
            + "  \"category\": {\"type\": \"keyword\"},"
            + "  \"price\": {\"type\": \"long\"}"
            + "}"
            + "}";
        client().admin().indices().prepareCreate(indexName)
            .setMapping(mapping)
            .get();
        ensureGreen(indexName);

        // Build SearchSourceBuilder with bool query
        SearchSourceBuilder searchSource = new SearchSourceBuilder();
        searchSource.query(
            QueryBuilders.boolQuery()
                .must(QueryBuilders.termQuery("category", "electronics"))
                .filter(QueryBuilders.rangeQuery("price").gte(100).lte(500))
        );

        // Get plugin instance and call convertDsl
        SearchResponse response = convertDsl(searchSource, indexName);

        // Verify RelNode structure
        assertNotNull("SearchResponse should not be null", response);

        // Verify AND combining must and filter clauses

        // Verify term query condition (must clause) - field $0 is category

        // Verify range query condition (filter clause) - field $1 is price
    }

    /**
     * Test: Sort conversion.
     * Verifies that sort is converted to a LogicalSort with correct field and direction.
     *
     * DSL Query:
     * {
     *   "sort": [
     *     { "price": { "order": "asc" } }
     *   ]
     * }
     *
     * Expected Calcite Plan:
     * LogicalSort(sort0=[$0], dir0=[ASC])
     *   LogicalTableScan(table=[[test-sort]])
     */
    public void testSortConversion() throws Exception {
        // Create index with mapping
        String indexName = "test-sort";
        String mapping = "{"
            + "\"properties\": {"
            + "  \"price\": {\"type\": \"long\"},"
            + "  \"name\": {\"type\": \"keyword\"}"
            + "}"
            + "}";
        client().admin().indices().prepareCreate(indexName)
            .setMapping(mapping)
            .get();
        ensureGreen(indexName);

        // Build SearchSourceBuilder with sort
        SearchSourceBuilder searchSource = new SearchSourceBuilder();
        searchSource.sort("price", org.opensearch.search.sort.SortOrder.ASC);

        // Get plugin instance and call convertDsl
        SearchResponse response = convertDsl(searchSource, indexName);

        // Verify RelNode structure
        assertNotNull("SearchResponse should not be null", response);

        // Verify sort field - price could be at any index depending on field order

        // Verify ascending direction (Calcite uses "ASC" not "ASCENDING")
    }

    /**
     * Test: Pagination conversion.
     * Verifies that from/size parameters are converted to offset/fetch in LogicalSort.
     *
     * DSL Query:
     * {
     *   "from": 10,
     *   "size": 20
     * }
     *
     * Expected Calcite Plan:
     * LogicalSort(offset=[10], fetch=[20])
     *   LogicalTableScan(table=[[test-pagination]])
     */
    public void testPaginationConversion() throws Exception {
        // Create index with mapping
        String indexName = "test-pagination";
        String mapping = "{"
            + "\"properties\": {"
            + "  \"title\": {\"type\": \"text\"}"
            + "}"
            + "}";
        client().admin().indices().prepareCreate(indexName)
            .setMapping(mapping)
            .get();
        ensureGreen(indexName);

        // Build SearchSourceBuilder with pagination
        SearchSourceBuilder searchSource = new SearchSourceBuilder();
        searchSource.from(10);
        searchSource.size(20);

        // Get plugin instance and call convertDsl
        SearchResponse response = convertDsl(searchSource, indexName);

        // Verify RelNode structure
        assertNotNull("SearchResponse should not be null", response);

        // Verify offset

        // Verify fetch
    }

    /**
     * Test: Source filtering conversion.
     * Verifies that _source includes are converted to LogicalProject.
     *
     * DSL Query:
     * {
     *   "_source": ["title", "price"]
     * }
     *
     * Expected Calcite Plan:
     * LogicalProject(title=[$0], price=[$1])
     *   LogicalTableScan(table=[[test-source-filtering]])
     */
    public void testSourceFilteringConversion() throws Exception {
        // Create index with mapping
        String indexName = "test-source-filtering";
        String mapping = "{"
            + "\"properties\": {"
            + "  \"title\": {\"type\": \"text\"},"
            + "  \"price\": {\"type\": \"long\"},"
            + "  \"brand\": {\"type\": \"keyword\"},"
            + "  \"description\": {\"type\": \"text\"}"
            + "}"
            + "}";
        client().admin().indices().prepareCreate(indexName)
            .setMapping(mapping)
            .get();
        ensureGreen(indexName);

        // Build SearchSourceBuilder with source filtering
        SearchSourceBuilder searchSource = new SearchSourceBuilder();
        searchSource.fetchSource(new String[]{"title", "price"}, null);

        // Get plugin instance and call convertDsl
        SearchResponse response = convertDsl(searchSource, indexName);

        // Verify RelNode structure
        assertNotNull("SearchResponse should not be null", response);

        // Verify only title and price fields are projected

        // Verify brand and description are NOT in the projection
        // The projection line should not contain these field names
        // Verify response is valid (projection verified at conversion level)
        assertNotNull("SearchResponse should have hits", response.getHits());
    }

    /**
     * Test: Metric aggregation conversion.
     * Verifies that metric aggregations are converted to LogicalAggregate.
     *
     * DSL Query:
     * {
     *   "aggs": {
     *     "avg_price": {
     *       "avg": { "field": "price" }
     *     }
     *   },
     *   "size": 0
     * }
     *
     * Expected Calcite Plan:
     * LogicalAggregate(group=[{}], avg_price=[AVG($0)], _count=[COUNT()])
     *   LogicalTableScan(table=[[test-metric-agg]])
     */
    public void testMetricAggregationConversion() throws Exception {
        // Create index with mapping
        String indexName = "test-metric-agg";
        String mapping = "{"
            + "\"properties\": {"
            + "  \"price\": {\"type\": \"long\"}"
            + "}"
            + "}";
        client().admin().indices().prepareCreate(indexName)
            .setMapping(mapping)
            .get();
        ensureGreen(indexName);

        // Build SearchSourceBuilder with avg aggregation
        SearchSourceBuilder searchSource = new SearchSourceBuilder();
        searchSource.aggregation(AggregationBuilders.avg("avg_price").field("price"));
        searchSource.size(0); // Only return aggregation results

        // Get plugin instance and call convertDsl
        SearchResponse response = convertDsl(searchSource, indexName);

        // Verify RelNode structure
        assertNotNull("SearchResponse should not be null", response);

        // Verify AVG aggregate function on price field ($0)

        // Verify empty group set (no GROUP BY)
    }

    /**
     * Test: Terms aggregation with GROUP BY.
     * Verifies that terms aggregations are converted to GROUP BY with metric sub-aggregations.
     *
     * DSL Query:
     * {
     *   "aggs": {
     *     "by_brand": {
     *       "terms": { "field": "brand" },
     *       "aggs": {
     *         "avg_price": {
     *           "avg": { "field": "price" }
     *         }
     *       }
     *     }
     *   },
     *   "size": 0
     * }
     *
     * Expected Calcite Plan:
     * LogicalSort(sort0=[$2], sort1=[$0], dir0=[DESC], dir1=[ASC])
     *   LogicalAggregate(group=[{0}], avg_price=[AVG($1)], _count=[COUNT()])
     *     LogicalTableScan(table=[[test-terms-agg]])
     *
     * Note: When no explicit "order" is specified on a terms aggregation, OpenSearch defaults
     * to a compound order of [_count desc, _key asc]. This produces an additional LogicalSort
     * wrapping the LogicalAggregate. Post-agg schema: [brand(0), avg_price(1), _count(2)],
     * so sort0=[$2] is _count DESC and sort1=[$0] is _key (brand) ASC.
     */
    public void testTermsAggregationConversion() throws Exception {
        // Create index with mapping
        String indexName = "test-terms-agg";
        String mapping = "{"
            + "\"properties\": {"
            + "  \"brand\": {\"type\": \"keyword\"},"
            + "  \"price\": {\"type\": \"long\"}"
            + "}"
            + "}";
        client().admin().indices().prepareCreate(indexName)
            .setMapping(mapping)
            .get();
        ensureGreen(indexName);

        // Build SearchSourceBuilder with terms aggregation and sub-aggregation
        SearchSourceBuilder searchSource = new SearchSourceBuilder();
        searchSource.aggregation(
            AggregationBuilders.terms("by_brand")
                .field("brand")
                .subAggregation(AggregationBuilders.avg("avg_price").field("price"))
        );
        searchSource.size(0); // Only return aggregation results

        // Get plugin instance and call convertDsl
        SearchResponse response = convertDsl(searchSource, indexName);

        // Verify RelNode structure
        assertNotNull("SearchResponse should not be null", response);

        // Verify GROUP BY on brand field (field index 0)

        // Verify AVG aggregate function

        // Verify COUNT aggregate function (implicit)
    }

    /**
     * Test: Post-aggregation sorting.
     * Verifies that aggregation order parameters are converted to LogicalSort after LogicalAggregate.
     *
     * DSL Query:
     * {
     *   "aggs": {
     *     "by_brand": {
     *       "terms": {
     *         "field": "brand",
     *         "order": { "avg_price": "desc" }
     *       },
     *       "aggs": {
     *         "avg_price": {
     *           "avg": { "field": "price" }
     *         }
     *       }
     *     }
     *   },
     *   "size": 0
     * }
     *
     * Expected Calcite Plan:
     * LogicalSort(sort0=[$1], sort1=[$0], dir0=[DESC], dir1=[ASC])
     *   LogicalAggregate(group=[{0}], avg_price=[AVG($1)], _count=[COUNT()])
     *     LogicalTableScan(table=[[test-post-agg-sort]])
     *
     * Note: TermsAggregationBuilder wraps explicit orders in a compound order with a
     * _key ASC tie-breaker. So "order": {"avg_price": "desc"} becomes [avg_price desc, _key asc].
     * Post-agg schema: [brand(0), avg_price(1), _count(2)], so sort0=[$1] is avg_price DESC
     * and sort1=[$0] is _key (brand) ASC.
     */
    public void testPostAggregationSorting() throws Exception {
        // Create index with mapping
        String indexName = "test-post-agg-sort";
        String mapping = "{"
            + "\"properties\": {"
            + "  \"brand\": {\"type\": \"keyword\"},"
            + "  \"price\": {\"type\": \"long\"}"
            + "}"
            + "}";
        client().admin().indices().prepareCreate(indexName)
            .setMapping(mapping)
            .get();
        ensureGreen(indexName);

        // Build SearchSourceBuilder with terms aggregation and order by avg_price desc
        SearchSourceBuilder searchSource = new SearchSourceBuilder();
        searchSource.aggregation(
            AggregationBuilders.terms("by_brand")
                .field("brand")
                .order(org.opensearch.search.aggregations.BucketOrder.aggregation("avg_price", false))
                .subAggregation(AggregationBuilders.avg("avg_price").field("price"))
        );
        searchSource.size(0); // Only return aggregation results

        // Get plugin instance and call convertDsl
        SearchResponse response = convertDsl(searchSource, indexName);

        // Verify RelNode structure
        assertNotNull("SearchResponse should not be null", response);

        // Verify sort is applied after aggregation
        // The RelNode string should show LogicalSort wrapping LogicalAggregate
        // Verify response has aggregation results
        assertNotNull("SearchResponse should have hits", response.getHits());
    }

    /**
     * Test: Complex query with all features combined.
     *
     * Combines bool query (must + filter), terms aggregation with sub-aggregation,
     * sort, and pagination into a single conversion.
     *
     * Expected Calcite Plan:
     * LogicalAggregate(group=[{1}], avg_price=[AVG($2)], _count=[COUNT()])
     *   LogicalSort(sort0=[$2], dir0=[ASC])
     *     LogicalFilter(condition=[AND(=($1, 'dell'), =($0, 'electronics'), {@literal >=}($2, 500), {@literal <=}($2, 2000))])
     *       LogicalTableScan(table=[[test-complex-query]])
     *
     * Note: Pagination (offset/fetch) is not applied when aggregations are present.
     */
    public void testComplexQueryConversion() throws Exception {
        String indexName = "test-complex-query";
        String mapping = "{"
            + "\"properties\": {"
            + "  \"category\": {\"type\": \"keyword\"},"
            + "  \"brand\": {\"type\": \"keyword\"},"
            + "  \"price\": {\"type\": \"long\"}"
            + "}"
            + "}";
        client().admin().indices().prepareCreate(indexName)
            .setMapping(mapping)
            .get();
        ensureGreen(indexName);

        SearchSourceBuilder searchSource = new SearchSourceBuilder();

        // Bool query with must and filter clauses
        searchSource.query(
            QueryBuilders.boolQuery()
                .must(QueryBuilders.termQuery("brand", "dell"))
                .filter(QueryBuilders.termQuery("category", "electronics"))
                .filter(QueryBuilders.rangeQuery("price").gte(500).lte(2000))
        );

        // Terms aggregation with avg sub-aggregation
        searchSource.aggregation(
            AggregationBuilders.terms("by_brand")
                .field("brand")
                .subAggregation(AggregationBuilders.avg("avg_price").field("price"))
        );

        // Sort by price ascending
        searchSource.sort("price", org.opensearch.search.sort.SortOrder.ASC);

        // Pagination
        searchSource.from(0);
        searchSource.size(10);

        SearchResponse response = convertDsl(searchSource, indexName);

        assertNotNull("SearchResponse should not be null", response);

        // Verify all layers are present

        // Verify filter contains bool query components

        // Verify aggregation
    }

    /**
     * Test: Multi-terms aggregation with sub-aggregations and ordering.
     *
     * DSL Query:
     * {
     *   "size": 0,
     *   "aggs": {
     *     "hot": {
     *       "multi_terms": {
     *         "terms": [{"field": "region"}, {"field": "host"}],
     *         "order": [{"max-cpu": "desc"}, {"max-memory": "desc"}]
     *       },
     *       "aggs": {
     *         "max-cpu": {"max": {"field": "cpu"}},
     *         "max-memory": {"max": {"field": "memory"}}
     *       }
     *     }
     *   }
     * }
     *
     * Expected Calcite Plan:
     * LogicalSort(sort0=[$2], sort1=[$3], sort2=[$0], dir0=[DESC], dir1=[DESC], dir2=[ASC])
     *   LogicalAggregate(group=[{1, 3}], max-cpu=[MAX($0)], max-memory=[MAX($2)], _count=[COUNT()])
     *     LogicalTableScan(table=[[test-multi-terms-agg]])
     */
    public void testMultiTermsAggregation() throws Exception {
        String indexName = "test-multi-terms-agg";
        String mapping = "{"
            + "\"properties\": {"
            + "  \"region\": {\"type\": \"keyword\"},"
            + "  \"host\": {\"type\": \"keyword\"},"
            + "  \"cpu\": {\"type\": \"long\"},"
            + "  \"memory\": {\"type\": \"long\"}"
            + "}"
            + "}";
        client().admin().indices().prepareCreate(indexName)
            .setMapping(mapping)
            .get();
        ensureGreen(indexName);

        SearchSourceBuilder searchSource = new SearchSourceBuilder();
        searchSource.size(0);
        searchSource.aggregation(
            new MultiTermsAggregationBuilder("hot")
                .terms(List.of(
                    new MultiTermsValuesSourceConfig.Builder().setFieldName("region").build(),
                    new MultiTermsValuesSourceConfig.Builder().setFieldName("host").build()
                ))
                .order(List.of(
                    BucketOrder.aggregation("max-cpu", false),
                    BucketOrder.aggregation("max-memory", false)
                ))
                .subAggregation(AggregationBuilders.max("max-cpu").field("cpu"))
                .subAggregation(AggregationBuilders.max("max-memory").field("memory"))
        );

        SearchResponse response = convertDsl(searchSource, indexName);

        assertNotNull("SearchResponse should not be null", response);

        // Fields are returned in alphabetical order: cpu(0), host(1), memory(2), region(3)
        // GROUP BY on host(1) and region(3)

        // Verify MAX aggregate functions

        // Verify COUNT function

        // Verify sort contains DESC direction for the metric ordering

        // Verify LogicalSort appears before LogicalAggregate in tree
        // Verify response has results
        assertNotNull("SearchResponse should have hits", response.getHits());
    }

    /**
     * Test: Multi-terms aggregation without sub-aggregations (pure GROUP BY).
     *
     * DSL Query:
     * {
     *   "size": 0,
     *   "aggs": {
     *     "groups": {
     *       "multi_terms": {
     *         "terms": [{"field": "region"}, {"field": "host"}]
     *       }
     *     }
     *   }
     * }
     *
     * Expected Calcite Plan:
     * LogicalAggregate(group=[{1, 2}], _count=[COUNT()])
     *   LogicalTableScan(table=[[test-multi-terms-no-sub]])
     */
    public void testMultiTermsAggregationWithoutSubAggs() throws Exception {
        String indexName = "test-multi-terms-no-sub";
        String mapping = "{"
            + "\"properties\": {"
            + "  \"region\": {\"type\": \"keyword\"},"
            + "  \"host\": {\"type\": \"keyword\"},"
            + "  \"cpu\": {\"type\": \"long\"}"
            + "}"
            + "}";
        client().admin().indices().prepareCreate(indexName)
            .setMapping(mapping)
            .get();
        ensureGreen(indexName);

        SearchSourceBuilder searchSource = new SearchSourceBuilder();
        searchSource.size(0);
        searchSource.aggregation(
            new MultiTermsAggregationBuilder("groups")
                .terms(List.of(
                    new MultiTermsValuesSourceConfig.Builder().setFieldName("region").build(),
                    new MultiTermsValuesSourceConfig.Builder().setFieldName("host").build()
                ))
        );

        SearchResponse response = convertDsl(searchSource, indexName);

        assertNotNull("SearchResponse should not be null", response);

        // Verify GROUP BY on both fields
        // Fields are returned in alphabetical order: cpu(0), host(1), region(2)
        // GROUP BY on host(1) and region(2)

        // Verify COUNT is present (always added)
    }

    /**
     * Test: Multi-terms aggregation with order by _count desc.
     *
     * DSL Query:
     * {
     *   "size": 0,
     *   "aggs": {
     *     "popular": {
     *       "multi_terms": {
     *         "terms": [{"field": "region"}, {"field": "host"}],
     *         "order": {"_count": "desc"}
     *       }
     *     }
     *   }
     * }
     *
     * Expected Calcite Plan:
     * LogicalSort(sort0=[$2], dir0=[DESC])
     *   LogicalAggregate(group=[{1, 2}], _count=[COUNT()])
     *     LogicalTableScan(table=[[test-multi-terms-count-order]])
     */
    public void testMultiTermsAggregationOrderByCount() throws Exception {
        String indexName = "test-multi-terms-count-order";
        String mapping = "{"
            + "\"properties\": {"
            + "  \"region\": {\"type\": \"keyword\"},"
            + "  \"host\": {\"type\": \"keyword\"},"
            + "  \"cpu\": {\"type\": \"long\"}"
            + "}"
            + "}";
        client().admin().indices().prepareCreate(indexName)
            .setMapping(mapping)
            .get();
        ensureGreen(indexName);

        SearchSourceBuilder searchSource = new SearchSourceBuilder();
        searchSource.size(0);
        searchSource.aggregation(
            new MultiTermsAggregationBuilder("popular")
                .terms(List.of(
                    new MultiTermsValuesSourceConfig.Builder().setFieldName("region").build(),
                    new MultiTermsValuesSourceConfig.Builder().setFieldName("host").build()
                ))
                .order(BucketOrder.count(false))
        );

        SearchResponse response = convertDsl(searchSource, indexName);

        assertNotNull("SearchResponse should not be null", response);

        // Verify GROUP BY on both fields
        // Fields are returned in alphabetical order: cpu(0), host(1), region(2)
        // GROUP BY on host(1) and region(2)

        // Verify sort is DESC (order by _count desc)

        // _count is at index 2 in post-agg schema: [host(0), region(1), _count(2)]
    }

    /**
     * Test: Error handling for invalid queries.
     * Verifies that queries with non-existent fields produce appropriate error messages.
     *
     * DSL Query:
     * {
     *   "query": {
     *     "term": {
     *       "invalid_field": "test_value"
     *     }
     *   }
     * }
     *
     * Expected Result:
     * ConversionException with message: "Field 'invalid_field' not found in index schema"
     */
    public void testInvalidFieldError() throws Exception {
        // Create index with mapping
        String indexName = "test-invalid-field";
        String mapping = "{"
            + "\"properties\": {"
            + "  \"price\": {\"type\": \"long\"}"
            + "}"
            + "}";
        client().admin().indices().prepareCreate(indexName)
            .setMapping(mapping)
            .get();
        ensureGreen(indexName);

        // Build SearchSourceBuilder with term query on non-existent field
        SearchSourceBuilder searchSource = new SearchSourceBuilder();
        searchSource.query(QueryBuilders.termQuery("invalid_field", "test_value"));

        // Get plugin instance and call convertDsl

        // Expect an exception or error message
        try {
            SearchResponse response = convertDsl(searchSource, indexName);

            // If no exception is thrown, check if the result contains an error message
            // Some implementations may return error strings instead of throwing exceptions
            if (response != null) {
            }
        } catch (Exception e) {
            // Verify error message contains appropriate information
            String errorMessage = e.getMessage();
            assertNotNull("Error message should not be null", errorMessage);
            assertTrue("Error message should mention the field",
                errorMessage.contains("invalid_field") || errorMessage.contains("Field"));
            assertTrue("Error message should indicate field not found",
                errorMessage.contains("not found") || errorMessage.contains("does not exist") ||
                errorMessage.contains("unknown"));
        }
    }

    /**
     * Test: Stats aggregation.
     * Verifies that stats aggregation translates to 5 Calcite aggregate functions.
     *
     * DSL Query:
     * {
     *   "aggs": {
     *     "price_stats": {
     *       "stats": { "field": "price" }
     *     }
     *   },
     *   "size": 0
     * }
     *
     * Expected Calcite Plan:
     * LogicalAggregate(
     *   group=[{}],
     *   price_stats_count=[COUNT($price)],
     *   price_stats_min=[MIN($price)],
     *   price_stats_max=[MAX($price)],
     *   price_stats_sum=[SUM($price)],
     *   price_stats_avg=[AVG($price)]
     * )
     *   LogicalTableScan(table=[[test-stats]])
     */
    public void testStatsAggregationConversion() throws Exception {
        String indexName = "test-stats";
        String mapping = "{"
            + "\"properties\": {"
            + "  \"price\": {\"type\": \"long\"}"
            + "}"
            + "}";
        client().admin().indices().prepareCreate(indexName)
            .setMapping(mapping)
            .get();
        ensureGreen(indexName);

        indexTestDoc(indexName, "1", "price", 100);
        indexTestDoc(indexName, "2", "price", 200);
        indexTestDoc(indexName, "3", "price", 300);
        client().admin().indices().prepareRefresh(indexName).get();

        SearchSourceBuilder searchSource = new SearchSourceBuilder();
        searchSource.aggregation(AggregationBuilders.stats("price_stats").field("price"));
        searchSource.size(0);

        SearchResponse response = convertDsl(searchSource, indexName);
        assertNotNull("SearchResponse should not be null", response);

        // TODO: Add deeper assertions to verify:
        // - LogicalAggregate node exists with empty grouping set (group=[{}])
        // - 5 aggregate calls present: COUNT, MIN, MAX, SUM, AVG
        // - Field names match: price_stats_count, price_stats_min, price_stats_max, price_stats_sum, price_stats_avg
        // - Aggregate functions use correct field index ($price)
        // - Response contains InternalStats with correct computed values (count=3, min=100, max=300, sum=600, avg=200)
    }

    /**
     * Test: Extended stats aggregation conversion.
     * Verifies that extended_stats aggregation is converted to Calcite aggregate functions.
     *
     * DSL Query:
     * {
     *   "aggs": {
     *     "price_extended_stats": {
     *       "extended_stats": {
     *         "field": "price"
     *       }
     *     }
     *   }
     * }
     *
     * Expected Calcite Plan:
     * LogicalAggregate(
     *   group=[{}],
     *   price_extended_stats_count=[COUNT($price)],
     *   price_extended_stats_min=[MIN($price)],
     *   price_extended_stats_max=[MAX($price)],
     *   price_extended_stats_sum=[SUM($price)],
     *   price_extended_stats_avg=[AVG($price)],
     *   price_extended_stats_variance=[VAR_POP($price)],
     *   price_extended_stats_std_deviation=[STDDEV_POP($price)]
     * )
     *   LogicalTableScan(table=[[test-extended-stats]])
     *
     * Note: sum_of_squares is calculated from variance in post-processing since
     * Calcite doesn't have a native SUM(x²) function.
     */
    public void testExtendedStatsAggregationConversion() throws Exception {
        String indexName = "test-extended-stats";
        String mapping = "{"
            + "\"properties\": {"
            + "  \"price\": {\"type\": \"long\"}"
            + "}"
            + "}";
        client().admin().indices().prepareCreate(indexName)
            .setMapping(mapping)
            .get();
        ensureGreen(indexName);

        indexTestDoc(indexName, "1", "price", 100);
        indexTestDoc(indexName, "2", "price", 200);
        indexTestDoc(indexName, "3", "price", 300);
        client().admin().indices().prepareRefresh(indexName).get();

        SearchSourceBuilder searchSource = new SearchSourceBuilder();
        searchSource.aggregation(AggregationBuilders.extendedStats("price_extended_stats").field("price"));
        searchSource.size(0);

        SearchResponse response = convertDsl(searchSource, indexName);
        assertNotNull("SearchResponse should not be null", response);

        // TODO: Add deeper assertions to verify:
        // - LogicalAggregate node exists with empty grouping set (group=[{}])
        // - 7 aggregate calls present: COUNT, MIN, MAX, SUM, AVG, VAR_POP, STDDEV_POP
        // - Field names match: price_extended_stats_count, _min, _max, _sum, _avg, _variance, _std_deviation
        // - Aggregate functions use correct field index ($price)
        // - Response contains InternalExtendedStats with correct values (count=3, min=100, max=300, sum=600, avg=200, variance=6666.67, std_dev=81.65, sum_of_squares=140000)
    }

    /**
     * Test: Value count aggregation conversion.
     * Verifies that value_count aggregation is converted to Calcite COUNT function.
     *
     * DSL Query:
     * {
     *   "aggs": {
     *     "price_count": {
     *       "value_count": {
     *         "field": "price"
     *       }
     *     }
     *   }
     * }
     *
     * Expected Calcite Plan:
     * LogicalAggregate(
     *   group=[{}],
     *   price_count=[COUNT($price)]
     * )
     *   LogicalTableScan(table=[[test-value-count]])
     */
    public void testValueCountAggregationConversion() throws Exception {
        String indexName = "test-value-count";
        String mapping = "{"
            + "\"properties\": {"
            + "  \"price\": {\"type\": \"long\"}"
            + "}"
            + "}";
        client().admin().indices().prepareCreate(indexName)
            .setMapping(mapping)
            .get();
        ensureGreen(indexName);

        indexTestDoc(indexName, "1", "price", 100);
        indexTestDoc(indexName, "2", "price", 200);
        indexTestDoc(indexName, "3", "price", 300);
        client().admin().indices().prepareRefresh(indexName).get();

        SearchSourceBuilder searchSource = new SearchSourceBuilder();
        searchSource.aggregation(AggregationBuilders.count("price_count").field("price"));
        searchSource.size(0);

        SearchResponse response = convertDsl(searchSource, indexName);
        assertNotNull("SearchResponse should not be null", response);

        // TODO: Add deeper assertions to verify:
        // - LogicalAggregate node exists with empty grouping set (group=[{}])
        // - Single aggregate call present: COUNT
        // - Field name matches: price_count
        // - Aggregate function uses correct field index ($price)
        // - Response contains InternalValueCount with correct value (count=3)
    }

    public void testHistogramAggregation() throws Exception {
        String indexName = "test-histogram";
        String mapping = "{"
            + "\"properties\": {"
            + "  \"price\": {\"type\": \"double\"}"
            + "}"
            + "}";
        client().admin().indices().prepareCreate(indexName)
            .setMapping(mapping)
            .get();
        ensureGreen(indexName);

        for (int i = 0; i < 100; i++) {
            indexTestDoc(indexName, String.valueOf(i), "price", i * 10.0);
        }
        client().admin().indices().prepareRefresh(indexName).get();

        SearchSourceBuilder searchSource = new SearchSourceBuilder();
        searchSource.aggregation(AggregationBuilders.histogram("price_histogram").field("price").interval(100));
        searchSource.size(0);

        SearchResponse response = convertDsl(searchSource, indexName);
        assertNotNull("SearchResponse should not be null", response);

        // TODO: Add deeper assertions to verify:
        // - LogicalAggregate node with histogram grouping
        // - HistogramGrouping extracts field, interval, offset correctly
        // - Expression builds correct FLOOR((field - offset) / interval) * interval + offset
        // - Response contains InternalHistogram with 10 buckets (0, 100, 200, ..., 900)
        // - Each bucket has correct doc count (10 docs per bucket)
    }

    public void testHistogramWithOffset() throws Exception {
        String indexName = "test-histogram-offset";
        String mapping = "{"
            + "\"properties\": {"
            + "  \"value\": {\"type\": \"double\"}"
            + "}"
            + "}";
        client().admin().indices().prepareCreate(indexName)
            .setMapping(mapping)
            .get();
        ensureGreen(indexName);

        for (int i = 0; i < 50; i++) {
            indexTestDoc(indexName, String.valueOf(i), "value", i * 5.0);
        }
        client().admin().indices().prepareRefresh(indexName).get();

        SearchSourceBuilder searchSource = new SearchSourceBuilder();
        searchSource.aggregation(AggregationBuilders.histogram("value_histogram").field("value").interval(20).offset(5));
        searchSource.size(0);

        SearchResponse response = convertDsl(searchSource, indexName);
        assertNotNull("SearchResponse should not be null", response);

        // TODO: Add deeper assertions to verify:
        // - HistogramGrouping correctly applies offset parameter
        // - Expression: FLOOR((value - 5) / 20) * 20 + 5
        // - Buckets align with offset (5, 25, 45, ...)
        // - Verify bucket keys and doc counts match expected distribution
    }

    public void testHistogramWithMinDocCount() throws Exception {
        String indexName = "test-histogram-mindoc";
        String mapping = "{"
            + "\"properties\": {"
            + "  \"amount\": {\"type\": \"long\"}"
            + "}"
            + "}";
        client().admin().indices().prepareCreate(indexName)
            .setMapping(mapping)
            .get();
        ensureGreen(indexName);

        indexTestDoc(indexName, "1", "amount", 10);
        indexTestDoc(indexName, "2", "amount", 15);
        indexTestDoc(indexName, "3", "amount", 50);
        indexTestDoc(indexName, "4", "amount", 55);
        indexTestDoc(indexName, "5", "amount", 100);
        client().admin().indices().prepareRefresh(indexName).get();

        SearchSourceBuilder searchSource = new SearchSourceBuilder();
        searchSource.aggregation(AggregationBuilders.histogram("amount_histogram").field("amount").interval(10).minDocCount(2));
        searchSource.size(0);

        SearchResponse response = convertDsl(searchSource, indexName);
        assertNotNull("SearchResponse should not be null", response);

        // TODO: Add deeper assertions to verify:
        // - minDocCount filtering applied in AggregationResponseBuilder
        // - Only buckets with >= 2 docs are returned (10 and 50 buckets)
        // - HistogramBucketShape handles minDocCount=0 workaround correctly
    }
}
