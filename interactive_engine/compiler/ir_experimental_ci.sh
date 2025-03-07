#!/bin/bash
base_dir=$(cd $(dirname $0); pwd)
# start engine service and load modern graph
cd ${base_dir}/../executor/ir/target/release && RUST_LOG=info ./start_rpc_server --config ${base_dir}/../executor/ir/integrated/config &
sleep 5s
# start compiler service
cd ${base_dir} && make run &
sleep 5s
# run gremlin standard tests
cd ${base_dir} && make gremlin_test
exit_code=$?
# report test result
if [ $exit_code -ne 0 ]; then
    echo "ir gremlin integration test on experimental store fail"
    exit 1
fi

# restart compiler service
ps -ef | grep "com.alibaba.graphscope.GraphServer" | grep -v grep | awk '{print $2}' | xargs kill -9 || true
cd ${base_dir} && make run gremlin.script.language.name=antlr_gremlin_calcite &
sleep 5s
# run gremlin standard tests to test calcite-based IR layer
cd ${base_dir} && make gremlin_calcite_test
exit_code=$?
# report test result
if [ $exit_code -ne 0 ]; then
    echo "ir\(calcite-based\) gremlin integration test on experimental store fail"
    exit 1
fi

# restart compiler service
ps -ef | grep "com.alibaba.graphscope.GraphServer" | grep -v grep | awk '{print $2}' | xargs kill -9 || true
cd ${base_dir} && make run gremlin.script.language.name=antlr_gremlin_calcite physical.opt.config=proto &
sleep 5s
# run gremlin standard tests to test calcite-based IR layer
cd ${base_dir} && make gremlin_calcite_test
exit_code=$?
# report test result
if [ $exit_code -ne 0 ]; then
    echo "ir\(calcite-based\) gremlin integration with proto physical test on experimental store fail"
    exit 1
fi

# clean service
ps -ef | grep "com.alibaba.graphscope.GraphServer" | grep -v grep | awk '{print $2}' | xargs kill -9 || true
ps -ef | grep "start_rpc_server" | grep -v grep | awk '{print $2}' | xargs kill -9

# start distributed engine service and load modern graph
cd ${base_dir}/../executor/ir/target/release &&
RUST_LOG=info DATA_PATH=/tmp/gstest/modern_graph_exp_bin PARTITION_ID=0 ./start_rpc_server --config ${base_dir}/../executor/ir/integrated/config/distributed/server_0 &
cd ${base_dir}/../executor/ir/target/release &&
RUST_LOG=info DATA_PATH=/tmp/gstest/modern_graph_exp_bin PARTITION_ID=1 ./start_rpc_server --config ${base_dir}/../executor/ir/integrated/config/distributed/server_1 &
# start compiler service
cd ${base_dir} && make run gremlin.script.language.name=antlr_gremlin_calcite physical.opt.config=proto pegasus.hosts:=127.0.0.1:1234,127.0.0.1:1235 &
sleep 5s
# run gremlin standard tests to test calcite-based IR layer
cd ${base_dir} && make gremlin_calcite_test
exit_code=$?
# report test result
if [ $exit_code -ne 0 ]; then
    echo "ir\(calcite-based\) gremlin integration with proto physical test on distributed experimental store fail"
    exit 1
fi

# clean service
ps -ef | grep "com.alibaba.graphscope.GraphServer" | grep -v grep | awk '{print $2}' | xargs kill -9 || true
ps -ef | grep "start_rpc_server" | grep -v grep | awk '{print $2}' | xargs kill -9

cd ${base_dir}/../executor/ir/target/release && DATA_PATH=/tmp/gstest/movie_graph_exp_bin RUST_LOG=info ./start_rpc_server --config ${base_dir}/../executor/ir/integrated/config &
sleep 5s
# start compiler service
cd ${base_dir} && make run graph.schema:=../executor/ir/core/resource/movie_schema.json &
sleep 10s
export ENGINE_TYPE=pegasus
# run cypher movie tests
export ENGINE_TYPE=pegasus
cd ${base_dir} && make cypher_test
exit_code=$?
# clean service
ps -ef | grep "com.alibaba.graphscope.GraphServer" | grep -v grep | awk '{print $2}' | xargs kill -9 || true
ps -ef | grep "start_rpc_server" | grep -v grep | awk '{print $2}' | xargs kill -9
# report test result
if [ $exit_code -ne 0 ]; then
    echo "ir cypher movie integration test on experimental store fail"
    exit 1
fi

