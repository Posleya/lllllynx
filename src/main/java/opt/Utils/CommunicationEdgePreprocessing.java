package opt.Utils;

import opt.Common.DAGGraph;
import opt.Common.DAGNode;
import opt.Common.DAGEdge;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class CommunicationEdgePreprocessing {

    /**
     * 将 "communication" 类型的边及其所连接的 A、B 节点合并为一个新节点 (A to B)，
     * 并更新、删除相关的节点和边。
     *
     * 需求:
     * 1. 任何指向 A 的边，改为指向 (A to B)
     * 2. 任何从 B 出去的边，改为从 (A to B) 出去
     * 3. 新节点的 cost = A.cost + B.cost + (A->B).cost
     * 4. 新节点的 demand = max(A.demand, B.demand)
     * 5. 新节点的 name 包含原节点的名称和成本信息 (便于还原)
     * 6. 删除原节点 A, B 以及它们之间的 communication 边
     */
    public static void createCommunicationNodes(DAGGraph graph) {
        // 先把 communication 边收集起来，避免边的修改影响遍历
        List<DAGEdge> communicationEdges = new ArrayList<>();
        for (DAGEdge edge : graph.edges) {
            if ("communication".equals(edge.typename)) {
                communicationEdges.add(edge);
            }
        }

        // 逐条处理 communication 边
        for (DAGEdge commEdge : communicationEdges) {
            // 找到对应的 A, B 节点
            DAGNode fromNode = findNodeByUuid(graph, commEdge.from);
            DAGNode toNode = findNodeByUuid(graph, commEdge.to);

            // 如果 A 或 B 已经被合并/删除，跳过
            if (fromNode == null || toNode == null) {
                continue;
            }

            // 计算合并后节点的信息
            String mergedUuid   = fromNode.uuid + "_" + toNode.uuid;
            int mergedCost      = fromNode.cost + toNode.cost + commEdge.cost;
            double mergedDemand = Math.max(fromNode.demand, toNode.demand);
            String mergedName   = fromNode.name + "#" + toNode.name
                    + "#" + fromNode.cost + "#" + toNode.cost;
            DAGNode mergedNode  = new DAGNode(mergedUuid,
                    "communication",
                    mergedName,
                    mergedCost,
                    mergedDemand);

            // 1) 重定向所有指向 A 的边，改为指向 mergedNode
            for (DAGEdge edge : graph.edges) {
                if (edge.to.equals(fromNode.uuid)) {
                    edge.to = mergedUuid;
                }
            }

            // 2) 重定向所有从 B 出去的边，改为从 mergedNode 出去
            for (DAGEdge edge : graph.edges) {
                if (edge.from.equals(toNode.uuid)) {
                    edge.from = mergedUuid;
                }
            }

            // 3) 删除节点 A 和 B
            graph.nodes.remove(fromNode);
            graph.nodes.remove(toNode);

            // 4) 删除 A->B 这条 communication 边以及与 A、B 相关的其他边
            //    （因为已被合并，且已重定向完毕）
            Iterator<DAGEdge> edgeIterator = graph.edges.iterator();
            while (edgeIterator.hasNext()) {
                DAGEdge e = edgeIterator.next();
                // 如果边的来源或目标是原来的 A 或者 B，就删除
                if (e.from.equals(fromNode.uuid)
                        || e.to.equals(fromNode.uuid)
                        || e.from.equals(toNode.uuid)
                        || e.to.equals(toNode.uuid)) {
                    edgeIterator.remove();
                }
            }

            // 5) 将新的合并节点加入图中
            graph.nodes.add(mergedNode);

            // 如果需要在 DAGGraph 中记录下来，以便导出时还原
             graph.mergedCommunicationNodes.add(mergedNode.name);
        }

//        graph.longestPath = findLongestPath(graph);
    }

    /**
     * 辅助方法：根据 uuid 在图中查找节点
     */
    private static DAGNode findNodeByUuid(DAGGraph graph, String uuid) {
        for (DAGNode node : graph.nodes) {
            if (node.uuid.equals(uuid)) {
                return node;
            }
        }
        return null;
    }
}
