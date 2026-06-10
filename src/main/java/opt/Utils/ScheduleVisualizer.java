package opt.Utils;

import opt.Common.DAGEdge;
import opt.Common.ScheduleResult;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class ScheduleVisualizer {

    public static void generateCSV(List<ScheduleResult> results, List<DAGEdge> edges,String outputPath, String path) {
        // Build nodeResultsMap, nodeStartTimes, nodeFinishTimes
        Map<String, ScheduleResult> nodeResultsMap = new HashMap<>();
        Map<String, Integer> nodeStartTimes = new HashMap<>();
        Map<String, Integer> nodeFinishTimes = new HashMap<>();

        for (ScheduleResult result : results) {
            nodeResultsMap.put(result.uuid, result);
            nodeStartTimes.put(result.uuid, result.startTime);
            nodeFinishTimes.put(result.uuid, result.finishTime);
        }

        // Create tasks list
        List<TaskExecution> tasks = new ArrayList<>();

        // Add node tasks
        for (ScheduleResult result : results) {
            String type = result.typename; // "compute", "communication", etc.
            tasks.add(new TaskExecution(result.uuid, type, result.startTime, result.finishTime));
        }

        // Add edge tasks
        for (DAGEdge edge : edges) {
            int edgeStartTime = nodeFinishTimes.get(edge.from);
            int edgeFinishTime = edgeStartTime + edge.cost;

            // 2 -> 2__3 的cost为0，无法显示
            if (edge.cost > 0) {
                String edgeName = edge.from + "->" + edge.to;
                tasks.add(new TaskExecution(edgeName, "edge", edgeStartTime, edgeFinishTime));
            }
        }

        // Determine total execution time
        int totalExecutionTime = 0;
        for (TaskExecution task : tasks) {
            if (task.finishTime > totalExecutionTime) {
                totalExecutionTime = task.finishTime;
            }
        }

        // Initialize time to type to task mapping
        Map<Integer, Map<String, String>> timeToTypeToTask = new TreeMap<>();

        // Initialize types
        String[] types = {"communication", "compute", "communication_edge", "edge"};

        // Initialize the maps for each time
        for (int t = 0; t <= totalExecutionTime; t++) {
            Map<String, String> typeToTask = new HashMap<>();
            for (String type : types) {
                typeToTask.put(type, "NaN");
            }
            timeToTypeToTask.put(t, typeToTask);
        }

        // Populate the maps
        for (TaskExecution task : tasks) {
            for (int t = task.startTime; t < task.finishTime; t++) {
                if (t > totalExecutionTime) {
                    continue;
                }
                Map<String, String> typeToTask = timeToTypeToTask.get(t);
                if (typeToTask == null) {
                    typeToTask = new HashMap<>();
                    for (String type : types) {
                        typeToTask.put(type, "NaN");
                    }
                    timeToTypeToTask.put(t, typeToTask);
                }
                if (task.type.equals("edge")) {
                    String existingEdges = typeToTask.get("edge");
                    if (existingEdges == null || existingEdges.equals("NaN")) {
                        typeToTask.put("edge", task.name);
                    } else {
                        typeToTask.put("edge", existingEdges + "&" + task.name);
                    }
                } else {
                    // For node types
                    typeToTask.put(task.type, task.name);
                }
            }
        }

        // Write the CSV
        try {
            FileWriter csvWriter = new FileWriter(outputPath + "/" + path + "_schedule.csv");

            // Write header
            csvWriter.append("time,communication,compute,communication_edge,edge\n");

            for (int t = 0; t <= totalExecutionTime; t++) {
                Map<String, String> typeToTask = timeToTypeToTask.get(t);
                csvWriter.append(String.valueOf(t));
                for (String type : types) {
                    String taskName = typeToTask.get(type);
                    csvWriter.append(",");
                    if (taskName == null || taskName.equals("NaN")) {
                        csvWriter.append("NaN");
                    } else {
                        csvWriter.append(taskName);
                    }
                }
                csvWriter.append("\n");
            }

            csvWriter.flush();
            csvWriter.close();
            System.out.println("CSV file 'schedule.csv' generated successfully.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class TaskExecution {
        String name;
        String type;
        int startTime;
        int finishTime;

        public TaskExecution(String name, String type, int startTime, int finishTime) {
            this.name = name;
            this.type = type;
            this.startTime = startTime;
            this.finishTime = finishTime;
        }
    }
}
