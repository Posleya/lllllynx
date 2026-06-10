# main.py

import time
import os
import csv

from DAG_ortools.scheduler import schedule_dag
from DAG_ortools.scheduler_gnn import schedule_dag_gnn
from DAG_ortools.utils import read_graph_from_json, create_communication_nodes


def main():
    # 设置路径
    path = "demo"
    output_path = "output_test"
    # 读取DAG图
    graph = read_graph_from_json(f"data/{path}.json")
    # 预处理通信边
    create_communication_nodes(graph)
    # 记录开始时间
    start_time = time.time()
    # 调用调度函数
    # results = schedule_dag(graph)
    results = schedule_dag_gnn(graph)
    # 记录结束时间
    end_time = time.time()
    # 计算执行时间
    duration = end_time - start_time  # 单位：秒
    # 输出调度结果
    print("Scheduling Results:")
    for result in results:
        print(f"Node UUID: {result.uuid}, Type: {result.typename}, Start Time: {result.start_time}, Finish Time: {result.finish_time}")
    # 计算总执行时间
    total_execution_time = max(r.finish_time for r in results)
    print(f"\nTotal Execution Time (Makespan): {total_execution_time}")
    # 写入CSV文件
    os.makedirs(output_path, exist_ok=True)
    with open(f"{output_path}/{path}_output.csv", 'w', newline='') as csvfile:
        csvwriter = csv.writer(csvfile)
        # 写入标题
        csvwriter.writerow(["UUID", "Type", "Start Time", "Finish Time"])
        # 写入调度结果
        for result in results:
            csvwriter.writerow([result.uuid, result.typename, result.start_time, result.finish_time])
        # 写入总执行时间和执行耗时
        csvwriter.writerow([])
        csvwriter.writerow(["", "", "Total Execution Time", total_execution_time])
        csvwriter.writerow(["", "", "schedule_dag ortools Execution time", duration])
    print(f"schedule_dag ortools Execution time: {duration} s")

if __name__ == "__main__":
    main()
