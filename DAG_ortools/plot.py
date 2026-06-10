import json
import networkx as nx
import matplotlib.pyplot as plt

# Load the JSON data
with open('data\debug_demo.json') as f:
    data = json.load(f)


# Initialize the graph
G = nx.DiGraph()

# Define colors for different types of nodes and edges
node_colors = {'compute': 'skyblue', 'communication': 'lightcoral'}
edge_colors = {'compute': 'blue', 'communication': 'red'}

# Add nodes with attributes
for node in data['nodes']:
    G.add_node(node['uuid'], typename=node['typename'])

# Add edges with attributes
for edge in data['edges']:
    G.add_edge(edge['from'], edge['to'], typename=edge['typename'])

# Prepare color mappings
node_color_map = [node_colors[G.nodes[node]['typename']] for node in G.nodes]
edge_color_map = [edge_colors[G.edges[edge]['typename']] for edge in G.edges]

# Draw the graph with smaller node size for better clarity
pos = nx.spring_layout(G)  # Layout for visualization
nx.draw(G, pos, node_color=node_color_map, edge_color=edge_color_map, node_size=30)

# Show plot
plt.title("Simplified DAG_ortools Visualization with Smaller Nodes")
plt.show()
