package com.mkei.backcast.ui;

import com.mkei.backcast.agent.SubAgentManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 子代理列表的排序和状态判断。 */
public final class AgentPanelState {
    private AgentPanelState() { }

    public static boolean active(String status) {
        return SubAgentManager.RUNNING.equals(status) || SubAgentManager.QUEUED.equals(status)
                || SubAgentManager.WAITING.equals(status);
    }

    private static final Comparator<SubAgentManager.Record> BY_ACTIVITY = new Comparator<SubAgentManager.Record>() {
        @Override public int compare(SubAgentManager.Record a, SubAgentManager.Record b) {
            if (active(a.status) != active(b.status)) return active(a.status) ? -1 : 1;
            if (a.lastActivityAt != b.lastActivityAt) return a.lastActivityAt > b.lastActivityAt ? -1 : 1;
            return a.id.compareTo(b.id);
        }
    };

    /** 去掉 main，运行中的在前，其余按最近活动排。 */
    public static List<SubAgentManager.Record> ordered(List<SubAgentManager.Record> source) {
        List<SubAgentManager.Record> rows = new ArrayList<SubAgentManager.Record>();
        for (SubAgentManager.Record row : source) if (!SubAgentManager.ROOT.equals(row.id)) rows.add(row);
        Collections.sort(rows, BY_ACTIVITY);
        return rows;
    }
}
