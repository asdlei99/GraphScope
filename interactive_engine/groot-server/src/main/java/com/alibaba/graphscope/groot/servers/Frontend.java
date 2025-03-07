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
package com.alibaba.graphscope.groot.servers;

import com.alibaba.graphscope.groot.CuratorUtils;
import com.alibaba.graphscope.groot.SnapshotCache;
import com.alibaba.graphscope.groot.common.RoleType;
import com.alibaba.graphscope.groot.common.config.CommonConfig;
import com.alibaba.graphscope.groot.common.config.Configs;
import com.alibaba.graphscope.groot.common.config.FrontendConfig;
import com.alibaba.graphscope.groot.common.exception.GrootException;
import com.alibaba.graphscope.groot.common.util.RpcUtils;
import com.alibaba.graphscope.groot.discovery.FileDiscovery;
import com.alibaba.graphscope.groot.discovery.LocalNodeProvider;
import com.alibaba.graphscope.groot.discovery.NodeDiscovery;
import com.alibaba.graphscope.groot.discovery.ZkDiscovery;
import com.alibaba.graphscope.groot.frontend.*;
import com.alibaba.graphscope.groot.frontend.write.DefaultEdgeIdGenerator;
import com.alibaba.graphscope.groot.frontend.write.EdgeIdGenerator;
import com.alibaba.graphscope.groot.frontend.write.GraphWriter;
import com.alibaba.graphscope.groot.frontend.write.KafkaAppender;
import com.alibaba.graphscope.groot.meta.DefaultMetaService;
import com.alibaba.graphscope.groot.meta.MetaService;
import com.alibaba.graphscope.groot.metrics.MetricsAggregator;
import com.alibaba.graphscope.groot.metrics.MetricsCollectClient;
import com.alibaba.graphscope.groot.metrics.MetricsCollectService;
import com.alibaba.graphscope.groot.metrics.MetricsCollector;
import com.alibaba.graphscope.groot.rpc.AuthorizationServerInterceptor;
import com.alibaba.graphscope.groot.rpc.ChannelManager;
import com.alibaba.graphscope.groot.rpc.GrootNameResolverFactory;
import com.alibaba.graphscope.groot.rpc.RoleClients;
import com.alibaba.graphscope.groot.rpc.RpcServer;
import com.alibaba.graphscope.groot.schema.ddl.DdlExecutors;
import com.alibaba.graphscope.groot.servers.ir.IrServiceProducer;
import com.alibaba.graphscope.groot.wal.LogService;
import com.alibaba.graphscope.groot.wal.LogServiceFactory;

import io.grpc.BindableService;
import io.grpc.NameResolver;
import io.grpc.netty.NettyServerBuilder;

import org.apache.curator.framework.CuratorFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;

public class Frontend extends NodeBase {
    private static final Logger logger = LoggerFactory.getLogger(Frontend.class);

    private CuratorFramework curator;
    private NodeDiscovery discovery;
    private ChannelManager channelManager;
    private MetaService metaService;
    private RpcServer rpcServer;
    private RpcServer serviceServer;
    private ClientService clientService;
    private AbstractService graphService;

    private SnapshotCache snapshotCache;

    private GraphWriter graphWriter;

    public Frontend(Configs configs) {
        super(configs);
        configs = reConfig(configs);
        LocalNodeProvider localNodeProvider = new LocalNodeProvider(configs);
        if (CommonConfig.DISCOVERY_MODE.get(configs).equalsIgnoreCase("file")) {
            this.discovery = new FileDiscovery(configs);
        } else {
            this.curator = CuratorUtils.makeCurator(configs);
            this.discovery = new ZkDiscovery(configs, localNodeProvider, this.curator);
        }
        NameResolver.Factory nameResolverFactory = new GrootNameResolverFactory(this.discovery);
        this.channelManager = new ChannelManager(configs, nameResolverFactory);

        snapshotCache = new SnapshotCache();

        RoleClients<MetricsCollectClient> frontendMetricsCollectClients =
                new RoleClients<>(
                        this.channelManager, RoleType.FRONTEND, MetricsCollectClient::new);
        RoleClients<MetricsCollectClient> storeMetricsCollectClients =
                new RoleClients<>(this.channelManager, RoleType.STORE, MetricsCollectClient::new);
        MetricsAggregator metricsAggregator =
                new MetricsAggregator(
                        configs, frontendMetricsCollectClients, storeMetricsCollectClients);

        StoreIngestClients storeIngestClients =
                new StoreIngestClients(this.channelManager, RoleType.STORE, StoreIngestClient::new);
        SchemaWriter schemaWriter =
                new SchemaWriter(
                        new RoleClients<>(
                                this.channelManager, RoleType.COORDINATOR, SchemaClient::new));

        BatchDdlClient batchDdlClient =
                new BatchDdlClient(new DdlExecutors(), snapshotCache, schemaWriter);

        this.metaService = new DefaultMetaService(configs);

        this.clientService =
                new ClientService(
                        snapshotCache,
                        metricsAggregator,
                        storeIngestClients,
                        this.metaService,
                        batchDdlClient);

        FrontendSnapshotService frontendSnapshotService =
                new FrontendSnapshotService(snapshotCache);

        MetricsCollector metricsCollector = new MetricsCollector(configs);
        MetricsCollectService metricsCollectService = new MetricsCollectService(metricsCollector);

        GrootDdlService clientDdlService = new GrootDdlService(snapshotCache, batchDdlClient);

        EdgeIdGenerator edgeIdGenerator = new DefaultEdgeIdGenerator(configs, this.channelManager);

        LogService logService = LogServiceFactory.makeLogService(configs);
        KafkaAppender kafkaAppender = new KafkaAppender(configs, metaService, logService);
        this.graphWriter =
                new GraphWriter(
                        snapshotCache,
                        edgeIdGenerator,
                        this.metaService,
                        metricsCollector,
                        kafkaAppender,
                        configs);
        ClientWriteService clientWriteService = new ClientWriteService(graphWriter);

        RoleClients<BackupClient> backupClients =
                new RoleClients<>(this.channelManager, RoleType.COORDINATOR, BackupClient::new);
        ClientBackupService clientBackupService = new ClientBackupService(backupClients);

        IngestorSnapshotService ingestorSnapshotService =
                new IngestorSnapshotService(kafkaAppender);
        IngestorWriteService ingestorWriteService = new IngestorWriteService(kafkaAppender);
        this.rpcServer =
                new RpcServer(
                        configs,
                        localNodeProvider,
                        frontendSnapshotService,
                        metricsCollectService,
                        ingestorSnapshotService,
                        ingestorWriteService);

        this.serviceServer =
                buildServiceServer(
                        configs,
                        clientService,
                        clientDdlService,
                        clientWriteService,
                        clientBackupService);

        boolean isSecondary = CommonConfig.SECONDARY_INSTANCE_ENABLED.get(configs);
        WrappedSchemaFetcher wrappedSchemaFetcher =
                new WrappedSchemaFetcher(snapshotCache, metaService, isSecondary);
        IrServiceProducer serviceProducer = new IrServiceProducer(configs);
        this.graphService = serviceProducer.makeGraphService(wrappedSchemaFetcher, channelManager);
    }

    private RpcServer buildServiceServer(Configs configs, BindableService... services) {
        int port = FrontendConfig.FRONTEND_SERVICE_PORT.get(configs);
        int threadCount = FrontendConfig.FRONTEND_SERVICE_THREAD_COUNT.get(configs);
        int maxBytes = CommonConfig.RPC_MAX_BYTES_MB.get(configs) * 1024 * 1024;
        NettyServerBuilder builder = NettyServerBuilder.forPort(port);
        builder.executor(RpcUtils.createGrpcExecutor(threadCount)).maxInboundMessageSize(maxBytes);

        String username = FrontendConfig.AUTH_USERNAME.get(configs);
        String password = FrontendConfig.AUTH_PASSWORD.get(configs);
        if (!username.isEmpty()) {
            AuthorizationServerInterceptor interceptor =
                    new AuthorizationServerInterceptor(
                            Collections.singletonMap(username, password));
            builder.intercept(interceptor);
        }
        LocalNodeProvider lnp = new LocalNodeProvider(RoleType.FRONTEND_SERVICE, configs);
        return new RpcServer(builder, lnp, services);
    }

    @Override
    public void start() {
        if (this.curator != null) {
            this.curator.start();
        }
        this.metaService.start();
        this.graphWriter.start();
        try {
            this.rpcServer.start();
        } catch (IOException e) {
            throw new GrootException(e);
        }
        this.discovery.start();
        this.channelManager.start();

        while (snapshotCache.getSnapshotWithSchema().getGraphDef() == null) {
            try {
                Thread.sleep(1000);
                logger.info("Waiting for schema ready...");
            } catch (InterruptedException e) {
                throw new GrootException(e);
            }
        }
        this.graphService.start();
        try {
            this.serviceServer.start();
        } catch (IOException e) {
            throw new GrootException(e);
        }
    }

    @Override
    public void close() throws IOException {
        this.serviceServer.stop();
        this.rpcServer.stop();
        this.metaService.stop();
        this.channelManager.stop();
        this.discovery.stop();
        if (this.curator != null) {
            this.curator.close();
        }
        this.graphService.stop();
    }

    public static void main(String[] args) throws IOException {
        String configFile = System.getProperty("config.file");
        Configs conf = new Configs(configFile);
        Frontend frontend = new Frontend(conf);
        NodeLauncher nodeLauncher = new NodeLauncher(frontend);
        nodeLauncher.start();
    }
}
