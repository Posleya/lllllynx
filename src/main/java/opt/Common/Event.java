package opt.Common;


// 定义事件类
public class Event implements Comparable<Event> {
    public int time;
    public String nodeUuid;
    public EventType eventType;

    public Event(int time, String nodeUuid, EventType type) {
        this.time = time;
        this.nodeUuid = nodeUuid;
        this.eventType = type;
    }

    @Override
    public int compareTo(Event other) {
        return Integer.compare(this.time, other.time);
    }
}