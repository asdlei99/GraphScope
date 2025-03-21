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
package com.alibaba.graphscope.groot.common.config;

import com.alibaba.graphscope.groot.common.RoleType;

public class CommonConfig {
    public static final String NODE_COUNT_FORMAT = "%s.node.count";

    public static final Config<String> ROLE_NAME = Config.stringConfig("role.name", "");

    public static final Config<Integer> NODE_IDX = Config.intConfig("node.idx", 0);

    public static final Config<String> RPC_HOST = Config.stringConfig("rpc.host", "");

    public static final Config<Integer> RPC_PORT = Config.intConfig("rpc.port", 0);

    public static final Config<String> GAIA_RPC_PORT = Config.stringConfig("gaia.rpc.port", "");
    public static final Config<String> GAIA_ENGINE_PORT =
            Config.stringConfig("gaia.engine.port", "");
    public static final Config<String> FRONTEND_RPC_PORT =
            Config.stringConfig("frontend.rpc.port", "");
    public static final Config<String> COORDINATOR_RPC_PORT =
            Config.stringConfig("coordinator.rpc.port", "");
    public static final Config<String> STORE_RPC_PORT = Config.stringConfig("store.rpc.port", "");

    public static final Config<Integer> RPC_THREAD_COUNT =
            Config.intConfig(
                    "rpc.thread.count",
                    Math.max(Math.min(Runtime.getRuntime().availableProcessors() / 2, 64), 4));

    public static final Config<Integer> NETTY_THREAD_COUNT =
            Config.intConfig(
                    "netty.thread.count",
                    Math.max(Math.min(Runtime.getRuntime().availableProcessors(), 64), 4));

    public static final Config<Integer> RPC_MAX_BYTES_MB = Config.intConfig("rpc.max.bytes.mb", 16);

    public static final Config<Integer> STORE_NODE_COUNT =
            Config.intConfig(String.format(NODE_COUNT_FORMAT, RoleType.STORE.getName()), 1);

    public static final Config<Integer> FRONTEND_NODE_COUNT =
            Config.intConfig(String.format(NODE_COUNT_FORMAT, RoleType.FRONTEND.getName()), 1);

    public static final Config<Integer> COORDINATOR_NODE_COUNT =
            Config.intConfig(String.format(NODE_COUNT_FORMAT, RoleType.COORDINATOR.getName()), 1);

    public static final Config<Integer> PARTITION_COUNT = Config.intConfig("partition.count", 1);

    public static final Config<Long> METRIC_UPDATE_INTERVAL_MS =
            Config.longConfig("metric.update.interval.ms", 5000L);

    public static final Config<String> LOG4RS_CONFIG = Config.stringConfig("log4rs.config", "");

    public static final Config<String> DISCOVERY_MODE =
            Config.stringConfig("discovery.mode", "file"); // others: zookeeper

    public static final Config<Integer> ID_ALLOCATE_SIZE =
            Config.intConfig("id.allocate.size", 1000000);
    // Whether to create test kafka cluster on MaxNode
    public static final Config<Boolean> KAFKA_TEST_CLUSTER_ENABLE =
            Config.boolConfig("kafka.test.cluster.enable", true);

    public static final Config<Boolean> SECONDARY_INSTANCE_ENABLED =
            Config.boolConfig("secondary.instance.enabled", false);

    // Create an extra store pod for each original store pod for backup.
    // Only available in multi pod mode.
    public static final Config<Boolean> WRITE_HIGH_AVAILABILITY_ENABLED =
            Config.boolConfig("write.high.availability.enabled", false);
}
