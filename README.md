# DAG 调度优化

这个项目用于求解深度学习训练计算图上的 DAG 调度问题。输入是由 StableHLO / XLA / ReorderPass 等流程抽象出的有向无环图，输出是每个节点的开始时间和结束时间，目标是在满足依赖关系和资源互斥约束的前提下尽量降低整体 makespan。项目也保留了峰值内存优化、结果校验和 Perfetto 可视化工具。

## 问题建模

输入图包含两类主要对象：

- `nodes`：计算节点或通信节点。节点包含 `uuid`、`typename`、`name`、`cost`、`space_cost` 等字段。
- `edges`：节点之间的依赖边。边包含 `from`、`to`、`typename`、`cost` 等字段。

当前调度需要满足以下约束：

- DAG 依赖约束：节点必须在所有前驱和对应边代价完成后才能开始。
- 类型互斥约束：同一 `typename` 的节点不能在同一时间并行执行，不同 `typename` 的节点可以重叠执行。
- 通信边约束：`CommunicationEdgePreprocessing` 会把 `communication` 类型的边和相关节点合并成新的通信节点，用节点互斥来表达通信边不能同时执行。
- 零耗时节点：`cost = 0` 的节点不占用执行时间和资源，只要入度为 0 就可以立即调度。
- 内存释放规则：节点从开始执行时占用 `space_cost`，不是自身结束就释放，而是等它的所有后继节点都执行完成后才释放。

默认主目标是最小化 makespan。内存峰值优化是单独入口，不接入默认执行链路。

## 当前主流程

Java 主入口是 `src/main/java/opt/Main.java`。

当前默认执行流程：

```text
Main.main()
  -> solveAndOpt(DEFAULT_INPUT_PATH, DEFAULT_OUTPUT_PATH)
  -> DAGGraphReader.readGraphFromJSON(...)
  -> CommunicationEdgePreprocessing.createCommunicationNodes(...)
  -> HybridScheduler_v6_7.scheduleDAG(...)
  -> DAGLocalSearch.iterativeOptimize(...)
  -> JsonSaver.saveResult(...)
```

也就是：

1. 读取原始 DAG 输入。
2. 将 `communication` 类型边预处理为通信节点。
3. 使用 `HybridScheduler_v6_7` 构造初始解。
4. 使用 `DAGLocalSearch` 做 makespan 局部优化。
5. 输出调度结果 json。

当前 `Main` 中默认输入为：

```text
data/antopt_ret/gantt_before_ReorderPassAntOPTDataAnalyseN100R0.1.json
```

当 `outputPath` 为 `"-1"` 时，输出路径会根据输入名和最终 makespan 自动生成，形式类似：

```text
output/antopt_ret/gantt_before_ReorderPassAntOPTDataAnalyseN100R0.1_<makespan>_output.json
```

内存优化入口单独保留为：

```java
Main.solveAndOptMemory(String path, String outputPath)
```

它同样先用 `HybridScheduler_v6_7` 构造初始解，然后通过 `DAGMemoryOptimizer` 统计并优化峰值内存，默认输出后缀为 `_mem_output.json`。

## 运行方式

项目使用 Java 8 和 Maven，主要依赖 `fastjson`。

编译和测试：

```bash
mvn test -q
mvn package -q
```

运行默认入口：

```bash
java -jar target/DAG-1.0-SNAPSHOT.jar
```

如果在 IntelliJ IDEA 中运行，直接运行 `opt.Main` 即可。默认输入路径在 `Main.DEFAULT_INPUT_PATH` 中配置。

## 输出格式

调度结果 json 只包含已经排好的节点时间：

```json
{
  "nodes": [
    {
      "typename": "compute",
      "name": "%add.18 = add(%p1, %p0)",
      "uuid": "56",
      "startTime": 1,
      "endTime": 56
    }
  ]
}
```

注意：这种输出结果没有 `edges`、`cost`、`space_cost`，不能再直接作为 `DAGGraphReader.readGraphFromJSON(...)` 的输入。新的调度输入必须使用包含 `nodes` 和 `edges` 的原始 DAG json。

## 核心算法

当前 `src/main/java/opt/Algorithm` 中保留的核心算法类如下：

| 类 | 定位 | 调度策略 | 是否考虑内存 | 当前 Main 是否使用 |
|---|---|---|---|---|
| `HybridScheduler_v4_3` | 基础 DAG 构造调度器，适合作为 baseline | 计算 upward rank，并结合 EFT / HEFT 类优先级规则，从 ready 节点中贪心选择一个节点调度 | 否 | 否 |
| `HybridScheduler_v5_8` | 带 capacity 约束的构造调度器，适合做内存/资源容量约束实验 | 使用事件推进调度时间，节点完成后释放或更新后继状态；调度 ready 节点时检查 `resourceUsed + demand <= capacity` | 是 | 否 |
| `HybridScheduler_v6_7` | 当前 makespan 核心构造调度器 | 对 ready 节点按最早开始时间和优先级筛选 topK，然后并行模拟未来贪心调度，选择预估效果最好的候选节点 | 否 | 是 |

辅助优化器：

- `DAGLocalSearch`：第二阶段 makespan 局部优化器，默认接在 `HybridScheduler_v6_7` 后面执行。
- `DAGMemoryOptimizer`：第二阶段内存峰值优化器，只在 `solveAndOptMemory(...)` 中使用。

可以简单理解为：

- `v4_3` 是简单贪心 baseline。
- `v5_8` 是构造阶段就考虑 capacity / memory 的实验版本。
- `v6_7` 是当前默认使用的前瞻构造算法。
- `DAGLocalSearch` 和 `DAGMemoryOptimizer` 是构造完成后的第二阶段优化器。

## 历史方法和实验记录

项目早期尝试过多种调度策略。对应的 `topsort.md`、`topsort+longestpath.md`、`gurobi.md`、`HEFT.md`、`EFT.md`、`HEFT+EFT.md` 等旧说明文件现在已经不保留为单独文档，也没有对应的可直接调用代码。这里直接记录它们的核心思路，作为理解算法演进的参考。

| 历史策略 | 核心思路 | 当前状态 |
|---|---|---|
| topsort + 节点选择规则 | 维护入度为 0 的 ready 节点，优先调度最早可开始的节点，并考虑 communication edge 的处理 | 仅保留思路，不作为当前代码入口 |
| topsort + 最长路径节点优先 | 在拓扑调度基础上优先选择关键路径 / 长剩余路径上的节点，再结合最早开始时间做选择 | 仅保留思路，不作为当前代码入口 |
| Gurobi / MILP | 把开始时间、结束时间、依赖约束、同类型互斥约束和 makespan 目标建成精确优化模型 | 适合小规模对照，当前没有可直接调用的 Java 入口 |
| OR-Tools Python 版本 | 早期 Python 实验目录 `DAG_ortools/`。其中 `scheduler.py` 用 OR-Tools CP-SAT 建模 DAG 调度，`scheduler_gnn.py` 做 GNN 优先级实验，`topsort.py` 是简单拓扑排序脚本，`plot.py` 用于画 DAG 图 | 作为参考保留，不属于当前 Java 主流程；依赖 `ortools`、`torch`、`torch_geometric` 等环境，不保证当前可直接运行 |
| HEFT | 计算 upward rank，优先调度 rank 更高的节点 | 历史 baseline；当前 `v6_7` 仍使用 rank 信息，但不只是静态 HEFT |
| EFT | 对 ready 节点计算当前资源状态下的 earliest finish time，优先调度最早完成的节点 | 历史 baseline |
| HEFT + EFT | 使用类似 `rank - EFT` 的混合优先级，同时考虑关键路径和当前资源可用性 | 是 HybridScheduler 系列之前的重要过渡思路 |

历史实验结果：

| Scheduler | demo | debug_demo | llama_7b | gantt |
|---|---:|---:|---:|---:|
| 文档 | 39 | 5352 | 1691894 | 964923 |
| gurobi | 39 | --- | --- | --- |
| ortools | 39 | --- | --- | --- |
| topsort | 39 | 5142 | 1427217 | 1085773 |
| longestpath | 39 | 5140 | 1421029 | 1085631 |
| HEFT | 42 | 4962 | 1523838 | 990490 |
| EFT | 39 | 5297 | 1417589 | 1150066 |
| HEFT+EFT | 40 | 4939 | 1437059 | 964636 |
| Hybrid_v0 | 40 | 4939 | 1437059 | 964636 |
| Hybrid_v1 | 40 | 4952 | 1358021 | 968504 |
| Hybrid_v2(HEFT) | 42 | 4994 | 1530197 | 984686 |
| Hybrid_v2(EFT) | 39 | 5341 | 1431051 | 1106643 |
| Hybrid_v2 | 42 | 4952 | 1358251 | 964611 |

## 数据目录

常用目录：

- `data/`：原始 DAG 输入文件。
- `data/antopt_ret/`：AntOPT / ReorderPass 相关样例，包含原始输入和已有调度结果。
- `output/`：本项目生成的调度结果。
- `graph/`：部分图和曲线图片。
- `DAG_ortools/`：早期 OR-Tools Python 实验代码，作为参考保留，不保证当前环境可直接运行。

### `data/antopt_ret` 三类文件

`data/antopt_ret` 目录中共有 48 个 json 文件，按文件名前缀分为 3 类。每类都有同一批 `R` 参数文件，例如 `R0.1`、`R0.15`、`R0.2` 到 `R0.85`。

| 文件前缀 | 含义 | json 内容 | 是否可直接作为 `Main` 输入 |
|---|---|---|---|
| `gantt_before_ReorderPassAntOPTDataAnalyseN100R*.json` | ReorderPass / AntOPT 处理前的原始 DAG 输入 | 包含 `nodes` 和 `edges`；节点包含 `cost`、`space_cost`、`opcode` 等字段 | 是 |
| `gantt_0ReorderPassAntOPTDataAnalyseN100R*.json` | ReorderPass / AntOPT 模式 0 生成的已有甘特图调度结果 | 只包含 `nodes`；节点包含 `startTime` 和 `endTime`；不包含 `edges`、`cost`、`space_cost` | 否 |
| `gantt_2ReorderPassAntOPTDataAnalyseN100R*.json` | ReorderPass / AntOPT 模式 2 生成的已有甘特图调度结果 | 只包含 `nodes`；节点包含 `startTime` 和 `endTime`；不包含 `edges`、`cost`、`space_cost` | 否 |

因此，运行当前 `Main.solveAndOpt(...)` 或 `DAGGraphReader.readGraphFromJSON(...)` 时，应使用 `gantt_before_...json` 文件。`gantt_0...json` 和 `gantt_2...json` 是已经排好的结果文件，更适合用于结果对比、甘特图展示和 makespan 统计。

以 `R0.1` 为例：

| 文件 | 节点数 | 边数 | makespan |
|---|---:|---:|---:|
| `gantt_before_ReorderPassAntOPTDataAnalyseN100R0.1.json` | 140 | 240 | 无，原始 DAG 输入 |
| `gantt_0ReorderPassAntOPTDataAnalyseN100R0.1.json` | 140 | 0 | 4951 |
| `gantt_2ReorderPassAntOPTDataAnalyseN100R0.1.json` | 140 | 0 | 4948 |

## Python 后处理脚本

仓库根目录下有三个主要 Python 工具。

### `validate_dag.py`

用途：校验调度结果是否合法。

运行：

```bash
python3 validate_dag.py <原始DAG请求.json> <调度结果.json>
```

检查内容：

- 调度结果中是否包含原始 DAG 的节点。
- 每条依赖边是否满足 `to.startTime >= from.startTime + from.cost + edge.cost`。
- 同一 `typename` 的节点是否发生时间重叠。

### `convert_xla2chrome_trace.py`

用途：把调度结果转换成 Chrome Trace json，用于在 Perfetto 中查看时间线。

运行：

```bash
python3 convert_xla2chrome_trace.py <原始DAG请求.json> <调度结果.json> <trace输出.json>
```

这个脚本支持两种结果格式：

- AntOPT 格式：包含 `finalized_node_start_end_time`。
- 当前项目 / OR-Tools 类格式：包含 `nodes`，每个节点有 `startTime` 和 `endTime`。

查看方式：

1. 运行脚本生成 trace json。
2. 打开 [https://ui.perfetto.dev/](https://ui.perfetto.dev/)。
3. 在 Perfetto UI 中选择生成的 trace json 文件。

trace 中 compute 节点会放在 `tid = 0`，communication 节点会转成 `ncclKernel_...` 并放在 `tid = 1`。

### `calculate.py`

用途：根据原始 DAG 和调度结果计算内存/资源占用曲线。

运行：

```bash
python3 calculate.py <原始DAG请求.json> <调度结果.json> [debug]
```

计算逻辑：

- 节点从 `startTime` 开始占用 `space_cost`。
- 节点自身结束时不立即释放。
- 当一个节点的所有后继节点都结束后，才释放该节点占用的 `space_cost`。

输出内容：

- 最大内存占用，单位包括 bytes、MB、GB。
- 一张 capacity-over-time 曲线图。

注意：当前脚本中的图片保存路径写死为 `/Users/cququantum/Desktop/...`，在其他机器上使用前需要改成自己的输出目录，例如项目内的 `output/` 或 `graph/`。

## 常见注意事项

- `gantt_0...json` 和 `gantt_2...json` 是结果文件，不是原始 DAG 输入。把它们传给 `Main` 或 `DAGGraphReader` 会因为缺少 `edges`、`cost`、`space_cost` 而失败。
- 当前 `Main` 不解析命令行参数。更换输入文件需要修改 `Main.DEFAULT_INPUT_PATH`，或者在代码中直接调用 `solveAndOpt(path, outputPath)`。
- `outputPath = "-1"` 表示自动生成输出文件名。指定其他路径时会直接写入该路径。
- `JsonSaver` 输出的是结果格式，只包含节点的 `startTime/endTime`，不会把原始 `edges` 一起写出。
- 内存优化可能降低峰值内存，但可能增加 makespan，因此没有接入默认的 makespan 优化主流程。

## 研究背景

大模型训练中的计算通信重叠可以抽象成带约束的 DAG 调度问题。节点表示计算或通信操作，边表示依赖和通信代价。同类型资源互斥、通信边互斥、节点内存生命周期等约束会让问题比普通 DAG list scheduling 更复杂。

精确方法如 MILP / Gurobi 可以在小规模问题上求最优解，但随着节点数量增长，求解时间会迅速变大。传统 list scheduling 速度快，但单一优先级规则容易陷入局部选择。当前项目的核心思路是使用带前瞻模拟的构造算法：在每一步不只看当前优先级，而是对 topK 候选节点做有限深度的未来调度模拟，再选择预计 makespan 更好的节点。
# scheduling
