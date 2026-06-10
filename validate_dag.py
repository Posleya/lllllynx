import argparse
import json
from collections import defaultdict

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("request_path", type=str)
    parser.add_argument("json_path", type=str)

    args = parser.parse_args()

    print(args)
    with open(args.json_path, "r") as f, open(args.request_path, "r") as r:
        data = json.load(f)
        request = json.load(r)

    # validate nodes execute in order
    schedule_map = {}
    for node in data["nodes"]:
        schedule_map[node["uuid"]] = node

    for node in request["nodes"]:
        # { "uuid": "316", "typename": "compute", "cost": 1, "startTime": 0 }
        if node["uuid"] not in schedule_map:
            print(f"Error: {node['uuid']} not in schedule_map")
            continue
        schedule_map[node["uuid"]]["cost"] = node["cost"]

    # validate deps link
    for edge in request["edges"]:
        # { "from": "316", "to": "461", "typename": "compute", "cost": 1 }
        from_node = edge["from"]
        to_node = edge["to"]
        edge_cost = edge["cost"]
        if from_node not in schedule_map:
            print(f"Error: {from_node} not in schedule_map")
            continue
        if to_node not in schedule_map:
            print(f"Error: {to_node} not in schedule_map")
            continue
        # after from_node, to_node should be executed
        if schedule_map[to_node]["startTime"] < schedule_map[from_node]["startTime"] + schedule_map[from_node]["cost"] + edge_cost:
            print(f"Error: {from_node} should be executed before {to_node}")

    # Check that for each type, no two nodes of the same type are running at the same time
    type2nodes = defaultdict(list)
    for node in data["nodes"]:
        type2nodes[node["typename"]].append(node)

    for typename, nodes in type2nodes.items():
        # Sort nodes by startTime
        sorted_nodes = sorted(nodes, key=lambda x: x["startTime"])
        current_usage_time = 0
        for node in sorted_nodes:
            # Check if the start time overlaps with any existing node of the same type
            for time in range(node["startTime"], node["startTime"] + node["cost"]):
                if current_usage_time > time:
                    print(f"Error: Node {node['uuid']} of type {typename} overlaps at time {time}")
                current_usage_time = time + 1


if __name__ == "__main__":
    main()
