/*
 * Copyright 2023 Alibaba Group Holding Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.graphscope.common.ir.runtime.proto;

import com.alibaba.graphscope.common.config.Configs;
import com.alibaba.graphscope.common.config.PegasusConfig;
import com.alibaba.graphscope.common.ir.rel.GraphLogicalAggregate;
import com.alibaba.graphscope.common.ir.rel.GraphLogicalDedupBy;
import com.alibaba.graphscope.common.ir.rel.GraphLogicalProject;
import com.alibaba.graphscope.common.ir.rel.GraphLogicalSort;
import com.alibaba.graphscope.common.ir.rel.GraphShuttle;
import com.alibaba.graphscope.common.ir.rel.graph.*;
import com.alibaba.graphscope.common.ir.rel.graph.match.GraphLogicalMultiMatch;
import com.alibaba.graphscope.common.ir.rel.graph.match.GraphLogicalSingleMatch;
import com.alibaba.graphscope.common.ir.rel.type.group.GraphAggCall;
import com.alibaba.graphscope.common.ir.rel.type.group.GraphGroupKeys;
import com.alibaba.graphscope.common.ir.rel.type.order.GraphFieldCollation;
import com.alibaba.graphscope.common.ir.rex.RexGraphVariable;
import com.alibaba.graphscope.common.ir.tools.AliasInference;
import com.alibaba.graphscope.common.ir.tools.GraphPlanner;
import com.alibaba.graphscope.common.ir.tools.config.GraphOpt;
import com.alibaba.graphscope.common.ir.tools.config.GraphOpt.PhysicalGetVOpt;
import com.alibaba.graphscope.common.ir.type.GraphLabelType;
import com.alibaba.graphscope.common.ir.type.GraphNameOrId;
import com.alibaba.graphscope.common.ir.type.GraphSchemaType;
import com.alibaba.graphscope.gaia.proto.GraphAlgebra;
import com.alibaba.graphscope.gaia.proto.GraphAlgebraPhysical;
import com.alibaba.graphscope.gaia.proto.OuterExpression;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.*;
import org.apache.calcite.sql.SqlKind;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

public class GraphRelToProtoConverter extends GraphShuttle {
    private static final Logger logger = LoggerFactory.getLogger(GraphRelToProtoConverter.class);
    private final boolean isColumnId;
    private final RexBuilder rexBuilder;
    private final Configs graphConfig;
    private GraphAlgebraPhysical.PhysicalPlan.Builder physicalBuilder;
    private final boolean isPartitioned;
    private boolean preCacheEdgeProps;

    public GraphRelToProtoConverter(
            boolean isColumnId,
            Configs configs,
            GraphAlgebraPhysical.PhysicalPlan.Builder physicalBuilder) {
        this.isColumnId = isColumnId;
        this.rexBuilder = GraphPlanner.rexBuilderFactory.apply(configs);
        this.graphConfig = configs;
        this.physicalBuilder = physicalBuilder;
        this.isPartitioned =
                !(PegasusConfig.PEGASUS_HOSTS.get(configs).split(",").length == 1
                        && PegasusConfig.PEGASUS_WORKER_NUM.get(configs) == 1);
        // currently, since the store doesn't support get properties from edges, we always need to
        // precache edge properties.
        this.preCacheEdgeProps = true;
    }

    @Override
    public RelNode visit(GraphLogicalSource source) {
        GraphAlgebraPhysical.PhysicalOpr.Builder oprBuilder =
                GraphAlgebraPhysical.PhysicalOpr.newBuilder();
        GraphAlgebraPhysical.Scan.Builder scanBuilder = GraphAlgebraPhysical.Scan.newBuilder();
        RexNode uniqueKeyFilters = source.getUniqueKeyFilters();
        if (uniqueKeyFilters != null) {
            GraphAlgebra.IndexPredicate indexPredicate = buildIndexPredicates(uniqueKeyFilters);
            scanBuilder.setIdxPredicate(indexPredicate);
        }
        GraphAlgebra.QueryParams.Builder queryParamsBuilder = buildQueryParams(source);
        if (preCacheEdgeProps && GraphOpt.Source.EDGE.equals(source.getOpt())) {
            addQueryColumns(
                    queryParamsBuilder,
                    Utils.extractColumnsFromRelDataType(source.getRowType(), isColumnId));
        }
        scanBuilder.setParams(buildQueryParams(source));
        if (source.getAliasId() != AliasInference.DEFAULT_ID) {
            scanBuilder.setAlias(Utils.asAliasId(source.getAliasId()));
        }
        scanBuilder.setScanOpt(Utils.protoScanOpt(source.getOpt()));
        oprBuilder.setOpr(
                GraphAlgebraPhysical.PhysicalOpr.Operator.newBuilder().setScan(scanBuilder));
        oprBuilder.addAllMetaData(Utils.physicalProtoRowType(source.getRowType(), isColumnId));
        physicalBuilder.addPlan(oprBuilder.build());
        return source;
    }

    @Override
    public RelNode visit(GraphLogicalExpand expand) {
        visitChildren(expand);
        GraphAlgebraPhysical.PhysicalOpr.Builder oprBuilder =
                GraphAlgebraPhysical.PhysicalOpr.newBuilder();
        GraphAlgebraPhysical.EdgeExpand.Builder edgeExpand = buildEdgeExpand(expand);
        oprBuilder.setOpr(
                GraphAlgebraPhysical.PhysicalOpr.Operator.newBuilder().setEdge(edgeExpand));
        oprBuilder.addAllMetaData(Utils.physicalProtoRowType(expand.getRowType(), isColumnId));
        if (isPartitioned) {
            addRepartitionToAnother(expand.getStartAlias().getAliasId());
        }
        physicalBuilder.addPlan(oprBuilder.build());
        return expand;
    }

    @Override
    public RelNode visit(GraphLogicalGetV getV) {
        visitChildren(getV);
        // convert getV:
        // if there is no filter, build getV(adj)
        // otherwise, build getV(adj) + auxilia(filter)
        if (ObjectUtils.isEmpty(getV.getFilters())) {
            GraphAlgebraPhysical.PhysicalOpr.Builder oprBuilder =
                    GraphAlgebraPhysical.PhysicalOpr.newBuilder();
            GraphAlgebraPhysical.GetV.Builder getVertex = buildGetV(getV);
            oprBuilder.setOpr(
                    GraphAlgebraPhysical.PhysicalOpr.Operator.newBuilder().setVertex(getVertex));
            oprBuilder.addAllMetaData(Utils.physicalProtoRowType(getV.getRowType(), isColumnId));
            physicalBuilder.addPlan(oprBuilder.build());
            return getV;
        } else {
            // build getV(adj) + auxilia(filter) if there is a filter in getV
            GraphAlgebraPhysical.PhysicalOpr.Builder adjOprBuilder =
                    GraphAlgebraPhysical.PhysicalOpr.newBuilder();
            GraphAlgebraPhysical.GetV.Builder adjVertexBuilder =
                    GraphAlgebraPhysical.GetV.newBuilder();
            adjVertexBuilder.setOpt(
                    Utils.protoGetVOpt(PhysicalGetVOpt.valueOf(getV.getOpt().name())));
            // 1. build adjV without filter
            GraphAlgebra.QueryParams.Builder adjParamsBuilder = defaultQueryParams();
            addQueryTables(adjParamsBuilder, getGraphLabels(getV).getLabelsEntry());
            adjVertexBuilder.setParams(adjParamsBuilder);
            if (getV.getStartAlias().getAliasId() != AliasInference.DEFAULT_ID) {
                adjVertexBuilder.setTag(Utils.asAliasId(getV.getStartAlias().getAliasId()));
            }
            if (getV.getAliasId() != AliasInference.DEFAULT_ID) {
                adjVertexBuilder.setAlias(Utils.asAliasId(getV.getAliasId()));
            }
            adjOprBuilder.setOpr(
                    GraphAlgebraPhysical.PhysicalOpr.Operator.newBuilder()
                            .setVertex(adjVertexBuilder));
            adjOprBuilder.addAllMetaData(Utils.physicalProtoRowType(getV.getRowType(), isColumnId));
            physicalBuilder.addPlan(adjOprBuilder.build());

            // 2. build auxilia(filter)
            GraphAlgebraPhysical.PhysicalOpr.Builder auxiliaOprBuilder =
                    GraphAlgebraPhysical.PhysicalOpr.newBuilder();
            GraphAlgebraPhysical.GetV.Builder auxiliaBuilder =
                    GraphAlgebraPhysical.GetV.newBuilder();
            auxiliaBuilder.setOpt(Utils.protoGetVOpt(PhysicalGetVOpt.ITSELF));
            GraphAlgebra.QueryParams.Builder auxiliaParamsBuilder = defaultQueryParams();
            addQueryFilters(auxiliaParamsBuilder, getV.getFilters());
            auxiliaBuilder.setParams(auxiliaParamsBuilder);
            auxiliaOprBuilder.setOpr(
                    GraphAlgebraPhysical.PhysicalOpr.Operator.newBuilder()
                            .setVertex(auxiliaBuilder));
            auxiliaOprBuilder.addAllMetaData(
                    Utils.physicalProtoRowType(getV.getRowType(), isColumnId));
            if (isPartitioned) {
                addRepartitionToAnother(getV.getAliasId());
            }
            physicalBuilder.addPlan(auxiliaOprBuilder.build());
            return getV;
        }
    }

    @Override
    public RelNode visit(GraphLogicalPathExpand pxd) {
        visitChildren(pxd);
        GraphAlgebraPhysical.PhysicalOpr.Builder oprBuilder =
                GraphAlgebraPhysical.PhysicalOpr.newBuilder();
        GraphAlgebraPhysical.PathExpand.Builder pathExpandBuilder =
                GraphAlgebraPhysical.PathExpand.newBuilder();
        GraphAlgebraPhysical.PathExpand.ExpandBase.Builder expandBaseBuilder =
                GraphAlgebraPhysical.PathExpand.ExpandBase.newBuilder();
        RelNode fused = pxd.getFused();
        if (fused != null) {
            // the case that expand base is fused
            if (fused instanceof GraphPhysicalGetV) {
                // fused into expand + auxilia
                GraphPhysicalGetV fusedGetV = (GraphPhysicalGetV) fused;
                GraphAlgebraPhysical.GetV.Builder auxilia = buildAuxilia(fusedGetV);
                expandBaseBuilder.setGetV(auxilia);
                if (fusedGetV.getInput() instanceof GraphPhysicalExpand) {
                    GraphPhysicalExpand fusedExpand = (GraphPhysicalExpand) fusedGetV.getInput();
                    GraphAlgebraPhysical.EdgeExpand.Builder expand = buildEdgeExpand(fusedExpand);
                    expandBaseBuilder.setEdgeExpand(expand);
                } else {
                    throw new UnsupportedOperationException(
                            "unsupported fused plan in path expand base: "
                                    + fusedGetV.getInput().getClass().getName());
                }
            } else if (fused instanceof GraphPhysicalExpand) {
                // fused into expand
                GraphPhysicalExpand fusedExpand = (GraphPhysicalExpand) fused;
                GraphAlgebraPhysical.EdgeExpand.Builder expand = buildEdgeExpand(fusedExpand);
                expandBaseBuilder.setEdgeExpand(expand);
            } else {
                throw new UnsupportedOperationException(
                        "unsupported fused plan in path expand base");
            }
        } else {
            // the case that expand base is not fused
            GraphAlgebraPhysical.EdgeExpand.Builder expand =
                    buildEdgeExpand((GraphLogicalExpand) pxd.getExpand());
            GraphAlgebraPhysical.GetV.Builder getV = buildGetV((GraphLogicalGetV) pxd.getGetV());
            expandBaseBuilder.setEdgeExpand(expand);
            expandBaseBuilder.setGetV(getV);
        }
        pathExpandBuilder.setBase(expandBaseBuilder);
        pathExpandBuilder.setPathOpt(Utils.protoPathOpt(pxd.getPathOpt()));
        pathExpandBuilder.setResultOpt(Utils.protoPathResultOpt(pxd.getResultOpt()));
        GraphAlgebra.Range range = buildRange(pxd.getOffset(), pxd.getFetch());
        pathExpandBuilder.setHopRange(range);
        if (pxd.getAliasId() != AliasInference.DEFAULT_ID) {
            pathExpandBuilder.setAlias(Utils.asAliasId(pxd.getAliasId()));
        }
        if (pxd.getStartAlias().getAliasId() != AliasInference.DEFAULT_ID) {
            pathExpandBuilder.setStartTag(Utils.asAliasId(pxd.getStartAlias().getAliasId()));
        }
        oprBuilder.setOpr(
                GraphAlgebraPhysical.PhysicalOpr.Operator.newBuilder().setPath(pathExpandBuilder));
        if (isPartitioned) {
            addRepartitionToAnother(pxd.getStartAlias().getAliasId());
        }
        physicalBuilder.addPlan(oprBuilder.build());
        return pxd;
    }

    @Override
    public RelNode visit(GraphPhysicalExpand physicalExpand) {
        visitChildren(physicalExpand);
        GraphAlgebraPhysical.PhysicalOpr.Builder oprBuilder =
                GraphAlgebraPhysical.PhysicalOpr.newBuilder();
        GraphAlgebraPhysical.EdgeExpand.Builder edgeExpand = buildEdgeExpand(physicalExpand);
        oprBuilder.setOpr(
                GraphAlgebraPhysical.PhysicalOpr.Operator.newBuilder().setEdge(edgeExpand));
        oprBuilder.addAllMetaData(
                Utils.physicalProtoRowType(physicalExpand.getRowType(), isColumnId));
        if (isPartitioned) {
            addRepartitionToAnother(physicalExpand.getStartAlias().getAliasId());
        }
        physicalBuilder.addPlan(oprBuilder.build());
        return physicalExpand;
    }

    @Override
    public RelNode visit(GraphPhysicalGetV physicalGetV) {
        visitChildren(physicalGetV);
        GraphAlgebraPhysical.PhysicalOpr.Builder oprBuilder =
                GraphAlgebraPhysical.PhysicalOpr.newBuilder();
        GraphAlgebraPhysical.GetV.Builder auxilia = buildAuxilia(physicalGetV);
        oprBuilder.setOpr(
                GraphAlgebraPhysical.PhysicalOpr.Operator.newBuilder().setVertex(auxilia));
        oprBuilder.addAllMetaData(
                Utils.physicalProtoRowType(physicalGetV.getRowType(), isColumnId));
        if (isPartitioned) {
            addRepartitionToAnother(physicalGetV.getStartAlias().getAliasId());
        }
        physicalBuilder.addPlan(oprBuilder.build());
        return physicalGetV;
    }

    @Override
    public RelNode visit(LogicalFilter filter) {
        visitChildren(filter);
        GraphAlgebraPhysical.PhysicalOpr.Builder oprBuilder =
                GraphAlgebraPhysical.PhysicalOpr.newBuilder();
        GraphAlgebra.Select.Builder selectBuilder = GraphAlgebra.Select.newBuilder();
        OuterExpression.Expression exprProto =
                filter.getCondition()
                        .accept(new RexToProtoConverter(true, isColumnId, this.rexBuilder));
        selectBuilder.setPredicate(exprProto);
        oprBuilder.setOpr(
                GraphAlgebraPhysical.PhysicalOpr.Operator.newBuilder().setSelect(selectBuilder));
        oprBuilder.addAllMetaData(Utils.physicalProtoRowType(filter.getRowType(), isColumnId));
        if (isPartitioned) {
            Map<Integer, Set<GraphNameOrId>> tagColumns =
                    Utils.extractTagColumnsFromRexNodes(List.of(filter.getCondition()));
            if (preCacheEdgeProps) {
                Utils.removeEdgeProperties(
                        com.alibaba.graphscope.common.ir.tools.Utils.getOutputType(
                                filter.getInput()),
                        tagColumns);
            }
            lazyPropertyFetching(tagColumns, true);
        }
        physicalBuilder.addPlan(oprBuilder.build());
        return filter;
    }

    @Override
    public RelNode visit(GraphLogicalProject project) {
        visitChildren(project);
        GraphAlgebraPhysical.PhysicalOpr.Builder oprBuilder =
                GraphAlgebraPhysical.PhysicalOpr.newBuilder();
        GraphAlgebraPhysical.Project.Builder projectBuilder =
                GraphAlgebraPhysical.Project.newBuilder();
        projectBuilder.setIsAppend(project.isAppend());
        List<RelDataTypeField> fields = project.getRowType().getFieldList();

        for (int i = 0; i < project.getProjects().size(); ++i) {
            OuterExpression.Expression expression =
                    project.getProjects()
                            .get(i)
                            .accept(new RexToProtoConverter(true, isColumnId, this.rexBuilder));
            int aliasId = fields.get(i).getIndex();
            GraphAlgebraPhysical.Project.ExprAlias.Builder projectExprAliasBuilder =
                    GraphAlgebraPhysical.Project.ExprAlias.newBuilder();
            projectExprAliasBuilder.setExpr(expression);
            if (aliasId != AliasInference.DEFAULT_ID) {
                projectExprAliasBuilder.setAlias(Utils.asAliasId(aliasId));
            }
            projectBuilder.addMappings(projectExprAliasBuilder.build());
        }
        oprBuilder.setOpr(
                GraphAlgebraPhysical.PhysicalOpr.Operator.newBuilder().setProject(projectBuilder));
        oprBuilder.addAllMetaData(Utils.physicalProtoRowType(project.getRowType(), isColumnId));
        if (isPartitioned) {
            Map<Integer, Set<GraphNameOrId>> tagColumns =
                    Utils.extractTagColumnsFromRexNodes(project.getProjects());
            if (preCacheEdgeProps) {
                Utils.removeEdgeProperties(
                        com.alibaba.graphscope.common.ir.tools.Utils.getOutputType(
                                project.getInput()),
                        tagColumns);
            }
            lazyPropertyFetching(tagColumns, true);
        }
        physicalBuilder.addPlan(oprBuilder.build());
        return project;
    }

    @Override
    public RelNode visit(GraphLogicalAggregate aggregate) {
        visitChildren(aggregate);
        List<RelDataTypeField> fields = aggregate.getRowType().getFieldList();
        List<GraphAggCall> groupCalls = aggregate.getAggCalls();
        GraphGroupKeys keys = aggregate.getGroupKey();
        if (groupCalls.isEmpty()) { // transform to project + dedup by keys
            Preconditions.checkArgument(
                    keys.groupKeyCount() > 0,
                    "group keys should not be empty while group calls is empty");
            GraphAlgebraPhysical.Project.Builder projectBuilder =
                    GraphAlgebraPhysical.Project.newBuilder();
            for (int i = 0; i < keys.groupKeyCount(); ++i) {
                RexNode var = keys.getVariables().get(i);
                Preconditions.checkArgument(
                        var instanceof RexGraphVariable,
                        "each group key should be type %s, but is %s",
                        RexGraphVariable.class,
                        var.getClass());
                OuterExpression.Expression expr =
                        var.accept(new RexToProtoConverter(true, isColumnId, this.rexBuilder));
                int aliasId;
                if (i >= fields.size()
                        || (aliasId = fields.get(i).getIndex()) == AliasInference.DEFAULT_ID) {
                    throw new IllegalArgumentException(
                            "each group key should have an alias if need dedup");
                }
                GraphAlgebraPhysical.Project.ExprAlias.Builder projectExprAliasBuilder =
                        GraphAlgebraPhysical.Project.ExprAlias.newBuilder();
                projectExprAliasBuilder.setExpr(expr);
                if (aliasId != AliasInference.DEFAULT_ID) {
                    projectExprAliasBuilder.setAlias(Utils.asAliasId(aliasId));
                }
                projectBuilder.addMappings(projectExprAliasBuilder.build());
            }
            GraphAlgebra.Dedup.Builder dedupBuilder = GraphAlgebra.Dedup.newBuilder();
            for (int i = 0; i < keys.groupKeyCount(); ++i) {
                RelDataTypeField field = fields.get(i);
                RexVariable rexVar =
                        RexGraphVariable.of(
                                field.getIndex(),
                                AliasInference.DEFAULT_COLUMN_ID,
                                field.getName(),
                                field.getType());
                OuterExpression.Variable exprVar =
                        rexVar.accept(new RexToProtoConverter(true, isColumnId, this.rexBuilder))
                                .getOperators(0)
                                .getVar();
                dedupBuilder.addKeys(exprVar);
            }
            GraphAlgebraPhysical.PhysicalOpr.Builder projectOprBuilder =
                    GraphAlgebraPhysical.PhysicalOpr.newBuilder();
            projectOprBuilder.setOpr(
                    GraphAlgebraPhysical.PhysicalOpr.Operator.newBuilder()
                            .setProject(projectBuilder));
            GraphAlgebraPhysical.PhysicalOpr.Builder dedupOprBuilder =
                    GraphAlgebraPhysical.PhysicalOpr.newBuilder();
            dedupOprBuilder.setOpr(
                    GraphAlgebraPhysical.PhysicalOpr.Operator.newBuilder().setDedup(dedupBuilder));
            if (isPartitioned) {
                Map<Integer, Set<GraphNameOrId>> tagColumns =
                        Utils.extractTagColumnsFromRexNodes(keys.getVariables());
                if (preCacheEdgeProps) {
                    Utils.removeEdgeProperties(
                            com.alibaba.graphscope.common.ir.tools.Utils.getOutputType(
                                    aggregate.getInput()),
                            tagColumns);
                }
                lazyPropertyFetching(tagColumns);
            }
            physicalBuilder.addPlan(projectOprBuilder.build());
            physicalBuilder.addPlan(dedupOprBuilder.build());
        } else {
            GraphAlgebraPhysical.PhysicalOpr.Builder oprBuilder =
                    GraphAlgebraPhysical.PhysicalOpr.newBuilder();
            GraphAlgebraPhysical.GroupBy.Builder groupByBuilder =
                    GraphAlgebraPhysical.GroupBy.newBuilder();
            for (int i = 0; i < keys.groupKeyCount(); ++i) {
                RexNode var = keys.getVariables().get(i);
                Preconditions.checkArgument(
                        var instanceof RexGraphVariable,
                        "each group key should be type %s, but is %s",
                        RexGraphVariable.class,
                        var.getClass());
                OuterExpression.Variable exprVar =
                        var.accept(new RexToProtoConverter(true, isColumnId, this.rexBuilder))
                                .getOperators(0)
                                .getVar();
                int aliasId = fields.get(i).getIndex();
                GraphAlgebraPhysical.GroupBy.KeyAlias.Builder keyAliasBuilder =
                        GraphAlgebraPhysical.GroupBy.KeyAlias.newBuilder();
                keyAliasBuilder.setKey(exprVar);
                if (aliasId != AliasInference.DEFAULT_ID) {
                    keyAliasBuilder.setAlias(Utils.asAliasId(aliasId));
                }
                groupByBuilder.addMappings(keyAliasBuilder);
            }
            for (int i = 0; i < groupCalls.size(); ++i) {
                List<RexNode> operands = groupCalls.get(i).getOperands();
                if (operands.isEmpty()) {
                    throw new IllegalArgumentException(
                            "operands in aggregate call should not be empty");
                }

                GraphAlgebraPhysical.GroupBy.AggFunc.Builder aggFnAliasBuilder =
                        GraphAlgebraPhysical.GroupBy.AggFunc.newBuilder();
                for (RexNode operand : operands) {
                    Preconditions.checkArgument(
                            operand instanceof RexGraphVariable,
                            "each expression in aggregate call should be type %s, but is %s",
                            RexGraphVariable.class,
                            operand.getClass());
                    OuterExpression.Variable var =
                            operand.accept(
                                            new RexToProtoConverter(
                                                    true, isColumnId, this.rexBuilder))
                                    .getOperators(0)
                                    .getVar();
                    aggFnAliasBuilder.addVars(var);
                }
                GraphAlgebraPhysical.GroupBy.AggFunc.Aggregate aggOpt =
                        Utils.protoAggOpt(groupCalls.get(i));
                aggFnAliasBuilder.setAggregate(aggOpt);
                int aliasId = fields.get(i + keys.groupKeyCount()).getIndex();
                if (aliasId != AliasInference.DEFAULT_ID) {
                    aggFnAliasBuilder.setAlias(Utils.asAliasId(aliasId));
                }
                groupByBuilder.addFunctions(aggFnAliasBuilder);
            }
            oprBuilder.setOpr(
                    GraphAlgebraPhysical.PhysicalOpr.Operator.newBuilder()
                            .setGroupBy(groupByBuilder));
            oprBuilder.addAllMetaData(
                    Utils.physicalProtoRowType(aggregate.getRowType(), isColumnId));
            if (isPartitioned) {
                List<RexNode> keysAndAggs = Lists.newArrayList();
                keysAndAggs.addAll(keys.getVariables());
                keysAndAggs.addAll(
                        groupCalls.stream()
                                .flatMap(k -> k.getOperands().stream())
                                .collect(Collectors.toList()));
                Map<Integer, Set<GraphNameOrId>> tagColumns =
                        Utils.extractTagColumnsFromRexNodes(keysAndAggs);
                if (preCacheEdgeProps) {
                    Utils.removeEdgeProperties(
                            com.alibaba.graphscope.common.ir.tools.Utils.getOutputType(
                                    aggregate.getInput()),
                            tagColumns);
                }
                lazyPropertyFetching(tagColumns);
            }
            physicalBuilder.addPlan(oprBuilder.build());
        }
        return aggregate;
    }

    @Override
    public RelNode visit(GraphLogicalDedupBy dedupBy) {
        visitChildren(dedupBy);
        GraphAlgebraPhysical.PhysicalOpr.Builder oprBuilder =
                GraphAlgebraPhysical.PhysicalOpr.newBuilder();
        GraphAlgebra.Dedup.Builder dedupBuilder = GraphAlgebra.Dedup.newBuilder();
        for (RexNode expr : dedupBy.getDedupByKeys()) {
            Preconditions.checkArgument(
                    expr instanceof RexGraphVariable,
                    "each expression in dedup by should be type %s, but is %s",
                    RexGraphVariable.class,
                    expr.getClass());
            OuterExpression.Variable var =
                    expr.accept(new RexToProtoConverter(true, isColumnId, this.rexBuilder))
                            .getOperators(0)
                            .getVar();
            dedupBuilder.addKeys(var);
        }
        oprBuilder.setOpr(
                GraphAlgebraPhysical.PhysicalOpr.Operator.newBuilder().setDedup(dedupBuilder));
        oprBuilder.addAllMetaData(Utils.physicalProtoRowType(dedupBy.getRowType(), isColumnId));
        if (isPartitioned) {
            Map<Integer, Set<GraphNameOrId>> tagColumns =
                    Utils.extractTagColumnsFromRexNodes(dedupBy.getDedupByKeys());
            if (preCacheEdgeProps) {
                Utils.removeEdgeProperties(
                        com.alibaba.graphscope.common.ir.tools.Utils.getOutputType(
                                dedupBy.getInput()),
                        tagColumns);
            }
            lazyPropertyFetching(tagColumns);
        }
        physicalBuilder.addPlan(oprBuilder.build());
        return dedupBy;
    }

    @Override
    public RelNode visit(GraphLogicalSort sort) {
        visitChildren(sort);
        GraphAlgebraPhysical.PhysicalOpr.Builder oprBuilder =
                GraphAlgebraPhysical.PhysicalOpr.newBuilder();
        List<RelFieldCollation> collations = sort.getCollation().getFieldCollations();
        if (!collations.isEmpty()) {
            GraphAlgebra.OrderBy.Builder orderByBuilder = GraphAlgebra.OrderBy.newBuilder();
            for (int i = 0; i < collations.size(); ++i) {
                GraphAlgebra.OrderBy.OrderingPair.Builder orderingPairBuilder =
                        GraphAlgebra.OrderBy.OrderingPair.newBuilder();
                RexGraphVariable expr = ((GraphFieldCollation) collations.get(i)).getVariable();
                OuterExpression.Variable var =
                        expr.accept(new RexToProtoConverter(true, isColumnId, this.rexBuilder))
                                .getOperators(0)
                                .getVar();

                orderingPairBuilder.setKey(var);
                orderingPairBuilder.setOrder(Utils.protoOrderOpt(collations.get(i).getDirection()));
                orderByBuilder.addPairs(orderingPairBuilder.build());
            }
            if (sort.offset != null || sort.fetch != null) {
                orderByBuilder.setLimit(buildRange(sort.offset, sort.fetch));
            }
            oprBuilder.setOpr(
                    GraphAlgebraPhysical.PhysicalOpr.Operator.newBuilder()
                            .setOrderBy(orderByBuilder));
            if (isPartitioned) {
                Map<Integer, Set<GraphNameOrId>> tagColumns =
                        Utils.extractTagColumnsFromRexNodes(
                                collations.stream()
                                        .map(k -> ((GraphFieldCollation) k).getVariable())
                                        .collect(Collectors.toList()));
                if (preCacheEdgeProps) {
                    Utils.removeEdgeProperties(
                            com.alibaba.graphscope.common.ir.tools.Utils.getOutputType(
                                    sort.getInput()),
                            tagColumns);
                }
                lazyPropertyFetching(tagColumns);
            }
        } else {
            GraphAlgebra.Limit.Builder limitBuilder = GraphAlgebra.Limit.newBuilder();
            limitBuilder.setRange(buildRange(sort.offset, sort.fetch));
            oprBuilder.setOpr(
                    GraphAlgebraPhysical.PhysicalOpr.Operator.newBuilder().setLimit(limitBuilder));
        }
        physicalBuilder.addPlan(oprBuilder.build());
        return sort;
    }

    @Override
    public RelNode visit(LogicalJoin join) {
        GraphAlgebraPhysical.PhysicalOpr.Builder oprBuilder =
                GraphAlgebraPhysical.PhysicalOpr.newBuilder();
        GraphAlgebraPhysical.Join.Builder joinBuilder = GraphAlgebraPhysical.Join.newBuilder();
        joinBuilder.setJoinKind(Utils.protoJoinKind(join.getJoinType()));
        List<RexNode> conditions = RelOptUtil.conjunctions(join.getCondition());
        Preconditions.checkArgument(
                !conditions.isEmpty(), "join condition in physical should not be empty");
        List<RexNode> leftKeys = Lists.newArrayList();
        List<RexNode> rightKeys = Lists.newArrayList();
        for (RexNode condition : conditions) {
            List<RexGraphVariable> leftRightVars = getLeftRightVariables(condition);
            Preconditions.checkArgument(
                    leftRightVars.size() == 2,
                    "join condition in physical should have two operands, while it is %s",
                    leftRightVars.size());
            leftKeys.add(leftRightVars.get(0));
            rightKeys.add(leftRightVars.get(1));
            OuterExpression.Variable leftVar =
                    leftRightVars
                            .get(0)
                            .accept(new RexToProtoConverter(true, isColumnId, this.rexBuilder))
                            .getOperators(0)
                            .getVar();
            OuterExpression.Variable rightVar =
                    leftRightVars
                            .get(1)
                            .accept(new RexToProtoConverter(true, isColumnId, this.rexBuilder))
                            .getOperators(0)
                            .getVar();
            joinBuilder.addLeftKeys(leftVar);
            joinBuilder.addRightKeys(rightVar);
        }

        GraphAlgebraPhysical.PhysicalPlan.Builder leftPlanBuilder =
                GraphAlgebraPhysical.PhysicalPlan.newBuilder();
        GraphAlgebraPhysical.PhysicalPlan.Builder rightPlanBuilder =
                GraphAlgebraPhysical.PhysicalPlan.newBuilder();

        RelNode left = join.getLeft();
        left.accept(new GraphRelToProtoConverter(isColumnId, graphConfig, leftPlanBuilder));
        RelNode right = join.getRight();
        right.accept(new GraphRelToProtoConverter(isColumnId, graphConfig, rightPlanBuilder));
        if (isPartitioned) {

            Map<Integer, Set<GraphNameOrId>> leftTagColumns =
                    Utils.extractTagColumnsFromRexNodes(leftKeys);
            Map<Integer, Set<GraphNameOrId>> rightTagColumns =
                    Utils.extractTagColumnsFromRexNodes(rightKeys);
            if (preCacheEdgeProps) {
                Utils.removeEdgeProperties(
                        com.alibaba.graphscope.common.ir.tools.Utils.getOutputType(join.getLeft()),
                        leftTagColumns);
                Utils.removeEdgeProperties(
                        com.alibaba.graphscope.common.ir.tools.Utils.getOutputType(join.getRight()),
                        rightTagColumns);
            }
            lazyPropertyFetching(leftPlanBuilder, leftTagColumns, false);
            lazyPropertyFetching(rightPlanBuilder, rightTagColumns, false);
        }
        joinBuilder.setLeftPlan(leftPlanBuilder);
        joinBuilder.setRightPlan(rightPlanBuilder);
        oprBuilder.setOpr(
                GraphAlgebraPhysical.PhysicalOpr.Operator.newBuilder().setJoin(joinBuilder));
        physicalBuilder.addPlan(oprBuilder.build());
        return join;
    }

    private List<RexGraphVariable> getLeftRightVariables(RexNode condition) {
        List<RexGraphVariable> vars = Lists.newArrayList();
        if (condition instanceof RexCall) {
            RexCall call = (RexCall) condition;
            if (call.getOperator().getKind() == SqlKind.EQUALS) {
                RexNode left = call.getOperands().get(0);
                RexNode right = call.getOperands().get(1);
                if (left instanceof RexGraphVariable && right instanceof RexGraphVariable) {
                    vars.add((RexGraphVariable) left);
                    vars.add((RexGraphVariable) right);
                }
            }
        }
        return vars;
    }

    private GraphAlgebraPhysical.EdgeExpand.Builder buildEdgeExpand(
            GraphLogicalExpand expand, GraphOpt.PhysicalExpandOpt opt, int aliasId) {
        GraphAlgebraPhysical.EdgeExpand.Builder expandBuilder =
                GraphAlgebraPhysical.EdgeExpand.newBuilder();
        expandBuilder.setDirection(Utils.protoExpandDirOpt(expand.getOpt()));
        GraphAlgebra.QueryParams.Builder queryParamsBuilder = buildQueryParams(expand);
        if (preCacheEdgeProps && GraphOpt.PhysicalExpandOpt.EDGE.equals(opt)) {
            addQueryColumns(
                    queryParamsBuilder,
                    Utils.extractColumnsFromRelDataType(expand.getRowType(), isColumnId));
        }
        expandBuilder.setParams(queryParamsBuilder);
        if (aliasId != AliasInference.DEFAULT_ID) {
            expandBuilder.setAlias(Utils.asAliasId(aliasId));
        }
        if (expand.getStartAlias().getAliasId() != AliasInference.DEFAULT_ID) {
            expandBuilder.setVTag(Utils.asAliasId(expand.getStartAlias().getAliasId()));
        }
        expandBuilder.setExpandOpt(Utils.protoExpandOpt(opt));
        return expandBuilder;
    }

    private GraphAlgebraPhysical.EdgeExpand.Builder buildEdgeExpand(GraphLogicalExpand expand) {
        return buildEdgeExpand(expand, GraphOpt.PhysicalExpandOpt.EDGE, expand.getAliasId());
    }

    private GraphAlgebraPhysical.EdgeExpand.Builder buildEdgeExpand(
            GraphPhysicalExpand physicalExpand) {
        return buildEdgeExpand(
                physicalExpand.getFusedExpand(),
                physicalExpand.getPhysicalOpt(),
                physicalExpand.getAliasId());
    }

    private GraphAlgebraPhysical.GetV.Builder buildVertex(
            GraphLogicalGetV getV, GraphOpt.PhysicalGetVOpt opt) {
        GraphAlgebraPhysical.GetV.Builder vertexBuilder = GraphAlgebraPhysical.GetV.newBuilder();
        vertexBuilder.setOpt(Utils.protoGetVOpt(opt));
        vertexBuilder.setParams(buildQueryParams(getV));
        if (getV.getAliasId() != AliasInference.DEFAULT_ID) {
            vertexBuilder.setAlias(Utils.asAliasId(getV.getAliasId()));
        }
        if (getV.getStartAlias().getAliasId() != AliasInference.DEFAULT_ID) {
            vertexBuilder.setTag(Utils.asAliasId(getV.getStartAlias().getAliasId()));
        }
        return vertexBuilder;
    }

    private GraphAlgebraPhysical.GetV.Builder buildGetV(GraphLogicalGetV getV) {
        return buildVertex(getV, PhysicalGetVOpt.valueOf(getV.getOpt().name()));
    }

    private GraphAlgebraPhysical.GetV.Builder buildAuxilia(GraphPhysicalGetV getV) {
        return buildVertex(getV.getFusedGetV(), PhysicalGetVOpt.ITSELF);
    }

    private GraphAlgebra.Range buildRange(RexNode offset, RexNode fetch) {
        if (offset != null && !(offset instanceof RexLiteral)
                || fetch != null && !(fetch instanceof RexLiteral)) {
            throw new IllegalArgumentException(
                    "can not get INTEGER hops from types instead of RexLiteral");
        }
        GraphAlgebra.Range.Builder rangeBuilder = GraphAlgebra.Range.newBuilder();
        int lower = (offset == null) ? 0 : ((Number) ((RexLiteral) offset).getValue()).intValue();
        rangeBuilder.setLower(lower);
        rangeBuilder.setUpper(
                fetch == null
                        ? Integer.MAX_VALUE
                        : lower + ((Number) ((RexLiteral) fetch).getValue()).intValue());
        return rangeBuilder.build();
    }

    private GraphAlgebra.IndexPredicate buildIndexPredicates(RexNode uniqueKeyFilters) {
        GraphAlgebra.IndexPredicate indexPredicate =
                uniqueKeyFilters.accept(
                        new RexToIndexPbConverter(true, this.isColumnId, this.rexBuilder));
        return indexPredicate;
    }

    private GraphLabelType getGraphLabels(AbstractBindableTableScan tableScan) {
        List<RelDataTypeField> fields = tableScan.getRowType().getFieldList();
        Preconditions.checkArgument(
                !fields.isEmpty() && fields.get(0).getType() instanceof GraphSchemaType,
                "data type of graph operators should be %s ",
                GraphSchemaType.class);
        GraphSchemaType schemaType = (GraphSchemaType) fields.get(0).getType();
        return schemaType.getLabelType();
    }

    private GraphAlgebra.QueryParams.Builder defaultQueryParams() {
        GraphAlgebra.QueryParams.Builder paramsBuilder = GraphAlgebra.QueryParams.newBuilder();
        // TODO: currently no sample rate fused into tableScan, so directly set 1.0 as default.
        paramsBuilder.setSampleRatio(1.0);
        return paramsBuilder;
    }

    private void addQueryTables(
            GraphAlgebra.QueryParams.Builder paramsBuilder, List<GraphLabelType.Entry> labels) {
        Set<Integer> uniqueLabelIds =
                labels.stream().map(k -> k.getLabelId()).collect(Collectors.toSet());
        uniqueLabelIds.forEach(
                k -> {
                    paramsBuilder.addTables(Utils.asNameOrId(k));
                });
    }

    private void addQueryFilters(
            GraphAlgebra.QueryParams.Builder paramsBuilder,
            @Nullable ImmutableList<RexNode> filters) {
        if (ObjectUtils.isNotEmpty(filters)) {
            OuterExpression.Expression expression =
                    filters.get(0)
                            .accept(new RexToProtoConverter(true, isColumnId, this.rexBuilder));
            paramsBuilder.setPredicate(expression);
        }
    }

    private void addQueryColumns(
            GraphAlgebra.QueryParams.Builder paramsBuilder, Set<GraphNameOrId> columns) {
        for (GraphNameOrId column : columns) {
            paramsBuilder.addColumns(Utils.protoNameOrId(column));
        }
    }

    private GraphAlgebra.QueryParams.Builder buildQueryParams(AbstractBindableTableScan tableScan) {
        GraphAlgebra.QueryParams.Builder paramsBuilder = defaultQueryParams();
        addQueryTables(paramsBuilder, getGraphLabels(tableScan).getLabelsEntry());
        addQueryFilters(paramsBuilder, tableScan.getFilters());
        return paramsBuilder;
    }

    private void addRepartitionToAnother(int repartitionKey) {
        addRepartitionToAnother(physicalBuilder, repartitionKey);
    }

    private void addRepartitionToAnother(
            GraphAlgebraPhysical.PhysicalPlan.Builder physicalBuilder, int repartitionKey) {
        GraphAlgebraPhysical.PhysicalOpr.Builder repartitionOprBuilder =
                GraphAlgebraPhysical.PhysicalOpr.newBuilder();
        GraphAlgebraPhysical.Repartition repartition =
                Utils.protoShuffleRepartition(repartitionKey);
        repartitionOprBuilder.setOpr(
                GraphAlgebraPhysical.PhysicalOpr.Operator.newBuilder().setRepartition(repartition));
        physicalBuilder.addPlan(repartitionOprBuilder.build());
    }

    private void addAuxilia(
            GraphAlgebraPhysical.PhysicalPlan.Builder physicalBuilder,
            Integer tag,
            Set<GraphNameOrId> columns) {
        GraphAlgebraPhysical.PhysicalOpr.Builder auxiliaOprBuilder =
                GraphAlgebraPhysical.PhysicalOpr.newBuilder();
        GraphAlgebraPhysical.GetV.Builder vertexBuilder = GraphAlgebraPhysical.GetV.newBuilder();
        vertexBuilder.setOpt(Utils.protoGetVOpt(PhysicalGetVOpt.ITSELF));
        GraphAlgebra.QueryParams.Builder paramsBuilder = defaultQueryParams();
        addQueryColumns(paramsBuilder, columns);
        vertexBuilder.setParams(paramsBuilder);
        if (tag != AliasInference.DEFAULT_ID) {
            vertexBuilder.setTag(Utils.asAliasId(tag));
            vertexBuilder.setAlias(Utils.asAliasId(tag));
        }
        auxiliaOprBuilder.setOpr(
                GraphAlgebraPhysical.PhysicalOpr.Operator.newBuilder().setVertex(vertexBuilder));
        physicalBuilder.addPlan(auxiliaOprBuilder.build());
    }

    private void lazyPropertyFetching(Map<Integer, Set<GraphNameOrId>> columns) {
        // by default, we cache the result of the tagColumns, i.e., the optimizedNoCaching is false
        lazyPropertyFetching(columns, false);
    }

    private void lazyPropertyFetching(
            Map<Integer, Set<GraphNameOrId>> tagColumns, boolean optimizedNoCaching) {
        lazyPropertyFetching(physicalBuilder, tagColumns, optimizedNoCaching);
    }

    private void lazyPropertyFetching(
            GraphAlgebraPhysical.PhysicalPlan.Builder physicalBuilder,
            Map<Integer, Set<GraphNameOrId>> tagColumns,
            boolean optimizedNoCaching) {
        if (tagColumns.isEmpty()) {
            return;
        } else if (tagColumns.size() == 1 && optimizedNoCaching) {
            addRepartitionToAnother(physicalBuilder, tagColumns.keySet().iterator().next());
        } else {
            for (Map.Entry<Integer, Set<GraphNameOrId>> tagColumn : tagColumns.entrySet()) {
                addRepartitionToAnother(physicalBuilder, tagColumn.getKey());
                addAuxilia(physicalBuilder, tagColumn.getKey(), tagColumn.getValue());
            }
        }
    }

    @Override
    public RelNode visit(GraphLogicalSingleMatch match) {
        throw new UnsupportedOperationException("Unimplemented method 'visit'");
    }

    @Override
    public RelNode visit(GraphLogicalMultiMatch match) {
        throw new UnsupportedOperationException("Unimplemented method 'visit'");
    }
}
