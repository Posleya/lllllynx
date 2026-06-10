import torch
import torch.nn as nn
import torch.optim as optim
import torch.nn.functional as F
from torch_geometric.data import Data
from torch_geometric.nn import GCNConv
from DAG_ortools.common import DAGNode, DAGEdge, DAGGraph, ScheduleResult

class GNNScheduler(nn.Module):
    """图神经网络模型，用于预测任务的优先级"""
    def __init__(self, num_node_features, hidden_channels):
        super(GNNScheduler, self).__init__()
        self.conv1 = GCNConv(num_node_features, hidden_channels)
        self.conv2 = GCNConv(hidden_channels, 1)  # 输出为一个标量，表示优先级

    def forward(self, x, edge_index):
        """前向传播"""
        x = F.relu(self.conv1(x, edge_index))
        x = self.conv2(x, edge_index)
        return x.squeeze()  # 返回形状为 [num_nodes]

def construct_dag_graph(graph):
    """构建图数据，包括节点特征和边的索引"""
    node_features = []
    edge_index = []
    node_mapping = {node.uuid: idx for idx, node in enumerate(graph.nodes)}
    idx_to_node = {idx: node for idx, node in enumerate(graph.nodes)}

    # 将节点特征（例如任务的执行时间、类型等）转换为图的节点特征
    type_encoding = {}
    type_list = list(set(node.typename for node in graph.nodes))
    for idx, typename in enumerate(type_list):
        type_encoding[typename] = idx

    for node in graph.nodes:
        # 任务的执行时间和类型编码作为特征
        type_feature = type_encoding[node.typename]
        node_features.append([node.cost, type_feature])

    # 将节点特征转化为 tensor
    node_features = torch.tensor(node_features, dtype=torch.float)

    # 创建边列表（依赖关系），每个边连接两个节点
    for edge in graph.edges:
        from_idx = node_mapping[edge.from_node]
        to_idx = node_mapping[edge.to_node]
        edge_index.append([from_idx, to_idx])

    # 转化为PyTorch Geometric的边索引格式
    if edge_index:
        edge_index = torch.tensor(edge_index, dtype=torch.long).t().contiguous()
    else:
        edge_index = torch.empty((2, 0), dtype=torch.long)

    return node_features, edge_index, node_mapping, idx_to_node, type_encoding

def schedule_dag_gnn(graph):
    """通过图神经网络调度DAG图的任务"""
    # 1. 构建图数据
    node_features, edge_index, node_mapping, idx_to_node, type_encoding = construct_dag_graph(graph)
    num_node_features = node_features.shape[1]  # 节点特征的维度
    hidden_channels = 16  # 隐藏层大小

    # 2. 初始化GNN模型
    model = GNNScheduler(num_node_features, hidden_channels)
    optimizer = optim.Adam(model.parameters(), lr=0.01)

    # 3. 训练GNN模型
    model.train()
    for epoch in range(1000000):  # 可以调整epoch数量
        optimizer.zero_grad()
        out = model(node_features, edge_index)  # 获取模型输出
        # 假设目标是最小化任务的执行时间
        # 这里由于缺乏真实标签，使用执行时间的负值作为伪标签
        loss = F.mse_loss(out, -node_features[:, 0])  # 希望优先级与任务执行时间成反比
        loss.backward()
        optimizer.step()
        # print(f'Epoch {epoch}, Loss: {loss.item()}')

    # 4. 使用训练好的模型进行预测
    model.eval()
    with torch.no_grad():
        priorities = model(node_features, edge_index)  # 得到每个任务的优先级

    # 5. 根据预测的优先级生成任务的排序
    node_indices = list(range(len(graph.nodes)))
    sorted_nodes = sorted(node_indices, key=lambda idx: priorities[idx].item(), reverse=True)  # 优先级高的任务先调度

    # 6. 开始调度，确保满足所有约束
    schedule_results = []
    node_scheduled = [False] * len(graph.nodes)
    node_start_time = [0.0] * len(graph.nodes)
    node_finish_time = [0.0] * len(graph.nodes)
    resource_available_time = {typename: 0.0 for typename in type_encoding.keys()}  # 每种类型资源的可用时间

    # 建立节点的前驱关系
    node_predecessors = {idx: [] for idx in range(len(graph.nodes))}
    for edge in graph.edges:
        from_idx = node_mapping[edge.from_node]
        to_idx = node_mapping[edge.to_node]
        edge_cost = edge.cost
        node_predecessors[to_idx].append((from_idx, edge_cost))

    # 调度任务
    unscheduled_nodes = set(node_indices)
    while unscheduled_nodes:
        for idx in sorted_nodes:
            if idx not in unscheduled_nodes:
                continue
            # 检查前驱任务是否已完成
            predecessors_done = True
            earliest_start_time = 0.0
            for pred_idx, edge_cost in node_predecessors.get(idx, []):
                if not node_scheduled[pred_idx]:
                    predecessors_done = False
                    break
                finish_time = node_finish_time[pred_idx] + edge_cost  # 考虑边的通信延迟
                if finish_time > earliest_start_time:
                    earliest_start_time = finish_time
            if not predecessors_done:
                continue
            # 检查资源可用性
            node = idx_to_node[idx]
            typename = node.typename
            available_time = resource_available_time[typename]
            start_time = max(earliest_start_time, available_time)
            # 更新任务的开始和结束时间
            node_start_time[idx] = start_time
            node_finish_time[idx] = start_time + node.cost
            resource_available_time[typename] = node_finish_time[idx]  # 更新资源的可用时间
            node_scheduled[idx] = True
            unscheduled_nodes.remove(idx)
            # 添加到调度结果中
            schedule_results.append(ScheduleResult(
                node.uuid, node.name, node.typename, node_start_time[idx], node_finish_time[idx]
            ))
            break  # 重新开始循环，以优先调度高优先级的任务

    # 7. 按开始时间对调度结果进行排序
    schedule_results.sort(key=lambda r: r.start_time)

    # 8. 返回调度结果
    return schedule_results
