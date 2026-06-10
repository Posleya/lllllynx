package opt.Common;

public class DAGNode {
    public String uuid;
    public String typename;
    public String name;
    public int cost;
    public double demand;

    public DAGNode(String uuid, String typename, String name, int cost, double demand) {
        this.uuid = uuid;
        this.typename = typename;
        this.name = name;
        this.cost = cost;
        this.demand = demand;
//        if(this.demand == 1048576)
//            this.demand += Parameter.random.nextInt(2000) - 1000;
    }
}