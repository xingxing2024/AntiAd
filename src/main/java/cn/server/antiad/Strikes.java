package cn.server.antiad;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 命中计数：只统计滑动窗口内的时间点，窗口外的记录和空条目会被顺手清掉。 */
public final class Strikes {
    private final Map<UUID, Deque<Long>> hits = new HashMap<>();

    /** 记一次命中，返回窗口内的累计次数。 */
    public synchronized int hit(UUID player, long now, int windowSeconds) {
        long window = windowSeconds * 1000L;
        hits.entrySet().removeIf(entry -> prune(entry.getValue(), now, window) == 0);
        Deque<Long> record = hits.computeIfAbsent(player, key -> new ArrayDeque<>());
        record.addLast(now);
        return record.size();
    }

    public synchronized void clear(UUID player) {
        hits.remove(player);
    }

    /** 窗口内的累计次数，不新增记录。 */
    public synchronized int count(UUID player, long now, int windowSeconds) {
        Deque<Long> record = hits.get(player);
        return record == null ? 0 : prune(record, now, windowSeconds * 1000L);
    }

    /** 重启后把库里的命中时间恢复进来，窗口外的会照常过期。 */
    public synchronized void seed(Map<UUID, List<Long>> records) {
        for (Map.Entry<UUID, List<Long>> entry : records.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            hits.computeIfAbsent(entry.getKey(), key -> new ArrayDeque<>()).addAll(entry.getValue());
        }
    }

    /** 仅用于自检：当前还留有记录的玩家数。 */
    synchronized int tracked() {
        return hits.size();
    }

    private static int prune(Deque<Long> record, long now, long window) {
        while (!record.isEmpty() && record.peekFirst() <= now - window) record.removeFirst();
        return record.size();
    }
}
