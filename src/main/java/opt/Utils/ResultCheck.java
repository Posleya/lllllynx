package opt.Utils;

import opt.Common.ScheduleResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ResultCheck {

    // check函数，检查调度结果是否符合约束
    public static void check(List<ScheduleResult> scheduleResults) {
        // 使用一个映射：时间 -> 资源类型 -> 执行的任务数量
        Map<Integer, Map<String, Integer>> scheduleMap = new HashMap<>();

        for (ScheduleResult result : scheduleResults) {
            // 只检查 cost 不为0的节点
            if (result.finishTime > result.startTime) {
                // 遍历节点的执行时间区间，逐个时间点检查
                for (int time = result.startTime; time < result.finishTime; time++) {
                    // 获取该时间点和资源类型的计数器
                    scheduleMap.putIfAbsent(time, new HashMap<>());
                    Map<String, Integer> typeCountMap = scheduleMap.get(time);

                    // 如果该时间点和该资源类型已经有任务在执行，则说明有冲突
                    if (typeCountMap.containsKey(result.typename)) {
                        int count = typeCountMap.get(result.typename);
                        if (count > 0) {
                            // 如果计数大于0，说明当前资源类型已经有任务在执行
                            throw new RuntimeException("资源类型 " + result.typename + " 在时间 " + time + " 上有冲突，多个任务同时执行！");
                        }
                    }

                    // 否则，记录该资源类型在这个时间点上有任务执行
                    typeCountMap.put(result.typename, typeCountMap.getOrDefault(result.typename, 0) + 1);
                }
            }
        }

        System.out.println("调度结果检查通过，没有资源冲突");
    }
}