package opt.Algorithm;

import opt.Common.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * DAGMemoryOptimizer_v1 - 优化版本的DAG调度内存优化器
 * 集成了SearchSolution和ScheduleResult之间的转换功能
 * 改进了内存优化逻辑，利用时间窗口分析进行更精确的优化
 */
public class DAGMemoryOptimizer {
    // 内存分析数据结构
    public static class MemoryProfile {
        public double maxMemory; // 修改为double类型
        public int peakTime;
        public String peakNode;
        public Map<Integer, Double> memoryOverTime;

        public MemoryProfile(double maxMemory, int peakTime, String peakNode, Map<Integer, Double> memoryOverTime) {
            this.maxMemory = maxMemory;
            this.peakTime = peakTime;
            this.peakNode = peakNode;
            this.memoryOverTime = memoryOverTime;
        }
    }

    /**
     * 结合结果和内存分析的封装类
     */
    public static class ScheduleWithMemory {
        public List<ScheduleResult> results;
        public MemoryProfile profile;

        public ScheduleWithMemory(List<ScheduleResult> results, MemoryProfile profile) {
            this.results = results;
            this.profile = profile;
        }
    }

    /**
     * 将SearchSolution转换为List<ScheduleResult>
     * 增加了peakNodes参数，来避免这些节点与其他节点并行执行
     *
     * @param _solution 搜索解决方案（其中 solution.solution 为 Map&lt;String, List&lt;String&gt;&gt;，键为类型，值为该类型节点 UUID 的有序列表）
     * @param graph     DAG图
     * @return 包含调度结果和内存分析的 ScheduleWithMemory 对象
     */
    public static ScheduleWithMemory toScheduleResults(SearchSolution _solution, DAGGraph graph) {
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
                MemoryProfile failureProfile = new MemoryProfile(Double.MAX_VALUE, 0, "", new TreeMap<>());
                return new ScheduleWithMemory(new ArrayList<>(), failureProfile);

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
            availableTime.put(type, fTime);


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


        MemoryProfile profile = calculateMemoryUsage(graph, scheduleResults);
        return new ScheduleWithMemory(scheduleResults, profile);
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


    public static void saveMapToTxt(Map<Integer, Double> map, String filePath) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            // 写入标题行（可选）
            writer.write("Time,Memory\n");

            // 遍历Map并写入每一行
            for (Map.Entry<Integer, Double> entry : map.entrySet()) {
                writer.write(entry.getKey() + "," + entry.getValue() + "\n");
            }

            System.out.println("数据已成功保存到 " + filePath);
        } catch (IOException e) {
            System.err.println("保存数据时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 根据调度计算内存使用情况
     */
    /**
     * 根据调度计算内存使用情况（基于Python逻辑重写）
     */
    public static MemoryProfile calculateMemoryUsage(DAGGraph graph, List<ScheduleResult> results) {
        // 创建节点映射以便快速查找
        Map<String, DAGNode> nodeMap = new HashMap<>();
        for (DAGNode node : graph.nodes) {
            nodeMap.put(node.uuid, node);
        }

        // 与Python版本对应的数据结构
        Map<String, Integer> uuidToDuration = new HashMap<>();
        Map<String, Double> uuidToSpacecost = new HashMap<>();
        Map<String, Integer> outDegree = new HashMap<>();
        Map<String, List<String>> preNodes = new HashMap<>();

        // 初始化映射
        for (DAGNode node : graph.nodes) {
            uuidToDuration.put(node.uuid, node.cost);
            uuidToSpacecost.put(node.uuid, node.demand); // demand对应Python的space_cost
            outDegree.put(node.uuid, 0);
            preNodes.put(node.uuid, new ArrayList<>());
        }

        // 计算出度和前驱节点
        for (DAGEdge edge : graph.edges) {
            outDegree.put(edge.from, outDegree.get(edge.from) + 1);
            preNodes.get(edge.to).add(edge.from);
        }

        // 跟踪执行时间点
        int maxTime = 0;
        Map<Integer, List<String>> startTimeMap = new HashMap<>();
        Map<Integer, List<String>> endTimeMap = new HashMap<>();

        for (ScheduleResult result : results) {
            int startTime = result.getStartTime();
            int endTime = result.getFinishTime();
            String uuid = result.getUuid();

            maxTime = Math.max(maxTime, endTime);

            startTimeMap.computeIfAbsent(startTime, k -> new ArrayList<>()).add(uuid);
            endTimeMap.computeIfAbsent(endTime, k -> new ArrayList<>()).add(uuid);
        }

        // 计算随时间变化的内存使用情况
        Map<Integer, Double> memoryOverTime = new TreeMap<>();
        double currentMemory = 0;
        double maxMemory = 0;
        int peakTime = 0;
        String peakNode = "";
        // 模拟执行
        for (int time = 0; time <= maxTime; time++) {
            // 节点开始时：分配内存
            if (startTimeMap.containsKey(time)) {
                for (String uuid : startTimeMap.get(time)) {
                    if (uuidToDuration.get(uuid) != 0) {
                        currentMemory += uuidToSpacecost.get(uuid);

                    }
                }
            }

            // 节点结束时：调整出度并可能释放内存
            if (endTimeMap.containsKey(time)) {
                for (String uuid : endTimeMap.get(time)) {

                    // 减少前驱节点的出度
                    for (String preNode : preNodes.get(uuid)) {
                        outDegree.put(preNode, outDegree.get(preNode) - 1);

                        // 如果前驱节点出度为0且duration不为0，释放内存
                        if (outDegree.get(preNode) == 0 && uuidToDuration.get(preNode) != 0) {
                            double memToRelease = uuidToSpacecost.get(preNode);
                            currentMemory -= memToRelease;

                        }
                    }
                }
            }

            memoryOverTime.put(time, currentMemory);

            // 记录峰值内存使用
            if (currentMemory > maxMemory) {
                maxMemory = currentMemory;
                peakTime = time;

                // 从此时间开始的节点中选择一个作为峰值节点
                if (startTimeMap.containsKey(time) && !startTimeMap.get(time).isEmpty()) {
                    for (String uuid : startTimeMap.get(time)) {
                        if (uuidToDuration.get(uuid) != 0) {
                            peakNode = uuid;
                            break;
                        }
                    }
                }
            }
        }
//        saveMapToTxt(memoryOverTime, "memoryData.txt");

        return new MemoryProfile(maxMemory, peakTime, peakNode, memoryOverTime);
    }

    /**
     * 优化内存使用的改进算法
     * 新的策略：
     * 1. 根据当前调度的内存配置，获得峰值时间 peakTime。
     * 2. 对于当前解中每个类型，在该类型的有序列表中查找第一个“执行”于 peakTime 的节点 T。
     * 3. 对于该类型，计算候选范围[minPosition, maxPosition]，并依次尝试将该范围内的每个候选节点（一次只移动一个）从原位置移除后插入到 T 之前，构造新的解。
     * 4. 对所有不同类型构造的解中，选择内存峰值更低的最佳解。
     *
     * @param graph           DAG图
     * @param results         当前调度结果
     * @param localSearchSize 局部搜索范围大小
     * @return 优化后的调度结果
     */
    public static List<ScheduleResult> optimizeMemoryUsage(DAGGraph graph, List<ScheduleResult> results, int localSearchSize) {
        // 计算初始内存配置
        MemoryProfile profile = calculateMemoryUsage(graph, results);
        System.out.println("初始峰值内存: " + profile.maxMemory + " 在时间 " + profile.peakTime +
                ", 由节点 " + profile.peakNode + " 引起");

        // 将当前调度转换为SearchSolution，便于修改
        SearchSolution currentSolution = toSearchSolution(results);
        // 构造一个UUID到调度结果的映射，便于查找节点的开始/结束时间
        Map<String, ScheduleResult> uuidToResult = new HashMap<>();
        for (ScheduleResult sr : results) {
            uuidToResult.put(sr.getUuid(), sr);
        }
        int peakTime = profile.peakTime;

        // 对每个类型，查找在peakTime执行的节点 T（即：startTime <= peakTime < finishTime）
        // 注意：每个类型只选取第一个满足条件的节点
        Map<String, Integer> typeToTIndex = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : currentSolution.solution.entrySet()) {
            String type = entry.getKey();
            List<String> nodeList = entry.getValue();
            for (int i = 0; i < nodeList.size(); i++) {
                String uuid = nodeList.get(i);
                if (uuidToResult.containsKey(uuid)) {
                    ScheduleResult sr = uuidToResult.get(uuid);
                    if (sr.getStartTime() <= peakTime && peakTime < sr.getFinishTime()) {
                        typeToTIndex.put(type, i);
                        break; // 选取第一个满足条件的节点作为 T
                    }
                }
            }
        }

        // 记录最佳解及其内存峰值
        List<ScheduleResult> bestResults = results;
        double bestMemory = profile.maxMemory;

        // 针对每个有 T 的类型进行候选节点的插入尝试
        for (Map.Entry<String, Integer> typeEntry : typeToTIndex.entrySet()) {
            String type = typeEntry.getKey();
            int T_index = typeEntry.getValue();
            List<String> typeNodes = currentSolution.solution.get(type);
            int minPos = Math.max(0, T_index - localSearchSize);
            int maxPos = Math.min(typeNodes.size() - 1, T_index + localSearchSize);

            // 保存当前类型中 T 的 uuid（用于后续查找其位置）
            String T_uuid = typeNodes.get(T_index);

            // 对候选范围内的每个节点（排除 T 本身）尝试移动到 T 之前
            for (int candidatePos = minPos; candidatePos <= maxPos; candidatePos++) {
                if (candidatePos == T_index) continue; // 跳过 T 自身

                // 创建 SearchSolution 的深拷贝
                SearchSolution testSolution = new SearchSolution();
                testSolution.solution = new HashMap<>();
                for (Map.Entry<String, List<String>> e : currentSolution.solution.entrySet()) {
                    testSolution.solution.put(e.getKey(), new ArrayList<>(e.getValue()));
                }
                // 针对当前类型，修改其节点顺序
                List<String> newTypeNodes = testSolution.solution.get(type);
                // 记录候选节点
                String candidateNode = newTypeNodes.get(candidatePos);
                // 移除候选节点
                newTypeNodes.remove(candidatePos);
                // 重新查找 T 在当前列表中的索引（注意候选节点移除后索引可能已发生变化）
                int newT_index = newTypeNodes.indexOf(T_uuid);
                // 将候选节点插入到 T 之前（即插入位置为 newT_index 位置）
                newTypeNodes.add(newT_index, candidateNode);

                // 转换为调度结果，并计算内存使用情况
                ScheduleWithMemory scheduleWithMemory = toScheduleResults(testSolution, graph);
                MemoryProfile testProfile = scheduleWithMemory.profile;
                System.out.println("类型 [" + type + "] 中将节点 " + candidateNode + " 移动到节点 " + T_uuid +
                        " 之前，新峰值内存 = " + String.format("%.0f", testProfile.maxMemory == Double.MAX_VALUE ? -1 : testProfile.maxMemory));

                // 如果新解内存峰值降低，则更新最佳解
                if (testProfile.maxMemory < bestMemory) {
                    bestMemory = testProfile.maxMemory;
                    bestResults = scheduleWithMemory.results;
                }
            }
        }

        if (bestMemory < profile.maxMemory) {
            System.out.println("找到更好的解，优化后的峰值内存: " + bestMemory +
                    " (减少了 " + (profile.maxMemory - bestMemory) + ")");
        } else {
            System.out.println("未找到更好的解。保持原始调度。");
        }
        return bestResults;
    }

    /**
     * 原始的toScheduleResults方法保留，用于初始解的生成
     */
//    public static ScheduleWithMemory toScheduleResults(SearchSolution solution, DAGGraph graph) {
//        // 使用空的peakNodes集合，相当于不应用峰值节点约束
//        return toScheduleResults(solution, graph, new HashSet<>());
//    }


    /**
     * 尝试多次迭代优化以获得更好的结果
     */
    public static List<ScheduleResult> iterativeOptimize(DAGGraph graph, List<ScheduleResult> results, int maxIterations) {
        List<ScheduleResult> currentBest = results;
        MemoryProfile initialProfile = calculateMemoryUsage(graph, currentBest);
        double initialPeakMemory = initialProfile.maxMemory;
        System.out.println("开始迭代优化。初始峰值内存: " + initialPeakMemory);
        for (int i = 0; i < maxIterations; i++) {
            List<ScheduleResult> optimized = optimizeMemoryUsage(graph, currentBest, Parameter.localSearchSize);
            MemoryProfile optimizedProfile = calculateMemoryUsage(graph, optimized);
            if (optimizedProfile.maxMemory < initialProfile.maxMemory) {
                System.out.println("迭代 " + (i + 1) + ": 内存从 " +
                        initialProfile.maxMemory + " 减少到 " + optimizedProfile.maxMemory);
                currentBest = optimized;
                initialProfile = optimizedProfile;
            } else {
                System.out.println("迭代 " + (i + 1) + ": 没有进一步改进。停止。");
                break;
            }
        }
        MemoryProfile finalProfile = calculateMemoryUsage(graph, currentBest);
        System.out.println("优化完成。最终峰值内存: " + finalProfile.maxMemory +
                " (减少了 " + (initialPeakMemory - finalProfile.maxMemory) + ")");
        return currentBest;
    }

    /**
     * 打印内存配置文件信息
     */
    public static void printMemoryProfile(MemoryProfile profile) {
        System.out.println("最大内存: " + String.format("%.1f", profile.maxMemory));
        System.out.println("最大内存 (MB): " + String.format("%.1f", profile.maxMemory / 1024.0 / 1024.0));
        System.out.println("最大内存 (GB): " + String.format("%.1f", profile.maxMemory / 1024.0 / 1024.0 / 1024.0));

        System.out.println("峰值时间: " + profile.peakTime);
        System.out.println("峰值节点: " + profile.peakNode);
    }

    /**
     * 获取调度的makespan（最晚节点的结束时间）
     */
    public static int getScheduleMakespan(List<ScheduleResult> results) {
        int makespan = 0;
        for (ScheduleResult result : results) {
            makespan = Math.max(makespan, result.getFinishTime());
        }
        return makespan;
    }
}
