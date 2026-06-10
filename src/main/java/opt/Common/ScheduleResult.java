package opt.Common;

public class ScheduleResult {
    public String uuid;
    public String name;
    public String typename;
    public int startTime;
    public int finishTime;

    public ScheduleResult() {}

    public ScheduleResult(String uuid, String name, String typename, int startTime, int finishTime) {
        this.uuid = uuid;
        this.name = name;
        this.typename = typename;
        this.startTime = startTime;
        this.finishTime = finishTime;
    }

    public String getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public String getTypename() {
        return typename;
    }

    public int getStartTime() {
        return startTime;
    }

    public int getFinishTime() {
        return finishTime;
    }
}
