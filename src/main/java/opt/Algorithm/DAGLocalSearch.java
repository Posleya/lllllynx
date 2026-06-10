package opt.Algorithm;

import opt.Common.*;
import opt.Utils.JsonSaver;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * DAG图的局部搜索优化类
 */
public class DAGLocalSearch {

    /**
     * 将SearchSolution转换为List<ScheduleResult>
     * 增加了peakNodes参数，来避免这些节点与其他节点并行执行
     *
     * @param _solution 搜索解决方案（其中 solution.solution 为 Map&lt;String, List&lt;String&gt;&gt;，键为类型，值为该类型节点 UUID 的有序列表）
     * @param graph     DAG图
     * @return 包含调度结果和内存分析的 ScheduleWithMemory 对象
     */
    public static List<ScheduleResult> toScheduleResults(SearchSolution _solution, DAGGraph graph) {
        // 1. 构造每种类型对应的有序列表（直接使用 solution 中的顺序）
        Map<String, List<String>> solution = _solution.solution;
        int nodeNum = 0;
        for (List<String> list : solution.values()) {
            nodeNum += list.size();
        }

        Map<String, Integer> pointer = new HashMap<>();
        for (String type : solution.keySet()) {
            pointer.put(type, 0);
        }


        // 构建图的数据结构
        Map<String, DAGNode> uuidToNode = new HashMap<>();
        Map<String, List<DAGEdge>> succList = new HashMap<>();
        Map<String, List<DAGEdge>> predList = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        // 初始化映射
        for (DAGNode node : graph.nodes) {
            uuidToNode.put(node.uuid, node);
            succList.put(node.uuid, new ArrayList<>());
            predList.put(node.uuid, new ArrayList<>());
            inDegree.put(node.uuid, 0);
        }

        // 构建邻接列表和入度计数
        for (DAGEdge edge : graph.edges) {
            succList.get(edge.from).add(edge);
            predList.get(edge.to).add(edge);
            inDegree.put(edge.to, inDegree.get(edge.to) + 1);
        }

        // 初始化开始时间、结束时间和资源可用性
        Map<String, Integer> startTime = new HashMap<>();
        Map<String, Integer> finishTime = new HashMap<>();
        Map<String, Integer> availableTime = new HashMap<>();

        // 初始化每种类型的资源可用性
        Set<String> types = new HashSet<>();
        for (DAGNode node : graph.nodes) {
            types.add(node.typename);
        }
        for (String type : types) {
            availableTime.put(type, 0);
        }

        // 主调度循环
        while (startTime.size() < nodeNum) {
            List<String> readyNodes = new ArrayList<>();
            for (String type : solution.keySet()) {
                List<String> list = solution.get(type);
                int p = pointer.get(type);
                if (p < list.size()) {
                    String uuid = list.get(p);
                    if (inDegree.get(uuid) == 0) {
                        readyNodes.add(uuid);
                    }

                }
            }

            if (readyNodes.isEmpty()) {
                return new ArrayList<>();
            }
            // 选择具有最高优先级的节点
            String nodeToSchedule = readyNodes.get(0);


            // 计算每个可调度节点的最早开始时间和最早结束时间
            Map<String, Integer> earliestStartTimes = new HashMap<>();
            Map<String, Integer> earliestFinishTimes = new HashMap<>();
            for (String uuid : readyNodes) {
                int estWithResource = computeEarliestStartTime(uuid, predList, finishTime, uuidToNode, availableTime);
                earliestStartTimes.put(uuid, estWithResource);
                int eftWithResource = estWithResource + uuidToNode.get(uuid).cost;
                earliestFinishTimes.put(uuid, eftWithResource);
            }

            // 调度节点
            DAGNode node = uuidToNode.get(nodeToSchedule);
            int estWithResource = earliestStartTimes.get(nodeToSchedule);
            int nodeCost = node.cost;
            String type = node.typename;

            pointer.put(type, pointer.get(type) + 1);

            // 更新开始和结束时间
            startTime.put(nodeToSchedule, estWithResource);
            int fTime = estWithResource + nodeCost;
            finishTime.put(nodeToSchedule, fTime);

            // 更新资源可用性
            availableTime.put(type, Integer.max(fTime, availableTime.get(type)));


            // 更新后继节点的入度
            for (DAGEdge edge : succList.get(nodeToSchedule)) {
                String succUuid = edge.to;
                inDegree.put(succUuid, inDegree.get(succUuid) - 1);
            }
        }

        // 准备调度结果
        List<ScheduleResult> scheduleResults = new ArrayList<>();
        for (String uuid : uuidToNode.keySet()) {
            DAGNode node = uuidToNode.get(uuid);
            int sTime = startTime.get(uuid);
            int fTime = finishTime.get(uuid);
            scheduleResults.add(new ScheduleResult(uuid, node.name, node.typename, sTime, fTime));
        }

        // 按开始时间排序
        scheduleResults.sort(Comparator.comparingInt(r -> r.startTime));

        return scheduleResults;
    }


    /**
     * 计算节点最早开始时间，考虑前驱完成时间和资源可用性。
     */
    private static int computeEarliestStartTime(String uuid,
                                                Map<String, List<DAGEdge>> predList,
                                                Map<String, Integer> finishTime,
                                                Map<String, DAGNode> uuidToNode,
                                                Map<String, Integer> availableTime) {
        DAGNode node = uuidToNode.get(uuid);
        String type = node.typename;

        int earliestStart = 0;
        for (DAGEdge edge : predList.get(uuid)) {
            String predUuid = edge.from;
            int predFinish = finishTime.get(predUuid);
            int edgeCost = edge.cost;
            int arrivalTime = predFinish + edgeCost;
            if (arrivalTime > earliestStart) {
                earliestStart = arrivalTime;
            }
        }

        int resourceAvailableTime = availableTime.get(type);

        if (node.cost == 0) {
            // 零成本节点不考虑资源占用
            return earliestStart;
        } else {
            return Math.max(earliestStart, resourceAvailableTime);
        }
    }

    /**
     * 将List<ScheduleResult>转换为SearchSolution
     *
     * @param results ScheduleResult对象列表
     * @return SearchSolution对象
     */
    public static SearchSolution toSearchSolution(List<ScheduleResult> results) {
        SearchSolution solution = new SearchSolution();
        solution.solution = new HashMap<>();

        for (ScheduleResult result : results) {
            String typename = result.getTypename();
            String uuid = result.getUuid();

            if (!solution.solution.containsKey(typename)) {
                solution.solution.put(typename, new ArrayList<>());
            }
            solution.solution.get(typename).add(uuid);
        }

        return solution;
    }

    /**
     * 表示局部搜索结果的内部类
     */
    private static class LocalSearchResult {
        final List<ScheduleResult> results;
        final int makespan;

        public LocalSearchResult(List<ScheduleResult> results, int makespan) {
            this.results = results;
            this.makespan = makespan;
        }
    }

    /**
     * 对调度结果进行迭代优化，通过并行局部搜索降低makespan
     *
     * @param graph DAG图
     * @param results 初始调度结果
     * @param iterations 优化迭代次数
     * @return 优化后的调度结果
     */
    public static List<ScheduleResult> iterativeOptimize(DAGGraph graph, List<ScheduleResult> results, int iterations) {
        List<ScheduleResult> bestResults = new ArrayList<>(results);
        int bestMakespan = calculateMakespan(bestResults);

        for (int iter = 0; iter < iterations; iter++) {

            boolean improved = false;

            // 将当前最佳结果转换为SearchSolution
            SearchSolution currentSolution = toSearchSolution(bestResults);

            // 获取communication类型的节点列表
            List<String> commNodes = currentSolution.solution.getOrDefault("communication", new ArrayList<>());
            if (commNodes.isEmpty()) {
                // 如果没有communication节点，提前退出
                break;
            }

            // 对每个communication节点尝试并行局部搜索
            for (int i = 0; i < commNodes.size(); i++) {
                String nodeUuid = commNodes.get(i);
                List<String> originalCommNodes = new ArrayList<>(commNodes);
                final int nodeIndex = i;

                // 并行尝试将节点移动到不同位置
                List<CompletableFuture<LocalSearchResult>> futures = new ArrayList<>();

                for (int newPos = Math.max(0, i - Parameter.localSearchSize);
                     newPos <= Math.min(commNodes.size() - 1, i + Parameter.localSearchSize);
                     newPos++) {

                    if (newPos == i) continue; // 跳过原始位置

                    final int targetPos = newPos;

                    // 创建异步任务评估新位置
                    CompletableFuture<LocalSearchResult> future = CompletableFuture.supplyAsync(() -> {
                        // 创建新的节点排序
                        List<String> newCommNodes = new ArrayList<>(originalCommNodes);
                        newCommNodes.remove(nodeIndex);
                        newCommNodes.add(targetPos, nodeUuid);

                        // 创建新的解决方案
                        SearchSolution newSolution = new SearchSolution();
                        newSolution.solution = new HashMap<>(currentSolution.solution);
                        newSolution.solution.put("communication", newCommNodes);

                        // 转换回调度结果并计算makespan
                        List<ScheduleResult> newResults = toScheduleResults(newSolution, graph);
                        if (newResults.isEmpty()) {
                            // 无效的调度方案，返回极大值makespan
                            return new LocalSearchResult(new ArrayList<>(), Integer.MAX_VALUE);
                        }

                        int newMakespan = calculateMakespan(newResults);
                        return new LocalSearchResult(newResults, newMakespan);
                    });

                    futures.add(future);
                }

                try {
                    // 等待所有并行任务完成并获取结果
                    CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                            futures.toArray(new CompletableFuture[0])
                    );

                    // 等待所有任务完成
                    allFutures.join();

                    // 查找最佳结果
                    LocalSearchResult bestLocalResult = null;
                    for (CompletableFuture<LocalSearchResult> future : futures) {
                        LocalSearchResult result = future.get();
                        if (result.results.isEmpty()) continue;

                        if (bestLocalResult == null || result.makespan < bestLocalResult.makespan) {
                            bestLocalResult = result;
                        }
                    }

                    // 如果找到了更好的解决方案，更新最佳结果
                    if (bestLocalResult != null && bestLocalResult.makespan < bestMakespan) {
                        bestMakespan = bestLocalResult.makespan;
                        bestResults = bestLocalResult.results;
                        improved = true;
                        break;
                    }

                } catch (InterruptedException | ExecutionException e) {
                    // 处理异常，继续使用当前最佳结果
                    e.printStackTrace();
                }

                if (improved) {
                    // 如果在当前节点找到了改进，跳出内层循环
                    break;
                }
            }

            System.out.println("Iteration " + iter + " / " + iterations + ": best makespan = " + bestMakespan);
            if (!improved) {
                // 如果一次迭代中没有找到改进，提前退出
                System.out.println("No improvement found in iteration " + iter);
                break;
            }
        }

        SearchSolution _S = toSearchSolution(bestResults);
        List<ScheduleResult> res = toScheduleResults(_S, graph);

        return bestResults;
    }

    /**
     * 计算调度方案的makespan（最大完成时间）
     *
     * @param results 调度结果
     * @return makespan值
     */
    private static int calculateMakespan(List<ScheduleResult> results) {
        return results.stream()
                .mapToInt(r -> r.finishTime)
                .max()
                .orElse(0);
    }
}