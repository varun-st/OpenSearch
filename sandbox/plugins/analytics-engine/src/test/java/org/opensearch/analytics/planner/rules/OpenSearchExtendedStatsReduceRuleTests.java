/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.planner.rules;

import org.apache.calcite.plan.hep.HepMatchOrder;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.ImmutableBitSet;
import org.opensearch.analytics.planner.BasePlannerRulesTests;
import org.opensearch.analytics.spi.OpenSearchAggregateOperators;

import java.util.List;

/**
 * Unit tests for {@link OpenSearchExtendedStatsReduceRule}. Mirrors
 * {@code OpenSearchStatsReduceRuleTests} — applies only this rule via a minimal
 * {@link HepPlanner} and asserts the resulting tree's structure.
 *
 * <p>Tests are intentionally rule-scoped — they do not exercise {@code PlannerImpl}
 * marking, CBO, or fragment conversion. End-to-end planner integration is covered by
 * {@code ExtendedStatsAggregatePlanShapeTests}.
 */
public class OpenSearchExtendedStatsReduceRuleTests extends BasePlannerRulesTests {

    public void testSingleExtendedStatsCall_noGroupBy_decomposesIntoFivePrimitivesPlusProject() {
        StubTableScan scan = (StubTableScan) stubScan(mockTable("orders", "price"));
        AggregateCall call = makeExtendedStatsCall(scan, 0, "ext_stats_price");
        LogicalAggregate aggregate = LogicalAggregate.create(scan, List.of(), ImmutableBitSet.of(), null, List.of(call));

        RelNode rewritten = applyRule(aggregate);

        assertProjectOverAggregateWithFivePrimitives(rewritten, 0);
    }

    public void testSingleExtendedStatsCall_withGroupBy_preservesGroupSetAndPassesThroughGroupColumn() {
        StubTableScan scan = (StubTableScan) stubScan(mockTable("orders", "city", "price"));
        AggregateCall call = makeExtendedStatsCall(scan, 1, "ext_stats_price");
        LogicalAggregate aggregate = LogicalAggregate.create(scan, List.of(), ImmutableBitSet.of(0), null, List.of(call));

        RelNode rewritten = applyRule(aggregate);

        assertTrue("Top must be a Project", rewritten instanceof Project);
        Project project = (Project) rewritten;
        assertEquals(2, project.getProjects().size());
        assertEquals("city", project.getRowType().getFieldList().get(0).getName());
        assertEquals("ext_stats_price", project.getRowType().getFieldList().get(1).getName());

        Aggregate inner = (Aggregate) project.getInput();
        assertEquals(ImmutableBitSet.of(0), inner.getGroupSet());
        // Order matters — SUM-first emission preserves typeMatchesInferred after Volcano split.
        assertEquals(5, inner.getAggCallList().size());
        assertAggCallKinds(inner, SqlKind.SUM, SqlKind.MIN, SqlKind.MAX, SqlKind.SUM, SqlKind.COUNT);
    }

    public void testExtendedStatsMixedWithOtherAggregate_decomposesOnlyExtendedStats() {
        StubTableScan scan = (StubTableScan) stubScan(mockTable("orders", "price", "quantity"));
        AggregateCall extStatsCall = makeExtendedStatsCall(scan, 0, "ext_stats_price");
        AggregateCall avgCall = AggregateCall.create(
            SqlStdOperatorTable.AVG,
            false,
            false,
            false,
            List.of(),
            List.of(1),
            -1,
            null,
            RelCollations.EMPTY,
            0,
            scan,
            null,
            "avg_qty"
        );
        LogicalAggregate aggregate = LogicalAggregate.create(
            scan,
            List.of(),
            ImmutableBitSet.of(),
            null,
            List.of(extStatsCall, avgCall)
        );

        RelNode rewritten = applyRule(aggregate);

        Project project = (Project) rewritten;
        assertEquals(2, project.getProjects().size());

        Aggregate inner = (Aggregate) project.getInput();
        // 5 primitives for EXTENDED_STATS + 1 surviving AVG = 6.
        assertEquals(6, inner.getAggCallList().size());
        assertAggCallKinds(inner, SqlKind.SUM, SqlKind.MIN, SqlKind.MAX, SqlKind.SUM, SqlKind.COUNT, SqlKind.AVG);
        assertSame(SqlStdOperatorTable.AVG, inner.getAggCallList().get(5).getAggregation());
    }

    public void testMultipleExtendedStatsCalls_eachExpandsIntoFivePrimitives() {
        StubTableScan scan = (StubTableScan) stubScan(mockTable("orders", "price", "quantity"));
        AggregateCall ext1 = makeExtendedStatsCall(scan, 0, "ext_price");
        AggregateCall ext2 = makeExtendedStatsCall(scan, 1, "ext_qty");
        LogicalAggregate aggregate = LogicalAggregate.create(scan, List.of(), ImmutableBitSet.of(), null, List.of(ext1, ext2));

        RelNode rewritten = applyRule(aggregate);

        Project project = (Project) rewritten;
        assertEquals(2, project.getProjects().size());

        Aggregate inner = (Aggregate) project.getInput();
        // 5 primitives per EXTENDED_STATS × 2 = 10.
        assertEquals(10, inner.getAggCallList().size());
        assertAggCallKinds(
            inner,
            SqlKind.SUM,
            SqlKind.MIN,
            SqlKind.MAX,
            SqlKind.SUM,
            SqlKind.COUNT,
            SqlKind.SUM,
            SqlKind.MIN,
            SqlKind.MAX,
            SqlKind.SUM,
            SqlKind.COUNT
        );
        assertTrue(project.getProjects().get(0) instanceof RexCall);
        assertEquals(SqlKind.ROW, ((RexCall) project.getProjects().get(0)).getOperator().getKind());
        assertTrue(project.getProjects().get(1) instanceof RexCall);
        assertEquals(SqlKind.ROW, ((RexCall) project.getProjects().get(1)).getOperator().getKind());
    }

    public void testSumOfSquaresAggregatesOverDerivedSquaredColumn() {
        StubTableScan scan = (StubTableScan) stubScan(mockTable("orders", "price"));
        AggregateCall call = makeExtendedStatsCall(scan, 0, "ext_stats_price");
        LogicalAggregate aggregate = LogicalAggregate.create(scan, List.of(), ImmutableBitSet.of(), null, List.of(call));

        RelNode rewritten = applyRule(aggregate);

        // The rebuilt aggregate's input must be a LogicalProject that exposes the original
        // columns plus one derived squared column. Putting x*x in a Project beneath (not in
        // the aggCall's rexList) is essential: DistributedAggregateRewriter preserves rexList
        // verbatim into FINAL after Volcano split, which would silently change the meaning of
        // SUM(x*x). Routing through a derived column lets FINAL's argList rebase normally.
        Aggregate inner = (Aggregate) ((Project) rewritten).getInput();
        assertTrue(
            "Aggregate's input must be a LogicalProject that adds the squared column",
            inner.getInput() instanceof LogicalProject
        );
        LogicalProject squaredProject = (LogicalProject) inner.getInput();
        assertEquals("Project must add 1 squared column to the original 1-column input", 2, squaredProject.getProjects().size());

        RexNode squaredExpr = squaredProject.getProjects().get(1);
        assertTrue(squaredExpr instanceof RexCall);
        assertSame(SqlStdOperatorTable.MULTIPLY, ((RexCall) squaredExpr).getOperator());

        AggregateCall sumOfSquares = inner.getAggCallList().get(3);
        assertSame(SqlStdOperatorTable.SUM, sumOfSquares.getAggregation());
        assertTrue("SUM_OF_SQUARES must have empty rexList", sumOfSquares.rexList.isEmpty());
        assertEquals("SUM_OF_SQUARES argList must reference the squared column", List.of(1), sumOfSquares.getArgList());
    }

    public void testMultipleExtendedStatsOnSameSourceShareOneSquaredColumn() {
        StubTableScan scan = (StubTableScan) stubScan(mockTable("orders", "price"));
        AggregateCall ext1 = makeExtendedStatsCall(scan, 0, "ext_a");
        AggregateCall ext2 = makeExtendedStatsCall(scan, 0, "ext_b");
        LogicalAggregate aggregate = LogicalAggregate.create(scan, List.of(), ImmutableBitSet.of(), null, List.of(ext1, ext2));

        RelNode rewritten = applyRule(aggregate);

        Aggregate inner = (Aggregate) ((Project) rewritten).getInput();
        LogicalProject squaredProject = (LogicalProject) inner.getInput();
        assertEquals("Same-source EXTENDED_STATS calls must share one squared column", 2, squaredProject.getProjects().size());
        assertEquals(List.of(1), inner.getAggCallList().get(3).getArgList());
        assertEquals(List.of(1), inner.getAggCallList().get(8).getArgList());
    }

    public void testExtendedStatsCall_filterArgIsPropagatedToAllPrimitives() {
        // mockTable's default schema is single-column INTEGER, but FILTER needs a BOOLEAN
        // NOT NULL column — build a 2-col table where col 1 is BOOLEAN.
        StubTableScan scan = (StubTableScan) stubScan(
            mockTable("orders2", new String[] { "price", "in_scope" }, new SqlTypeName[] { SqlTypeName.INTEGER, SqlTypeName.BOOLEAN })
        );
        AggregateCall call = AggregateCall.create(
            OpenSearchAggregateOperators.EXTENDED_STATS,
            false,
            false,
            false,
            List.of(),
            List.of(0),
            1,
            null,
            RelCollations.EMPTY,
            0,
            scan,
            null,
            "ext_stats_price"
        );
        LogicalAggregate aggregate = LogicalAggregate.create(scan, List.of(), ImmutableBitSet.of(), null, List.of(call));

        RelNode rewritten = applyRule(aggregate);

        Aggregate inner = (Aggregate) ((Project) rewritten).getInput();
        for (AggregateCall primitive : inner.getAggCallList()) {
            assertEquals("filterArg must propagate to " + primitive.getAggregation().getName(), 1, primitive.filterArg);
        }
    }

    public void testRuleNoOpWhenNoExtendedStatsCallPresent() {
        StubTableScan scan = (StubTableScan) stubScan(mockTable("orders", "price"));
        AggregateCall sumCall = AggregateCall.create(
            SqlStdOperatorTable.SUM,
            false,
            false,
            false,
            List.of(),
            List.of(0),
            -1,
            null,
            RelCollations.EMPTY,
            0,
            scan,
            null,
            "sum_price"
        );
        LogicalAggregate aggregate = LogicalAggregate.create(scan, List.of(), ImmutableBitSet.of(), null, List.of(sumCall));

        RelNode rewritten = applyRule(aggregate);

        // No EXTENDED_STATS → rule does not fire → HEP returns the input unchanged.
        assertTrue("Plan must remain a LogicalAggregate", rewritten instanceof LogicalAggregate);
        assertEquals(1, ((LogicalAggregate) rewritten).getAggCallList().size());
        assertSame(SqlStdOperatorTable.SUM, ((LogicalAggregate) rewritten).getAggCallList().get(0).getAggregation());
    }

    public void testExtendedStatsStructFieldNamesMatchSpec() {
        StubTableScan scan = (StubTableScan) stubScan(mockTable("orders", "price"));
        AggregateCall call = makeExtendedStatsCall(scan, 0, "ext_stats_price");
        LogicalAggregate aggregate = LogicalAggregate.create(scan, List.of(), ImmutableBitSet.of(), null, List.of(call));

        RelNode rewritten = applyRule(aggregate);

        // Order matches legacy OpenSearch InternalExtendedStats.toXContent.
        Project project = (Project) rewritten;
        RelDataType type = project.getRowType().getFieldList().get(0).getType();
        assertTrue(type.isStruct());
        assertEquals(13, type.getFieldList().size());
        assertEquals(OpenSearchAggregateOperators.STATS_FIELD_COUNT, type.getFieldList().get(0).getName());
        assertEquals(OpenSearchAggregateOperators.STATS_FIELD_MIN, type.getFieldList().get(1).getName());
        assertEquals(OpenSearchAggregateOperators.STATS_FIELD_MAX, type.getFieldList().get(2).getName());
        assertEquals(OpenSearchAggregateOperators.STATS_FIELD_AVG, type.getFieldList().get(3).getName());
        assertEquals(OpenSearchAggregateOperators.STATS_FIELD_SUM, type.getFieldList().get(4).getName());
        assertEquals(OpenSearchAggregateOperators.EXTENDED_STATS_FIELD_SUM_OF_SQUARES, type.getFieldList().get(5).getName());
        assertEquals(OpenSearchAggregateOperators.EXTENDED_STATS_FIELD_VARIANCE, type.getFieldList().get(6).getName());
        assertEquals(OpenSearchAggregateOperators.EXTENDED_STATS_FIELD_VARIANCE_POPULATION, type.getFieldList().get(7).getName());
        assertEquals(OpenSearchAggregateOperators.EXTENDED_STATS_FIELD_VARIANCE_SAMPLING, type.getFieldList().get(8).getName());
        assertEquals(OpenSearchAggregateOperators.EXTENDED_STATS_FIELD_STD_DEVIATION, type.getFieldList().get(9).getName());
        assertEquals(OpenSearchAggregateOperators.EXTENDED_STATS_FIELD_STD_DEVIATION_POPULATION, type.getFieldList().get(10).getName());
        assertEquals(OpenSearchAggregateOperators.EXTENDED_STATS_FIELD_STD_DEVIATION_SAMPLING, type.getFieldList().get(11).getName());
        assertEquals(OpenSearchAggregateOperators.EXTENDED_STATS_FIELD_STD_DEVIATION_BOUNDS, type.getFieldList().get(12).getName());
    }

    public void testExtendedStatsBoundsStructFieldNamesMatchSpec() {
        StubTableScan scan = (StubTableScan) stubScan(mockTable("orders", "price"));
        AggregateCall call = makeExtendedStatsCall(scan, 0, "ext_stats_price");
        LogicalAggregate aggregate = LogicalAggregate.create(scan, List.of(), ImmutableBitSet.of(), null, List.of(call));

        RelNode rewritten = applyRule(aggregate);

        // Order matches the std_deviation_bounds sub-struct of legacy InternalExtendedStats.
        Project project = (Project) rewritten;
        RelDataType extType = project.getRowType().getFieldList().get(0).getType();
        RelDataType boundsType = extType.getFieldList().get(12).getType();
        assertTrue("std_deviation_bounds must be a struct", boundsType.isStruct());
        assertEquals(6, boundsType.getFieldList().size());
        assertEquals(OpenSearchAggregateOperators.EXTENDED_STATS_BOUND_UPPER, boundsType.getFieldList().get(0).getName());
        assertEquals(OpenSearchAggregateOperators.EXTENDED_STATS_BOUND_LOWER, boundsType.getFieldList().get(1).getName());
        assertEquals(OpenSearchAggregateOperators.EXTENDED_STATS_BOUND_UPPER_POPULATION, boundsType.getFieldList().get(2).getName());
        assertEquals(OpenSearchAggregateOperators.EXTENDED_STATS_BOUND_LOWER_POPULATION, boundsType.getFieldList().get(3).getName());
        assertEquals(OpenSearchAggregateOperators.EXTENDED_STATS_BOUND_UPPER_SAMPLING, boundsType.getFieldList().get(4).getName());
        assertEquals(OpenSearchAggregateOperators.EXTENDED_STATS_BOUND_LOWER_SAMPLING, boundsType.getFieldList().get(5).getName());
    }

    public void testExtendedStatsCall_distinctIsPropagatedWhereMeaningful() {
        StubTableScan scan = (StubTableScan) stubScan(mockTable("orders", "price"));
        AggregateCall call = AggregateCall.create(
            OpenSearchAggregateOperators.EXTENDED_STATS,
            true,
            false,
            false,
            List.of(),
            List.of(0),
            -1,
            null,
            RelCollations.EMPTY,
            0,
            scan,
            null,
            "ext_stats_price"
        );
        LogicalAggregate aggregate = LogicalAggregate.create(scan, List.of(), ImmutableBitSet.of(), null, List.of(call));

        RelNode rewritten = applyRule(aggregate);

        Aggregate inner = (Aggregate) ((Project) rewritten).getInput();
        // Calcite normalizes DISTINCT off MIN/MAX (idempotent). SUM and COUNT preserve it.
        for (AggregateCall primitive : inner.getAggCallList()) {
            SqlKind kind = primitive.getAggregation().getKind();
            if (kind == SqlKind.SUM || kind == SqlKind.COUNT) {
                assertTrue("DISTINCT must propagate to " + kind, primitive.isDistinct());
            } else {
                assertFalse("Calcite normalizes DISTINCT off " + kind, primitive.isDistinct());
            }
        }
    }

    // ---- Helpers ----

    private AggregateCall makeExtendedStatsCall(RelNode input, int colIdx, String name) {
        return AggregateCall.create(
            OpenSearchAggregateOperators.EXTENDED_STATS,
            false,
            false,
            false,
            List.of(),
            List.of(colIdx),
            -1,
            null,
            RelCollations.EMPTY,
            0,
            input,
            null,
            name
        );
    }

    private RelNode applyRule(RelNode root) {
        HepProgramBuilder builder = new HepProgramBuilder();
        builder.addMatchOrder(HepMatchOrder.BOTTOM_UP);
        builder.addRuleInstance(OpenSearchExtendedStatsReduceRule.INSTANCE);
        HepPlanner planner = new HepPlanner(builder.build());
        planner.setRoot(root);
        return planner.findBestExp();
    }

    private static void assertProjectOverAggregateWithFivePrimitives(RelNode rewritten, int colIdx) {
        assertTrue("Top must be a Project", rewritten instanceof Project);
        Project project = (Project) rewritten;
        assertTrue("Project's input must be a LogicalAggregate", project.getInput() instanceof LogicalAggregate);
        LogicalAggregate inner = (LogicalAggregate) project.getInput();
        assertEquals(5, inner.getAggCallList().size());
        assertAggCallKinds(inner, SqlKind.SUM, SqlKind.MIN, SqlKind.MAX, SqlKind.SUM, SqlKind.COUNT);
        RelDataType extType = project.getRowType().getFieldList().get(colIdx).getType();
        assertTrue("EXTENDED_STATS column must be a struct type", extType.isStruct());
    }

    private static void assertAggCallKinds(Aggregate aggregate, SqlKind... expectedKinds) {
        assertEquals("aggCall count mismatch", expectedKinds.length, aggregate.getAggCallList().size());
        for (int i = 0; i < expectedKinds.length; i++) {
            assertEquals(
                "aggCall " + i + " kind mismatch",
                expectedKinds[i],
                aggregate.getAggCallList().get(i).getAggregation().getKind()
            );
        }
    }
}
