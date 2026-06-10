package opt.Algorithm;

import opt.Common.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class HybridScheduler_v6_7 {

    public static List<ScheduleResult> scheduleDAG(DAGGraph graph) {
//        Map<String, Double> heftSave = new HashMap<>();
//        Map<String, Integer> eftSave = new HashMap<>();
        // 1) 计算上行等级
        Map<String, Double> upwardRanks = computeUpwardRanks(graph);

        // 2) 构建图的数据结构
        Map<String, DAGNode> uuidToNode = new HashMap<>();
        Map<String, List<DAGEdge>> succList = new HashMap<>();
        Map<String, List<DAGEdge>> predList = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (DAGNode node : graph.nodes) {
            uuidToNode.put(node.uuid, node);
            succList.put(node.uuid, new ArrayList<>());
            predList.put(node.uuid, new ArrayList<>());
            inDegree.put(node.uuid, 0);
        }
        for (DAGEdge edge : graph.edges) {
            succList.get(edge.from).add(edge);
            predList.get(edge.to).add(edge);
            inDegree.put(edge.to, inDegree.get(edge.to) + 1);
        }

        // 3) 开始/结束时间、资源可用性
        Map<String, Integer> startTime = new HashMap<>();
        Map<String, Integer> finishTime = new HashMap<>();
        Map<String, Integer> availableTime = new HashMap<>();

        // 初始化资源类型
        Set<String> types = new HashSet<>();
        for (DAGNode node : graph.nodes) {
            types.add(node.typename);
        }
        for (String type : types) {
            availableTime.put(type, 0);
        }

        // 4) 未调度节点 & 零成本节点
        Set<String> unscheduledNodes = new HashSet<>(uuidToNode.keySet());
        Set<String> zeroCostNodes = new HashSet<>();
        for (DAGNode node : graph.nodes) {
            if (node.cost == 0) {
                zeroCostNodes.add(node.uuid);
            }
        }

        int flag = 0;

        // 5) 主调度循环
        while (!unscheduledNodes.isEmpty()) {
            int startTimeSize = startTime.size();
            if (startTimeSize >= flag) {
                System.out.println(startTimeSize + "/" + graph.nodes.size());
                flag = startTimeSize + 100;
            }
            // (A) 先处理零成本节点
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

                for (DAGEdge edge : succList.get(uuid)) {
                    String succUuid = edge.to;
                    inDegree.put(succUuid, inDegree.get(succUuid) - 1);
                }
            }

            // (B) 处理有成本的就绪节点
            List<String> readyNodes = new ArrayList<>();
            for (String uuid : unscheduledNodes) {
                if (inDegree.get(uuid) == 0 && !zeroCostNodes.contains(uuid)) {
                    readyNodes.add(uuid);
                }
            }
            if (readyNodes.isEmpty()) {
                if (unscheduledNodes.isEmpty()) {
                    break; // 所有节点都已调度
                } else {
                    continue;
                }
            }

            // 计算最早开始/结束时间 & 基础优先级
            Map<String, Integer> earliestStartTimes = new HashMap<>();
            Map<String, Integer> earliestFinishTimes = new HashMap<>();
            Map<String, Double> basePriorities = new HashMap<>();

            for (String uuid : readyNodes) {
                int estWithResource = computeEarliestStartTime(uuid, predList, finishTime, uuidToNode, availableTime);
                earliestStartTimes.put(uuid, estWithResource);
                int eftWithResource = estWithResource + uuidToNode.get(uuid).cost;
                earliestFinishTimes.put(uuid, eftWithResource);

                // 用来排序拿 topK
                double rank = upwardRanks.get(uuid);
                int priorityEFT = eftWithResource;

                double p;
                p = rank - priorityEFT;
                basePriorities.put(uuid, p);
            }

            // (C) 排序并取 topK
            int topK = Parameter.topK; // 在Parameter类中定义

            readyNodes.sort((a, b) -> {
                // 首先比较estWithResource，小的优先
                int estCompare = Integer.compare(earliestStartTimes.get(a), earliestStartTimes.get(b));
                if (estCompare != 0) {
                    return estCompare;
                }
                // 若estWithResource相等，则比较basePriorities，大的优先
                return Double.compare(basePriorities.get(b), basePriorities.get(a));
            });

            List<String> candidateNodes = readyNodes.subList(0, Math.min(topK, readyNodes.size()));

//            // 如果readyNodes中有不在topK内的节点，随机选择一个添加到候选列表
//            if (readyNodes.size() > topK) {
//                int randomIndex = topK + Parameter.random.nextInt(readyNodes.size() - topK);
//                String randomNode = readyNodes.get(randomIndex);
//                candidateNodes.add(randomNode);
//            }

            // (D) 使用CompletableFuture进行并行模拟
            List<CompletableFuture<CandidateResult>> futures = new ArrayList<>();

            for (String candidate : candidateNodes) {
                CompletableFuture<CandidateResult> future = CompletableFuture.supplyAsync(() -> {
                    // === 拷贝当前状态 ===
                    Map<String, Integer> copyStartTime = new HashMap<>(startTime);
                    Map<String, Integer> copyFinishTime = new HashMap<>(finishTime);
                    Map<String, Integer> copyAvailableTime = new HashMap<>(availableTime);
                    Set<String> copyUnscheduled = new HashSet<>(unscheduledNodes);
                    Map<String, Integer> copyInDegree = new HashMap<>(inDegree);


                    // 先"假设"调度 candidate
                    DAGNode node = uuidToNode.get(candidate);
                    int estCandidate = earliestStartTimes.get(candidate);
                    int finishCandidate = estCandidate + node.cost;

                    copyStartTime.put(candidate, estCandidate);
                    copyFinishTime.put(candidate, finishCandidate);
                    copyAvailableTime.put(node.typename, finishCandidate);
                    copyUnscheduled.remove(candidate);

                    for (DAGEdge edge : succList.get(candidate)) {
                        copyInDegree.put(edge.to, copyInDegree.get(edge.to) - 1);
                    }

                    // 计算当前的makespan（补全前）
                    int initialMakespan = 0;
                    for (Integer ft : finishTime.values()) {
                        if (ft > initialMakespan) {
                            initialMakespan = ft;
                        }
                    }

                    // 然后用贪心模拟调度剩余节点
                    double efficiencyRatio = simulateGreedySchedule(
                            copyUnscheduled,
                            copyInDegree,
                            copyStartTime,
                            copyFinishTime,
                            copyAvailableTime,
                            uuidToNode,
                            succList,
                            predList,
                            upwardRanks,
                            initialMakespan,
                            node.cost
                    );

                    return new CandidateResult(candidate, efficiencyRatio);
                });

                futures.add(future);
            }

            String bestNode = null;
            double bestRatio = Double.MAX_VALUE;

            try {
                // 等待所有CompletableFuture完成
                CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                        futures.toArray(new CompletableFuture[0])
                );

                // 获取所有结果
                allFutures.join(); // 等待所有任务完成

                // 处理结果
                for (CompletableFuture<CandidateResult> future : futures) {
                    CandidateResult res = future.get(); // 不会阻塞，因为已经使用join等待所有任务完成
                    if (res.efficiencyRatio < bestRatio ||
                            (res.efficiencyRatio == bestRatio && succList.get(res.candidate).size() > succList.get(bestNode).size())) {
                        bestRatio = res.efficiencyRatio;
                        bestNode = res.candidate;
                    }
                }
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                // 出现异常的话，根据需求处理，这里简单跳过
            }

            // (E) 选定 bestNode 并更新真实调度状态
            if (bestNode != null) {
//                heftSave.put(bestNode, upwardRanks.get(bestNode));
//                eftSave.put(bestNode, earliestFinishTimes.get(bestNode));
                readyNodes.remove(bestNode);
//                graph.candidateNodesMap.put(bestNode, readyNodes);
                DAGNode finalNode = uuidToNode.get(bestNode);
                int realEst = earliestStartTimes.get(bestNode);
                int realFinish = realEst + finalNode.cost;

                startTime.put(bestNode, realEst);
                finishTime.put(bestNode, realFinish);
                availableTime.put(finalNode.typename, realFinish);

                unscheduledNodes.remove(bestNode);
                for (DAGEdge edge : succList.get(bestNode)) {
                    String succUuid = edge.to;
                    inDegree.put(succUuid, inDegree.get(succUuid) - 1);
                }
            }
        }

        // 6) 组装结果并排序
        List<ScheduleResult> scheduleResults = new ArrayList<>();
        for (String uuid : uuidToNode.keySet()) {
            DAGNode node = uuidToNode.get(uuid);
            int sTime = startTime.getOrDefault(uuid, 0);
            int fTime = finishTime.getOrDefault(uuid, 0);
            scheduleResults.add(new ScheduleResult(uuid, node.name, node.typename, sTime, fTime));
        }
        scheduleResults.sort(Comparator.comparingInt(r -> r.startTime));

//        // 文件名
//        String fileName = "heft.txt";
//
//        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
//            // 遍历 HashMap 并将每个键值对写入文件
//            for (Map.Entry<String, Double> entry : heftSave.entrySet()) {
//                writer.write(entry.getKey() + "=" + entry.getValue());
//                writer.newLine(); // 写入换行符
//            }
//            System.out.println("HashMap 已成功保存到文件: " + fileName);
//        } catch (IOException e) {
//            System.err.println("写入文件时发生错误: " + e.getMessage());
//        }
//
//        fileName = "eft.txt";
//
//        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
//            // 遍历 HashMap 并将每个键值对写入文件
//            for (Map.Entry<String, Integer> entry : eftSave.entrySet()) {
//                writer.write(entry.getKey() + "=" + entry.getValue());
//                writer.newLine(); // 写入换行符
//            }
//            System.out.println("HashMap 已成功保存到文件: " + fileName);
//        } catch (IOException e) {
//            System.err.println("写入文件时发生错误: " + e.getMessage());
//        }
        return scheduleResults;
    }


    // =============== 辅助函数 ===============

    /**
     * 用于返回候选节点模拟结果的简单结构
     */
    private static class CandidateResult {
        public final String candidate;
        public final double efficiencyRatio; // 修改为效率比率

        public CandidateResult(String candidate, double efficiencyRatio) {
            this.candidate = candidate;
            this.efficiencyRatio = Double.parseDouble(String.format("%.6f", efficiencyRatio));
        }
    }

    /**
     * 使用有限深度的贪心调度模拟，返回效率比率：(补全后的makespan-补全前的makespan)/调度节点的总成本
     */
    private static double simulateGreedySchedule(
            Set<String> unscheduled,
            Map<String, Integer> inDegree,
            Map<String, Integer> startTime,
            Map<String, Integer> finishTime,
            Map<String, Integer> availableTime,
            Map<String, DAGNode> uuidToNode,
            Map<String, List<DAGEdge>> succList,
            Map<String, List<DAGEdge>> predList,
            Map<String, Double> upwardRanks,
            int initialMakespan,
            int currentNodeCost
    ) {
        // 检查是否有可调度的非零成本节点
        boolean hasSchedulableNodes = false;
        for (String uuid : unscheduled) {
            if (inDegree.get(uuid) == 0 && uuidToNode.get(uuid).cost > 0) {
                hasSchedulableNodes = true;
                break;
            }
        }


        // 计算补全后的makespan
        int finalMakespan = 0;

        // 跟踪已调度节点数量和总成本
        int nodesScheduled = 0;
        int totalCostOfScheduledNodes = 0;
        int iterationCount = 0; // 防止无限循环
        int maxIterations = unscheduled.size() * 2; // 设置一个合理的最大迭代次数

        Set<String> zeroCostNodes = new HashSet<>();
        for (String u : unscheduled) {
            if (uuidToNode.get(u).cost == 0) {
                zeroCostNodes.add(u);
            }
        }

        while (!unscheduled.isEmpty() &&
                (nodesScheduled < Parameter.depth || Parameter.depth <= 0) &&
                iterationCount < maxIterations) {
            iterationCount++;

            // 先处理零成本节点
            List<String> zeroCostReady = new ArrayList<>();
            for (String uuid : unscheduled) {
                if (inDegree.get(uuid) == 0 && zeroCostNodes.contains(uuid)) {
                    zeroCostReady.add(uuid);
                }
            }

            // 如果没有零成本节点可处理，也没有后续非零成本节点
            if (zeroCostReady.isEmpty() && unscheduled.stream().noneMatch(uuid ->
                    inDegree.get(uuid) == 0 && !zeroCostNodes.contains(uuid))) {
                // 剩余节点都被依赖关系阻塞了，可能是图中有环
                break;
            }

            for (String z : zeroCostReady) {
                int currentTime = computeEarliestStartTime(z, predList, finishTime, uuidToNode, availableTime);
                startTime.put(z, currentTime);
                finishTime.put(z, currentTime);
                finalMakespan = Math.max(finalMakespan, currentTime);
                unscheduled.remove(z);

                for (DAGEdge edge : succList.get(z)) {
                    String succUuid = edge.to;
                    inDegree.put(succUuid, inDegree.get(succUuid) - 1);
                }
                // 零成本节点不计入深度和总成本
            }
            if (unscheduled.isEmpty()) {
                break;
            }

            List<String> readyNodes = new ArrayList<>();
            for (String uuid : unscheduled) {
                if (inDegree.get(uuid) == 0 && !zeroCostNodes.contains(uuid)) {
                    readyNodes.add(uuid);
                }
            }
            if (readyNodes.isEmpty()) {
                // 没有就绪的非零成本节点，但可能还有被阻塞的节点
                // 由于之前已经处理了所有就绪的零成本节点，所以这里是一个死锁情况
                break;
            }

            Map<String, Integer> estMap = new HashMap<>();
            Map<String, Integer> eftMap = new HashMap<>();
            for (String r : readyNodes) {
                int est = computeEarliestStartTime(r, predList, finishTime, uuidToNode, availableTime);
                int eft = est + uuidToNode.get(r).cost;
                estMap.put(r, est);
                eftMap.put(r, eft);
            }

            double maxPriority = Double.NEGATIVE_INFINITY;
            String bestNode = null;
            for (String r : readyNodes) {
                double rank = upwardRanks.get(r);
                double priority = rank - eftMap.get(r);
                if (priority > maxPriority) {
                    maxPriority = priority;
                    bestNode = r;
                }
            }


            if (bestNode != null) {
                DAGNode node = uuidToNode.get(bestNode);
                int start = estMap.get(bestNode);
                int finish = eftMap.get(bestNode);
                startTime.put(bestNode, start);
                finishTime.put(bestNode, finish);
                finalMakespan = Math.max(finalMakespan, finish);
                availableTime.put(node.typename, finish);

                unscheduled.remove(bestNode);
                for (DAGEdge edge : succList.get(bestNode)) {
                    String succUuid = edge.to;
                    inDegree.put(succUuid, inDegree.get(succUuid) - 1);
                }

                // 只对有成本的节点计数和累加成本
                if (node.cost > 0) {
                    totalCostOfScheduledNodes += node.cost;
                    nodesScheduled++;
                }

                // 如果已达到指定深度，则停止模拟
                if (nodesScheduled >= Parameter.depth && Parameter.depth > 0) {
                    break;
                }
            }
        }

        // 计算效率比率
        return (double) (finalMakespan - initialMakespan) / (totalCostOfScheduledNodes + currentNodeCost);
    }

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
            return earliestStart;
        } else {
            return Math.max(earliestStart, resourceAvailableTime);
        }
    }

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

    private static boolean validateSchedule(List<ScheduleResult> scheduleResults,
                                            Map<String, List<DAGEdge>> predList,
                                            Map<String, DAGNode> uuidToNode) {
        Map<String, Integer> scheduledStartTimes = new HashMap<>();
        Map<String, Integer> scheduledFinishTimes = new HashMap<>();

        for (ScheduleResult result : scheduleResults) {
            scheduledStartTimes.put(result.uuid, result.startTime);
            scheduledFinishTimes.put(result.uuid, result.finishTime);
        }

        // 检查依赖
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

        // 检查资源冲突
        Map<String, Map<Integer, Integer>> resourceUsage = new HashMap<>();
        for (ScheduleResult result : scheduleResults) {
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