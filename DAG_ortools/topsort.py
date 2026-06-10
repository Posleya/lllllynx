import json
from collections import defaultdict, deque

# 读取JSON数据
with open('data/debug_demo.json', 'r') as file:
    data = json.load(file)

# 构建节点和邻接表
nodes = {node['uuid']: node for node in data['nodes']}
graph = defaultdict(list)
in_degree = defaultdict(int)

# 初始化入度为0的节点
for node_id in nodes:
    in_degree[node_id] = 0

# 构建邻接表和计算入度
for edge in data['edges']:
    from_node = edge['from']
    to_node = edge['to']
    graph[from_node].append(to_node)
    in_degree[to_node] += 1

# 拓扑排序（Kahn算法）
queue = deque([node_id for node_id in nodes if in_degree[node_id] == 0])
topo_order = []

while queue:
    current = queue.popleft()
    topo_order.append(current)
    for neighbor in graph[current]:
        in_degree[neighbor] -= 1
        if in_degree[neighbor] == 0:
            queue.append(neighbor)

# 检查是否存在环
if len(topo_order) != len(nodes):
    print("图中存在环，无法进行拓扑排序。")
else:
    print("拓扑排序结果：")
    for idx, node_id in enumerate(topo_order, 1):
        node = nodes[node_id]
        print(f"{idx}. 节点ID: {node_id}, 名称: {node.get('name', '无')}")
