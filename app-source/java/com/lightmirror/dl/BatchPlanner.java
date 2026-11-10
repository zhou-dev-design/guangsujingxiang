package com.lightmirror.dl;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 批量下载的入队筛选：哪些能进、哪些已经在队列里、哪些装不下。
 * 抽成纯逻辑，界面只管展示结果，也方便直接跑 JVM 测试。
 */
final class BatchPlanner {

    static final class Result {
        final List<GithubUrlParser.Info> accepted = new ArrayList<>();
        int dup;        // 已经在队列 / 引擎里，跳过
        int full;       // 队列装不下
        int invalid;    // 链接解析不出来

        boolean nothing() {
            return accepted.isEmpty();
        }

        String message() {
            if (accepted.isEmpty()) {
                if (dup > 0 && full == 0) return "选中的文件都已经在队列里了";
                if (full > 0) return "队列已满，先清除已完成的任务";
                return "这些文件加不进队列";
            }
            StringBuilder sb = new StringBuilder("已加入 ").append(accepted.size()).append(" 个文件");
            if (dup > 0) sb.append(" · 跳过 ").append(dup).append(" 个重复");
            if (full > 0) sb.append(" · 队列满，少了 ").append(full).append(" 个");
            if (invalid > 0) sb.append(" · ").append(invalid).append(" 个链接无效");
            return sb.toString();
        }
    }

    private BatchPlanner() {
    }

    static Result plan(List<WatchController.Pick> items, Predicate<String> queued, int room) {
        Result r = new Result();
        if (items == null) return r;
        int cap = Math.max(0, room);
        for (WatchController.Pick p : items) {
            if (p == null || p.url == null) continue;
            if (queued != null && queued.test(p.url)) {
                r.dup++;
                continue;
            }
            if (r.accepted.size() >= cap) {
                r.full++;
                continue;
            }
            GithubUrlParser.Info info = GithubUrlParser.parse(p.url);
            if (info == null || !info.downloadable) {
                r.invalid++;
                continue;
            }
            info.tag = p.tag;
            info.watchRepo = p.repo;
            r.accepted.add(info);
        }
        return r;
    }
}
