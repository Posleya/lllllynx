from ortools.sat.python import cp_model

from DAG_ortools.common import DAGGraph, ScheduleResult
from DAG_ortools.utils import compute_max_time

class SolutionPrinter(cp_model.CpSolverSolutionCallback):
    """在求解过程中打印中间解并保存最好的解"""

    def __init__(self, makespan, start_vars, end_vars):
        cp_model.CpSolverSolutionCallback.__init__(self)
        self.__makespan = makespan
        self.__start_vars = start_vars
        self.__end_vars = end_vars
        self.__solution_count = 0
        self.best_solution = {}
        self.best_makespan = None
        self.best_gap = None

    def on_solution_callback(self):
        current_makespan = self.Value(self.__makespan)
        best_bound = self.BestObjectiveBound()
        gap = abs(best_bound - current_makespan) if best_bound != 0 else 0
        print(f'找到第 {self.__solution_count} 个解: 目标值 = {current_makespan}, 最优界 = {best_bound}, Gap = {gap}')
        self.__solution_count += 1
        # 保存当前最好的解
        self.best_solution['start_times'] = {uuid: self.Value(var) for uuid, var in self.__start_vars.items()}
        self.best_solution['end_times'] = {uuid: self.Value(var) for uuid, var in self.__end_vars.items()}
        self.best_makespan = current_makespan
        self.best_gap = gap

def schedule_dag(graph: DAGGraph, time_limit_seconds=3600):
    schedule_results = []
    model = cp_model.CpModel()
    uuid_to_node = {node.uuid: node for node in graph.nodes}
    edges = graph.edges
    horizon = compute_max_time(graph)

    # 为每个任务创建区间变量
    task_intervals = {}
    start_vars = {}
    end_vars = {}
    durations = {}
    for node in graph.nodes:
        duration = node.cost
        durations[node.uuid] = duration
        start_var = model.NewIntVar(0, horizon, f'start_{node.uuid}')
        end_var = model.NewIntVar(0, horizon, f'end_{node.uuid}')
        interval = model.NewIntervalVar(start_var, duration, end_var, f'interval_{node.uuid}')
        task_intervals[node.uuid] = interval
        start_vars[node.uuid] = start_var
        end_vars[node.uuid] = end_var

    # 根据依赖关系添加优先约束
    for edge in edges:
        from_uuid = edge.from_node
        to_uuid = edge.to_node
        edge_cost = edge.cost
        if from_uuid in end_vars and to_uuid in start_vars:
            model.Add(start_vars[to_uuid] >= end_vars[from_uuid] + edge_cost)
        else:
            print(f"Warning: Edge from '{from_uuid}' to '{to_uuid}' references undefined node.")

    # 创建资源约束，确保同类型的任务不重叠
    type_to_intervals = {}
    for node in graph.nodes:
        type_name = node.typename
        type_to_intervals.setdefault(type_name, []).append(task_intervals[node.uuid])

    for intervals in type_to_intervals.values():
        model.AddNoOverlap(intervals)

    # 目标：最小化完成时间
    makespan = model.NewIntVar(0, horizon, 'makespan')
    model.AddMaxEquality(makespan, list(end_vars.values()))
    model.Minimize(makespan)

    # 创建求解器
    solver = cp_model.CpSolver()
    solver.parameters.max_time_in_seconds = time_limit_seconds  # 设置最大求解时间
    solver.parameters.log_search_progress = True  # 输出求解进度

    # 创建并注册回调函数
    solution_printer = SolutionPrinter(makespan, start_vars, end_vars)
    status = solver.SolveWithSolutionCallback(model, solution_printer)

    # 检查求解状态并输出解
    if status == cp_model.OPTIMAL or status == cp_model.FEASIBLE:
        # 使用保存的最好的解
        start_times = solution_printer.best_solution['start_times']
        end_times = solution_printer.best_solution['end_times']
        for node_uuid in start_vars.keys():
            s_time = start_times[node_uuid]
            f_time = end_times[node_uuid]
            node = uuid_to_node[node_uuid]
            schedule_results.append(ScheduleResult(node.uuid, node.name, node.typename, s_time, f_time))
        # 按开始时间排序并输出结果
        schedule_results.sort(key=lambda r: r.start_time)
        # print("调度结果:")
        # for result in schedule_results:
        #     print(f"节点 UUID: {result.uuid}, 名称: {result.name}, 类型: {result.typename}, 开始时间: {result.start_time}, 结束时间: {result.finish_time}")
        # # 计算总执行时间
        # total_execution_time = max(r.finish_time for r in schedule_results)
    else:
        print("未找到可行解。")

    # 输出最终的 gap
    best_objective_bound = solver.BestObjectiveBound()
    if solution_printer.best_makespan is not None and best_objective_bound is not None:
        final_gap = abs(best_objective_bound - solution_printer.best_makespan)
        print(f"最终 Gap: {final_gap}")
    else:
        print("最终 Gap: N/A")

    return schedule_results
