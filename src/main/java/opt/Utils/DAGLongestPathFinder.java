package opt.Utils;

import opt.Common.DAGEdge;
import opt.Common.DAGGraph;
import opt.Common.DAGNode;

import java.util.*;

public class DAGLongestPathFinder {

    public static Set<DAGNode> findLongestPath(DAGGraph graph) {
        // Build the graph data structures
        Map<String, DAGNode> uuidToNode = new HashMap<>();
        Map<String, List<DAGEdge>> succList = new HashMap<>();
        Map<String, List<DAGEdge>> predList = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        // Initialize the maps
        for (DAGNode node : graph.nodes) {
            uuidToNode.put(node.uuid, node);
            succList.put(node.uuid, new ArrayList<>());
            predList.put(node.uuid, new ArrayList<>());
            inDegree.put(node.uuid, 0);
        }

        // Build the adjacency list and in-degree count
        for (DAGEdge edge : graph.edges) {
            succList.get(edge.from).add(edge);
            predList.get(edge.to).add(edge);
            inDegree.put(edge.to, inDegree.get(edge.to) + 1);
        }

        // Perform topological sort using Kahn's algorithm
        List<String> topoOrder = new ArrayList<>();
        Queue<String> queue = new LinkedList<>();

        // Nodes with in-degree 0
        for (String uuid : inDegree.keySet()) {
            if (inDegree.get(uuid) == 0) {
                queue.offer(uuid);
            }
        }

        while (!queue.isEmpty()) {
            String u = queue.poll();
            topoOrder.add(u);

            for (DAGEdge edge : succList.get(u)) {
                String v = edge.to;
                inDegree.put(v, inDegree.get(v) - 1);
                if (inDegree.get(v) == 0) {
                    queue.offer(v);
                }
            }
        }

        if (topoOrder.size() != graph.nodes.size()) {
            throw new RuntimeException("Graph has a cycle!");
        }

        // Initialize distances and predecessors
        Map<String, Integer> distance = new HashMap<>();
        Map<String, String> predecessor = new HashMap<>();

        // Initialize distances to negative infinity
        for (String uuid : uuidToNode.keySet()) {
            distance.put(uuid, Integer.MIN_VALUE);
            predecessor.put(uuid, null);
        }

        // Nodes with in-degree 0 have distance 0
        for (String uuid : uuidToNode.keySet()) {
            if (predList.get(uuid).isEmpty()) {
                distance.put(uuid, 0);
            }
        }

        // Process nodes in topological order
        for (String u : topoOrder) {
            for (DAGEdge edge : succList.get(u)) {
                String v = edge.to;
                int weight = uuidToNode.get(v).cost + edge.cost; // Node cost + edge cost
                if (distance.get(u) + weight > distance.get(v)) {
                    distance.put(v, distance.get(u) + weight);
                    predecessor.put(v, u);
                }
            }
        }

        // Find the node with the maximum distance (may be multiple ending nodes)
        String maxNode = null;
        int maxDistance = Integer.MIN_VALUE;
        for (String uuid : uuidToNode.keySet()) {
            // Nodes with out-degree 0 (ending nodes)
            if (succList.get(uuid).isEmpty()) {
                if (distance.get(uuid) > maxDistance) {
                    maxDistance = distance.get(uuid);
                    maxNode = uuid;
                }
            }
        }

        if (maxNode == null) {
            throw new RuntimeException("No ending node found!");
        }

        // Reconstruct the longest path
        Set<DAGNode> longestPath = new HashSet<>();
        String current = maxNode;
        while (current != null) {
            longestPath.add(uuidToNode.get(current));
            current = predecessor.get(current);
        }

        // The path is from end to start, so reverse it
//        Collections.reverse(longestPath);

        return longestPath;
    }

    // Example usage
    public static void main(String[] args) {
        String path = "demo"; // Replace with your JSON file name without extension
        DAGGraph graph = DAGGraphReader.readGraphFromJSON("data/" + path + ".json");

        Set<DAGNode> longestPath = findLongestPath(graph);

        // Output the longest path
        System.out.println("Longest Path:");
        for (DAGNode node : longestPath) {
            System.out.println("Node UUID: " + node.uuid + ", Name: " + node.name + ", Type: " + node.typename);
        }

        // Compute the total cost (execution time) of the longest path
        int totalCost = 0;
        for (DAGNode node : longestPath) {
            totalCost += node.cost;
        }

        System.out.println("\nTotal Cost (Execution Time) of Longest Path: " + totalCost);
    }
}
