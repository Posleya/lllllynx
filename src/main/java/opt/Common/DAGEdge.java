package opt.Common;

public class DAGEdge {
    public String from;
    public String to;
    public String typename;
    public int cost;

    public DAGEdge(String from, String to, String typename, int cost) {
        this.from = from;
        this.to = to;
        this.typename = typename;
        this.cost = cost;
    }
}