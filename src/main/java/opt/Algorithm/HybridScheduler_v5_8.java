package opt.Algorithm;

import opt.Common.*;

import java.util.*;
import java.util.stream.Collectors;

import static opt.Common.Parameter.alpha;
import static opt.Common.Parameter.beta;

public class HybridScheduler_v5_8 {

    public static List<ScheduleResult> scheduleDAG(DAGGraph graph, double capacity) {

        // ===== 1. 计算 Upward Ranks =====
        Map<String, Double> upwardRanks = computeUpwardRanks(graph);

        // ===== 2. 构建基础数据结构 =====
        Map<String, DAGNode> uuidToNode = new HashMap<>();
        Map<String, List<DAGEdge>> succList = new HashMap<>();   // nodeUuid -> List<edges from this node>
        Map<String, List<DAGEdge>> predList = new HashMap<>(); // nodeUuid -> List<edges pointing into this node>
        Map<String, Integer> inDegree = new HashMap<>();

        // 初始化
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

        // ===== 3. 不再使用非动态EFT的预计算逻辑，故删除相关代码 =====
        // （可以保留 computeEST 方法，但此处不再调用）

        // ===== 4. 准备调度过程需要的数据结构 =====
        Map<String, Integer> startTime = new HashMap<>();
        Map<String, Integer> finishTime = new HashMap<>();
        Map<String, Integer> availableTime = new HashMap<>(); // 记录各类型节点的可用时刻

        // 所有类型节点初始化可用时刻=0
        Set<String> types = new HashSet<>();
        for (DAGNode node : graph.nodes) {
            types.add(node.typename);
        }
        for (String type : types) {
            availableTime.put(type, 0);
        }

        // 未调度节点
        Set<String> unscheduledNodes = new HashSet<>(uuidToNode.keySet());

        // currentInDegree: 在“节点完成事件”时才真正减后继节点的入度
        Map<String, Integer> currentInDegree = new HashMap<>(inDegree);

        // 标记 cost=0 的节点
        Set<String> zeroCostNodes = new HashSet<>();
        for (DAGNode node : graph.nodes) {
            if (node.cost == 0) {
                zeroCostNodes.add(node.uuid);
            }
        }

        int currentTime = 0;       // 调度时钟

        if (Parameter.DEBUG) {
            System.out.println("[Init] currentTime = " + currentTime);
        }

        double resourceUsed = 0;      // 当前占用资源
        PriorityQueue<Event> eventQueue = new PriorityQueue<>(); // 事件队列

        // 追踪每个节点还有多少后继没完成 => 用于在最后一个后继完成时释放资源
        Map<String, Integer> pendingSuccessors = new HashMap<>();
        for (DAGNode node : graph.nodes) {
            pendingSuccessors.put(node.uuid, succList.get(node.uuid).size());
        }

        // 建立 前驱 列表
        Map<String, List<String>> nodeToPredecessors = new HashMap<>();
        for (DAGNode node : graph.nodes) {
            nodeToPredecessors.put(node.uuid, new ArrayList<>());
        }
        for (DAGEdge edge : graph.edges) {
            nodeToPredecessors.get(edge.to).add(edge.from);
        }

        // ======== 5. 主调度循环 ========
        while (!unscheduledNodes.isEmpty()) {

            // A) 处理已经到达 currentTime 的完成事件
            while (!eventQueue.isEmpty() && eventQueue.peek().time <= currentTime) {
                Event e = eventQueue.poll();
                String finishedNodeUuid = e.nodeUuid;
                DAGNode finishedNode = uuidToNode.get(finishedNodeUuid);

                if (e.eventType == EventType.NODE_FINISH) {
                    if (Parameter.DEBUG) {
                        System.out.println("[Event] Node finished: " + finishedNode.name
                                + " at time=" + e.time);
                    }
                    // 在完成时减少其后继的 inDegree
                    for (DAGEdge edge : succList.get(finishedNodeUuid)) {
                        String succUuid = edge.to;
                        currentInDegree.put(succUuid, currentInDegree.get(succUuid) - 1);
                        if (Parameter.DEBUG) {
                            System.out.println("    Decrease inDegree of " + succUuid
                                    + " => " + currentInDegree.get(succUuid));
                        }
                    }
                    // 释放前驱的资源（当某节点的所有后继都完成了，才释放它的资源）
                    for (String predUuid : nodeToPredecessors.get(finishedNodeUuid)) {
                        int remain = pendingSuccessors.get(predUuid) - 1;
                        pendingSuccessors.put(predUuid, remain);
                        if (remain == 0) {
                            DAGNode predNode = uuidToNode.get(predUuid);
                            resourceUsed -= predNode.demand;
                            if (Parameter.DEBUG) {
                                System.out.println("    All successors of " + predNode.name
                                        + " done => release resource. used=" + resourceUsed);
                            }
                        }
                    }
                }
            }

            // B) 处理 cost=0 并且 inDegree=0 的节点（它们瞬时完成）
            List<String> zeroCostNow = new ArrayList<>();
            for (String uuid : unscheduledNodes) {
                if (zeroCostNodes.contains(uuid) && currentInDegree.get(uuid) == 0) {
                    zeroCostNow.add(uuid);
                }
            }
            for (String uuid : zeroCostNow) {
                DAGNode node = uuidToNode.get(uuid);
                int earliestStart = computeEarliestStartTime(uuid, predList, finishTime, uuidToNode, availableTime);
                startTime.put(uuid, earliestStart);
                finishTime.put(uuid, earliestStart); // cost=0 => start=finish
                // 立刻视为完成 => 减少后继入度
                for (DAGEdge edge : succList.get(uuid)) {
                    String succ = edge.to;
                    currentInDegree.put(succ, currentInDegree.get(succ) - 1);
                    if (Parameter.DEBUG) {
                        System.out.println("[ZeroCost] Decrease inDegree of " + succ
                                + " => " + currentInDegree.get(succ));
                    }
                }
                // 释放前驱的资源
                for (String predUuid : nodeToPredecessors.get(uuid)) {
                    int remain = pendingSuccessors.get(predUuid) - 1;
                    pendingSuccessors.put(predUuid, remain);
                    if (remain == 0) {
                        DAGNode predNode = uuidToNode.get(predUuid);
                        resourceUsed -= predNode.demand;
                        if (Parameter.DEBUG) {
                            System.out.println("    Zero-cost node triggers release from "
                                    + predNode.name + ", resourceUsed=" + resourceUsed);
                        }
                    }
                }
                if (Parameter.DEBUG) {
                    System.out.println("[ZeroCost Scheduling] Node " + node.name
                            + " => start=finish=" + earliestStart);
                }
                unscheduledNodes.remove(uuid);
            }
            if (unscheduledNodes.isEmpty()) {
                break; // 全部调度完成
            }

            // C) 找到所有 inDegree=0 的剩余节点（真正就绪）
            List<String> readyNodes = new ArrayList<>();
            for (String uuid : unscheduledNodes) {
                if (currentInDegree.get(uuid) == 0) {
                    readyNodes.add(uuid);
                }
            }

            // 如果当前没有就绪节点
            if (readyNodes.isEmpty()) {
                // 如果还有正在执行的事件，则把时间跳到下一个事件发生时
                if (!eventQueue.isEmpty()) {
                    int nextEventTime = eventQueue.peek().time;
                    if (Parameter.DEBUG) {
                        System.out.println("[No ready node] Jump time from " + currentTime
                                + " => " + nextEventTime);
                    }
                    currentTime = nextEventTime;  // ← time 改变处
                    continue; // 再次循环
                } else {
                    // 没有事件也没有就绪节点 => 剩余节点无法调度 => 图有环或资源永远无法满足
                    throw new RuntimeException(
                            "Graph has a cycle or is unschedulable. " +
                                    "No ready nodes & no future events, but there are still "
                                    + unscheduledNodes.size() + " unscheduled nodes!");
                }
            }

            // D) 计算这些就绪节点的最早开始时间 + 最早完成时间 + 优先级
            Map<String, Integer> earliestStartTimes = new HashMap<>();
            Map<String, Integer> earliestFinishTimes = new HashMap<>();
            for (String uuid : readyNodes) {
                DAGNode node = uuidToNode.get(uuid);
                int estWithResource = computeEarliestStartTime(uuid, predList, finishTime, uuidToNode, availableTime);
                earliestStartTimes.put(uuid, estWithResource);
                earliestFinishTimes.put(uuid, estWithResource + node.cost);
            }

            // 计算调度优先级
            Map<String, Double> priorities = new HashMap<>();
            for (String uuid : readyNodes) {
                DAGNode node = uuidToNode.get(uuid);
                double rank = upwardRanks.get(uuid);  // 上行等级
                int priorityEFT = earliestFinishTimes.get(uuid);

                double priority;
                if (Parameter.priorityType == 0) {
                    // 示例：priority = rank - EFT
                    priority = rank - priorityEFT;
                } else if (Parameter.priorityType == 1) {
                    // 示例：priority = rank
                    priority = rank;
                } else {
                    // 示例：priority = -EFT
                    priority = -priorityEFT;
                }
                priorities.put(uuid, alpha * priority + beta * node.demand);

                if (Parameter.DEBUG) {
                    System.out.println("[ReadyNode] " + node.name
                            + ": rank=" + rank
                            + ", earliestStart=" + earliestStartTimes.get(uuid)
                            + ", earliestFinish=" + earliestFinishTimes.get(uuid)
                            + ", priority=" + priority);
                }
            }

            // 按优先级排序(降序)
            List<String> sortedReadyNodes = readyNodes.stream()
                    .sorted((a, b) -> Double.compare(priorities.get(b), priorities.get(a)))
                    .collect(Collectors.toList());

            // E) 依次尝试调度可行的节点(并行)，而不是只调度一个就break
            int scheduledCount = 0;
            for (String nodeUuid : sortedReadyNodes) {
                DAGNode node = uuidToNode.get(nodeUuid);

                int estWithResource = computeEarliestStartTime(nodeUuid, predList, finishTime, uuidToNode, availableTime);
                int scheduleTime = Math.max(currentTime, estWithResource);

                // 检查容量
                if (resourceUsed + node.demand <= capacity) {
                    // 可以调度
                    startTime.put(nodeUuid, scheduleTime);
                    finishTime.put(nodeUuid, scheduleTime + node.cost);
                    resourceUsed += node.demand;

                    if (Parameter.DEBUG) {
                        System.out.println("[SchedulingParallel] " + node.name
                                + "(type=" + node.typename + ") => start=" + scheduleTime
                                + ", finish=" + (scheduleTime + node.cost)
                                + ", used=" + resourceUsed
                                + " (demand=" + node.demand + ")");
                    }

                    // 更新资源可用时间（同类型下一次要等它完成后才能开始）
                    if (node.cost > 0) {
                        availableTime.put(node.typename, scheduleTime + node.cost);
                    }

                    // 放到事件队列 => 在其完成时再减后继入度
                    eventQueue.add(new Event(scheduleTime + node.cost, nodeUuid, EventType.NODE_FINISH));

                    unscheduledNodes.remove(nodeUuid);
                    scheduledCount++;
                    break;
                }
            }

            // F) 如果本轮什么也调度不了，则跳到下一个事件发生
            if (scheduledCount == 0) {
                if (!eventQueue.isEmpty()) {
                    int nextEventTime = eventQueue.peek().time;
                    if (Parameter.DEBUG) {
                        System.out.println("[No node scheduled] Jump time from "
                                + currentTime + " => " + nextEventTime);
                    }
                    currentTime = nextEventTime;  // ← time 改变处
                } else {
                    // 没事件也调度不了 => 不可行
                    throw new RuntimeException(
                            "Cannot schedule any node due to resource constraints, and no further events!");
                }
            }
        }

        // ======== 6. 组装调度结果 ========
        List<ScheduleResult> scheduleResults = new ArrayList<>();
        for (String uuid : uuidToNode.keySet()) {
            DAGNode node = uuidToNode.get(uuid);
            int sTime = startTime.getOrDefault(uuid, 0);
            int fTime = finishTime.getOrDefault(uuid, sTime);
            scheduleResults.add(new ScheduleResult(uuid, node.name, node.typename, sTime, fTime));
        }
        scheduleResults.sort(Comparator.comparingInt(r -> r.startTime));
        return scheduleResults;
    }

    // ================ 仅保留，暂不使用的 computeEST ================
    private static Map<String, Integer> computeEST(DAGGraph graph, Map<String, DAGNode> uuidToNode) {
        Map<String, Integer> inDeg = new HashMap<>();
        for (DAGNode node : graph.nodes) {
            inDeg.put(node.uuid, 0);
        }
        for (DAGEdge e : graph.edges) {
            inDeg.put(e.to, inDeg.get(e.to) + 1);
        }

        Queue<String> queue = new LinkedList<>();
        for (String uuid : inDeg.keySet()) {
            if (inDeg.get(uuid) == 0) {
                queue.add(uuid);
            }
        }

        List<String> topoOrder = new ArrayList<>();
        while (!queue.isEmpty()) {
            String u = queue.poll();
            topoOrder.add(u);
            for (DAGEdge edge : graph.edges) {
                if (edge.from.equals(u)) {
                    String v = edge.to;
                    inDeg.put(v, inDeg.get(v) - 1);
                    if (inDeg.get(v) == 0) {
                        queue.add(v);
                    }
                }
            }
        }

        Map<String, Integer> est = new HashMap<>();
        for (DAGNode node : graph.nodes) {
            est.put(node.uuid, 0);
        }

        for (String u : topoOrder) {
            DAGNode uNode = uuidToNode.get(u);
            int uEft = est.get(u) + uNode.cost;
            for (DAGEdge edge : graph.edges) {
                if (edge.from.equals(u)) {
                    String v = edge.to;
                    int arrivalTime = uEft + edge.cost;
                    if (arrivalTime > est.get(v)) {
                        est.put(v, arrivalTime);
                    }
                }
            }
        }

        return est;
    }

    /**
     * 递归计算节点的 Upward Rank
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
            throw new RuntimeException("Node not found: " + nodeId);
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
     * 计算所有节点的 Upward Rank
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
     * 计算节点的最早可开时间(考虑所有前驱的 finishTime + edge.cost + 当前类型资源availableTime)
     * - 对 cost=0 的节点，资源等待可以忽略
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
            int arrivalTime = finishTime.getOrDefault(predUuid, 0) + edge.cost;
            if (arrivalTime > earliestStart) {
                earliestStart = arrivalTime;
            }
        }
        // 资源
        int resourceAvailable = availableTime.getOrDefault(type, 0);
        // cost=0 => 不需要等待资源
        if (node.cost == 0) {
            return earliestStart;
        } else {
            return Math.max(earliestStart, resourceAvailable);
        }
    }
}
