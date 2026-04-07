/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.Test;
import org.opensearch.dsl.converter.ConversionException;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class CompositeGroupingTests {

    @Test
    public void testGetSourceGroupings() {
        SimpleFieldGrouping terms = new SimpleFieldGrouping(List.of("category"));
        HistogramGrouping histogram = new HistogramGrouping("price_hist", "price", 50.0, 0.0);

        CompositeGrouping composite = new CompositeGrouping(List.of(terms, histogram), null);

        assertEquals(2, composite.getSourceGroupings().size());
        assertSame(terms, composite.getSourceGroupings().get(0));
        assertSame(histogram, composite.getSourceGroupings().get(1));
    }

    @Test
    public void testGetFieldNames() {
        SimpleFieldGrouping terms = new SimpleFieldGrouping(List.of("category"));
        HistogramGrouping histogram = new HistogramGrouping("price_hist", "price", 50.0, 0.0);

        CompositeGrouping composite = new CompositeGrouping(List.of(terms, histogram), null);

        List<String> fieldNames = composite.getFieldNames();

        assertEquals(2, fieldNames.size());
        assertEquals("category", fieldNames.get(0));
        assertEquals("price", fieldNames.get(1));
    }

    @Test
    public void testGetFieldNamesWithMultiFieldSource() {
        SimpleFieldGrouping multiTerms = new SimpleFieldGrouping(List.of("category", "brand"));
        HistogramGrouping histogram = new HistogramGrouping("price_hist", "price", 50.0, 0.0);

        CompositeGrouping composite = new CompositeGrouping(List.of(multiTerms, histogram), null);

        List<String> fieldNames = composite.getFieldNames();

        assertEquals(3, fieldNames.size());
        assertEquals("category", fieldNames.get(0));
        assertEquals("brand", fieldNames.get(1));
        assertEquals("price", fieldNames.get(2));
    }

    @Test
    public void testHasAfterKey() {
        SimpleFieldGrouping terms = new SimpleFieldGrouping(List.of("category"));

        CompositeGrouping withoutAfterKey = new CompositeGrouping(List.of(terms), null);
        assertFalse(withoutAfterKey.hasAfterKey());

        CompositeGrouping withEmptyAfterKey = new CompositeGrouping(List.of(terms), Map.of());
        assertFalse(withEmptyAfterKey.hasAfterKey());

        CompositeGrouping withAfterKey = new CompositeGrouping(List.of(terms), Map.of("category", "Electronics"));
        assertTrue(withAfterKey.hasAfterKey());
    }

    @Test
    public void testBuildAfterKeyFilterSingleSource() throws ConversionException {
        SimpleFieldGrouping terms = new SimpleFieldGrouping(List.of("category"));
        Map<String, Object> afterKey = Map.of("category", "Electronics");
        CompositeGrouping composite = new CompositeGrouping(List.of(terms), afterKey);

        RelDataTypeFactory typeFactory = new SqlTypeFactoryImpl(org.apache.calcite.rel.type.RelDataTypeSystem.DEFAULT);
        RelDataType rowType = typeFactory.builder()
            .add("category", SqlTypeName.VARCHAR)
            .build();
        RexBuilder rexBuilder = new RexBuilder(typeFactory);

        RexNode filter = composite.buildAfterKeyFilter(rowType, rexBuilder, List.of("category"));

        assertNotNull(filter);
        assertTrue(filter.toString().contains(">"));
        assertTrue(filter.toString().contains("Electronics"));
    }

    @Test
    public void testBuildAfterKeyFilterMultipleSources() throws ConversionException {
        SimpleFieldGrouping terms = new SimpleFieldGrouping(List.of("category"));
        SimpleFieldGrouping status = new SimpleFieldGrouping(List.of("status"));
        Map<String, Object> afterKey = Map.of("category", "Electronics", "status", "active");
        CompositeGrouping composite = new CompositeGrouping(List.of(terms, status), afterKey);

        RelDataTypeFactory typeFactory = new SqlTypeFactoryImpl(org.apache.calcite.rel.type.RelDataTypeSystem.DEFAULT);
        RelDataType rowType = typeFactory.builder()
            .add("category", SqlTypeName.VARCHAR)
            .add("status", SqlTypeName.VARCHAR)
            .build();
        RexBuilder rexBuilder = new RexBuilder(typeFactory);

        RexNode filter = composite.buildAfterKeyFilter(rowType, rexBuilder, List.of("category", "status"));

        assertNotNull(filter);
        String filterStr = filter.toString();
        assertTrue(filterStr.contains("OR"));
        assertTrue(filterStr.contains("AND"));
        assertTrue(filterStr.contains("Electronics"));
        assertTrue(filterStr.contains("active"));
    }

    @Test
    public void testBuildAfterKeyFilterReturnsNullWhenNoAfterKey() throws ConversionException {
        SimpleFieldGrouping terms = new SimpleFieldGrouping(List.of("category"));
        CompositeGrouping composite = new CompositeGrouping(List.of(terms), null);

        RelDataTypeFactory typeFactory = new SqlTypeFactoryImpl(org.apache.calcite.rel.type.RelDataTypeSystem.DEFAULT);
        RelDataType rowType = typeFactory.builder()
            .add("category", SqlTypeName.VARCHAR)
            .build();
        RexBuilder rexBuilder = new RexBuilder(typeFactory);

        RexNode filter = composite.buildAfterKeyFilter(rowType, rexBuilder, List.of("category"));

        assertNull(filter);
    }
}
