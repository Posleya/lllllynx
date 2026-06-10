package opt.Utils;

import opt.Common.DAGGraph;
import opt.Common.ScheduleResult;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class JsonSaver {

    /**
     * 将 ScheduleResult 列表保存为指定格式的 JSON 文件。
     *
     * @param results    ScheduleResult 对象的列表
     * @param outputPath 输出文件的路径
     */
    public static List<ScheduleResult> saveResult(List<ScheduleResult> results, String outputPath, DAGGraph graph) {
        // Create a new list to store the modified results
        List<ScheduleResult> modifiedResults = new ArrayList<>();

        // 创建根 JSON 对象
        JSONObject root = new JSONObject();

        // 创建 "nodes" JSON 数组
        JSONArray nodesArray = new JSONArray();

        // 遍历 results 列表并填充 nodes 数组
        for (ScheduleResult result : results) {
            if(graph.mergedCommunicationNodes.contains(result.name)) {
                String[] info = result.name.split("#");

                // 创建第一个分割节点
                ScheduleResult firstNode = new ScheduleResult();
                firstNode.typename = result.typename;
                firstNode.name = info[0];
                firstNode.uuid = result.uuid.split("_")[0];
                firstNode.startTime = result.startTime;
                firstNode.finishTime = result.startTime + Integer.parseInt(info[2]);
                modifiedResults.add(firstNode);

                // 创建第二个分割节点
                ScheduleResult secondNode = new ScheduleResult();
                secondNode.typename = result.typename;
                secondNode.name = info[1];
                secondNode.uuid = result.uuid.split("_")[1];
                secondNode.startTime = result.finishTime - Integer.parseInt(info[3]);
                secondNode.finishTime = result.finishTime;
                modifiedResults.add(secondNode);



                // 添加到JSON输出
                JSONObject node = new JSONObject();
                node.put("typename", firstNode.getTypename());
                node.put("name", firstNode.getName());
                node.put("uuid", firstNode.getUuid());
                node.put("startTime", firstNode.getStartTime());
                node.put("endTime", firstNode.getFinishTime());
                nodesArray.add(node);

                JSONObject node1 = new JSONObject();
                node1.put("typename", secondNode.getTypename());
                node1.put("name", secondNode.getName());
                node1.put("uuid", secondNode.getUuid());
                node1.put("startTime", secondNode.getStartTime());
                node1.put("endTime", secondNode.getFinishTime());
                nodesArray.add(node1);
            } else {
                // 保持未合并节点不变
                modifiedResults.add(result);

                // 添加到JSON输出
                JSONObject node = new JSONObject();
                node.put("typename", result.getTypename());
                node.put("name", result.getName());
                node.put("uuid", result.getUuid());
                node.put("startTime", result.getStartTime());
                node.put("endTime", result.getFinishTime());
                nodesArray.add(node);
            }
        }

        // 将 nodes 数组添加到根对象
        root.put("nodes", nodesArray);

        // 将 JSON 对象写入指定的输出文件
        try (FileWriter fileWriter = new FileWriter(outputPath)) {
            // 使用 PrettyFormat 格式化输出
            String jsonString = root.toJSONString();
            fileWriter.write(jsonString);
        } catch (IOException e) {
            System.err.println("保存 JSON 文件时发生错误: " + e.getMessage());
            e.printStackTrace();
        }

        return modifiedResults;
    }
}
