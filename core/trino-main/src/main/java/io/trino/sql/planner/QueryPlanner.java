/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.sql.planner;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import io.trino.Session;
import io.trino.metadata.Metadata;
import io.trino.metadata.ResolvedFunction;
import io.trino.metadata.TableHandle;
import io.trino.metadata.TableMetadata;
import io.trino.operator.ChangeOnlyUpdatedColumnsMergeProcessor;
import io.trino.operator.DeleteAndInsertMergeProcessor;
import io.trino.operator.RowChangeProcessor;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ColumnSchema;
import io.trino.spi.connector.MergeCaseDetails;
import io.trino.spi.connector.MergeCaseKind;
import io.trino.spi.connector.MergeDetails;
import io.trino.spi.connector.RowChangeParadigm;
import io.trino.spi.connector.SortOrder;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.RowType;
import io.trino.spi.type.Type;
import io.trino.sql.ExpressionUtils;
import io.trino.sql.NodeUtils;
import io.trino.sql.analyzer.Analysis;
import io.trino.sql.analyzer.Analysis.GroupingSetAnalysis;
import io.trino.sql.analyzer.Analysis.MergeAnalysis;
import io.trino.sql.analyzer.Analysis.ResolvedWindow;
import io.trino.sql.analyzer.Analysis.SelectExpression;
import io.trino.sql.analyzer.Field;
import io.trino.sql.analyzer.FieldId;
import io.trino.sql.analyzer.RelationType;
import io.trino.sql.planner.plan.AggregationNode;
import io.trino.sql.planner.plan.AggregationNode.Aggregation;
import io.trino.sql.planner.plan.Assignments;
import io.trino.sql.planner.plan.DeleteAndInsertNode;
import io.trino.sql.planner.plan.DeleteNode;
import io.trino.sql.planner.plan.FilterNode;
import io.trino.sql.planner.plan.GroupIdNode;
import io.trino.sql.planner.plan.LimitNode;
import io.trino.sql.planner.plan.MergeNode;
import io.trino.sql.planner.plan.OffsetNode;
import io.trino.sql.planner.plan.PlanNode;
import io.trino.sql.planner.plan.PlanNodeId;
import io.trino.sql.planner.plan.ProjectNode;
import io.trino.sql.planner.plan.SimplePlanRewriter;
import io.trino.sql.planner.plan.SortNode;
import io.trino.sql.planner.plan.TableScanNode;
import io.trino.sql.planner.plan.TableWriterNode.DeleteTarget;
import io.trino.sql.planner.plan.TableWriterNode.MergeTarget;
import io.trino.sql.planner.plan.TableWriterNode.UpdateTarget;
import io.trino.sql.planner.plan.UnionNode;
import io.trino.sql.planner.plan.UpdateNode;
import io.trino.sql.planner.plan.ValuesNode;
import io.trino.sql.planner.plan.WindowNode;
import io.trino.sql.tree.Cast;
import io.trino.sql.tree.ComparisonExpression;
import io.trino.sql.tree.DecimalLiteral;
import io.trino.sql.tree.Delete;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.FetchFirst;
import io.trino.sql.tree.FieldReference;
import io.trino.sql.tree.FrameBound;
import io.trino.sql.tree.FunctionCall;
import io.trino.sql.tree.FunctionCall.NullTreatment;
import io.trino.sql.tree.GenericLiteral;
import io.trino.sql.tree.Identifier;
import io.trino.sql.tree.IfExpression;
import io.trino.sql.tree.IntervalLiteral;
import io.trino.sql.tree.IsNullPredicate;
import io.trino.sql.tree.Join;
import io.trino.sql.tree.LambdaArgumentDeclaration;
import io.trino.sql.tree.LambdaExpression;
import io.trino.sql.tree.LongLiteral;
import io.trino.sql.tree.Merge;
import io.trino.sql.tree.MergeCase;
import io.trino.sql.tree.MergeDelete;
import io.trino.sql.tree.MergeInsert;
import io.trino.sql.tree.MergeUpdate;
import io.trino.sql.tree.Node;
import io.trino.sql.tree.NodeRef;
import io.trino.sql.tree.NullLiteral;
import io.trino.sql.tree.Offset;
import io.trino.sql.tree.OrderBy;
import io.trino.sql.tree.QualifiedName;
import io.trino.sql.tree.Query;
import io.trino.sql.tree.QuerySpecification;
import io.trino.sql.tree.Relation;
import io.trino.sql.tree.Row;
import io.trino.sql.tree.SearchedCaseExpression;
import io.trino.sql.tree.SortItem;
import io.trino.sql.tree.StringLiteral;
import io.trino.sql.tree.SubscriptExpression;
import io.trino.sql.tree.SymbolReference;
import io.trino.sql.tree.Table;
import io.trino.sql.tree.Union;
import io.trino.sql.tree.Update;
import io.trino.sql.tree.WhenClause;
import io.trino.sql.tree.WindowFrame;
import io.trino.type.TypeCoercion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.trino.SystemSessionProperties.getMaxRecursionDepth;
import static io.trino.SystemSessionProperties.isSkipRedundantSort;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.sql.NodeUtils.getSortItemsFromOrderBy;
import static io.trino.sql.analyzer.ExpressionAnalyzer.isNumericType;
import static io.trino.sql.analyzer.TypeSignatureProvider.fromTypes;
import static io.trino.sql.analyzer.TypeSignatureTranslator.toSqlType;
import static io.trino.sql.planner.GroupingOperationRewriter.rewriteGroupingOperation;
import static io.trino.sql.planner.LogicalPlanner.createPartitioningScheme;
import static io.trino.sql.planner.OrderingScheme.sortItemToSortOrder;
import static io.trino.sql.planner.PlanBuilder.newPlanBuilder;
import static io.trino.sql.planner.ScopeAware.scopeAwareKey;
import static io.trino.sql.planner.plan.AggregationNode.groupingSets;
import static io.trino.sql.planner.plan.AggregationNode.singleGroupingSet;
import static io.trino.sql.planner.plan.WindowNode.Frame.DEFAULT_FRAME;
import static io.trino.sql.tree.BooleanLiteral.TRUE_LITERAL;
import static io.trino.sql.tree.ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL;
import static io.trino.sql.tree.ComparisonExpression.Operator.LESS_THAN_OR_EQUAL;
import static io.trino.sql.tree.IntervalLiteral.IntervalField.DAY;
import static io.trino.sql.tree.IntervalLiteral.IntervalField.YEAR;
import static io.trino.sql.tree.IntervalLiteral.Sign.POSITIVE;
import static io.trino.sql.tree.WindowFrame.Type.GROUPS;
import static io.trino.sql.tree.WindowFrame.Type.RANGE;
import static io.trino.sql.tree.WindowFrame.Type.ROWS;
import static io.trino.type.IntervalDayTimeType.INTERVAL_DAY_TIME;
import static io.trino.type.IntervalYearMonthType.INTERVAL_YEAR_MONTH;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

class QueryPlanner
{
    private final Analysis analysis;
    private final SymbolAllocator symbolAllocator;
    private final PlanNodeIdAllocator idAllocator;
    private final Map<NodeRef<LambdaArgumentDeclaration>, Symbol> lambdaDeclarationToSymbolMap;
    private final Metadata metadata;
    private final TypeCoercion typeCoercion;
    private final Session session;
    private final SubqueryPlanner subqueryPlanner;
    private final Optional<TranslationMap> outerContext;
    private final Map<NodeRef<Node>, RelationPlan> recursiveSubqueries;

    QueryPlanner(
            Analysis analysis,
            SymbolAllocator symbolAllocator,
            PlanNodeIdAllocator idAllocator,
            Map<NodeRef<LambdaArgumentDeclaration>, Symbol> lambdaDeclarationToSymbolMap,
            Metadata metadata,
            Optional<TranslationMap> outerContext,
            Session session,
            Map<NodeRef<Node>, RelationPlan> recursiveSubqueries)
    {
        requireNonNull(analysis, "analysis is null");
        requireNonNull(symbolAllocator, "symbolAllocator is null");
        requireNonNull(idAllocator, "idAllocator is null");
        requireNonNull(lambdaDeclarationToSymbolMap, "lambdaDeclarationToSymbolMap is null");
        requireNonNull(metadata, "metadata is null");
        requireNonNull(session, "session is null");
        requireNonNull(outerContext, "outerContext is null");
        requireNonNull(recursiveSubqueries, "recursiveSubqueries is null");

        this.analysis = analysis;
        this.symbolAllocator = symbolAllocator;
        this.idAllocator = idAllocator;
        this.lambdaDeclarationToSymbolMap = lambdaDeclarationToSymbolMap;
        this.metadata = metadata;
        this.typeCoercion = new TypeCoercion(metadata::getType);
        this.session = session;
        this.outerContext = outerContext;
        this.subqueryPlanner = new SubqueryPlanner(analysis, symbolAllocator, idAllocator, lambdaDeclarationToSymbolMap, metadata, typeCoercion, outerContext, session, recursiveSubqueries);
        this.recursiveSubqueries = recursiveSubqueries;
    }

    public RelationPlan plan(Query query)
    {
        PlanBuilder builder = planQueryBody(query);

        List<Expression> orderBy = analysis.getOrderByExpressions(query);
        builder = subqueryPlanner.handleSubqueries(builder, orderBy, analysis.getSubqueries(query));

        List<SelectExpression> selectExpressions = analysis.getSelectExpressions(query);
        List<Expression> outputs = selectExpressions.stream()
                .map(SelectExpression::getExpression)
                .collect(toImmutableList());
        builder = builder.appendProjections(Iterables.concat(orderBy, outputs), symbolAllocator, idAllocator);

        Optional<OrderingScheme> orderingScheme = orderingScheme(builder, query.getOrderBy(), analysis.getOrderByExpressions(query));
        builder = sort(builder, orderingScheme);
        builder = offset(builder, query.getOffset());
        builder = limit(builder, query.getLimit(), orderingScheme);
        builder = builder.appendProjections(outputs, symbolAllocator, idAllocator);

        return new RelationPlan(
                builder.getRoot(),
                analysis.getScope(query),
                computeOutputs(builder, outputs),
                outerContext);
    }

    public RelationPlan planExpand(Query query)
    {
        checkArgument(analysis.isExpandableQuery(query), "query is not registered as expandable");

        Union union = (Union) query.getQueryBody();
        ImmutableList.Builder<NodeAndMappings> recursionSteps = ImmutableList.builder();

        // plan anchor relation
        Relation anchorNode = union.getRelations().get(0);
        RelationPlan anchorPlan = new RelationPlanner(analysis, symbolAllocator, idAllocator, lambdaDeclarationToSymbolMap, metadata, outerContext, session, recursiveSubqueries)
                .process(anchorNode, null);

        // prune anchor plan outputs to contain only the symbols exposed in the scope
        NodeAndMappings prunedAnchorPlan = pruneInvisibleFields(anchorPlan, idAllocator);
        anchorPlan = new RelationPlan(prunedAnchorPlan.getNode(), analysis.getScope(query), prunedAnchorPlan.getFields(), outerContext);

        recursionSteps.add(copy(anchorPlan.getRoot(), anchorPlan.getFieldMappings()));

        // plan recursion step
        Relation recursionStepRelation = union.getRelations().get(1);
        RelationPlan recursionStepPlan = new RelationPlanner(
                analysis,
                symbolAllocator,
                idAllocator,
                lambdaDeclarationToSymbolMap,
                metadata,
                outerContext,
                session,
                ImmutableMap.of(NodeRef.of(analysis.getRecursiveReference(query)), anchorPlan))
                .process(recursionStepRelation, null);

        // coerce recursion step outputs and prune them to contain only the symbols exposed in the scope
        NodeAndMappings coercedRecursionStep;
        List<Type> types = analysis.getRelationCoercion(recursionStepRelation);
        if (types == null) {
            coercedRecursionStep = pruneInvisibleFields(recursionStepPlan, idAllocator);
        }
        else {
            coercedRecursionStep = coerce(recursionStepPlan, types, symbolAllocator, idAllocator);
        }

        NodeAndMappings replacementSpot = new NodeAndMappings(anchorPlan.getRoot(), anchorPlan.getFieldMappings());
        PlanNode recursionStep = coercedRecursionStep.getNode();
        List<Symbol> mappings = coercedRecursionStep.getFields();

        // unroll recursion
        int maxRecursionDepth = getMaxRecursionDepth(session);
        for (int i = 0; i < maxRecursionDepth; i++) {
            recursionSteps.add(copy(recursionStep, mappings));
            NodeAndMappings replacement = copy(recursionStep, mappings);
            recursionStep = replace(recursionStep, replacementSpot, replacement);
            replacementSpot = replacement;
        }

        // after the last recursion step, check if the recursion converged. the last step is expected to return empty result
        // 1. append window to count rows
        NodeAndMappings checkConvergenceStep = copy(recursionStep, mappings);
        Symbol countSymbol = symbolAllocator.newSymbol("count", BIGINT);
        ResolvedFunction function = metadata.resolveFunction(QualifiedName.of("count"), ImmutableList.of());
        WindowNode.Function countFunction = new WindowNode.Function(function, ImmutableList.of(), DEFAULT_FRAME, false);

        WindowNode windowNode = new WindowNode(
                idAllocator.getNextId(),
                checkConvergenceStep.getNode(),
                new WindowNode.Specification(ImmutableList.of(), Optional.empty()),
                ImmutableMap.of(countSymbol, countFunction),
                Optional.empty(),
                ImmutableSet.of(),
                0);

        // 2. append filter to fail on non-empty result
        ResolvedFunction fail = metadata.resolveFunction(QualifiedName.of("fail"), fromTypes(VARCHAR));
        String recursionLimitExceededMessage = format("Recursion depth limit exceeded (%s). Use 'max_recursion_depth' session property to modify the limit.", maxRecursionDepth);
        Expression predicate = new IfExpression(
                new ComparisonExpression(
                        GREATER_THAN_OR_EQUAL,
                        countSymbol.toSymbolReference(),
                        new GenericLiteral("BIGINT", "0")),
                new Cast(
                        new FunctionCall(
                                fail.toQualifiedName(),
                                ImmutableList.of(new Cast(new StringLiteral(recursionLimitExceededMessage), toSqlType(VARCHAR)))),
                        toSqlType(BOOLEAN)),
                TRUE_LITERAL);
        FilterNode filterNode = new FilterNode(idAllocator.getNextId(), windowNode, predicate);

        recursionSteps.add(new NodeAndMappings(filterNode, checkConvergenceStep.getFields()));

        // union all the recursion steps
        List<NodeAndMappings> recursionStepsToUnion = recursionSteps.build();

        List<Symbol> unionOutputSymbols = anchorPlan.getFieldMappings().stream()
                .map(symbol -> symbolAllocator.newSymbol(symbol, "_expanded"))
                .collect(toImmutableList());

        ImmutableListMultimap.Builder<Symbol, Symbol> unionSymbolMapping = ImmutableListMultimap.builder();
        for (NodeAndMappings plan : recursionStepsToUnion) {
            for (int i = 0; i < unionOutputSymbols.size(); i++) {
                unionSymbolMapping.put(unionOutputSymbols.get(i), plan.getFields().get(i));
            }
        }

        List<PlanNode> nodesToUnion = recursionStepsToUnion.stream()
                .map(NodeAndMappings::getNode)
                .collect(toImmutableList());

        PlanNode result = new UnionNode(idAllocator.getNextId(), nodesToUnion, unionSymbolMapping.build(), unionOutputSymbols);

        if (union.isDistinct()) {
            result = new AggregationNode(
                    idAllocator.getNextId(),
                    result,
                    ImmutableMap.of(),
                    singleGroupingSet(result.getOutputSymbols()),
                    ImmutableList.of(),
                    AggregationNode.Step.SINGLE,
                    Optional.empty(),
                    Optional.empty());
        }

        return new RelationPlan(result, anchorPlan.getScope(), unionOutputSymbols, outerContext);
    }

    // Return a copy of the plan and remapped field mappings. In the copied plan:
    // - all PlanNodeIds are replaced with new values,
    // - all symbols are replaced with new symbols.
    // Copying the plan might reorder symbols. The returned field mappings keep the original
    // order and might be used to identify the original output symbols with their copies.
    private NodeAndMappings copy(PlanNode plan, List<Symbol> fields)
    {
        return PlanCopier.copyPlan(plan, fields, metadata, symbolAllocator, idAllocator);
    }

    private PlanNode replace(PlanNode plan, NodeAndMappings replacementSpot, NodeAndMappings replacement)
    {
        checkArgument(
                replacementSpot.getFields().size() == replacement.getFields().size(),
                "mismatching outputs in replacement, expected: %s, got: %s",
                replacementSpot.getFields().size(),
                replacement.getFields().size());

        return SimplePlanRewriter.rewriteWith(new SimplePlanRewriter<Void>()
        {
            @Override
            protected PlanNode visitPlan(PlanNode node, RewriteContext<Void> context)
            {
                return node.replaceChildren(node.getSources().stream()
                        .map(child -> {
                            if (child == replacementSpot.getNode()) {
                                // add projection to adjust symbols
                                Assignments.Builder assignments = Assignments.builder();
                                for (int i = 0; i < replacementSpot.getFields().size(); i++) {
                                    assignments.put(replacementSpot.getFields().get(i), replacement.getFields().get(i).toSymbolReference());
                                }
                                return new ProjectNode(idAllocator.getNextId(), replacement.getNode(), assignments.build());
                            }
                            return context.rewrite(child);
                        })
                        .collect(toImmutableList()));
            }
        }, plan, null);
    }

    public RelationPlan plan(QuerySpecification node)
    {
        PlanBuilder builder = planFrom(node);

        builder = filter(builder, analysis.getWhere(node), node);
        builder = aggregate(builder, node);
        builder = filter(builder, analysis.getHaving(node), node);
        builder = window(node, builder, ImmutableList.copyOf(analysis.getWindowFunctions(node)));

        List<SelectExpression> selectExpressions = analysis.getSelectExpressions(node);
        List<Expression> expressions = selectExpressions.stream()
                .map(SelectExpression::getExpression)
                .collect(toImmutableList());
        builder = subqueryPlanner.handleSubqueries(builder, expressions, analysis.getSubqueries(node));

        if (hasExpressionsToUnfold(selectExpressions)) {
            // pre-project the folded expressions to preserve any non-deterministic semantics of functions that might be referenced
            builder = builder.appendProjections(expressions, symbolAllocator, idAllocator);
        }

        List<Expression> outputs = outputExpressions(selectExpressions);
        if (node.getOrderBy().isPresent()) {
            // ORDER BY requires outputs of SELECT to be visible.
            // For queries with aggregation, it also requires grouping keys and translated aggregations.
            if (analysis.isAggregation(node)) {
                // Add projections for aggregations required by ORDER BY. After this step, grouping keys and translated
                // aggregations are visible.
                List<Expression> orderByAggregates = analysis.getOrderByAggregates(node.getOrderBy().get());
                builder = builder.appendProjections(orderByAggregates, symbolAllocator, idAllocator);
            }

            // Add projections for the outputs of SELECT, but stack them on top of the ones from the FROM clause so both are visible
            // when resolving the ORDER BY clause.
            builder = builder.appendProjections(outputs, symbolAllocator, idAllocator);

            // The new scope is the composite of the fields from the FROM and SELECT clause (local nested scopes). Fields from the bottom of
            // the scope stack need to be placed first to match the expected layout for nested scopes.
            List<Symbol> newFields = new ArrayList<>();
            newFields.addAll(builder.getTranslations().getFieldSymbols());

            outputs.stream()
                    .map(builder::translate)
                    .forEach(newFields::add);

            builder = builder.withScope(analysis.getScope(node.getOrderBy().get()), newFields);

            builder = window(node, builder, ImmutableList.copyOf(analysis.getOrderByWindowFunctions(node.getOrderBy().get())));
        }

        List<Expression> orderBy = analysis.getOrderByExpressions(node);
        builder = subqueryPlanner.handleSubqueries(builder, orderBy, analysis.getSubqueries(node));
        builder = builder.appendProjections(Iterables.concat(orderBy, outputs), symbolAllocator, idAllocator);

        builder = distinct(builder, node, outputs);
        Optional<OrderingScheme> orderingScheme = orderingScheme(builder, node.getOrderBy(), analysis.getOrderByExpressions(node));
        builder = sort(builder, orderingScheme);
        builder = offset(builder, node.getOffset());
        builder = limit(builder, node.getLimit(), orderingScheme);
        builder = builder.appendProjections(outputs, symbolAllocator, idAllocator);

        return new RelationPlan(
                builder.getRoot(),
                analysis.getScope(node),
                computeOutputs(builder, outputs),
                outerContext);
    }

    private boolean hasExpressionsToUnfold(List<SelectExpression> selectExpressions)
    {
        return selectExpressions.stream()
                .map(SelectExpression::getUnfoldedExpressions)
                .anyMatch(Optional::isPresent);
    }

    private List<Expression> outputExpressions(List<SelectExpression> selectExpressions)
    {
        ImmutableList.Builder<Expression> result = ImmutableList.builder();
        for (SelectExpression selectExpression : selectExpressions) {
            if (selectExpression.getUnfoldedExpressions().isPresent()) {
                result.addAll(selectExpression.getUnfoldedExpressions().get());
            }
            else {
                result.add(selectExpression.getExpression());
            }
        }
        return result.build();
    }

    public DeleteNode plan(Delete node)
    {
        Table table = node.getTable();
        TableHandle handle = analysis.getTableHandle(table);

        // create table scan
        RelationPlan relationPlan = new RelationPlanner(analysis, symbolAllocator, idAllocator, lambdaDeclarationToSymbolMap, metadata, outerContext, session, recursiveSubqueries)
                .process(table, null);

        PlanBuilder builder = newPlanBuilder(relationPlan, analysis, lambdaDeclarationToSymbolMap);
        if (node.getWhere().isPresent()) {
            builder = filter(builder, node.getWhere().get(), node);
        }

        // create delete node
        Symbol rowId = builder.translate(analysis.getRowIdField(table));
        List<Symbol> outputs = ImmutableList.of(
                symbolAllocator.newSymbol("partialrows", BIGINT),
                symbolAllocator.newSymbol("fragment", VARBINARY));

        return new DeleteNode(
                idAllocator.getNextId(),
                builder.getRoot(),
                new DeleteTarget(
                        Optional.empty(),
                        metadata.getTableMetadata(session, handle).getTable()),
                rowId,
                outputs);
    }

    public UpdateNode plan(Update node)
    {
        Table table = node.getTable();
        TableHandle handle = analysis.getTableHandle(table);

        TableMetadata tableMetadata = metadata.getTableMetadata(session, handle);
        Map<String, ColumnHandle> columnMap = metadata.getColumnHandles(session, handle);
        List<ColumnMetadata> dataColumns = tableMetadata.getMetadata().getColumns().stream()
                .filter(column -> !column.isHidden())
                .collect(toImmutableList());

        List<String> targetColumnNames = node.getAssignments().stream()
                .map(assignment -> assignment.getName().getValue())
                .collect(toImmutableList());

        // Create lists of columnnames and SET expressions, in table column order
        ImmutableList.Builder<String> updatedColumnNamesBuilder = ImmutableList.builder();
        ImmutableList.Builder<ColumnHandle> updatedColumnHandlesBuilder = ImmutableList.builder();
        ImmutableList.Builder<Expression> orderedColumnValuesBuilder = ImmutableList.builder();
        for (ColumnMetadata columnMetadata : dataColumns) {
            String name = columnMetadata.getName();
            int index = targetColumnNames.indexOf(name);
            if (index >= 0) {
                updatedColumnNamesBuilder.add(name);
                updatedColumnHandlesBuilder.add(requireNonNull(columnMap.get(name), "columnMap didn't contain name"));
                orderedColumnValuesBuilder.add(node.getAssignments().get(index).getValue());
            }
        }
        List<String> updatedColumnNames = updatedColumnNamesBuilder.build();
        List<ColumnHandle> updatedColumnHandles = updatedColumnHandlesBuilder.build();
        List<Expression> orderedColumnValues = orderedColumnValuesBuilder.build();

        // create table scan
        RelationPlan relationPlan = new RelationPlanner(analysis, symbolAllocator, idAllocator, lambdaDeclarationToSymbolMap, metadata, outerContext, session, recursiveSubqueries)
                .process(table, null);

        PlanBuilder builder = newPlanBuilder(relationPlan, analysis, lambdaDeclarationToSymbolMap);

        if (node.getWhere().isPresent()) {
            builder = filter(builder, node.getWhere().get(), node);
        }

        builder = builder.appendProjections(orderedColumnValues, symbolAllocator, idAllocator);

        PlanAndMappings planAndMappings = coerce(builder, orderedColumnValues, analysis, idAllocator, symbolAllocator, typeCoercion);
        builder = planAndMappings.getSubPlan();

        ImmutableList.Builder<Symbol> updatedColumnValuesBuilder = ImmutableList.builder();
        orderedColumnValues.forEach(columnValue -> updatedColumnValuesBuilder.add(planAndMappings.get(columnValue)));
        Symbol rowId = builder.translate(analysis.getRowIdField(table));
        updatedColumnValuesBuilder.add(rowId);

        List<Symbol> outputs = ImmutableList.of(
                symbolAllocator.newSymbol("partialrows", BIGINT),
                symbolAllocator.newSymbol("fragment", VARBINARY));

        Optional<PlanNodeId> tableScanId = getIdForLeftTableScan(relationPlan.getRoot());
        checkArgument(tableScanId.isPresent(), "tableScanId not present");

        // create update node
        return new UpdateNode(
                idAllocator.getNextId(),
                builder.getRoot(),
                new UpdateTarget(
                        Optional.empty(),
                        metadata.getTableMetadata(session, handle).getTable(),
                        updatedColumnNames,
                        updatedColumnHandles),
                rowId,
                updatedColumnValuesBuilder.build(),
                outputs);
    }

    private MergeCaseKind getMergeCaseKind(MergeCase mergeCase)
    {
        requireNonNull(mergeCase, "mergeCase is null");
        if (mergeCase instanceof MergeInsert) {
            return MergeCaseKind.INSERT;
        }
        if (mergeCase instanceof MergeUpdate) {
            return MergeCaseKind.UPDATE;
        }
        if (mergeCase instanceof MergeDelete) {
            return MergeCaseKind.DELETE;
        }
        throw new IllegalArgumentException("Unrecognized MergeCase " + mergeCase.getClass());
    }

    public MergeNode plan(Merge merge)
    {
        MergeAnalysis mergeAnalysis = analysis.getMergeAnalysis().orElseThrow(() -> new IllegalArgumentException("analysis.getMergeAnalysis() isn't present"));

        List<ColumnHandle> redistributionColumns = mergeAnalysis.getRedistributionColumnHandles();
        Map<Integer, List<ColumnHandle>> mergeCaseColumnsHandles = mergeAnalysis.getMergeCaseColumnHandles();

        // TODO: canonicalize in analyzer, record the list of names for each case in MergeAnalysis
        ImmutableMap.Builder<Integer, List<String>> mergeCaseColumnsListsBuilder = ImmutableMap.builder();
        for (int caseCounter = 0; caseCounter < merge.getMergeCases().size(); caseCounter++) {
            MergeCase operation = merge.getMergeCases().get(caseCounter);
            List<String> mergeColumnNames = canonicalizeIdentifierList(operation.getSetColumns());
            mergeCaseColumnsListsBuilder.put(caseCounter, mergeColumnNames);
        }

        MergeDetails mergeDetails = createMergeDetails(merge, mergeCaseColumnsHandles);

        RelationPlan target = new RelationPlanner(analysis, symbolAllocator, idAllocator, lambdaDeclarationToSymbolMap, metadata, outerContext, session, recursiveSubqueries)
                .process(merge.getTable());

        PlanBuilder targetPlanBuilder = newPlanBuilder(target, analysis, lambdaDeclarationToSymbolMap);

        Assignments.Builder projections = Assignments.builder();
        projections.putIdentities(targetPlanBuilder.getRoot().getOutputSymbols());

        Symbol presentColumn = symbolAllocator.newSymbol("present", BOOLEAN);
        projections.put(presentColumn, TRUE_LITERAL);

        target = new RelationPlan(
                new ProjectNode(idAllocator.getNextId(), targetPlanBuilder.getRoot(), projections.build()),
                analysis.getScope(merge.getTable()),
                target.getFieldMappings(),
                outerContext);

        RelationPlan source = new RelationPlanner(analysis, symbolAllocator, idAllocator, lambdaDeclarationToSymbolMap, metadata, outerContext, session, recursiveSubqueries)
                .process(merge.getSource());

        RelationPlan joinPlan = new RelationPlanner(analysis, symbolAllocator, idAllocator, lambdaDeclarationToSymbolMap, metadata, outerContext, session, recursiveSubqueries)
                .planJoin(merge.getPredicate(), Join.Type.RIGHT, mergeAnalysis.getJoinScope(), target, source, analysis.getSubqueries(merge));

        PlanBuilder joinBuilder = newPlanBuilder(joinPlan, analysis, lambdaDeclarationToSymbolMap);

        ImmutableList.Builder<WhenClause> whenClauses = ImmutableList.builder();
        for (int caseNumber = 0; caseNumber < merge.getMergeCases().size(); caseNumber++) {
            MergeCase mergeCase = merge.getMergeCases().get(caseNumber);

            ImmutableList.Builder<Expression> rowBuilder = ImmutableList.builder();
            List<ColumnHandle> mergeCaseSetColumns = mergeCaseColumnsHandles.get(caseNumber);
            for (ColumnHandle dataColumnHandle : mergeAnalysis.getDataColumnHandles()) {
                int index = mergeCaseSetColumns.indexOf(dataColumnHandle);
                if (index >= 0) {
                    rowBuilder.add(joinBuilder.rewrite(mergeCase.getSetExpressions().get(index)));
                }
                else {
                    Integer fieldNumber = requireNonNull(mergeAnalysis.getColumnHandleFieldNumbers().get(dataColumnHandle), "Field number for ColumnHandle is null");
                    rowBuilder.add(target.getFieldMappings().get(fieldNumber).toSymbolReference());
                }
            }

            // Build the match condition for the MERGE case
            MergeCaseKind mergeKind = getMergeCaseKind(mergeCase);

            // Add the caseNumber and the operation number
            rowBuilder.add(new GenericLiteral("INTEGER", String.valueOf(caseNumber)));
            rowBuilder.add(new GenericLiteral("INTEGER", String.valueOf(mergeKind.getOperationNumber())));

            Optional<Expression> rewritten = mergeCase.getExpression().map(joinBuilder::rewrite);
            Expression condition = presentColumn.toSymbolReference();
            if (mergeCase instanceof MergeInsert) {
                condition = new IsNullPredicate(presentColumn.toSymbolReference());
            }

            if (rewritten.isPresent()) {
                condition = ExpressionUtils.and(condition, rewritten.get());
            }

            whenClauses.add(new WhenClause(condition, new Row(rowBuilder.build())));
        }

        ImmutableList.Builder<Expression> rowBuilder = ImmutableList.builder();
        for (int i = 0; i < target.getDescriptor().getAllFieldCount(); i++) {
            Field field = target.getDescriptor().getFieldByIndex(i);
            if (field.isHidden() || field.getName().isEmpty()) {
                continue;
            }

            rowBuilder.add(new Cast(new NullLiteral(), toSqlType(field.getType())));
        }
        rowBuilder.add(new GenericLiteral("INTEGER", "-1"));
        rowBuilder.add(new GenericLiteral("INTEGER", "-1"));

        SearchedCaseExpression caseExpression = new SearchedCaseExpression(whenClauses.build(), Optional.of(new Row(rowBuilder.build())));
        RowType rowType = createRowType(mergeAnalysis.getDataColumnSchemas());
        Expression castCaseExpression = new Cast(caseExpression, toSqlType(rowType));

        Assignments.Builder projectionAssignmentsBuilder = Assignments.builder();
        for (ColumnHandle column : mergeAnalysis.getRedistributionColumnHandles()) {
            int fieldIndex = requireNonNull(mergeAnalysis.getColumnHandleFieldNumbers().get(column), "Could not find fieldIndex for redistribution column");
            Field field = target.getDescriptor().getFieldByIndex(fieldIndex);
            Symbol symbol = symbolAllocator.newSymbol(field.getName().get(), field.getType());
            projectionAssignmentsBuilder.put(symbol, target.getFieldMappings().get(fieldIndex).toSymbolReference());
        }

        FieldReference reference = analysis.getRowIdField(mergeAnalysis.getTargetTable());
        Field rowIdField = target.getDescriptor().getFieldByIndex(reference.getFieldIndex());
        Symbol rowId = symbolAllocator.newSymbol("row_id", rowIdField.getType());
        projectionAssignmentsBuilder.put(rowId, target.getFieldMappings().get(reference.getFieldIndex()).toSymbolReference());
        Symbol mergeOutput = symbolAllocator.newSymbol("merge_row", rowType);
        projectionAssignmentsBuilder.put(mergeOutput, castCaseExpression);

        ProjectNode project = new ProjectNode(
                idAllocator.getNextId(),
                joinBuilder.getRoot(),
                projectionAssignmentsBuilder.build());
        // Projecting the writeRedistributionColumns, the merge RowBlock, and the rowId column
        int expectedSize = redistributionColumns.size() + 1 + 1;
        int actualSize = project.getOutputSymbols().size();
        checkArgument(actualSize == expectedSize, "projectedSymbols should have size %s, but is %s", expectedSize, actualSize);

        Table table = merge.getTable();
        TableHandle handle = analysis.getTableHandle(table);
        TableMetadata tableMetadata = metadata.getTableMetadata(session, handle);

        RowChangeParadigm paradigm = metadata.getRowChangeParadigm(session, handle);
        Map<String, ColumnHandle> columnMap = metadata.getColumnHandles(session, handle);
        Type rowIdType = analysis.getType(analysis.getRowIdField(table));
        RowChangeProcessor rowChangeProcessor = createMergeProcessor(paradigm, tableMetadata, mergeAnalysis.getDataColumnHandles(), mergeAnalysis.getRedistributionColumnHandles(), rowIdType);
        MergeTarget mergeTarget = new MergeTarget(handle, Optional.empty(), tableMetadata.getTable(), mergeDetails, rowChangeProcessor);

        Map<String, Type> allColumnTypes = mergeAnalysis.getDataColumnSchemas().stream().collect(toImmutableMap(ColumnSchema::getName, ColumnSchema::getType));
        Set<String> columnNamesSet = allColumnTypes.keySet();

        List<String> columnNames = tableMetadata.getColumns().stream()
                .map(ColumnMetadata::getName)
                .filter(columnNamesSet::contains)  // TODO: is columnNamesSet different from tableMetadata.getColumns()?
                .collect(toImmutableList());

        /* TODO

            List<Symbol> columnSymbols = target.getFieldMappings();

            field mappings match the order of fields in the descriptor

         */
        List<Symbol> columnSymbols = target.getRoot().getOutputSymbols().stream()
                .filter(symbol -> columnNamesSet.contains(symbol.getName()))
                .collect(toImmutableList());
        checkState(columnNames.size() == columnSymbols.size(), "Didn't find symbols for all the columns, columns %s, symbols %s", columnNames, columnSymbols);

        ImmutableList.Builder<Symbol> projectedSymbolsBuilder = ImmutableList.builder();
        int subscriptIndex = 1;
        for (Symbol columnSymbol : columnSymbols) {
            SubscriptExpression subscriptExpression = new SubscriptExpression(new SymbolReference(mergeOutput.getName()), new GenericLiteral("BIGINT", String.valueOf(subscriptIndex)));
            projectedSymbolsBuilder.add(columnSymbol);
            analysis.addTypes(ImmutableMap.of(NodeRef.of(subscriptExpression), allColumnTypes.get(columnSymbol.getName())));
            subscriptIndex++;
        }

        Symbol operationSymbol = symbolAllocator.newSymbol("operation", INTEGER);
        projectedSymbolsBuilder.add(operationSymbol);
        projectedSymbolsBuilder.add(rowId);

        List<Symbol> finalProjectedSymbols = projectedSymbolsBuilder.build();

        Optional<PartitioningScheme> partitioningScheme = createPartitioningScheme(mergeAnalysis.getNewTableLayout(), columnSymbols, columnNames);
        DeleteAndInsertNode deleteAndInsertNode = new DeleteAndInsertNode(
                idAllocator.getNextId(),
                project,
                mergeTarget,
                finalProjectedSymbols);

        Optional<PlanNodeId> tableScanId = getIdForLeftTableScan(target.getRoot());
        checkArgument(tableScanId.isPresent(), "tableScanId not present");
        List<Symbol> outputs = ImmutableList.of(
                symbolAllocator.newSymbol("partialrows", BIGINT),
                symbolAllocator.newSymbol("fragment", VARBINARY));
        return new MergeNode(
                idAllocator.getNextId(),
                deleteAndInsertNode,
                mergeTarget,
                tableScanId,
                finalProjectedSymbols,
                partitioningScheme,
                outputs);
    }

    // TODO: this shouldn't be done in planning
    private List<String> canonicalizeIdentifierList(Collection<Identifier> identifiers)
    {
        return identifiers.stream()
                // TODO Is the toLowerCase() necessary?  It doesn't seem to impact test results
                .map(identifier -> identifier.getValue().toLowerCase(ENGLISH))
                .collect(toImmutableList());
    }

    private RowType createRowType(List<ColumnSchema> allColumnsSchema)
    {
        // Create the RowType that holds all column values
        ImmutableList.Builder<RowType.Field> fieldsBuilder = ImmutableList.builder();
        allColumnsSchema.forEach(columnSchema -> fieldsBuilder.add(new RowType.Field(Optional.of(columnSchema.getName()), columnSchema.getType())));
        // Add the case number and the operation number
        fieldsBuilder.add(new RowType.Field(Optional.empty(), INTEGER));
        fieldsBuilder.add(new RowType.Field(Optional.empty(), INTEGER));
        List<RowType.Field> rowFields = fieldsBuilder.build();
        return RowType.from(rowFields);
    }

    private MergeDetails createMergeDetails(Merge merge, Map<Integer, List<ColumnHandle>> mergeCaseColumnsLists)
    {
        // Create MergeDetails
        ImmutableList.Builder<MergeCaseDetails> caseDetailsBuilder = ImmutableList.builder();
        int caseCounter = 0;
        for (MergeCase operation : merge.getMergeCases()) {
            List<ColumnHandle> caseColumnHandles = mergeCaseColumnsLists.get(caseCounter);
            if (operation instanceof MergeInsert) {
                caseDetailsBuilder.add(new MergeCaseDetails(
                        caseCounter,
                        MergeCaseKind.INSERT,
                        ImmutableSet.copyOf(caseColumnHandles)));
            }
            else if (operation instanceof MergeUpdate) {
                caseDetailsBuilder.add(new MergeCaseDetails(caseCounter, MergeCaseKind.UPDATE, ImmutableSet.copyOf(caseColumnHandles)));
            }
            else if (operation instanceof MergeDelete) {
                caseDetailsBuilder.add(new MergeCaseDetails(caseCounter, MergeCaseKind.DELETE, ImmutableSet.of()));
            }
            else {
                throw new IllegalArgumentException(format("Unknown MergeOperation %s of class %s", operation, operation.getClass().getName()));
            }
            caseCounter++;
        }
        List<MergeCaseDetails> mergeCases = caseDetailsBuilder.build();
        return new MergeDetails(mergeCases);
    }

    private RowChangeProcessor createMergeProcessor(
            RowChangeParadigm paradigm,
            TableMetadata tableMetadata,
            List<ColumnHandle> columnHandles,
            List<ColumnHandle> writeRedistributionColumnNames,
            Type rowIdType)
    {
        List<ColumnMetadata> dataColumnMetadata = tableMetadata.getMetadata().getColumns().stream()
                .filter(column -> !column.isHidden())
                .collect(toImmutableList());
        List<Type> dataColumnTypes = dataColumnMetadata.stream().map(ColumnMetadata::getType).collect(toImmutableList());
        switch (paradigm) {
            case DELETE_ROW_AND_INSERT_ROW:
                return new DeleteAndInsertMergeProcessor(columnHandles, dataColumnTypes, writeRedistributionColumnNames, rowIdType);
            case CHANGE_ONLY_UPDATED_COLUMNS:
                return new ChangeOnlyUpdatedColumnsMergeProcessor(columnHandles, dataColumnTypes, writeRedistributionColumnNames, rowIdType);
            default:
                throw new IllegalArgumentException("Unsupported RowChangeParadigm " + paradigm);
        }
    }

    private Optional<PlanNodeId> getIdForLeftTableScan(PlanNode node)
    {
        if (node instanceof TableScanNode) {
            return Optional.of(node.getId());
        }
        List<PlanNode> sources = node.getSources();
        if (sources.isEmpty()) {
            return Optional.empty();
        }
        return getIdForLeftTableScan(sources.get(0));
    }

    private static List<Symbol> computeOutputs(PlanBuilder builder, List<Expression> outputExpressions)
    {
        ImmutableList.Builder<Symbol> outputSymbols = ImmutableList.builder();
        for (Expression expression : outputExpressions) {
            outputSymbols.add(builder.translate(expression));
        }
        return outputSymbols.build();
    }

    private PlanBuilder planQueryBody(Query query)
    {
        RelationPlan relationPlan = new RelationPlanner(analysis, symbolAllocator, idAllocator, lambdaDeclarationToSymbolMap, metadata, outerContext, session, recursiveSubqueries)
                .process(query.getQueryBody(), null);

        return newPlanBuilder(relationPlan, analysis, lambdaDeclarationToSymbolMap);
    }

    private PlanBuilder planFrom(QuerySpecification node)
    {
        if (node.getFrom().isPresent()) {
            RelationPlan relationPlan = new RelationPlanner(analysis, symbolAllocator, idAllocator, lambdaDeclarationToSymbolMap, metadata, outerContext, session, recursiveSubqueries)
                    .process(node.getFrom().get(), null);
            return newPlanBuilder(relationPlan, analysis, lambdaDeclarationToSymbolMap);
        }

        return new PlanBuilder(
                new TranslationMap(outerContext, analysis.getImplicitFromScope(node), analysis, lambdaDeclarationToSymbolMap, ImmutableList.of()),
                new ValuesNode(idAllocator.getNextId(), 1));
    }

    private PlanBuilder filter(PlanBuilder subPlan, Expression predicate, Node node)
    {
        if (predicate == null) {
            return subPlan;
        }

        subPlan = subqueryPlanner.handleSubqueries(subPlan, predicate, analysis.getSubqueries(node));

        return subPlan.withNewRoot(new FilterNode(idAllocator.getNextId(), subPlan.getRoot(), subPlan.rewrite(predicate)));
    }

    private PlanBuilder aggregate(PlanBuilder subPlan, QuerySpecification node)
    {
        if (!analysis.isAggregation(node)) {
            return subPlan;
        }

        ImmutableList.Builder<Expression> inputBuilder = ImmutableList.builder();
        analysis.getAggregates(node).stream()
                .map(FunctionCall::getArguments)
                .flatMap(List::stream)
                .filter(expression -> !(expression instanceof LambdaExpression)) // lambda expression is generated at execution time
                .forEach(inputBuilder::add);

        analysis.getAggregates(node).stream()
                .map(FunctionCall::getOrderBy)
                .map(NodeUtils::getSortItemsFromOrderBy)
                .flatMap(List::stream)
                .map(SortItem::getSortKey)
                .forEach(inputBuilder::add);

        // filter expressions need to be projected first
        analysis.getAggregates(node).stream()
                .map(FunctionCall::getFilter)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(inputBuilder::add);

        GroupingSetAnalysis groupingSetAnalysis = analysis.getGroupingSets(node);
        inputBuilder.addAll(groupingSetAnalysis.getComplexExpressions());

        List<Expression> inputs = inputBuilder.build();
        subPlan = subqueryPlanner.handleSubqueries(subPlan, inputs, analysis.getSubqueries(node));
        subPlan = subPlan.appendProjections(inputs, symbolAllocator, idAllocator);

        // Add projection to coerce inputs to their site-specific types.
        // This is important because the same lexical expression may need to be coerced
        // in different ways if it's referenced by multiple arguments to the window function.
        // For example, given v::integer,
        //    avg(v)
        // Needs to be rewritten as
        //    avg(CAST(v AS double))
        PlanAndMappings coercions = coerce(subPlan, inputs, analysis, idAllocator, symbolAllocator, typeCoercion);
        subPlan = coercions.getSubPlan();

        GroupingSetsPlan groupingSets = planGroupingSets(subPlan, node, groupingSetAnalysis);

        subPlan = planAggregation(groupingSets.getSubPlan(), groupingSets.getGroupingSets(), groupingSets.getGroupIdSymbol(), analysis.getAggregates(node), coercions::get);

        return planGroupingOperations(subPlan, node, groupingSets.getGroupIdSymbol(), groupingSets.getColumnOnlyGroupingSets());
    }

    private GroupingSetsPlan planGroupingSets(PlanBuilder subPlan, QuerySpecification node, GroupingSetAnalysis groupingSetAnalysis)
    {
        Map<Symbol, Symbol> groupingSetMappings = new LinkedHashMap<>();

        // Compute a set of artificial columns that will contain the values of the original columns
        // filtered by whether the column is included in the grouping set
        // This will become the basis for the scope for any column references
        Symbol[] fields = new Symbol[subPlan.getTranslations().getFieldSymbols().size()];
        for (FieldId field : groupingSetAnalysis.getAllFields()) {
            Symbol input = subPlan.getTranslations().getFieldSymbols().get(field.getFieldIndex());
            Symbol output = symbolAllocator.newSymbol(input, "gid");
            fields[field.getFieldIndex()] = output;
            groupingSetMappings.put(output, input);
        }

        Map<ScopeAware<Expression>, Symbol> complexExpressions = new HashMap<>();
        for (Expression expression : groupingSetAnalysis.getComplexExpressions()) {
            if (!complexExpressions.containsKey(scopeAwareKey(expression, analysis, subPlan.getScope()))) {
                Symbol input = subPlan.translate(expression);
                Symbol output = symbolAllocator.newSymbol(expression, analysis.getType(expression), "gid");
                complexExpressions.put(scopeAwareKey(expression, analysis, subPlan.getScope()), output);
                groupingSetMappings.put(output, input);
            }
        }

        // For the purpose of "distinct", we need to canonicalize column references that may have varying
        // syntactic forms (e.g., "t.a" vs "a"). Thus we need to enumerate grouping sets based on the underlying
        // fieldId associated with each column reference expression.

        // The catch is that simple group-by expressions can be arbitrary expressions (this is a departure from the SQL specification).
        // But, they don't affect the number of grouping sets or the behavior of "distinct" . We can compute all the candidate
        // grouping sets in terms of fieldId, dedup as appropriate and then cross-join them with the complex expressions.

        // This tracks the grouping sets before complex expressions are considered.
        // It's also used to compute the descriptors needed to implement grouping()
        List<Set<FieldId>> columnOnlyGroupingSets = enumerateGroupingSets(groupingSetAnalysis);
        if (node.getGroupBy().isPresent() && node.getGroupBy().get().isDistinct()) {
            columnOnlyGroupingSets = columnOnlyGroupingSets.stream()
                    .distinct()
                    .collect(toImmutableList());
        }

        // translate from FieldIds to Symbols
        List<List<Symbol>> sets = columnOnlyGroupingSets.stream()
                .map(set -> set.stream()
                        .map(FieldId::getFieldIndex)
                        .map(index -> fields[index])
                        .collect(toImmutableList()))
                .collect(toImmutableList());

        // combine (cartesian product) with complex expressions
        List<List<Symbol>> groupingSets = sets.stream()
                .map(set -> ImmutableList.<Symbol>builder()
                        .addAll(set)
                        .addAll(complexExpressions.values())
                        .build())
                .collect(toImmutableList());

        // Generate GroupIdNode (multiple grouping sets) or ProjectNode (single grouping set)
        PlanNode groupId;
        Optional<Symbol> groupIdSymbol = Optional.empty();
        if (groupingSets.size() > 1) {
            groupIdSymbol = Optional.of(symbolAllocator.newSymbol("groupId", BIGINT));
            groupId = new GroupIdNode(
                    idAllocator.getNextId(),
                    subPlan.getRoot(),
                    groupingSets,
                    groupingSetMappings,
                    subPlan.getRoot().getOutputSymbols(),
                    groupIdSymbol.get());
        }
        else {
            Assignments.Builder assignments = Assignments.builder();
            assignments.putIdentities(subPlan.getRoot().getOutputSymbols());
            groupingSetMappings.forEach((key, value) -> assignments.put(key, value.toSymbolReference()));

            groupId = new ProjectNode(idAllocator.getNextId(), subPlan.getRoot(), assignments.build());
        }

        subPlan = new PlanBuilder(
                subPlan.getTranslations()
                        .withNewMappings(complexExpressions, Arrays.asList(fields)),
                groupId);

        return new GroupingSetsPlan(subPlan, columnOnlyGroupingSets, groupingSets, groupIdSymbol);
    }

    private PlanBuilder planAggregation(PlanBuilder subPlan, List<List<Symbol>> groupingSets, Optional<Symbol> groupIdSymbol, List<FunctionCall> aggregates, Function<Expression, Symbol> coercions)
    {
        ImmutableList.Builder<AggregationAssignment> aggregateMappingBuilder = ImmutableList.builder();

        // deduplicate based on scope-aware equality
        for (FunctionCall function : scopeAwareDistinct(subPlan, aggregates)) {
            Symbol symbol = symbolAllocator.newSymbol(function, analysis.getType(function));

            // TODO: for ORDER BY arguments, rewrite them such that they match the actual arguments to the function. This is necessary to maintain the semantics of DISTINCT + ORDER BY,
            //   which requires that ORDER BY be a subset of arguments
            //   What can happen currently is that if the argument requires a coercion, the argument will take a different input that the ORDER BY clause, which is undefined behavior
            Aggregation aggregation = new Aggregation(
                    analysis.getResolvedFunction(function),
                    function.getArguments().stream()
                            .map(argument -> {
                                if (argument instanceof LambdaExpression) {
                                    return subPlan.rewrite(argument);
                                }
                                return coercions.apply(argument).toSymbolReference();
                            })
                            .collect(toImmutableList()),
                    function.isDistinct(),
                    function.getFilter().map(coercions),
                    function.getOrderBy().map(orderBy -> translateOrderingScheme(orderBy.getSortItems(), coercions)),
                    Optional.empty());

            aggregateMappingBuilder.add(new AggregationAssignment(symbol, function, aggregation));
        }
        List<AggregationAssignment> aggregateMappings = aggregateMappingBuilder.build();

        ImmutableSet.Builder<Integer> globalGroupingSets = ImmutableSet.builder();
        for (int i = 0; i < groupingSets.size(); i++) {
            if (groupingSets.get(i).isEmpty()) {
                globalGroupingSets.add(i);
            }
        }

        ImmutableList.Builder<Symbol> groupingKeys = ImmutableList.builder();
        groupingSets.stream()
                .flatMap(List::stream)
                .distinct()
                .forEach(groupingKeys::add);
        groupIdSymbol.ifPresent(groupingKeys::add);

        AggregationNode aggregationNode = new AggregationNode(
                idAllocator.getNextId(),
                subPlan.getRoot(),
                aggregateMappings.stream()
                        .collect(toImmutableMap(AggregationAssignment::getSymbol, AggregationAssignment::getRewritten)),
                groupingSets(
                        groupingKeys.build(),
                        groupingSets.size(),
                        globalGroupingSets.build()),
                ImmutableList.of(),
                AggregationNode.Step.SINGLE,
                Optional.empty(),
                groupIdSymbol);

        return new PlanBuilder(
                subPlan.getTranslations()
                        .withAdditionalMappings(aggregateMappings.stream()
                                .collect(toImmutableMap(assignment -> scopeAwareKey(assignment.getAstExpression(), analysis, subPlan.getScope()), AggregationAssignment::getSymbol))),
                aggregationNode);
    }

    private <T extends Expression> List<T> scopeAwareDistinct(PlanBuilder subPlan, List<T> expressions)
    {
        return expressions.stream()
                .map(function -> scopeAwareKey(function, analysis, subPlan.getScope()))
                .distinct()
                .map(ScopeAware::getNode)
                .collect(toImmutableList());
    }

    private OrderingScheme translateOrderingScheme(List<SortItem> items, Function<Expression, Symbol> coercions)
    {
        List<Symbol> symbols = items.stream()
                .map(SortItem::getSortKey)
                .map(coercions)
                .collect(toImmutableList());

        ImmutableMap.Builder<Symbol, SortOrder> orders = ImmutableMap.builder();
        for (int i = 0; i < symbols.size(); i++) {
            orders.put(symbols.get(i), OrderingScheme.sortItemToSortOrder(items.get(i)));
        }

        return new OrderingScheme(symbols, orders.build());
    }

    private List<Set<FieldId>> enumerateGroupingSets(GroupingSetAnalysis groupingSetAnalysis)
    {
        List<List<Set<FieldId>>> partialSets = new ArrayList<>();

        for (Set<FieldId> cube : groupingSetAnalysis.getCubes()) {
            partialSets.add(ImmutableList.copyOf(Sets.powerSet(cube)));
        }

        for (List<FieldId> rollup : groupingSetAnalysis.getRollups()) {
            List<Set<FieldId>> sets = IntStream.rangeClosed(0, rollup.size())
                    .mapToObj(i -> ImmutableSet.copyOf(rollup.subList(0, i)))
                    .collect(toImmutableList());

            partialSets.add(sets);
        }

        partialSets.addAll(groupingSetAnalysis.getOrdinarySets());

        if (partialSets.isEmpty()) {
            return ImmutableList.of(ImmutableSet.of());
        }

        // compute the cross product of the partial sets
        List<Set<FieldId>> allSets = new ArrayList<>();
        partialSets.get(0)
                .stream()
                .map(ImmutableSet::copyOf)
                .forEach(allSets::add);

        for (int i = 1; i < partialSets.size(); i++) {
            List<Set<FieldId>> groupingSets = partialSets.get(i);
            List<Set<FieldId>> oldGroupingSetsCrossProduct = ImmutableList.copyOf(allSets);
            allSets.clear();
            for (Set<FieldId> existingSet : oldGroupingSetsCrossProduct) {
                for (Set<FieldId> groupingSet : groupingSets) {
                    Set<FieldId> concatenatedSet = ImmutableSet.<FieldId>builder()
                            .addAll(existingSet)
                            .addAll(groupingSet)
                            .build();
                    allSets.add(concatenatedSet);
                }
            }
        }

        return allSets;
    }

    private PlanBuilder planGroupingOperations(PlanBuilder subPlan, QuerySpecification node, Optional<Symbol> groupIdSymbol, List<Set<FieldId>> groupingSets)
    {
        if (analysis.getGroupingOperations(node).isEmpty()) {
            return subPlan;
        }

        List<Set<Integer>> descriptor = groupingSets.stream()
                .map(set -> set.stream()
                        .map(FieldId::getFieldIndex)
                        .collect(toImmutableSet()))
                .collect(toImmutableList());

        return subPlan.appendProjections(
                analysis.getGroupingOperations(node),
                symbolAllocator,
                idAllocator,
                (translations, groupingOperation) -> rewriteGroupingOperation(groupingOperation, descriptor, analysis.getColumnReferenceFields(), groupIdSymbol),
                (translations, groupingOperation) -> false);
    }

    private PlanBuilder window(Node node, PlanBuilder subPlan, List<FunctionCall> windowFunctions)
    {
        if (windowFunctions.isEmpty()) {
            return subPlan;
        }

        for (FunctionCall windowFunction : scopeAwareDistinct(subPlan, windowFunctions)) {
            checkArgument(windowFunction.getFilter().isEmpty(), "Window functions cannot have filter");

            ResolvedWindow window = analysis.getWindow(windowFunction);
            checkState(window != null, "no resolved window for: " + windowFunction);

            // Pre-project inputs.
            // Predefined window parts (specified in WINDOW clause) can only use source symbols, and no output symbols.
            // It matters in case when this window planning takes place in ORDER BY clause, where both source and output
            // symbols are visible.
            // This issue is solved by analyzing window definitions in the source scope. After analysis, the expressions
            // are recorded as belonging to the source scope, and consequentially source symbols will be used to plan them.
            ImmutableList.Builder<Expression> inputsBuilder = ImmutableList.<Expression>builder()
                    .addAll(windowFunction.getArguments().stream()
                            .filter(argument -> !(argument instanceof LambdaExpression)) // lambda expression is generated at execution time
                            .collect(Collectors.toList()))
                    .addAll(window.getPartitionBy())
                    .addAll(getSortItemsFromOrderBy(window.getOrderBy()).stream()
                            .map(SortItem::getSortKey)
                            .iterator());

            if (window.getFrame().isPresent()) {
                WindowFrame frame = window.getFrame().get();
                frame.getStart().getValue().ifPresent(inputsBuilder::add);

                if (frame.getEnd().isPresent()) {
                    frame.getEnd().get().getValue().ifPresent(inputsBuilder::add);
                }
            }

            List<Expression> inputs = inputsBuilder.build();

            subPlan = subqueryPlanner.handleSubqueries(subPlan, inputs, analysis.getSubqueries(node));
            subPlan = subPlan.appendProjections(inputs, symbolAllocator, idAllocator);

            // Add projection to coerce inputs to their site-specific types.
            // This is important because the same lexical expression may need to be coerced
            // in different ways if it's referenced by multiple arguments to the window function.
            // For example, given v::integer,
            //    avg(v) OVER (ORDER BY v)
            // Needs to be rewritten as
            //    avg(CAST(v AS double)) OVER (ORDER BY v)
            PlanAndMappings coercions = coerce(subPlan, inputs, analysis, idAllocator, symbolAllocator, typeCoercion);
            subPlan = coercions.getSubPlan();

            // For frame of type RANGE, append casts and functions necessary for frame bound calculations
            Optional<Symbol> frameStart = Optional.empty();
            Optional<Symbol> frameEnd = Optional.empty();
            Optional<Symbol> sortKeyCoercedForFrameStartComparison = Optional.empty();
            Optional<Symbol> sortKeyCoercedForFrameEndComparison = Optional.empty();

            if (window.getFrame().isPresent() && window.getFrame().get().getType() == RANGE) {
                Optional<Expression> startValue = window.getFrame().get().getStart().getValue();
                Optional<Expression> endValue = window.getFrame().get().getEnd().flatMap(FrameBound::getValue);
                // record sortKey coercions for reuse
                Map<Type, Symbol> sortKeyCoercions = new HashMap<>();

                // process frame start
                FrameBoundPlanAndSymbols plan = planFrameBound(subPlan, coercions, startValue, window, sortKeyCoercions);
                subPlan = plan.getSubPlan();
                frameStart = plan.getFrameBoundSymbol();
                sortKeyCoercedForFrameStartComparison = plan.getSortKeyCoercedForFrameBoundComparison();

                // process frame end
                plan = planFrameBound(subPlan, coercions, endValue, window, sortKeyCoercions);
                subPlan = plan.getSubPlan();
                frameEnd = plan.getFrameBoundSymbol();
                sortKeyCoercedForFrameEndComparison = plan.getSortKeyCoercedForFrameBoundComparison();
            }
            else if (window.getFrame().isPresent() && (window.getFrame().get().getType() == ROWS || window.getFrame().get().getType() == GROUPS)) {
                Optional<Expression> startValue = window.getFrame().get().getStart().getValue();
                Optional<Expression> endValue = window.getFrame().get().getEnd().flatMap(FrameBound::getValue);

                // process frame start
                FrameOffsetPlanAndSymbol plan = planFrameOffset(subPlan, startValue.map(coercions::get));
                subPlan = plan.getSubPlan();
                frameStart = plan.getFrameOffsetSymbol();

                // process frame end
                plan = planFrameOffset(subPlan, endValue.map(coercions::get));
                subPlan = plan.getSubPlan();
                frameEnd = plan.getFrameOffsetSymbol();
            }
            else if (window.getFrame().isPresent()) {
                throw new IllegalArgumentException("unexpected window frame type: " + window.getFrame().get().getType());
            }

            subPlan = planWindow(subPlan, windowFunction, window, coercions, frameStart, sortKeyCoercedForFrameStartComparison, frameEnd, sortKeyCoercedForFrameEndComparison);
        }

        return subPlan;
    }

    private FrameBoundPlanAndSymbols planFrameBound(PlanBuilder subPlan, PlanAndMappings coercions, Optional<Expression> frameOffset, ResolvedWindow window, Map<Type, Symbol> sortKeyCoercions)
    {
        Optional<ResolvedFunction> frameBoundCalculationFunction = frameOffset.map(analysis::getFrameBoundCalculation);

        // Empty frameBoundCalculationFunction indicates that frame bound type is CURRENT ROW or UNBOUNDED.
        // Handling it doesn't require any additional symbols.
        if (frameBoundCalculationFunction.isEmpty()) {
            return new FrameBoundPlanAndSymbols(subPlan, Optional.empty(), Optional.empty());
        }

        // Present frameBoundCalculationFunction indicates that frame bound type is <expression> PRECEDING or <expression> FOLLOWING.
        // It requires adding certain projections to the plan so that the operator can determine frame bounds.

        // First, append filter to validate offset values. They mustn't be negative or null.
        Symbol offsetSymbol = coercions.get(frameOffset.get());
        Expression zeroOffset = zeroOfType(symbolAllocator.getTypes().get(offsetSymbol));
        ResolvedFunction fail = metadata.resolveFunction(QualifiedName.of("fail"), fromTypes(VARCHAR));
        Expression predicate = new IfExpression(
                new ComparisonExpression(
                        GREATER_THAN_OR_EQUAL,
                        offsetSymbol.toSymbolReference(),
                        zeroOffset),
                TRUE_LITERAL,
                new Cast(
                        new FunctionCall(
                                fail.toQualifiedName(),
                                ImmutableList.of(new Cast(new StringLiteral("Window frame offset value must not be negative or null"), toSqlType(VARCHAR)))),
                        toSqlType(BOOLEAN)));
        subPlan = subPlan.withNewRoot(new FilterNode(
                idAllocator.getNextId(),
                subPlan.getRoot(),
                predicate));

        // Then, coerce the sortKey so that we can add / subtract the offset.
        // Note: for that we cannot rely on the usual mechanism of using the coerce() method. The coerce() method can only handle one coercion for a node,
        // while the sortKey node might require several different coercions, e.g. one for frame start and one for frame end.
        Expression sortKey = Iterables.getOnlyElement(window.getOrderBy().get().getSortItems()).getSortKey();
        Symbol sortKeyCoercedForFrameBoundCalculation = coercions.get(sortKey);
        Optional<Type> coercion = frameOffset.map(analysis::getSortKeyCoercionForFrameBoundCalculation);
        if (coercion.isPresent()) {
            Type expectedType = coercion.get();
            Symbol alreadyCoerced = sortKeyCoercions.get(expectedType);
            if (alreadyCoerced != null) {
                sortKeyCoercedForFrameBoundCalculation = alreadyCoerced;
            }
            else {
                Expression cast = new Cast(
                        coercions.get(sortKey).toSymbolReference(),
                        toSqlType(expectedType),
                        false,
                        typeCoercion.isTypeOnlyCoercion(analysis.getType(sortKey), expectedType));
                sortKeyCoercedForFrameBoundCalculation = symbolAllocator.newSymbol(cast, expectedType);
                sortKeyCoercions.put(expectedType, sortKeyCoercedForFrameBoundCalculation);
                subPlan = subPlan.withNewRoot(new ProjectNode(
                        idAllocator.getNextId(),
                        subPlan.getRoot(),
                        Assignments.builder()
                                .putIdentities(subPlan.getRoot().getOutputSymbols())
                                .put(sortKeyCoercedForFrameBoundCalculation, cast)
                                .build()));
            }
        }

        // Next, pre-project the function which combines sortKey with the offset.
        // Note: if frameOffset needs a coercion, it was added before by a call to coerce() method.
        ResolvedFunction function = frameBoundCalculationFunction.get();
        Expression functionCall = new FunctionCall(
                function.toQualifiedName(),
                ImmutableList.of(
                        sortKeyCoercedForFrameBoundCalculation.toSymbolReference(),
                        offsetSymbol.toSymbolReference()));
        Symbol frameBoundSymbol = symbolAllocator.newSymbol(functionCall, function.getSignature().getReturnType());
        subPlan = subPlan.withNewRoot(new ProjectNode(
                idAllocator.getNextId(),
                subPlan.getRoot(),
                Assignments.builder()
                        .putIdentities(subPlan.getRoot().getOutputSymbols())
                        .put(frameBoundSymbol, functionCall)
                        .build()));

        // Finally, coerce the sortKey to the type of frameBound so that the operator can perform comparisons on them
        Optional<Symbol> sortKeyCoercedForFrameBoundComparison = Optional.of(coercions.get(sortKey));
        coercion = frameOffset.map(analysis::getSortKeyCoercionForFrameBoundComparison);
        if (coercion.isPresent()) {
            Type expectedType = coercion.get();
            Symbol alreadyCoerced = sortKeyCoercions.get(expectedType);
            if (alreadyCoerced != null) {
                sortKeyCoercedForFrameBoundComparison = Optional.of(alreadyCoerced);
            }
            else {
                Expression cast = new Cast(
                        coercions.get(sortKey).toSymbolReference(),
                        toSqlType(expectedType),
                        false,
                        typeCoercion.isTypeOnlyCoercion(analysis.getType(sortKey), expectedType));
                Symbol castSymbol = symbolAllocator.newSymbol(cast, expectedType);
                sortKeyCoercions.put(expectedType, castSymbol);
                subPlan = subPlan.withNewRoot(new ProjectNode(
                        idAllocator.getNextId(),
                        subPlan.getRoot(),
                        Assignments.builder()
                                .putIdentities(subPlan.getRoot().getOutputSymbols())
                                .put(castSymbol, cast)
                                .build()));
                sortKeyCoercedForFrameBoundComparison = Optional.of(castSymbol);
            }
        }

        return new FrameBoundPlanAndSymbols(subPlan, Optional.of(frameBoundSymbol), sortKeyCoercedForFrameBoundComparison);
    }

    private FrameOffsetPlanAndSymbol planFrameOffset(PlanBuilder subPlan, Optional<Symbol> frameOffset)
    {
        if (frameOffset.isEmpty()) {
            return new FrameOffsetPlanAndSymbol(subPlan, Optional.empty());
        }

        Symbol offsetSymbol = frameOffset.get();
        Type offsetType = symbolAllocator.getTypes().get(offsetSymbol);

        // Append filter to validate offset values. They mustn't be negative or null.
        Expression zeroOffset = zeroOfType(offsetType);
        ResolvedFunction fail = metadata.resolveFunction(QualifiedName.of("fail"), fromTypes(VARCHAR));
        Expression predicate = new IfExpression(
                new ComparisonExpression(GREATER_THAN_OR_EQUAL, offsetSymbol.toSymbolReference(), zeroOffset),
                TRUE_LITERAL,
                new Cast(
                        new FunctionCall(
                                fail.toQualifiedName(),
                                ImmutableList.of(new Cast(new StringLiteral("Window frame offset value must not be negative or null"), toSqlType(VARCHAR)))),
                        toSqlType(BOOLEAN)));
        subPlan = subPlan.withNewRoot(new FilterNode(
                idAllocator.getNextId(),
                subPlan.getRoot(),
                predicate));

        if (offsetType.equals(BIGINT)) {
            return new FrameOffsetPlanAndSymbol(subPlan, Optional.of(offsetSymbol));
        }

        Expression offsetToBigint;

        if (offsetType instanceof DecimalType && !((DecimalType) offsetType).isShort()) {
            String maxBigint = Long.toString(Long.MAX_VALUE);
            int maxBigintPrecision = maxBigint.length();
            int actualPrecision = ((DecimalType) offsetType).getPrecision();

            if (actualPrecision < maxBigintPrecision) {
                offsetToBigint = new Cast(offsetSymbol.toSymbolReference(), toSqlType(BIGINT));
            }
            else if (actualPrecision > maxBigintPrecision) {
                // If the offset value exceeds max bigint, it implies that the frame bound falls beyond the partition bound.
                // In such case, the frame bound is set to the partition bound. Passing max bigint as the offset value has
                // the same effect. The offset value can be truncated to max bigint for the purpose of cast.
                offsetToBigint = new GenericLiteral("BIGINT", maxBigint);
            }
            else {
                offsetToBigint = new IfExpression(
                        new ComparisonExpression(LESS_THAN_OR_EQUAL, offsetSymbol.toSymbolReference(), new DecimalLiteral(maxBigint)),
                        new Cast(offsetSymbol.toSymbolReference(), toSqlType(BIGINT)),
                        new GenericLiteral("BIGINT", maxBigint));
            }
        }
        else {
            offsetToBigint = new Cast(
                    offsetSymbol.toSymbolReference(),
                    toSqlType(BIGINT),
                    false,
                    typeCoercion.isTypeOnlyCoercion(offsetType, BIGINT));
        }

        Symbol coercedOffsetSymbol = symbolAllocator.newSymbol(offsetToBigint, BIGINT);
        subPlan = subPlan.withNewRoot(new ProjectNode(
                idAllocator.getNextId(),
                subPlan.getRoot(),
                Assignments.builder()
                        .putIdentities(subPlan.getRoot().getOutputSymbols())
                        .put(coercedOffsetSymbol, offsetToBigint)
                        .build()));

        return new FrameOffsetPlanAndSymbol(subPlan, Optional.of(coercedOffsetSymbol));
    }

    private Expression zeroOfType(Type type)
    {
        if (isNumericType(type)) {
            return new Cast(new LongLiteral("0"), toSqlType(type));
        }
        if (type.equals(INTERVAL_DAY_TIME)) {
            return new IntervalLiteral("0", POSITIVE, DAY);
        }
        if (type.equals(INTERVAL_YEAR_MONTH)) {
            return new IntervalLiteral("0", POSITIVE, YEAR);
        }
        throw new IllegalArgumentException("unexpected type: " + type);
    }

    private PlanBuilder planWindow(
            PlanBuilder subPlan,
            FunctionCall windowFunction,
            ResolvedWindow window,
            PlanAndMappings coercions,
            Optional<Symbol> frameStartSymbol,
            Optional<Symbol> sortKeyCoercedForFrameStartComparison,
            Optional<Symbol> frameEndSymbol,
            Optional<Symbol> sortKeyCoercedForFrameEndComparison)
    {
        WindowFrame.Type frameType = WindowFrame.Type.RANGE;
        FrameBound.Type frameStartType = FrameBound.Type.UNBOUNDED_PRECEDING;
        FrameBound.Type frameEndType = FrameBound.Type.CURRENT_ROW;

        Optional<Expression> frameStartExpression = Optional.empty();
        Optional<Expression> frameEndExpression = Optional.empty();

        if (window.getFrame().isPresent()) {
            WindowFrame frame = window.getFrame().get();
            frameType = frame.getType();

            frameStartType = frame.getStart().getType();
            frameStartExpression = frame.getStart().getValue();

            if (frame.getEnd().isPresent()) {
                frameEndType = frame.getEnd().get().getType();
                frameEndExpression = frame.getEnd().get().getValue();
            }
        }

        // Rewrite PARTITION BY in terms of pre-projected inputs
        ImmutableList.Builder<Symbol> partitionBySymbols = ImmutableList.builder();
        for (Expression expression : window.getPartitionBy()) {
            partitionBySymbols.add(coercions.get(expression));
        }

        // Rewrite ORDER BY in terms of pre-projected inputs
        LinkedHashMap<Symbol, SortOrder> orderings = new LinkedHashMap<>();
        for (SortItem item : getSortItemsFromOrderBy(window.getOrderBy())) {
            Symbol symbol = coercions.get(item.getSortKey());
            // don't override existing keys, i.e. when "ORDER BY a ASC, a DESC" is specified
            orderings.putIfAbsent(symbol, sortItemToSortOrder(item));
        }

        // Rewrite frame bounds in terms of pre-projected inputs
        WindowNode.Frame frame = new WindowNode.Frame(
                frameType,
                frameStartType,
                frameStartSymbol,
                sortKeyCoercedForFrameStartComparison,
                frameEndType,
                frameEndSymbol,
                sortKeyCoercedForFrameEndComparison,
                frameStartExpression,
                frameEndExpression);

        Symbol newSymbol = symbolAllocator.newSymbol(windowFunction, analysis.getType(windowFunction));

        NullTreatment nullTreatment = windowFunction.getNullTreatment()
                .orElse(NullTreatment.RESPECT);

        WindowNode.Function function = new WindowNode.Function(
                analysis.getResolvedFunction(windowFunction),
                windowFunction.getArguments().stream()
                        .map(argument -> {
                            if (argument instanceof LambdaExpression) {
                                return subPlan.rewrite(argument);
                            }
                            return coercions.get(argument).toSymbolReference();
                        })
                        .collect(toImmutableList()),
                frame,
                nullTreatment == NullTreatment.IGNORE);

        ImmutableList.Builder<Symbol> orderBySymbols = ImmutableList.builder();
        orderBySymbols.addAll(orderings.keySet());
        Optional<OrderingScheme> orderingScheme = Optional.empty();
        if (!orderings.isEmpty()) {
            orderingScheme = Optional.of(new OrderingScheme(orderBySymbols.build(), orderings));
        }

        // create window node
        return new PlanBuilder(
                subPlan.getTranslations()
                        .withAdditionalMappings(ImmutableMap.of(scopeAwareKey(windowFunction, analysis, subPlan.getScope()), newSymbol)),
                new WindowNode(
                        idAllocator.getNextId(),
                        subPlan.getRoot(),
                        new WindowNode.Specification(
                                partitionBySymbols.build(),
                                orderingScheme),
                        ImmutableMap.of(newSymbol, function),
                        Optional.empty(),
                        ImmutableSet.of(),
                        0));
    }

    /**
     * Creates a projection with any additional coercions by identity of the provided expressions.
     *
     * @return the new subplan and a mapping of each expression to the symbol representing the coercion or an existing symbol if a coercion wasn't needed
     */
    public static PlanAndMappings coerce(PlanBuilder subPlan, List<Expression> expressions, Analysis analysis, PlanNodeIdAllocator idAllocator, SymbolAllocator symbolAllocator, TypeCoercion typeCoercion)
    {
        Assignments.Builder assignments = Assignments.builder();
        assignments.putIdentities(subPlan.getRoot().getOutputSymbols());

        ImmutableMap.Builder<NodeRef<Expression>, Symbol> mappings = ImmutableMap.builder();
        for (Expression expression : expressions) {
            Type coercion = analysis.getCoercion(expression);

            if (coercion != null) {
                Type type = analysis.getType(expression);
                Symbol symbol = symbolAllocator.newSymbol(expression, coercion);

                assignments.put(symbol, new Cast(
                        subPlan.rewrite(expression),
                        toSqlType(coercion),
                        false,
                        typeCoercion.isTypeOnlyCoercion(type, coercion)));

                mappings.put(NodeRef.of(expression), symbol);
            }
            else {
                mappings.put(NodeRef.of(expression), subPlan.translate(expression));
            }
        }

        subPlan = subPlan.withNewRoot(
                new ProjectNode(
                        idAllocator.getNextId(),
                        subPlan.getRoot(),
                        assignments.build()));

        return new PlanAndMappings(subPlan, mappings.build());
    }

    public static Expression coerceIfNecessary(Analysis analysis, Expression original, Expression rewritten)
    {
        Type coercion = analysis.getCoercion(original);
        if (coercion == null) {
            return rewritten;
        }

        return new Cast(
                rewritten,
                toSqlType(coercion),
                false,
                analysis.isTypeOnlyCoercion(original));
    }

    public static NodeAndMappings coerce(RelationPlan plan, List<Type> types, SymbolAllocator symbolAllocator, PlanNodeIdAllocator idAllocator)
    {
        List<Symbol> visibleFields = visibleFields(plan);
        checkArgument(visibleFields.size() == types.size());

        Assignments.Builder assignments = Assignments.builder();
        ImmutableList.Builder<Symbol> mappings = ImmutableList.builder();
        for (int i = 0; i < types.size(); i++) {
            Symbol input = visibleFields.get(i);
            Type type = types.get(i);

            if (!symbolAllocator.getTypes().get(input).equals(type)) {
                Symbol coerced = symbolAllocator.newSymbol(input.getName(), type);
                assignments.put(coerced, new Cast(input.toSymbolReference(), toSqlType(type)));
                mappings.add(coerced);
            }
            else {
                assignments.putIdentity(input);
                mappings.add(input);
            }
        }

        ProjectNode coerced = new ProjectNode(idAllocator.getNextId(), plan.getRoot(), assignments.build());
        return new NodeAndMappings(coerced, mappings.build());
    }

    public static List<Symbol> visibleFields(RelationPlan subPlan)
    {
        RelationType descriptor = subPlan.getDescriptor();
        return descriptor.getAllFields().stream()
                .filter(field -> !field.isHidden())
                .map(descriptor::indexOf)
                .map(subPlan.getFieldMappings()::get)
                .collect(toImmutableList());
    }

    public static NodeAndMappings pruneInvisibleFields(RelationPlan plan, PlanNodeIdAllocator idAllocator)
    {
        List<Symbol> visibleFields = visibleFields(plan);
        ProjectNode pruned = new ProjectNode(idAllocator.getNextId(), plan.getRoot(), Assignments.identity(visibleFields));
        return new NodeAndMappings(pruned, visibleFields);
    }

    private PlanBuilder distinct(PlanBuilder subPlan, QuerySpecification node, List<Expression> expressions)
    {
        if (node.getSelect().isDistinct()) {
            List<Symbol> symbols = expressions.stream()
                    .map(subPlan::translate)
                    .collect(Collectors.toList());

            return subPlan.withNewRoot(
                    new AggregationNode(
                            idAllocator.getNextId(),
                            subPlan.getRoot(),
                            ImmutableMap.of(),
                            singleGroupingSet(symbols),
                            ImmutableList.of(),
                            AggregationNode.Step.SINGLE,
                            Optional.empty(),
                            Optional.empty()));
        }

        return subPlan;
    }

    private Optional<OrderingScheme> orderingScheme(PlanBuilder subPlan, Optional<OrderBy> orderBy, List<Expression> orderByExpressions)
    {
        if (orderBy.isEmpty() || (isSkipRedundantSort(session)) && analysis.isOrderByRedundant(orderBy.get())) {
            return Optional.empty();
        }

        Iterator<SortItem> sortItems = orderBy.get().getSortItems().iterator();

        ImmutableList.Builder<Symbol> orderBySymbols = ImmutableList.builder();
        Map<Symbol, SortOrder> orderings = new HashMap<>();
        for (Expression fieldOrExpression : orderByExpressions) {
            Symbol symbol = subPlan.translate(fieldOrExpression);

            SortItem sortItem = sortItems.next();
            if (!orderings.containsKey(symbol)) {
                orderBySymbols.add(symbol);
                orderings.put(symbol, sortItemToSortOrder(sortItem));
            }
        }
        return Optional.of(new OrderingScheme(orderBySymbols.build(), orderings));
    }

    private PlanBuilder sort(PlanBuilder subPlan, Optional<OrderingScheme> orderingScheme)
    {
        if (orderingScheme.isEmpty()) {
            return subPlan;
        }

        return subPlan.withNewRoot(
                new SortNode(
                        idAllocator.getNextId(),
                        subPlan.getRoot(),
                        orderingScheme.get(),
                        false));
    }

    private PlanBuilder offset(PlanBuilder subPlan, Optional<Offset> offset)
    {
        if (offset.isEmpty()) {
            return subPlan;
        }

        return subPlan.withNewRoot(
                new OffsetNode(
                        idAllocator.getNextId(),
                        subPlan.getRoot(),
                        analysis.getOffset(offset.get())));
    }

    private PlanBuilder limit(PlanBuilder subPlan, Optional<Node> limit, Optional<OrderingScheme> orderingScheme)
    {
        if (limit.isPresent() && analysis.getLimit(limit.get()).isPresent()) {
            Optional<OrderingScheme> tiesResolvingScheme = Optional.empty();
            if (limit.get() instanceof FetchFirst && ((FetchFirst) limit.get()).isWithTies()) {
                tiesResolvingScheme = orderingScheme;
            }
            return subPlan.withNewRoot(
                    new LimitNode(
                            idAllocator.getNextId(),
                            subPlan.getRoot(),
                            analysis.getLimit(limit.get()).getAsLong(),
                            tiesResolvingScheme,
                            false));
        }
        return subPlan;
    }

    private static class GroupingSetsPlan
    {
        private final PlanBuilder subPlan;
        private final List<Set<FieldId>> columnOnlyGroupingSets;
        private final List<List<Symbol>> groupingSets;
        private final Optional<Symbol> groupIdSymbol;

        public GroupingSetsPlan(PlanBuilder subPlan, List<Set<FieldId>> columnOnlyGroupingSets, List<List<Symbol>> groupingSets, Optional<Symbol> groupIdSymbol)
        {
            this.columnOnlyGroupingSets = columnOnlyGroupingSets;
            this.groupingSets = groupingSets;
            this.groupIdSymbol = groupIdSymbol;
            this.subPlan = subPlan;
        }

        public PlanBuilder getSubPlan()
        {
            return subPlan;
        }

        public List<Set<FieldId>> getColumnOnlyGroupingSets()
        {
            return columnOnlyGroupingSets;
        }

        public List<List<Symbol>> getGroupingSets()
        {
            return groupingSets;
        }

        public Optional<Symbol> getGroupIdSymbol()
        {
            return groupIdSymbol;
        }
    }

    public static class PlanAndMappings
    {
        private final PlanBuilder subPlan;
        private final Map<NodeRef<Expression>, Symbol> mappings;

        public PlanAndMappings(PlanBuilder subPlan, Map<NodeRef<Expression>, Symbol> mappings)
        {
            this.subPlan = subPlan;
            this.mappings = mappings;
        }

        public PlanBuilder getSubPlan()
        {
            return subPlan;
        }

        public Symbol get(Expression expression)
        {
            return tryGet(expression)
                    .orElseThrow(() -> new IllegalArgumentException(format("No mapping for expression: %s (%s)", expression, System.identityHashCode(expression))));
        }

        public Optional<Symbol> tryGet(Expression expression)
        {
            Symbol result = mappings.get(NodeRef.of(expression));

            if (result != null) {
                return Optional.of(result);
            }

            return Optional.empty();
        }
    }

    private static class AggregationAssignment
    {
        private final Symbol symbol;
        private final Expression astExpression;
        private final Aggregation aggregation;

        public AggregationAssignment(Symbol symbol, Expression astExpression, Aggregation aggregation)
        {
            this.astExpression = astExpression;
            this.symbol = symbol;
            this.aggregation = aggregation;
        }

        public Symbol getSymbol()
        {
            return symbol;
        }

        public Expression getAstExpression()
        {
            return astExpression;
        }

        public Aggregation getRewritten()
        {
            return aggregation;
        }
    }

    private static class FrameBoundPlanAndSymbols
    {
        private final PlanBuilder subPlan;
        private final Optional<Symbol> frameBoundSymbol;
        private final Optional<Symbol> sortKeyCoercedForFrameBoundComparison;

        public FrameBoundPlanAndSymbols(PlanBuilder subPlan, Optional<Symbol> frameBoundSymbol, Optional<Symbol> sortKeyCoercedForFrameBoundComparison)
        {
            this.subPlan = subPlan;
            this.frameBoundSymbol = frameBoundSymbol;
            this.sortKeyCoercedForFrameBoundComparison = sortKeyCoercedForFrameBoundComparison;
        }

        public PlanBuilder getSubPlan()
        {
            return subPlan;
        }

        public Optional<Symbol> getFrameBoundSymbol()
        {
            return frameBoundSymbol;
        }

        public Optional<Symbol> getSortKeyCoercedForFrameBoundComparison()
        {
            return sortKeyCoercedForFrameBoundComparison;
        }
    }

    private static class FrameOffsetPlanAndSymbol
    {
        private final PlanBuilder subPlan;
        private final Optional<Symbol> frameOffsetSymbol;

        public FrameOffsetPlanAndSymbol(PlanBuilder subPlan, Optional<Symbol> frameOffsetSymbol)
        {
            this.subPlan = subPlan;
            this.frameOffsetSymbol = frameOffsetSymbol;
        }

        public PlanBuilder getSubPlan()
        {
            return subPlan;
        }

        public Optional<Symbol> getFrameOffsetSymbol()
        {
            return frameOffsetSymbol;
        }
    }
}
