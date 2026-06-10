package opt;

import opt.Algorithm.DAGLocalSearch;
import opt.Algorithm.DAGMemoryOptimizer;
import opt.Algorithm.HybridScheduler_v6_7;
import opt.Common.DAGGraph;
import opt.Common.ScheduleResult;
import opt.Utils.CommunicationEdgePreprocessing;
import opt.Utils.DAGGraphReader;
import opt.Utils.JsonSaver;

import java.util.List;

public class Main {
    private static final String DEFAULT_INPUT_PATH = "data/antopt_ret/gantt_before_ReorderPassAntOPTDataAnalyseN100R0.1.json";
    private static final String DEFAULT_OUTPUT_PATH = "-1";
    private static final int LOCAL_SEARCH_ITERATIONS = 20;
    private static final int MEMORY_OPT_ITERATIONS = 20;

    public static void main(String[] args) {
        solveAndOpt(DEFAULT_INPUT_PATH, DEFAULT_OUTPUT_PATH);
    }

    public static double solveAndOpt(String path, String outputPath) {
        DAGGraph graph = readAndPreprocessGraph(path);

        long scheduleStartTime = System.nanoTime();
        List<ScheduleResult> initialResults = HybridScheduler_v6_7.scheduleDAG(graph);
        long scheduleEndTime = System.nanoTime();

        double scheduleTime = secondsBetween(scheduleStartTime, scheduleEndTime);
        int initialMakespan = getMakespan(initialResults);
        System.out.println("Initial scheduling time: " + scheduleTime + " s");
        System.out.println("Initial makespan: " + initialMakespan);
        System.out.println("Initial schedule size: " + initialResults.size());

        long localSearchStartTime = System.nanoTime();
        List<ScheduleResult> optimizedResults =
                DAGLocalSearch.iterativeOptimize(graph, initialResults, LOCAL_SEARCH_ITERATIONS);
        long localSearchEndTime = System.nanoTime();

        double localSearchTime = secondsBetween(localSearchStartTime, localSearchEndTime);
        int finalMakespan = getMakespan(optimizedResults);
        double improvement = initialMakespan > 0
                ? (initialMakespan - finalMakespan) * 100.0 / initialMakespan
                : 0;

        System.out.println("Local search time: " + localSearchTime + " s");
        System.out.println("Final makespan: " + finalMakespan);
        System.out.println("Improvement: " + String.format("%.2f%%", improvement));
        System.out.println("Total execution time: " + (scheduleTime + localSearchTime) + " s");
        System.out.println("Final schedule size: " + optimizedResults.size());

        String resolvedOutputPath = resolveOutputPath(path, outputPath, finalMakespan, "_output");
        JsonSaver.saveResult(optimizedResults, resolvedOutputPath, graph);
        return finalMakespan;
    }

    public static double solveAndOptMemory(String path, String outputPath) {
        DAGGraph graph = readAndPreprocessGraph(path);

        long scheduleStartTime = System.nanoTime();
        List<ScheduleResult> initialResults = HybridScheduler_v6_7.scheduleDAG(graph);
        long scheduleEndTime = System.nanoTime();

        double scheduleTime = secondsBetween(scheduleStartTime, scheduleEndTime);
        int initialMakespan = getMakespan(initialResults);
        System.out.println("Initial scheduling time: " + scheduleTime + " s");
        System.out.println("Initial makespan: " + initialMakespan);
        System.out.println("Initial schedule size: " + initialResults.size());

        DAGMemoryOptimizer.MemoryProfile initialProfile =
                DAGMemoryOptimizer.calculateMemoryUsage(graph, initialResults);
        System.out.println("Initial memory profile:");
        DAGMemoryOptimizer.printMemoryProfile(initialProfile);

        long memorySearchStartTime = System.nanoTime();
        List<ScheduleResult> optimizedResults =
                DAGMemoryOptimizer.iterativeOptimize(graph, initialResults, MEMORY_OPT_ITERATIONS);
        long memorySearchEndTime = System.nanoTime();

        double memorySearchTime = secondsBetween(memorySearchStartTime, memorySearchEndTime);
        int finalMakespan = getMakespan(optimizedResults);
        DAGMemoryOptimizer.MemoryProfile optimizedProfile =
                DAGMemoryOptimizer.calculateMemoryUsage(graph, optimizedResults);

        double memorySavings = initialProfile.maxMemory - optimizedProfile.maxMemory;
        double memorySavingsPercent = initialProfile.maxMemory > 0
                ? memorySavings * 100.0 / initialProfile.maxMemory
                : 0;
        int makespanIncrease = finalMakespan - initialMakespan;
        double makespanIncreasePercent = initialMakespan > 0
                ? makespanIncrease * 100.0 / initialMakespan
                : 0;

        System.out.println("Memory optimization time: " + memorySearchTime + " s");
        System.out.println("Optimized memory profile:");
        DAGMemoryOptimizer.printMemoryProfile(optimizedProfile);
        System.out.println("Memory savings: " + memorySavings
                + " (" + String.format("%.2f%%", memorySavingsPercent) + ")");
        System.out.println("Final makespan: " + finalMakespan);
        System.out.println("Makespan increase: " + makespanIncrease
                + " (" + String.format("%.2f%%", makespanIncreasePercent) + ")");
        System.out.println("Total execution time: " + (scheduleTime + memorySearchTime) + " s");

        String resolvedOutputPath = resolveOutputPath(path, outputPath, finalMakespan, "_mem_output");
        JsonSaver.saveResult(optimizedResults, resolvedOutputPath, graph);
        return finalMakespan;
    }

    private static DAGGraph readAndPreprocessGraph(String path) {
        DAGGraph graph = DAGGraphReader.readGraphFromJSON(path);
        CommunicationEdgePreprocessing.createCommunicationNodes(graph);
        System.out.println("input node size: " + graph.nodes.size());
        return graph;
    }

    private static int getMakespan(List<ScheduleResult> results) {
        return results.stream()
                .mapToInt(r -> r.finishTime)
                .max()
                .orElse(0);
    }

    private static String resolveOutputPath(String inputPath, String outputPath, int makespan, String suffix) {
        if (outputPath != null && !outputPath.equals("-1")) {
            return outputPath;
        }
        return inputPath.replace(".json", "_" + makespan + suffix + ".json").replace("data", "output");
    }

    private static double secondsBetween(long startTime, long endTime) {
        return (endTime - startTime) / 1_000_000_000.0;
    }
}
