# utils.py

import json

from DAG_ortools.common import DAGNode, DAGEdge, DAGGraph


def read_graph_from_json(file_path):
    graph = DAGGraph()
    with open(file_path, 'r') as f:
        data = json.load(f)
    nodes_array = data['nodes']
    edges_array = data['edges']
    # 解析节点
    for node_data in nodes_array:
        uuid = node_data['uuid']
        typename = node_data['typename']
        name = node_data['name']
        cost = node_data['cost']
        node = DAGNode(uuid, typename, name, cost)
        graph.add_node(node)
    # 解析边
    for edge_data in edges_array:
        from_node = edge_data['from']
        to_node = edge_data['to']
        typename = edge_data['typename']
        cost = edge_data['cost']
        edge = DAGEdge(from_node, to_node, typename, cost)
        graph.add_edge(edge)
    return graph

def create_communication_nodes(graph: DAGGraph):
    original_edges = list(graph.edges)
    edges_to_remove = []
    new_nodes = []
    new_edges = []
    for edge in original_edges:
        if edge.typename == 'communication':
            # 创建新的通信节点
            new_node_uuid = f"{edge.from_node}__{edge.to_node}"
            new_node_name = new_node_uuid
            new_node_cost = edge.cost
            new_node = DAGNode(new_node_uuid, 'communication_edge', new_node_name, new_node_cost)
            new_nodes.append(new_node)
            # 创建代价为0的虚拟边
            virtual_edge1 = DAGEdge(edge.from_node, new_node_uuid, 'virtual', 0)
            virtual_edge2 = DAGEdge(new_node_uuid, edge.to_node, 'virtual', 0)
            new_edges.extend([virtual_edge1, virtual_edge2])
            # 标记原始边以供删除
            edges_to_remove.append(edge)
    # 删除原始的通信边
    for edge in edges_to_remove:
        graph.edges.remove(edge)
    # 添加新的通信节点和虚拟边
    graph.nodes.extend(new_nodes)
    graph.edges.extend(new_edges)

def compute_max_time(graph: DAGGraph):
    total_cost = sum(node.cost for node in graph.nodes)
    total_cost += sum(edge.cost for edge in graph.edges)
    return total_cost * 2
