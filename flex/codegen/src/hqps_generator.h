/** Copyright 2020 Alibaba Group Holding Limited.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
#ifndef CODEGEN_SRC_HQPS_HQPS_GENERATOR_H_
#define CODEGEN_SRC_HQPS_HQPS_GENERATOR_H_

#include <boost/format.hpp>
#include <string>
#include <vector>

#include "flex/codegen/src/building_context.h"
#include "flex/codegen/src/codegen_utils.h"
#include "flex/codegen/src/hqps/hqps_dedup_builder.h"
#include "flex/codegen/src/hqps/hqps_edge_expand_builder.h"
#include "flex/codegen/src/hqps/hqps_fold_builder.h"
#include "flex/codegen/src/hqps/hqps_get_v_builder.h"
#include "flex/codegen/src/hqps/hqps_join_utils.h"
#include "flex/codegen/src/hqps/hqps_limit_builder.h"
#include "flex/codegen/src/hqps/hqps_path_expand_builder.h"
#include "flex/codegen/src/hqps/hqps_project_builder.h"
#include "flex/codegen/src/hqps/hqps_scan_builder.h"
#include "flex/codegen/src/hqps/hqps_select_builder.h"
#include "flex/codegen/src/hqps/hqps_sink_builder.h"
#include "flex/codegen/src/hqps/hqps_sort_builder.h"
#include "flex/proto_generated_gie/physical.pb.h"

namespace gs {

static constexpr const char* QUERY_TEMPLATE_STR =
    "// Generated by query_generator.h\n"
    "// This file is generated by codegen/query_generator.h\n"
    "// DO NOT EDIT\n"
    "\n"
    "#include \"flex/engines/hqps_db/core/sync_engine.h\"\n"
    "#include \"flex/engines/graph_db/app/app_base.h\"\n"  // app_base_header.h
    "#include \"%1%\"\n"  // graph_interface_header.h
    "\n"
    "\n"
    "namespace gs {\n"
    "// Auto generated expression class definition\n"
    "%2%\n"
    "\n"
    "// Auto generated query class definition\n"
    "class %3% : public AppBase {\n"
    " public:\n"
    "  using Engine = SyncEngine<%4%>;\n"
    "  using label_id_t = typename %4%::label_id_t;\n"
    "  using vertex_id_t = typename %4%::vertex_id_t;\n"
    " // constructor\n"
    "  %3%(const GraphDBSession& session) : %6%(session) {}\n"
    "// Query function for query class\n"
    "  %5% Query(%7%) const{\n"
    "     %8%\n"
    "  }\n"
    "// Wrapper query function for query class\n"
    "  bool Query(Decoder& decoder, Encoder& encoder) override {\n"
    "    //decoding params from decoder, and call real query func\n"
    "    %9%\n"
    "    auto res =  Query(%10%);\n"
    "    // dump results to string\n"
    "    std::string res_str = res.SerializeAsString();\n"
    "    // encode results to encoder\n"
    "    if (!res_str.empty()){\n"
    "      encoder.put_string_view(res_str);\n"
    "    }\n"
    "    return true;\n"
    "  }\n"
    "  //private members\n"
    " private:\n"
    "  %4% %6%;\n"
    "};\n"
    "} // namespace gs\n"
    "\n"
    "// extern c interfaces\n"
    "extern \"C\" {\n"
    "void* CreateApp(gs::GraphDBSession& db) {\n"
    "  gs::%3%* app = new gs::%3%(db);\n"
    "  return static_cast<void*>(app);\n"
    "}\n"
    "void DeleteApp(void* app) {\n"
    "  if (app != nullptr) {\n"
    "    gs::%3%* casted = static_cast<gs::%3%*>(app);\n"
    "    delete casted;\n"
    "  }\n"
    "}\n"
    "}\n";

// declare
template <typename LabelT>
static std::array<std::string, 4> BuildJoinOp(
    BuildingContext& ctx, const physical::Join& join_op_pb,
    const physical::PhysicalOpr::MetaData& meta_data);

// declare
template <typename LabelT>
static std::string BuildApplyOp(
    BuildingContext& ctx, const physical::Apply& apply_op_pb,
    const physical::PhysicalOpr::MetaData& meta_data);

// declare
template <typename LabelT>
static std::array<std::string, 4> BuildIntersectOp(
    BuildingContext& ctx, const physical::Intersect& intersect_op);

// get_v can contains labels and filters.
// what ever it takes, we will always fuse label info into edge_expand,
// but if get_v contains expression, we will not fuse it into edge_expand
bool simple_get_v(const physical::GetV& get_v_op) {
  if (get_v_op.params().has_predicate()) {
    return false;
  }
  return true;
}

bool intermediate_edge_op(const physical::EdgeExpand& expand_op) {
  if (!expand_op.has_alias() || expand_op.alias().value() == -1) {
    return true;
  }
  return false;
}

template <typename LabelT>
void extract_vertex_labels(const physical::GetV& get_v_op,
                           std::vector<LabelT>& vertex_labels) {
  // get vertex label id from get_
  auto get_v_tables = get_v_op.params().tables();
  for (auto vertex_label_pb : get_v_tables) {
    vertex_labels.emplace_back(
        try_get_label_from_name_or_id<LabelT>(vertex_label_pb));
  }
  VLOG(10) << "Got vertex labels : " << gs::to_string(vertex_labels);
}

template <typename LabelT>
void build_fused_edge_get_v(
    BuildingContext& ctx, std::stringstream& ss,
    physical::EdgeExpand& edge_expand_op,
    const physical::PhysicalOpr::MetaData& edge_meta_data,
    const physical::GetV& get_v_op, const std::vector<LabelT>& vertex_labels) {
  // build edge expand

  CHECK(vertex_labels.size() > 0);
  edge_expand_op.set_expand_opt(
      physical::EdgeExpand::ExpandOpt::EdgeExpand_ExpandOpt_VERTEX);
  if (get_v_op.has_alias()) {
    edge_expand_op.mutable_alias()->set_value(get_v_op.alias().value());
  } else {
    edge_expand_op.mutable_alias()->set_value(-1);
  }

  ss << _4_SPACES
     << BuildEdgeExpandOp<LabelT>(ctx, edge_expand_op, edge_meta_data,
                                  vertex_labels)
     << std::endl;
}

// Entrance for generating a parameterized query
// The generated class will have two function
// 1. Query(GraphInterface& graph, int64_t ts, Decoder& input) const override
// 2. Query(GraphInterface& graph, int64_t ts, Params&...params) const
// the first one overrides the base class function, and the second one will be
// called by the first one, with some params(depends on the plan received)
template <typename LabelT>
class QueryGenerator {
 public:
  // if edge expand e is followed by a get_v, we can fuse them into one op
  static constexpr bool FUSE_EDGE_GET_V = true;
  static constexpr bool FUSE_PATH_EXPAND_V = true;
  QueryGenerator(BuildingContext& ctx, const physical::PhysicalPlan& plan)
      : ctx_(ctx), plan_(plan) {}

  std::string GenerateQuery() {
    // During generate query body, we will track the parameters
    // And also generate the expression for needed
    std::string query_code = build_query_code();
    std::string expr_code;
    {
      std::stringstream ss;
      auto exprs = ctx_.GetExprCode();
      for (auto& expr : exprs) {
        ss << expr << std::endl;
      }
      ss << std::endl;
      expr_code = ss.str();
    }
    std::string dynamic_vars_str = concat_param_vars(ctx_.GetParameterVars());
    std::string decoding_params_code, decoded_params_str;
    std::tie(decoding_params_code, decoded_params_str) =
        decode_params_from_decoder(ctx_.GetParameterVars());
    boost::format formater(QUERY_TEMPLATE_STR);
    formater % ctx_.GetGraphHeader() % expr_code % ctx_.GetQueryClassName() %
        ctx_.GetGraphInterface() % ctx_.GetQueryRet() % ctx_.GraphVar() %
        dynamic_vars_str % query_code % decoding_params_code %
        decoded_params_str;
    return formater.str();
  }

  // Generate a subtask for a subplan
  // 0: expr codes.
  // 1. query codes.
  std::pair<std::vector<std::string>, std::string> GenerateSubTask() const {
    auto query_body = build_query_code();
    return std::make_pair(ctx_.GetExprCode(), query_body);
  }

 private:
  // copy the param vars to sort
  std::string concat_param_vars(
      std::vector<codegen::ParamConst> param_vars) const {
    std::stringstream ss;
    if (param_vars.size() > 0) {
      sort(param_vars.begin(), param_vars.end(),
           [](const auto& a, const auto& b) { return a.id < b.id; });
      CHECK(param_vars[0].id == 0);
      for (size_t i = 0; i < param_vars.size(); ++i) {
        if (i > 0 && param_vars[i].id == param_vars[i - 1].id) {
          // found duplicate
          CHECK(param_vars[i] == param_vars[i - 1]);
          continue;
        } else {
          ss << data_type_2_string(param_vars[i].type) << " "
             << param_vars[i].var_name << ",";
        }
      }
    }
    auto str = ss.str();
    if (str.size() > 0) {
      str.pop_back();  // remove the last comma
    }
    return str;
  }

  // implement the function that overrides the base class.
  std::tuple<std::string, std::string> decode_params_from_decoder(
      std::vector<codegen::ParamConst> param_vars) const {
    std::vector<std::string> param_names, param_decoding_codes;
    // the param vars itself contains the index, which is the order of the param
    sort(param_vars.begin(), param_vars.end(),
         [](const auto& a, const auto& b) { return a.id < b.id; });
    if (param_vars.size() > 0) {
      CHECK(param_vars[0].id == 0);  // encoding start from 0
    }

    for (size_t i = 0; i < param_vars.size(); ++i) {
      if (i > 0 && param_vars[i].id == param_vars[i - 1].id) {
        CHECK(param_vars[i] == param_vars[i - 1]);
        continue;
      } else {
        auto& cur_param_var = param_vars[i];
        // for each param_var, decode the param from decoder,and one line of
        // code
        std::string cur_param_name, cur_param_decoding_code;
        std::tie(cur_param_name, cur_param_decoding_code) =
            decode_param_from_decoder(cur_param_var, i, "var", "decoder");
        param_names.push_back(cur_param_name);
        param_decoding_codes.push_back(cur_param_decoding_code);
      }
    }
    VLOG(10) << "Finish decoding params, size: " << param_names.size();
    std::string param_vars_decoding, param_vars_concat_str;
    {
      std::stringstream ss;
      for (size_t i = 0; i < param_names.size(); ++i) {
        ss << param_names[i];
        if (i != param_names.size() - 1) {
          ss << ", ";
        }
      }
      param_vars_concat_str = ss.str();
    }
    {
      std::stringstream ss;
      for (size_t i = 0; i < param_decoding_codes.size(); ++i) {
        ss << param_decoding_codes[i] << std::endl;
      }
      param_vars_decoding = ss.str();
    }
    return std::make_tuple(param_vars_decoding, param_vars_concat_str);
  }

  std::string build_query_code() const {
    std::stringstream ss;
    auto size = plan_.plan_size();

    LOG(INFO) << "Found " << size << " operators in the plan";
    for (int32_t i = 0; i < size; ++i) {
      auto op = plan_.plan(i);
      auto& meta_datas = op.meta_data();
      // CHECK(meta_datas.size() == 1) << "meta data size: " <<
      // meta_datas.size();
      // physical::PhysicalOpr::MetaData meta_data; //fake meta
      auto opr = op.opr();
      switch (opr.op_kind_case()) {
      case physical::PhysicalOpr::Operator::kRoot: {
        LOG(INFO) << "Skip root_scan";
        break;
      }

      case physical::PhysicalOpr::Operator::kScan: {  // scan
        // TODO: meta_data is not found in scan
        physical::PhysicalOpr::MetaData meta_data;

        LOG(INFO) << "Found a scan operator";
        auto& scan_op = opr.scan();

        ss << BuildScanOp(ctx_, scan_op, meta_data) << std::endl;
        break;
      }

      case physical::PhysicalOpr::Operator::kEdge: {  // edge expand
        physical::EdgeExpand real_edge_expand = opr.edge();
        // try to use information from later operator
        std::vector<LabelT> dst_vertex_labels;
        if (i + 1 < size) {
          auto& get_v_op_opr = plan_.plan(i + 1).opr();
          if (get_v_op_opr.op_kind_case() ==
              physical::PhysicalOpr::Operator::kVertex) {
            auto& get_v_op = get_v_op_opr.vertex();
            extract_vertex_labels(get_v_op, dst_vertex_labels);

            if (FUSE_EDGE_GET_V) {
              if (simple_get_v(get_v_op) &&
                  intermediate_edge_op(real_edge_expand)) {
                CHECK(dst_vertex_labels.size() > 0);
                VLOG(10) << "When fusing edge+get_v, get_v has labels: "
                         << gs::to_string(dst_vertex_labels);
                build_fused_edge_get_v<LabelT>(ctx_, ss, real_edge_expand,
                                               meta_datas[0], get_v_op,
                                               dst_vertex_labels);
                LOG(INFO) << "Fuse edge expand and get_v since get_v is simple";
                i += 1;
                break;
              } else if (intermediate_edge_op(real_edge_expand)) {
                LOG(INFO) << "try to fuse edge expand with complex get_v, take "
                             "take the get_v' vertex label";
              } else {
                // only fuse get_v label into edge expand
                LOG(INFO)
                    << "Skip fusing edge expand and get_v since simple get v";
              }
            }
          } else {
            LOG(INFO) << "Skip fusing edge expand and get_v since the next "
                         "operator is not get_v";
          }
        } else {
          LOG(INFO) << "EdgeExpand is the last operator";
        }
        auto& meta_data = meta_datas[0];
        LOG(INFO) << "Found a edge expand operator";
        ss << BuildEdgeExpandOp<LabelT>(ctx_, real_edge_expand, meta_data,
                                        dst_vertex_labels)
           << std::endl;

        break;
      }

      case physical::PhysicalOpr::Operator::kDedup: {  // dedup
        // auto& meta_data = meta_datas[0];
        physical::PhysicalOpr::MetaData meta_data;  // fake meta
        LOG(INFO) << "Found a dedup operator";
        auto& dedup_op = opr.dedup();
        ss << BuildDedupOp(ctx_, dedup_op, meta_data) << std::endl;
        break;
      }

      case physical::PhysicalOpr::Operator::kProject: {  // project
        // project op can result into multiple meta data
        // auto& meta_data = meta_datas[0];
        physical::PhysicalOpr::MetaData meta_data;
        LOG(INFO) << "Found a project operator";
        auto& project_op = opr.project();
        std::string call_project_code;
        call_project_code = BuildProjectOp(ctx_, project_op, meta_data);
        ss << call_project_code;
        break;
      }

      case physical::PhysicalOpr::Operator::kSelect: {
        // auto& meta_data = meta_datas[0];
        physical::PhysicalOpr::MetaData meta_data;
        LOG(INFO) << "Found a select operator";
        auto& select_op = opr.select();
        std::string select_code = BuildSelectOp(ctx_, select_op, meta_data);
        ss << select_code << std::endl;
        break;
      }

      case physical::PhysicalOpr::Operator::kVertex: {
        physical::PhysicalOpr::MetaData meta_data;
        LOG(INFO) << "Found a get v operator";
        auto& get_v_op = opr.vertex();
        auto get_v_code = BuildGetVOp<LabelT>(ctx_, get_v_op, meta_data);
        // first output code can be empty, just ignore
        ss << get_v_code;
        break;
      }

      case physical::PhysicalOpr::Operator::kGroupBy: {
        // auto& meta_data = meta_datas[0];
        // meta_data is currently not used in groupby.
        physical::PhysicalOpr::MetaData meta_data;
        auto& group_by_op = opr.group_by();
        if (group_by_op.mappings_size() > 0) {
          LOG(INFO) << "Found a group by operator";
          auto code_lines = BuildGroupByOp(ctx_, group_by_op, meta_data);
          ss << code_lines;
        } else {
          LOG(INFO) << "Found a group by operator with no group by keys";
          auto code_lines =
              BuildGroupWithoutKeyOp(ctx_, group_by_op, meta_data);
          ss << code_lines;
        }
        LOG(INFO) << "Finish groupby operator gen";
        break;
      }

      // Path Expand + GetV shall be always fused.
      case physical::PhysicalOpr::Operator::kPath: {
        physical::PhysicalOpr::MetaData meta_data;
        LOG(INFO) << "Found a path operator";
        auto& path_op = opr.path();
        if (FUSE_PATH_EXPAND_V && !path_op.has_alias() && (i + 1 < size)) {
          auto& next_op = plan_.plan(i + 1).opr();
          if (next_op.op_kind_case() ==
              physical::PhysicalOpr::Operator::kVertex) {
            LOG(INFO) << " Fusing path expand and get_v";
            auto& get_v_op = next_op.vertex();
            int32_t get_v_res_alias = -1;
            if (get_v_op.has_alias()) {
              get_v_res_alias = get_v_op.alias().value();
            }

            auto res = BuildPathExpandVOp<LabelT>(ctx_, path_op, meta_datas,
                                                  get_v_res_alias);
            ss << res;
            i += 1;  // jump one step
            break;
          }
        }
        LOG(INFO) << " PathExpand to Path";
        // otherwise, just expand path
        auto res = BuildPathExpandPathOp<LabelT>(ctx_, path_op, meta_datas);
        ss << res;
        break;
      }

      case physical::PhysicalOpr::Operator::kApply: {
        auto& meta_data = meta_datas[0];
        LOG(INFO) << "Found a apply operator";
        auto& apply_op = opr.apply();
        std::string call_apply_code =
            BuildApplyOp<LabelT>(ctx_, apply_op, meta_data);
        ss << call_apply_code << std::endl;
        break;
      }

      case physical::PhysicalOpr::Operator::kJoin: {
        // auto& meta_data = meta_datas[0];
        LOG(INFO) << "Found a join operator";
        auto& join_op = opr.join();
        auto join_opt_code = BuildJoinOp<LabelT>(ctx_, join_op);
        for (auto& line : join_opt_code) {
          ss << line << std::endl;
        }
        break;
      }

      case physical::PhysicalOpr::Operator::kIntersect: {
        LOG(INFO) << "Found a intersect operator";
        // a intersect op must be followed by a unfold op
        CHECK(i + 1 < size) << " intersect op must be followed by a unfold op";
        auto& next_op = plan_.plan(i + 1).opr();
        CHECK(next_op.op_kind_case() ==
              physical::PhysicalOpr::Operator::kUnfold)
            << "intersect op must be followed by a unfold op";
        auto& intersect_op = opr.intersect();
        auto intersect_opt_code = BuildIntersectOp<LabelT>(ctx_, intersect_op);
        for (auto& line : intersect_opt_code) {
          ss << line << std::endl;
        }
        i += 1;  // skip unfold
        break;
      }

      case physical::PhysicalOpr::Operator::kOrderBy: {
        physical::PhysicalOpr::MetaData meta_data;
        LOG(INFO) << "Found a order by operator";
        auto& order_by_op = opr.order_by();
        std::string sort_code = BuildSortOp(ctx_, order_by_op, meta_data);
        ss << sort_code << std::endl;
        break;
      }

      case physical::PhysicalOpr::Operator::kSink: {
        physical::PhysicalOpr::MetaData meta_data;
        LOG(INFO) << "Found a sink operator";
        auto& sink_op = opr.sink();
        std::string call_sink_code = BuildSinkOp(ctx_, sink_op, meta_data);
        ss << call_sink_code << std::endl;
        break;
      }

      case physical::PhysicalOpr::Operator::kRepartition: {
        LOG(INFO) << "Found a repartition operator, just ignore";
        break;
      }

      case physical::PhysicalOpr::Operator::kLimit: {
        LOG(INFO) << "Found a limit operator";
        auto& limit_op = opr.limit();
        std::string limit_code = BuildLimitOp(ctx_, limit_op);
        ss << limit_code << std::endl;
        break;
      }

      default:
        LOG(FATAL) << "Unsupported operator type: " << opr.op_kind_case();
      }
    }
    LOG(INFO) << "Finish adding query";
    return ss.str();
  }

  BuildingContext& ctx_;
  const physical::PhysicalPlan& plan_;
};

// When building a join op, we need to consider the following cases:
// 0. tag_id to tag_ind mapping, two plan should keep different mappings
// const physical::PhysicalOpr::MetaData& meta_data
template <typename LabelT>
static std::array<std::string, 4> BuildJoinOp(
    BuildingContext& ctx, const physical::Join& join_op_pb) {
  auto join_kind = join_kind_pb_to_internal(join_op_pb.join_kind());
  CHECK(join_op_pb.left_keys_size() == join_op_pb.right_keys_size());
  // these keys are tag_ids.
  auto& left_keys = join_op_pb.left_keys();
  auto& right_keys = join_op_pb.right_keys();
  std::vector<int32_t> join_keys;  // the left_keys and
  for (int i = 0; i < left_keys.size(); ++i) {
    CHECK(left_keys[i].tag().id() == right_keys[i].tag().id());
    join_keys.push_back(left_keys[i].tag().id());
  }

  VLOG(10) << "Join tag: " << gs::to_string(join_keys);
  std::string copy_context_code, left_plan_code, right_plan_code, join_code;
  std::string left_res_ctx_name, right_res_ctx_name;
  std::string left_start_ctx_name, right_start_ctx_name;
  // the derived context should preserve the tag_id to tag_inds mappings we
  // already have.
  auto right_context = ctx.CreateSubTaskContext("right_");
  // if join op is the start node, the copy_context_code is empty
  if (ctx.EmptyContext()) {
    // the prefix of left context should be appended.
    // can append fix this problem?
    ctx.AppendContextPrefix("left_");
  } else {
    // copy the context.
    // always copy for right context.
    std::stringstream cur_ss;
    right_start_ctx_name = right_context.GetCurCtxName();
    left_start_ctx_name = ctx.GetCurCtxName();
    cur_ss << "auto " << right_start_ctx_name << "(" << ctx.GetCurCtxName()
           << ");" << std::endl;
    copy_context_code = cur_ss.str();
  }
  {
    // left code.
    // before enter left, we need to rename the context with left.
    auto left_task_generator =
        QueryGenerator<LabelT>(ctx, join_op_pb.left_plan());
    std::vector<std::string> left_exprs;
    // the generate left exprs are already contained in ctx;
    std::tie(left_exprs, left_plan_code) =
        left_task_generator.GenerateSubTask();
    left_res_ctx_name = ctx.GetCurCtxName();
  }
  LOG(INFO) << "Finish building left code";

  {
    // right code
    auto right_task_generator =
        QueryGenerator<LabelT>(right_context, join_op_pb.right_plan());
    std::vector<std::string> right_exprs;
    std::tie(right_exprs, right_plan_code) =
        right_task_generator.GenerateSubTask();
    right_res_ctx_name = right_context.GetCurCtxName();
    for (auto expr : right_exprs) {
      ctx.AddExprCode(expr);
    }
    auto right_param_vars = right_context.GetParameterVars();
    for (auto right_param_var : right_param_vars) {
      ctx.AddParameterVar(right_param_var);
    }
  }
  LOG(INFO) << "Finish building right code";

  // join code.
  {
    // we need to extract distinct inds for two side join key
    std::stringstream cur_ss;
    std::string cur_ctx_name, prev_ctx_name;
    std::tie(prev_ctx_name, cur_ctx_name) = ctx.GetPrevAndNextCtxName();
    CHECK(prev_ctx_name == left_res_ctx_name)
        << prev_ctx_name << ", " << left_res_ctx_name;
    cur_ss << "auto " << cur_ctx_name << _ASSIGN_STR_;
    if (join_keys.size() == 1) {
      cur_ss << " Engine::template Join";
      cur_ss << "<";
      {
        cur_ss << ctx.GetTagInd(join_keys[0]) << ", "
               << right_context.GetTagInd(join_keys[0]) << ",";
        cur_ss << join_kind_to_str(join_kind);
      }
    } else if (join_keys.size() == 2) {
      cur_ss << " Engine::template Join";
      cur_ss << "<";
      {
        cur_ss << ctx.GetTagInd(join_keys[0]) << ", "
               << ctx.GetTagInd(join_keys[1]) << ","
               << right_context.GetTagInd(join_keys[0]) << ","
               << right_context.GetTagInd(join_keys[1]) << ",";
        cur_ss << join_kind_to_str(join_kind);
      }
    } else {
      LOG(FATAL) << "Join on more than two key is not supported yet.";
    }

    cur_ss << ">";
    cur_ss << "(";
    {
      cur_ss << "std::move(" << left_res_ctx_name << "),";
      cur_ss << "std::move(" << right_res_ctx_name << ")";
    }
    cur_ss << ");";
    join_code = cur_ss.str();
  }
  {
    // The tags in right ctx should be added to left ctx.
    // after join, the tags/columns from right ctx will be appended to left
    // ctx.
    auto right_tag_inds = right_context.GetTagIdAndIndMapping();
    auto left_tag_inds = ctx.GetTagIdAndIndMapping();
    for (auto right_tag : right_tag_inds.GetTagInd2TagIds()) {
      left_tag_inds.CreateOrGetTagInd(right_tag);
    }
    VLOG(10) << "Merging right tag ids to left, got : "
             << gs::to_string(left_tag_inds.GetTagInd2TagIds()) << std::endl
             << gs::to_string(left_tag_inds.GetTagId2TagInds());
    ctx.UpdateTagIdAndIndMapping(left_tag_inds);
  }
  LOG(INFO) << "Finish building join code";
  return std::array<std::string, 4>{copy_context_code, left_plan_code,
                                    right_plan_code, join_code};
}

template <typename LabelT>
static std::string BuildApplyOp(
    BuildingContext& ctx, const physical::Apply& apply_op_pb,
    const physical::PhysicalOpr::MetaData& meta_data) {
  auto join_kind = join_kind_pb_to_internal(apply_op_pb.join_kind());
  auto res_alias = apply_op_pb.alias().value();
  auto& sub_plan = apply_op_pb.sub_plan();
  std::string lambda_func_name, lambda_func_code;
  {
    auto new_building_ctx = ctx.CreateSubTaskContext();
    auto sub_task_generator =
        QueryGenerator<LabelT>(new_building_ctx, sub_plan);
    // QueryGenerator<LabelT> sub_task_generator(new_building_ctx, sub_plan_);
    // gen a lambda function.
    lambda_func_name = ctx.GetNextLambdaFuncName();
    std::stringstream inner_ss;
    // header
    inner_ss << "auto " << lambda_func_name << " = [&]";
    inner_ss << "(auto&& " << new_building_ctx.GetCurCtxName() << ") {"
             << std::endl;

    // body
    std::vector<std::string> exprs;
    std::string query_code;
    std::tie(exprs, query_code) = sub_task_generator.GenerateSubTask();
    inner_ss << query_code;
    for (auto expr : exprs) {
      ctx.AddExprCode(expr);
    }
    // end
    // return last context;
    inner_ss << " return " << new_building_ctx.GetCurCtxName() << ";"
             << std::endl;
    inner_ss << "};" << std::endl;

    lambda_func_code = inner_ss.str();
  }

  std::stringstream inner_ss;
  std::string prev_ctx_name, next_ctx_name;
  std::tie(prev_ctx_name, next_ctx_name) = ctx.GetPrevAndNextCtxName();
  inner_ss << lambda_func_code << std::endl;
  inner_ss << "auto " << next_ctx_name << " = Engine::template";
  inner_ss << " Apply<" << res_alias << "," << join_kind_to_str(join_kind)
           << ">";
  inner_ss << "(std::move(" << prev_ctx_name << "),";
  inner_ss << "std::move(" << lambda_func_name << "));" << std::endl;
  return inner_ss.str();
}

// declare
template <typename LabelT>
static std::array<std::string, 4> BuildIntersectOp(
    BuildingContext& ctx, const physical::Intersect& intersect_op) {
  auto& sub_plans = intersect_op.sub_plans();
  CHECK(sub_plans.size() == 2) << "Only support two sub plans intersect now.";
  auto& left_plan = sub_plans[0];
  auto& right_plan = sub_plans[1];
  auto join_key = intersect_op.key();
  VLOG(10) << "join on key: " << join_key;

  std::string copy_context_code;
  std::string left_res_ctx_name, right_res_ctx_name;
  std::string left_plan_code, right_plan_code;
  std::string intersect_code;

  auto right_context = ctx.CreateSubTaskContext("right_");
  CHECK(!ctx.EmptyContext());

  {
    std::stringstream cur_ss;
    auto right_start_ctx_name = right_context.GetCurCtxName();
    auto left_start_ctx_name = ctx.GetCurCtxName();
    cur_ss << "auto " << right_start_ctx_name << "(" << left_start_ctx_name
           << ");" << std::endl;
    copy_context_code = cur_ss.str();
  }

  {
    // left code;
    auto left_task_generator = QueryGenerator<LabelT>(ctx, left_plan);
    std::vector<std::string> left_exprs;
    // the generate left exprs are already contained in ctx;
    std::tie(left_exprs, left_plan_code) =
        left_task_generator.GenerateSubTask();
    left_res_ctx_name = ctx.GetCurCtxName();
  }
  {
    // right code
    auto right_task_generator =
        QueryGenerator<LabelT>(right_context, right_plan);
    std::vector<std::string> right_exprs;
    std::tie(right_exprs, right_plan_code) =
        right_task_generator.GenerateSubTask();
    right_res_ctx_name = right_context.GetCurCtxName();
    for (auto expr : right_exprs) {
      ctx.AddExprCode(expr);
    }
  }
  // intersect code;
  {
    std::stringstream cur_ss;
    std::string cur_ctx_name, prev_ctx_name;
    std::tie(prev_ctx_name, cur_ctx_name) = ctx.GetPrevAndNextCtxName();
    CHECK(prev_ctx_name == left_res_ctx_name)
        << prev_ctx_name << ", " << left_res_ctx_name;

    auto right_tag_ind = right_context.GetTagInd(join_key);
    auto left_tag_ind = ctx.GetTagInd(join_key);
    VLOG(10) << "Intersect on tag ind: " << left_tag_ind << ", "
             << right_tag_ind;

    cur_ss << "auto " << cur_ctx_name << _ASSIGN_STR_;
    cur_ss << " Engine::template Intersect";
    cur_ss << "<" << left_tag_ind << "," << right_tag_ind << ">";
    cur_ss << "(std::move(" << left_res_ctx_name << "),std::move("
           << right_res_ctx_name << "));";
    intersect_code = cur_ss.str();
  }
  return std::array<std::string, 4>{copy_context_code, left_plan_code,
                                    right_plan_code, intersect_code};
}

}  // namespace gs

#endif  // CODEGEN_SRC_HQPS_HQPS_GENERATOR_H_
