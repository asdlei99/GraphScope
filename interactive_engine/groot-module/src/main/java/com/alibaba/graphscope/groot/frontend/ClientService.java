/**
 * Copyright 2020 Alibaba Group Holding Limited.
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.graphscope.groot.frontend;

import com.alibaba.graphscope.groot.CompletionCallback;
import com.alibaba.graphscope.groot.SnapshotCache;
import com.alibaba.graphscope.groot.common.schema.api.*;
import com.alibaba.graphscope.groot.common.schema.mapper.GraphSchemaMapper;
import com.alibaba.graphscope.groot.common.schema.wrapper.*;
import com.alibaba.graphscope.groot.common.util.DataLoadTarget;
import com.alibaba.graphscope.groot.meta.MetaService;
import com.alibaba.graphscope.groot.metrics.MetricsAggregator;
import com.alibaba.graphscope.groot.schema.request.AddEdgeKindRequest;
import com.alibaba.graphscope.groot.schema.request.CreateEdgeTypeRequest;
import com.alibaba.graphscope.groot.schema.request.CreateVertexTypeRequest;
import com.alibaba.graphscope.groot.schema.request.DdlRequestBatch;
import com.alibaba.graphscope.groot.schema.request.DropEdgeTypeRequest;
import com.alibaba.graphscope.groot.schema.request.DropVertexTypeRequest;
import com.alibaba.graphscope.groot.schema.request.RemoveEdgeKindRequest;
import com.alibaba.graphscope.proto.groot.*;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class ClientService extends ClientGrpc.ClientImplBase {
    private static final Logger logger = LoggerFactory.getLogger(ClientService.class);

    private final SnapshotCache snapshotCache;
    private final MetricsAggregator metricsAggregator;
    private final StoreIngestClients storeIngestor;
    private final MetaService metaService;
    private final BatchDdlClient batchDdlClient;

    public ClientService(
            SnapshotCache snapshotCache,
            MetricsAggregator metricsAggregator,
            StoreIngestClients storeIngestor,
            MetaService metaService,
            BatchDdlClient batchDdlClient) {
        this.snapshotCache = snapshotCache;
        this.metricsAggregator = metricsAggregator;
        this.storeIngestor = storeIngestor;
        this.metaService = metaService;
        this.batchDdlClient = batchDdlClient;
    }

    @Override
    public void getPartitionNum(
            GetPartitionNumRequest request,
            StreamObserver<GetPartitionNumResponse> responseObserver) {
        int partitionCount = metaService.getPartitionCount();
        responseObserver.onNext(
                GetPartitionNumResponse.newBuilder().setPartitionNum(partitionCount).build());
        responseObserver.onCompleted();
    }

    @Override
    public void prepareDataLoad(
            PrepareDataLoadRequest request,
            StreamObserver<PrepareDataLoadResponse> responseObserver) {
        DdlRequestBatch.Builder builder = DdlRequestBatch.newBuilder();
        for (DataLoadTargetPb dataLoadTargetPb : request.getDataLoadTargetsList()) {
            DataLoadTarget dataLoadTarget = DataLoadTarget.parseProto(dataLoadTargetPb);
            builder.addDdlRequest(
                    new com.alibaba.graphscope.groot.schema.request.PrepareDataLoadRequest(
                            dataLoadTarget));
        }
        DdlRequestBatch batch = builder.build();
        try {
            long snapshotId = this.batchDdlClient.batchDdl(batch);
            this.snapshotCache.addListener(
                    snapshotId,
                    () -> {
                        responseObserver.onNext(
                                PrepareDataLoadResponse.newBuilder()
                                        .setGraphDef(
                                                this.snapshotCache
                                                        .getSnapshotWithSchema()
                                                        .getGraphDef()
                                                        .toProto())
                                        .build());
                        responseObserver.onCompleted();
                    });
        } catch (Exception e) {
            logger.error("prepare data load failed", e);
            responseObserver.onError(
                    Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void commitDataLoad(
            CommitDataLoadRequest request,
            StreamObserver<CommitDataLoadResponse> responseObserver) {
        logger.info("Committing data load");
        DdlRequestBatch.Builder builder = DdlRequestBatch.newBuilder();
        Map<Long, DataLoadTargetPb> tableToTarget = request.getTableToTargetMap();
        String path = request.getPath();
        tableToTarget.forEach(
                (tableId, targetPb) -> {
                    DataLoadTarget dataLoadTarget = DataLoadTarget.parseProto(targetPb);
                    builder.addDdlRequest(
                            new com.alibaba.graphscope.groot.schema.request.CommitDataLoadRequest(
                                    dataLoadTarget, tableId, path));
                });
        DdlRequestBatch batch = builder.build();
        try {
            long snapshotId = this.batchDdlClient.batchDdl(batch);
            this.snapshotCache.addListener(
                    snapshotId,
                    () -> {
                        responseObserver.onNext(CommitDataLoadResponse.newBuilder().build());
                        responseObserver.onCompleted();
                    });
        } catch (Exception e) {
            logger.error("commit data load failed", e);
            responseObserver.onError(
                    Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void loadJsonSchema(
            LoadJsonSchemaRequest request,
            StreamObserver<LoadJsonSchemaResponse> responseObserver) {
        try {
            String schemaJson = request.getSchemaJson();
            GraphSchema graphSchema = GraphSchemaMapper.parseFromJson(schemaJson).toGraphSchema();
            DdlRequestBatch.Builder ddlBatchBuilder = DdlRequestBatch.newBuilder();
            for (GraphVertex graphVertex : graphSchema.getVertexList()) {
                String label = graphVertex.getLabel();
                List<String> primaryKeyList =
                        graphVertex.getPrimaryKeyList().stream()
                                .map(GraphProperty::getName)
                                .collect(Collectors.toList());
                List<GraphProperty> propertyList = graphVertex.getPropertyList();
                TypeDef.Builder typeDefBuilder = TypeDef.newBuilder();
                typeDefBuilder.setLabel(label);
                typeDefBuilder.setTypeEnum(TypeEnum.VERTEX);
                Set<String> primaryKeys = new HashSet<>(primaryKeyList);
                for (GraphProperty graphProperty : propertyList) {
                    PropertyDef.Builder propertyBuilder = PropertyDef.newBuilder();
                    String propertyName = graphProperty.getName();
                    DataType dataType = graphProperty.getDataType();
                    propertyBuilder
                            .setName(propertyName)
                            .setDataType(dataType)
                            .setComment(graphProperty.getComment());
                    if (primaryKeys.contains(propertyName)) {
                        propertyBuilder.setPk(true);
                    }
                    if (graphProperty.hasDefaultValue()) {
                        Object defaultValue = graphProperty.getDefaultValue();
                        propertyBuilder.setDefaultValue(new PropertyValue(dataType, defaultValue));
                    }
                    typeDefBuilder.addPropertyDef(propertyBuilder.build());
                }
                CreateVertexTypeRequest ddlRequest =
                        new CreateVertexTypeRequest(typeDefBuilder.build());
                ddlBatchBuilder.addDdlRequest(ddlRequest);
            }
            for (GraphEdge graphEdge : graphSchema.getEdgeList()) {
                String label = graphEdge.getLabel();
                List<GraphProperty> propertyList = graphEdge.getPropertyList();
                List<EdgeRelation> relationList = graphEdge.getRelationList();

                TypeDef.Builder typeDefBuilder = TypeDef.newBuilder();
                typeDefBuilder.setLabel(label);
                typeDefBuilder.setTypeEnum(TypeEnum.EDGE);
                for (GraphProperty graphProperty : propertyList) {
                    PropertyDef.Builder propertyBuilder = PropertyDef.newBuilder();
                    String propertyName = graphProperty.getName();
                    DataType dataType = graphProperty.getDataType();
                    propertyBuilder
                            .setName(propertyName)
                            .setDataType(dataType)
                            .setComment(graphProperty.getComment());

                    if (graphProperty.hasDefaultValue()) {
                        Object defaultValue = graphProperty.getDefaultValue();
                        propertyBuilder.setDefaultValue(new PropertyValue(dataType, defaultValue));
                    }
                    typeDefBuilder.addPropertyDef(propertyBuilder.build());
                }
                CreateEdgeTypeRequest createEdgeTypeRequest =
                        new CreateEdgeTypeRequest(typeDefBuilder.build());
                ddlBatchBuilder.addDdlRequest(createEdgeTypeRequest);
                for (EdgeRelation edgeRelation : relationList) {
                    AddEdgeKindRequest addEdgeKindRequest =
                            new AddEdgeKindRequest(
                                    EdgeKind.newBuilder()
                                            .setEdgeLabel(label)
                                            .setSrcVertexLabel(edgeRelation.getSource().getLabel())
                                            .setDstVertexLabel(edgeRelation.getTarget().getLabel())
                                            .build());
                    ddlBatchBuilder.addDdlRequest(addEdgeKindRequest);
                }
            }
            long snapshotId = this.batchDdlClient.batchDdl(ddlBatchBuilder.build());
            this.snapshotCache.addListener(
                    snapshotId,
                    () -> {
                        responseObserver.onNext(
                                LoadJsonSchemaResponse.newBuilder()
                                        .setGraphDef(
                                                this.snapshotCache
                                                        .getSnapshotWithSchema()
                                                        .getGraphDef()
                                                        .toProto())
                                        .build());
                        responseObserver.onCompleted();
                    });
        } catch (Exception e) {
            logger.error("load json schema failed", e);
            responseObserver.onError(
                    Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void dropSchema(
            DropSchemaRequest request, StreamObserver<DropSchemaResponse> responseObserver) {
        try {
            DdlRequestBatch.Builder ddlBatchBuilder = DdlRequestBatch.newBuilder();
            GraphDef graphDef = this.snapshotCache.getSnapshotWithSchema().getGraphDef();
            for (GraphEdge graphEdge : graphDef.getEdgeList()) {
                String label = graphEdge.getLabel();
                for (EdgeRelation relation : graphEdge.getRelationList()) {
                    String sourceLabel = relation.getSource().getLabel();
                    String destLabel = relation.getTarget().getLabel();
                    EdgeKind edgeKind =
                            EdgeKind.newBuilder()
                                    .setEdgeLabel(label)
                                    .setSrcVertexLabel(sourceLabel)
                                    .setDstVertexLabel(destLabel)
                                    .build();
                    RemoveEdgeKindRequest removeEdgeKindRequest =
                            new RemoveEdgeKindRequest(edgeKind);
                    ddlBatchBuilder.addDdlRequest(removeEdgeKindRequest);
                }
                DropEdgeTypeRequest dropEdgeTypeRequest = new DropEdgeTypeRequest(label);
                ddlBatchBuilder.addDdlRequest(dropEdgeTypeRequest);
            }
            for (GraphVertex graphVertex : graphDef.getVertexList()) {
                DropVertexTypeRequest dropVertexTypeRequest =
                        new DropVertexTypeRequest(graphVertex.getLabel());
                ddlBatchBuilder.addDdlRequest(dropVertexTypeRequest);
            }
            long snapshotId = this.batchDdlClient.batchDdl(ddlBatchBuilder.build());
            this.snapshotCache.addListener(
                    snapshotId,
                    () -> {
                        responseObserver.onNext(
                                DropSchemaResponse.newBuilder()
                                        .setGraphDef(
                                                this.snapshotCache
                                                        .getSnapshotWithSchema()
                                                        .getGraphDef()
                                                        .toProto())
                                        .build());
                        responseObserver.onCompleted();
                    });
        } catch (Exception e) {
            logger.error("drop schema commit failed", e);
            responseObserver.onError(
                    Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getMetrics(
            GetMetricsRequest request, StreamObserver<GetMetricsResponse> responseObserver) {
        String roleNames = request.getRoleNames();
        this.metricsAggregator.aggregateMetricsJson(
                roleNames,
                new CompletionCallback<String>() {
                    @Override
                    public void onCompleted(String res) {
                        responseObserver.onNext(
                                GetMetricsResponse.newBuilder().setMetricsJson(res).build());
                        responseObserver.onCompleted();
                    }

                    @Override
                    public void onError(Throwable t) {
                        logger.error("get metrics failed", t);
                        responseObserver.onError(t);
                    }
                });
    }

    @Override
    public void getSchema(GetSchemaRequest request, StreamObserver<GetSchemaResponse> observer) {
        GraphDef graphDef = this.snapshotCache.getSnapshotWithSchema().getGraphDef();
        observer.onNext(GetSchemaResponse.newBuilder().setGraphDef(graphDef.toProto()).build());
        observer.onCompleted();
    }

    @Override
    public void getLoggerInfo(
            GetLoggerInfoRequest request, StreamObserver<GetLoggerInfoResponse> observer) {
        GetLoggerInfoResponse response =
                GetLoggerInfoResponse.newBuilder()
                        .setLoggerServers(metaService.getLoggerServers())
                        .setLoggerTopic(metaService.getLoggerTopicName())
                        .setLoggerQueueCount(metaService.getQueueCount())
                        .build();
        observer.onNext(response);
        observer.onCompleted();
    }

    @Override
    public void ingestData(
            IngestDataRequest request, StreamObserver<IngestDataResponse> responseObserver) {
        String dataPath = request.getDataPath();
        Map<String, String> config = request.getConfigMap();
        logger.info("ingestData. path [" + dataPath + "]");
        int storeCount = this.metaService.getStoreCount();
        AtomicInteger counter = new AtomicInteger(storeCount);
        AtomicBoolean finished = new AtomicBoolean(false);
        for (int i = 0; i < storeCount; i++) {
            logger.info("Store [" + i + "] started to ingest...");
            this.storeIngestor.ingest(
                    i,
                    dataPath,
                    config,
                    new CompletionCallback<Void>() {
                        @Override
                        public void onCompleted(Void res) {
                            if (!finished.get() && counter.decrementAndGet() == 0) {
                                finish(null);
                            }
                        }

                        @Override
                        public void onError(Throwable t) {
                            logger.error("failed ingest", t);
                            finish(t);
                        }

                        private void finish(Throwable t) {
                            if (finished.getAndSet(true)) {
                                return;
                            }
                            logger.info("ingest finished. Error [" + t + "]");
                            if (t != null) {
                                responseObserver.onError(t);
                            } else {
                                responseObserver.onNext(IngestDataResponse.newBuilder().build());
                                responseObserver.onCompleted();
                            }
                        }
                    });
        }
    }

    @Override
    public void clearIngest(
            ClearIngestRequest request, StreamObserver<ClearIngestResponse> responseObserver) {
        logger.info("clear ingest data");
        int storeCount = this.metaService.getStoreCount();
        AtomicInteger counter = new AtomicInteger(storeCount);
        AtomicBoolean finished = new AtomicBoolean(false);
        String dataPath = request.getDataPath();
        for (int i = 0; i < storeCount; i++) {
            this.storeIngestor.clearIngest(
                    i,
                    dataPath,
                    new CompletionCallback<Void>() {
                        @Override
                        public void onCompleted(Void res) {
                            if (!finished.get() && counter.decrementAndGet() == 0) {
                                finish(null);
                            }
                        }

                        @Override
                        public void onError(Throwable t) {
                            logger.error("failed clear ingest", t);
                            finish(t);
                        }

                        private void finish(Throwable t) {
                            if (finished.getAndSet(true)) {
                                return;
                            }
                            logger.info("ingest finished. Error [" + t + "]");
                            if (t != null) {
                                responseObserver.onError(t);
                            } else {
                                responseObserver.onNext(ClearIngestResponse.newBuilder().build());
                                responseObserver.onCompleted();
                            }
                        }
                    });
        }
    }

    @Override
    public void reopenSecondary(
            ReopenSecondaryRequest request,
            StreamObserver<ReopenSecondaryResponse> responseObserver) {
        logger.info("Reopen secondary");
        int storeCount = this.metaService.getStoreCount();
        AtomicInteger counter = new AtomicInteger(storeCount);
        AtomicBoolean finished = new AtomicBoolean(false);
        for (int i = 0; i < storeCount; i++) {
            this.storeIngestor.reopenSecondary(
                    i,
                    new CompletionCallback<Void>() {
                        @Override
                        public void onCompleted(Void res) {
                            if (!finished.get() && counter.decrementAndGet() == 0) {
                                finish(null);
                            }
                        }

                        @Override
                        public void onError(Throwable t) {
                            logger.error("failed reopen secondary", t);
                            finish(t);
                        }

                        private void finish(Throwable t) {
                            if (finished.getAndSet(true)) {
                                return;
                            }
                            logger.info("reopen secondary finished. Error [" + t + "]");
                            if (t != null) {
                                responseObserver.onError(t);
                            } else {
                                ReopenSecondaryResponse res =
                                        ReopenSecondaryResponse.newBuilder()
                                                .setSuccess(true)
                                                .build();
                                responseObserver.onNext(res);
                                responseObserver.onCompleted();
                            }
                        }
                    });
        }
    }

    @Override
    public void compactDB(
            CompactDBRequest request, StreamObserver<CompactDBResponse> responseObserver) {
        logger.info("Compact DB");
        int storeCount = this.metaService.getStoreCount();
        AtomicInteger counter = new AtomicInteger(storeCount);
        AtomicBoolean finished = new AtomicBoolean(false);
        for (int i = 0; i < storeCount; i++) {
            this.storeIngestor.compactDB(
                    i,
                    new CompletionCallback<Void>() {
                        @Override
                        public void onCompleted(Void res) {
                            if (!finished.get() && counter.decrementAndGet() == 0) {
                                finish(null);
                            }
                        }

                        @Override
                        public void onError(Throwable t) {
                            logger.error("failed compact", t);
                            finish(t);
                        }

                        private void finish(Throwable t) {
                            if (finished.getAndSet(true)) {
                                return;
                            }
                            logger.info("compact finished. Error [" + t + "]");
                            if (t != null) {
                                responseObserver.onError(t);
                            } else {
                                CompactDBResponse res =
                                        CompactDBResponse.newBuilder().setSuccess(true).build();
                                responseObserver.onNext(res);
                                responseObserver.onCompleted();
                            }
                        }
                    });
        }
    }
}
