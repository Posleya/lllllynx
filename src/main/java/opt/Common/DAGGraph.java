package opt.Common;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class DAGGraph {
    public List<DAGNode> nodes;
    public List<DAGEdge> edges;
    public List<String> mergedCommunicationNodes;
    public Set<DAGNode> longestPath;

    // 1. 新增字段：存放 “节点UUID -> 距离”
    public Map<String, Integer> uuidDistanceMap;

//    public Map<String, List<String>> candidateNodesMap;

//    public Map<String, Integer> nodeDurations;


    public DAGGraph() {
        nodes = new ArrayList<>();
        edges = new ArrayList<>();
        mergedCommunicationNodes = new ArrayList<>();
        uuidDistanceMap = new HashMap<>(); // 初始化
//        candidateNodesMap = new HashMap<>();
    }

    public void addNode(DAGNode node) {
        nodes.add(node);
    }

    public void addEdge(DAGEdge edge) {
        edges.add(edge);
    }

    public void displayGraph() {
        System.out.println("Nodes:");
        for (DAGNode node : nodes) {
            System.out.println("UUID: " + node.uuid
                    + ", Type: " + node.typename
                    + ", Name: " + node.name
                    + ", Cost: " + node.cost);
        }

        System.out.println("\nEdges:");
        for (DAGEdge edge : edges) {
            System.out.println("From: " + edge.from
                    + ", To: " + edge.to
                    + ", Type: " + edge.typename
                    + ", Cost: " + edge.cost);
        }
    }

    /**
     * 计算并打印：每个节点到“任意终点节点”（出度为0）的最长路径分布（distance : count [uuids]）。
     * 并将 uuid->distance 存入 uuidDistanceMap。
     */
    public void printLongestPathDistribution() {
        // 1. 找到所有终点节点（出度=0）
        List<DAGNode> terminalNodes = findAllTerminalNodes();
        if (terminalNodes.isEmpty()) {
            System.out.println("图中没有出度为0的节点，无法计算最长路径。");
            return;
        }

        // 2. 构建正向邻接表
        Map<DAGNode, List<DAGNode>> forwardAdj = buildForwardAdjList();

        // 3. DFS + 记忆化：计算每个节点到“任意终点”的最长距离
        Map<DAGNode, Integer> memo = new HashMap<>();
        Set<DAGNode> terminalSet = new HashSet<>(terminalNodes);
        for (DAGNode node : nodes) {
            getLongestDistance(node, terminalSet, forwardAdj, memo);
        }

        // 4. 将距离 > 0 的节点分组统计：distance -> [list of uuids]
        Map<Integer, List<String>> distanceToNodesMap = new HashMap<>();

        // 这里顺便把节点的距离存到 uuidDistanceMap 中
        for (DAGNode node : nodes) {
            int dist = memo.getOrDefault(node, 0);
            // 将结果写入 graph 的 uuidDistanceMap
            uuidDistanceMap.put(node.uuid, dist);

            if (dist > 0) {
                distanceToNodesMap.putIfAbsent(dist, new ArrayList<>());
                distanceToNodesMap.get(dist).add(node.name);
            }
        }

        // 5. 打印“distance : count [uuid...]”
        List<Integer> sortedDistances = new ArrayList<>(distanceToNodesMap.keySet());
        Collections.sort(sortedDistances);

        System.out.println("\n=== Longest Path Distribution (distance : count [uuids]) ===");
        for (Integer dist : sortedDistances) {
            List<String> uuidList = distanceToNodesMap.get(dist);
            int count = uuidList.size();
            System.out.println(dist + " : " + count + " " + uuidList);
        }

        // 文件名
        String fileName = "distanceToNodesMap.txt";

        // 保存 Map 到文件
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
            // 遍历 Map
            for (Map.Entry<Integer, List<String>> entry : distanceToNodesMap.entrySet()) {
                // 获取键和值
                Integer key = entry.getKey();
                List<String> values = entry.getValue();

                // 将每个键值对写入文件，用格式： key=[value1, value2, ...]
                writer.write(key + " = " + values);
                writer.newLine(); // 写入换行符
            }
            System.out.println("Map 已成功保存到文件: " + fileName);
        } catch (IOException e) {
            System.err.println("写入文件时发生错误: " + e.getMessage());
        }
    }

    private List<DAGNode> findAllTerminalNodes() {
        Map<DAGNode, Integer> outDegreeMap = new HashMap<>();
        for (DAGNode node : nodes) {
            outDegreeMap.put(node, 0);
        }

        for (DAGEdge edge : edges) {
            DAGNode fromNode = getNodeByUuid(edge.from);
            if (fromNode != null) {
                outDegreeMap.put(fromNode, outDegreeMap.get(fromNode) + 1);
            }
        }

        List<DAGNode> zeroOutDegreeNodes = new ArrayList<>();
        for (Map.Entry<DAGNode, Integer> entry : outDegreeMap.entrySet()) {
            if (entry.getValue() == 0) {
                zeroOutDegreeNodes.add(entry.getKey());
            }
        }
        return zeroOutDegreeNodes;
    }

    private Map<DAGNode, List<DAGNode>> buildForwardAdjList() {
        Map<DAGNode, List<DAGNode>> forwardAdj = new HashMap<>();
        for (DAGNode node : nodes) {
            forwardAdj.put(node, new ArrayList<>());
        }
        for (DAGEdge edge : edges) {
            DAGNode fromNode = getNodeByUuid(edge.from);
            DAGNode toNode = getNodeByUuid(edge.to);
            if (fromNode != null && toNode != null) {
                forwardAdj.get(fromNode).add(toNode);
            }
        }
        return forwardAdj;
    }

    /**
     * DFS + 记忆化：求从当前节点到“任意终点”节点的最长路径长度（基于节点数）。
     *
     * @param node        当前节点
     * @param terminalSet 存放所有终点节点的集合
     * @param adj         正向邻接表
     * @param memo        记忆化表：<节点, 最长路径长度>
     * @return 如果 node 可达任意终点 => 返回最长路径长度；否则返回 0
     */
    private int getLongestDistance(DAGNode node,
                                   Set<DAGNode> terminalSet,
                                   Map<DAGNode, List<DAGNode>> adj,
                                   Map<DAGNode, Integer> memo) {
        if (memo.containsKey(node)) {
            return memo.get(node);
        }
        if (terminalSet.contains(node)) {
            memo.put(node, 1);
            return 1;
        }

        List<DAGNode> children = adj.get(node);
        if (children == null || children.isEmpty()) {
            memo.put(node, 0);
            return 0;
        }

        int maxDist = 0;
        for (DAGNode child : children) {
            int d = getLongestDistance(child, terminalSet, adj, memo);
            maxDist = Math.max(maxDist, d);
        }
        int result = (maxDist == 0) ? 0 : (1 + maxDist);
        memo.put(node, result);
        return result;
    }

    private DAGNode getNodeByUuid(String uuid) {
        for (DAGNode node : nodes) {
            if (node.uuid.equals(uuid)) {
                return node;
            }
        }
        return null;
    }
}
