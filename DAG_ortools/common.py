# common.py

class DAGNode:
    def __init__(self, uuid, typename, name, cost):
        self.uuid = uuid
        self.typename = typename
        self.name = name
        self.cost = cost

class DAGEdge:
    def __init__(self, from_node, to_node, typename, cost):
        self.from_node = from_node
        self.to_node = to_node
        self.typename = typename
        self.cost = cost

class DAGGraph:
    def __init__(self):
        self.nodes = []
        self.edges = []

    def add_node(self, node: DAGNode):
        self.nodes.append(node)

    def add_edge(self, edge: DAGEdge):
        self.edges.append(edge)

class ScheduleResult:
    def __init__(self, uuid, name, typename, start_time, finish_time):
        self.uuid = uuid
        self.name = name
        self.typename = typename
        self.start_time = start_time
        self.finish_time = finish_time
