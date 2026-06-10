import sys
import json
import matplotlib.pyplot as plt

def calculate(request_path, res_path, debug=False):
    # Load the graph from request JSON
    with open(request_path, 'r') as f:
        request_data = json.load(f)

    # Process the DAG graph
    nodes = request_data['nodes']
    edges = request_data['edges']

    uuid_to_duration = {node['uuid']: node['cost'] for node in nodes}
    uuid_to_spacecost = {node['uuid']: node['space_cost'] for node in nodes}
    out_degree = {node['uuid']: 0 for node in nodes}
    pre_nodes = {node['uuid']: [] for node in nodes}

    # Count out-degrees for each node
    for edge in edges:
        out_degree[edge['from']] += 1
        pre_nodes[edge['to']].append(edge['from'])

    # Load the response JSON
    with open(res_path, 'r') as f:
        res_data = json.load(f)

    start_time_map = {}
    end_time_map = {}
    max_time = 0

    for node in res_data['nodes']:
        start_time = node['startTime']
        end_time = node['endTime']
        uuid = node['uuid']

        max_time = max(max_time, end_time)

        if start_time not in start_time_map:
            start_time_map[start_time] = []
        start_time_map[start_time].append(uuid)

        if end_time not in end_time_map:
            end_time_map[end_time] = []
        end_time_map[end_time].append(uuid)

    # Calculate the capacity
    current_capacity = 0
    max_capacity = 0

    # Create lists to store time and capacity data for plotting
    time_points = []
    capacity_values = []

    for time in range(int(max_time) + 1):
        # Start time: add resource usage
        if time in start_time_map:
            for uuid in start_time_map[time]:
                if uuid_to_duration.get(uuid, 0) != 0:
                    current_capacity += uuid_to_spacecost.get(uuid, 0)
                    if debug:
                        print(f"Time {time}: Node {uuid} starts, current usage: {current_capacity}")

        # End time: adjust out-degree and possibly release resources
        if time in end_time_map:
            for uuid in end_time_map[time]:
                if debug:
                    print(f"Time {time}: Node {uuid} ends.")
                for pre_node in pre_nodes[uuid]:
                    out_degree[pre_node] -= 1
                    if out_degree[pre_node] == 0 and uuid_to_duration.get(pre_node) != 0:
                        current_capacity -= uuid_to_spacecost.get(pre_node, 0)
                        if debug:
                            print(f"Time {time}: Predecessor {pre_node} becomes free, releasing resources, current usage: {current_capacity}")

        # Store the time and capacity data instead of printing
        time_points.append(time)
        capacity_values.append(current_capacity)

        # Track maximum capacity
        max_capacity = max(max_capacity, current_capacity)

    # Print max capacity information
    print(f"Max Capacity: {max_capacity}")
    print(f"Max Capacity(MB): {max_capacity/1024.0/1024.0}")
    print(f"Max Capacity(GB): {max_capacity/1024.0/1024.0/1024.0}")

    # Plot the capacity vs time graph
    plt.figure(figsize=(10, 6))
    plt.plot(time_points, capacity_values, marker='', linestyle='-', linewidth=2)
    plt.title('Resource Capacity Over Time')
    plt.xlabel('Time')
    plt.ylabel('Capacity')
    plt.grid(True, linestyle='--', alpha=0.7)
    plt.tight_layout()

    # Save the plot to a file
    plt.savefig(f'/Users/cququantum/Desktop/{res_path.replace("output/", "").replace(".json", "")}.png')

    # Show the plot if not running in a non-interactive environment
    plt.show()

    return max_capacity, time_points, capacity_values


def main():
    if len(sys.argv) < 3:
        print("Usage: python calculate.py <request.json> <res.json> [debug]")
        sys.exit(1)

    request_path = sys.argv[1]
    res_path = sys.argv[2]
    debug = len(sys.argv) == 4 and sys.argv[3] == 'debug'

    max_capacity, _, _ = calculate(request_path, res_path, debug)
    # Max capacity information is now printed inside the calculate function


if __name__ == "__main__":
    main()