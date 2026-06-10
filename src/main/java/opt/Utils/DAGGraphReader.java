package opt.Utils;

import opt.Common.DAGEdge;
import opt.Common.DAGGraph;
import opt.Common.DAGNode;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class DAGGraphReader {
    public static DAGGraph readGraphFromJSON(String filePath) {
        DAGGraph graph = new DAGGraph();

        try {
            // Read the content of the file as a String
            String content = new String(Files.readAllBytes(Paths.get(filePath)));

            // Parse the JSON content using FastJSON
            JSONObject jsonObject = JSON.parseObject(content);

            // Extract nodes and edges
            JSONArray nodesArray = jsonObject.getJSONArray("nodes");
            JSONArray edgesArray = jsonObject.getJSONArray("edges");

            // Parse nodes
            for (int i = 0; i < nodesArray.size(); i++) {
                JSONObject node = nodesArray.getJSONObject(i);
                String uuid = node.getString("uuid");
                String typename = node.getString("typename");
                String name = node.getString("name");
                int cost = node.getIntValue("cost");
                double demand = node.containsKey("space_cost") ? node.getDoubleValue("space_cost") : 1;
                graph.addNode(new DAGNode(uuid, typename, name, cost, demand));
            }

            // Parse edges
            for (int i = 0; i < edgesArray.size(); i++) {
                JSONObject edge = edgesArray.getJSONObject(i);
                String from = edge.getString("from");
                String to = edge.getString("to");
                String typename = edge.getString("typename");
                int cost = edge.getIntValue("cost");
                graph.addEdge(new DAGEdge(from, to, typename, cost));
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        return graph;
    }
}