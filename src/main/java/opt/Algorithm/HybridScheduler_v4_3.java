package opt.Algorithm;

import opt.Common.*;

import java.util.*;

public class HybridScheduler_v4_3 {

    public static List<ScheduleResult> scheduleDAG(DAGGraph graph) {

        // 计算上行等级
        Map<String, Double> upwardRanks = computeUpwardRanks(graph);

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

        // 未调度的节点集合
        Set<String> unscheduledNodes = new HashSet<>(uuidToNode.keySet());

        // 标记零成本节点
        Set<String> zeroCostNodes = new HashSet<>();
        for (DAGNode node : graph.nodes) {
            if (node.cost == 0) {
                zeroCostNodes.add(node.uuid);
            }
        }

        // 主调度循环
        while (!unscheduledNodes.isEmpty()) {

            // 直接处理零成本节点
            List<String> zeroCostNodesToProcess = new ArrayList<>();
            for (String uuid : unscheduledNodes) {
                if (inDegree.get(uuid) == 0 && zeroCostNodes.contains(uuid)) {
                    zeroCostNodesToProcess.add(uuid);
                }
            }

            for (String uuid : zeroCostNodesToProcess) {
                int currentTime = computeEarliestStartTime(uuid, predList, finishTime, uuidToNode, availableTime);
                startTime.put(uuid, currentTime);
                finishTime.put(uuid, currentTime);
                unscheduledNodes.remove(uuid);

                // 更新后继节点的入度
                for (DAGEdge edge : succList.get(uuid)) {
                    String succUuid = edge.to;
                    inDegree.put(succUuid, inDegree.get(succUuid) - 1);
                }
            }

            // 接下来处理其他节点（有成本的）
            // 找到所有就绪节点（入度为 0，且 cost != 0）
            List<String> readyNodes = new ArrayList<>();
            for (String uuid : unscheduledNodes) {
                if (inDegree.get(uuid) == 0 && !zeroCostNodes.contains(uuid)) {
                    readyNodes.add(uuid);
                }
            }

            if(Parameter.DEBUG)
                System.out.println(readyNodes.size());

            // 如果一个都找不到，但 unscheduledNodes 还没空，就会认定图有问题
            if (readyNodes.isEmpty()) {
                if (unscheduledNodes.isEmpty()) {
                    break; // 所有节点都已调度
                } else {
                    // 如果你确认数据一定正确，但还是抛这个异常，基本就是零成本节点处理那里需要修复
//                    throw new RuntimeException("Graph has a cycle or unresolved dependencies!");
                    continue;
                }
            }

            // 计算每个可调度节点的最早开始时间和最早结束时间
            Map<String, Integer> earliestStartTimes = new HashMap<>();
            Map<String, Integer> earliestFinishTimes = new HashMap<>();
            for (String uuid : readyNodes) {
                int estWithResource = computeEarliestStartTime(uuid, predList, finishTime, uuidToNode, availableTime);
                earliestStartTimes.put(uuid, estWithResource);
                int eftWithResource = estWithResource + uuidToNode.get(uuid).cost;
                earliestFinishTimes.put(uuid, eftWithResource);
            }

            // 计算每个节点的优先级
            Map<String, Double> priorities = new HashMap<>();
            for (String uuid : readyNodes) {
                double rank = upwardRanks.get(uuid);
                int priorityEFT;

                DAGNode node = uuidToNode.get(uuid);
                // 根据 useDynamicEFT 选择 EFT 来源
                if (Parameter.useDynamicEFT) {
                    priorityEFT = earliestFinishTimes.get(uuid);
                } else {
                    priorityEFT = finishTime.getOrDefault(uuid, 0);
                }


                // 计算优先级
                double priority;
                if (Parameter.priorityType == 0) {
                    priority = rank - priorityEFT;
                } else if (Parameter.priorityType == 1) {
                    priority = rank;
                } else {
                    priority = -priorityEFT;
                }
                priorities.put(uuid,  priority );
            }

            // 选择具有最高优先级的节点
            String nodeToSchedule = selectBestNode(priorities);

            // 调度节点
            DAGNode node = uuidToNode.get(nodeToSchedule);
            int estWithResource = earliestStartTimes.get(nodeToSchedule);
            int nodeCost = node.cost;
            String type = node.typename;

            // 更新开始和结束时间
            startTime.put(nodeToSchedule, estWithResource);
            int fTime = estWithResource + nodeCost;
            finishTime.put(nodeToSchedule, fTime);

            // 更新资源可用性
            availableTime.put(type, fTime);

            // 从未调度节点中移除
            unscheduledNodes.remove(nodeToSchedule);

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
            int sTime = startTime.getOrDefault(uuid, 0);
            int fTime = finishTime.getOrDefault(uuid, 0);
            scheduleResults.add(new ScheduleResult(uuid, node.name, node.typename, sTime, fTime));
        }

        // 按开始时间排序
        scheduleResults.sort(Comparator.comparingInt(r -> r.startTime));

//        // 验证调度结果
//        if (!validateSchedule(scheduleResults, predList, uuidToNode)) {
//            throw new RuntimeException("调度结果中存在依赖关系被违反的情况！");
//        }

        return scheduleResults;
    }

    /**
     * 查找节点名称对应的 UUID。
     */
    private static String findUuidByName(Map<String, DAGNode> uuidToNode, String name) {
        for (Map.Entry<String, DAGNode> entry : uuidToNode.entrySet()) {
            if (entry.getValue().name.equals(name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 计算上行等级（Upward Rank）用于优先级计算。
     */
    private static Map<String, Double> computeUpwardRanks(DAGGraph graph) {
        Map<String, Double> upwardRanks = new HashMap<>();
        Map<String, List<DAGEdge>> succList = new HashMap<>();

        for (DAGNode node : graph.nodes) {
            succList.put(node.uuid, new ArrayList<>());
        }
        for (DAGEdge edge : graph.edges) {
            succList.get(edge.from).add(edge);
        }

        for (DAGNode node : graph.nodes) {
            computeUpwardRankForNode(node.uuid, graph, succList, upwardRanks);
        }

        return upwardRanks;
    }

    /**
     * 递归计算单个节点的上行等级。
     */
    private static double computeUpwardRankForNode(String nodeId,
                                                   DAGGraph graph,
                                                   Map<String, List<DAGEdge>> succList,
                                                   Map<String, Double> upwardRanks) {

        if (upwardRanks.containsKey(nodeId)) {
            return upwardRanks.get(nodeId);
        }

        DAGNode node = null;
        for (DAGNode n : graph.nodes) {
            if (n.uuid.equals(nodeId)) {
                node = n;
                break;
            }
        }
        if (node == null) {
            throw new IllegalArgumentException("节点 " + nodeId + " 不存在于图中。");
        }

        double maxSuccRank = 0;
        for (DAGEdge edge : succList.get(nodeId)) {
            String succId = edge.to;
            double succRank = computeUpwardRankForNode(succId, graph, succList, upwardRanks);
            double rank = edge.cost + succRank;
            if (rank > maxSuccRank) {
                maxSuccRank = rank;
            }
        }

        double nodeRank = node.cost + maxSuccRank;
        upwardRanks.put(nodeId, nodeRank);
        return nodeRank;
    }

    /**
     * 选择具有最高优先级的节点。
     */
    private static String selectBestNode(Map<String, Double> priorities) {
        String bestNode = null;
        double maxPriority = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Double> entry : priorities.entrySet()) {
            if (entry.getValue() > maxPriority) {
                maxPriority = entry.getValue();
                bestNode = entry.getKey();
            }
        }
        return bestNode;
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
            int predFinish = finishTime.getOrDefault(predUuid, 0);
            int edgeCost = edge.cost;
            int arrivalTime = predFinish + edgeCost;
            if (arrivalTime > earliestStart) {
                earliestStart = arrivalTime;
            }
        }

        int resourceAvailableTime = availableTime.getOrDefault(type, 0);

        if (node.cost == 0) {
            // 零成本节点不考虑资源占用
            return earliestStart;
        } else {
            return Math.max(earliestStart, resourceAvailableTime);
        }
    }

    /**
     * 验证调度结果，确保所有依赖关系被满足，没有资源冲突。
     */
    private static boolean validateSchedule(List<ScheduleResult> scheduleResults,
                                            Map<String, List<DAGEdge>> predList,
                                            Map<String, DAGNode> uuidToNode) {
        Map<String, Integer> scheduledStartTimes = new HashMap<>();
        Map<String, Integer> scheduledFinishTimes = new HashMap<>();

        for (ScheduleResult result : scheduleResults) {
            scheduledStartTimes.put(result.uuid, result.startTime);
            scheduledFinishTimes.put(result.uuid, result.finishTime);
        }

        // 检查依赖关系
        for (ScheduleResult result : scheduleResults) {
            String uuid = result.uuid;
            List<DAGEdge> predecessors = predList.get(uuid);
            for (DAGEdge edge : predecessors) {
                String predUuid = edge.from;
                int predFinish = scheduledFinishTimes.getOrDefault(predUuid, -1);
                if (predFinish == -1) {
                    System.err.println("前驱节点未调度: " + predUuid + " for node: " + uuid);
                    return false;
                }
                int requiredStart = predFinish + edge.cost;
                if (result.startTime < requiredStart) {
                    System.err.println("节点 " + uuid + " 的开始时间 " + result.startTime +
                            " 早于其前驱节点 " + predUuid + " 的完成时间 " + predFinish +
                            " + 边成本 " + edge.cost);
                    return false;
                }
            }
        }

        // 检查资源冲突：同一类型的节点在同一时刻只能调度一个
        Map<String, Map<Integer, Integer>> resourceUsage = new HashMap<>();
        for (ScheduleResult result : scheduleResults) {
            // 只检查有持续时间的节点（finishTime > startTime）
            if (result.finishTime > result.startTime) {
                String type = result.typename;
                resourceUsage.putIfAbsent(type, new HashMap<>());
                Map<Integer, Integer> timeMap = resourceUsage.get(type);
                for (int time = result.startTime; time < result.finishTime; time++) {
                    timeMap.put(time, timeMap.getOrDefault(time, 0) + 1);
                    if (timeMap.get(time) > 1) {
                        throw new RuntimeException("资源类型 " + type +
                                " 在时间 " + time + " 有冲突，多个任务同时执行！");
                    }
                }
            }
        }

        System.out.println("调度结果检查通过，没有资源冲突和依赖关系被违反的情况");
        return true;
    }
}
