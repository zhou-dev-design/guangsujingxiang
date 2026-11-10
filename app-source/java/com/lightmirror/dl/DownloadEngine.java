package com.lightmirror.dl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 下载引擎：一张任务表 + 可调并发的线程池。
 *
 * 两级并发：
 * 1) 多个文件同时下（任务数 1~10，可调）；
 * 2) 单个文件切成 N 段、用 N 条连接一起拉（默认开启，镜像支持 Range 时生效）。
 *
 * 每个任务有独立状态、进度、计时、暂停/取消；失败按镜像排名顺延，分片文件可断点续传。
 */
public class DownloadEngine {

    public enum State {QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELLED}

    public interface Listener {
        void onState(State s, String msg);

        void onMirror(String mirrorName, int attempt);

        void onProgress(long got, long total, double mbps);

        void onDone(File file, String mirrorName);

        /** 实际用的连接数，1 表示没有分段（镜像不支持 Range 或文件太小） */
        default void onPlan(int threads) {
        }
    }

    /** 一个下载任务。字段全是 volatile，UI 线程可以直接读快照。 */
    public static class Task {
        public final int id;
        public final String url;
        public final String fileName;
        public final File dir;
        public final List<Mirror> ranked;
        public final Listener listener;

        public volatile State state = State.QUEUED;
        public volatile boolean pauseFlag;
        public volatile boolean cancelFlag;
        public volatile long got, total;
        public volatile double speed;
        public volatile long startMs, pausedAt, pausedTotal;
        public volatile String mirrorName = "";
        public volatile int attempt;
        public volatile int threads = 1;
        public volatile String message = "";
        public volatile File file;

        Task(int id, File dir, String url, String fileName, List<Mirror> ranked, Listener l) {
            this.id = id;
            this.dir = dir;
            this.url = url;
            this.fileName = fileName;
            this.ranked = ranked;
            this.listener = l;
        }

        public boolean active() {
            return state == State.QUEUED || state == State.RUNNING || state == State.PAUSED;
        }

        public long elapsedSec() {
            if (startMs == 0) return 0;
            long now = pausedAt > 0 ? pausedAt : System.currentTimeMillis();
            return Math.max(0, (now - startMs - pausedTotal) / 1000);
        }
    }

    private static final DownloadEngine INSTANCE = new DownloadEngine();

    public static DownloadEngine get() {
        return INSTANCE;
    }

    private static final int DEFAULT_CONCURRENT = 3;
    private static final int MAX_CONCURRENT = 10;
    private static final int MAX_SEGMENTS = 10;

    private final ThreadPoolExecutor pool;
    private final Map<Integer, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();
    private volatile int concurrent = DEFAULT_CONCURRENT;
    private volatile boolean resumeFlag = true;
    private volatile int maxAttempts = 3;
    private volatile int segments = 10;
    private volatile boolean multiThread = true;
    private volatile int connectTimeout = 8000;
    private volatile int readTimeout = 20000;

    private DownloadEngine() {
        // 核心线程数先按默认值，最大放到 10，后面 setConcurrent 只调 core/max 成对变化
        pool = new ThreadPoolExecutor(DEFAULT_CONCURRENT, MAX_CONCURRENT,
                30L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), r -> {
            Thread t = new Thread(r, "lightmirror-dl");
            t.setDaemon(true);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
    }

    public int concurrent() {
        return concurrent;
    }

    public void setConcurrent(int n) {
        int want = Math.max(1, Math.min(MAX_CONCURRENT, n));
        // core 不能大于 max，按方向决定先改哪个
        if (want > concurrent) {
            pool.setMaximumPoolSize(want);
            pool.setCorePoolSize(want);
        } else {
            pool.setCorePoolSize(want);
            pool.setMaximumPoolSize(want);
        }
        concurrent = want;
    }

    public void setResumeEnabled(boolean on) {
        resumeFlag = on;
    }

    /** 失败后最多试几个镜像（设置页可调，测试也要看得到） */
    public int maxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int n) {
        maxAttempts = Math.max(1, n);
    }

    /** 单个文件的分段数（1 = 单连接） */
    public void setSegments(int n) {
        segments = Math.max(1, Math.min(MAX_SEGMENTS, n));
    }

    public void setMultiThread(boolean on) {
        multiThread = on;
    }

    /** 单连接超时，同时用于探测和分片连接；读超时给 3 倍，避免慢速大分片被误杀 */
    public void setTimeoutMs(int ms) {
        connectTimeout = Math.max(2000, Math.min(60000, ms));
        long read = Math.max(5000L, Math.min(120000L, ms * 3L));
        readTimeout = (int) read;
    }

    // ---------------------------------------------------------------- 任务表

    public Task get(int id) {
        return tasks.get(id);
    }

    public List<Task> snapshot() {
        List<Task> out = new ArrayList<>(tasks.values());
        out.sort((a, b) -> Integer.compare(a.id, b.id));
        return out;
    }

    public boolean isBusy() {
        for (Task t : tasks.values()) if (t.active()) return true;
        return false;
    }

    public int activeCount() {
        int n = 0;
        for (Task t : tasks.values()) if (t.active()) n++;
        return n;
    }

    /** 这个地址是不是已经在队列里（排队 / 下载中 / 暂停都算），用来挡重复添加 */
    public boolean hasTask(String url) {
        if (url == null || url.isEmpty()) return false;
        for (Task t : tasks.values()) {
            if (t.active() && url.equals(t.url)) return true;
        }
        return false;
    }

    public void pause(int id) {
        Task t = tasks.get(id);
        if (t != null && t.active()) t.pauseFlag = true;
    }

    public void resume(int id) {
        Task t = tasks.get(id);
        if (t != null) t.pauseFlag = false;
    }

    public void cancel(int id) {
        Task t = tasks.get(id);
        if (t != null) {
            t.cancelFlag = true;
            t.pauseFlag = false;
        }
    }

    public void cancelAll() {
        for (Task t : tasks.values()) {
            if (t.active()) {
                t.cancelFlag = true;
                t.pauseFlag = false;
            }
        }
    }

    /** 清掉已结束的任务记录（UI 上的「清除已完成」） */
    public void clearFinished() {
        for (Task t : new ArrayList<>(tasks.values())) {
            if (!t.active()) tasks.remove(t.id);
        }
    }

    // ---------------------------------------------------------------- 提交

    public int submit(final File dir, final String url, final String fileName,
                      final List<Mirror> ranked, final Listener l) {
        final int id = seq.incrementAndGet();
        String fn = uniqueName(fileName, id);
        final Task t = new Task(id, dir, url, fn, new ArrayList<>(ranked), l);
        tasks.put(id, t);
        notifyState(t, State.QUEUED, "排队中");
        pool.execute(() -> {
            try {
                runTask(t);
            } catch (Throwable e) {
                t.message = "任务异常：" + e.getClass().getSimpleName();
                t.state = State.FAILED;
                notifyState(t, State.FAILED, t.message);
            }
        });
        return id;
    }

    /** 同名文件并发下载会互相覆盖，自动加 (2)、(3) 后缀 */
    private String uniqueName(String fileName, int myId) {
        String candidate = fileName;
        int n = 2;
        while (true) {
            boolean clash = false;
            for (Task t : tasks.values()) {
                if (t.id != myId && t.active() && t.fileName.equals(candidate)) {
                    clash = true;
                    break;
                }
            }
            if (!clash) return candidate;
            int dot = fileName.lastIndexOf('.');
            candidate = (dot > 0 ? fileName.substring(0, dot) : fileName)
                    + "(" + n + ")" + (dot > 0 ? fileName.substring(dot) : "");
            n++;
        }
    }

    // ---------------------------------------------------------------- 执行

    private void runTask(Task t) {
        if (!t.dir.exists() && !t.dir.mkdirs()) {
            fail(t, "无法创建保存目录");
            return;
        }
        File part = new File(t.dir, t.fileName + ".part");
        File target = new File(t.dir, t.fileName);
        if (!resumeFlag) {
            deleteQuietly(part);
            wipeParts(part);
        }

        t.startMs = System.currentTimeMillis();
        t.state = State.RUNNING;
        notifyState(t, State.RUNNING, "开始");

        int attempts = Math.min(maxAttempts, t.ranked.size());
        Exception last = null;
        for (int i = 0; i < attempts; i++) {
            if (t.cancelFlag) {
                cleanup(part);
                t.state = State.CANCELLED;
                notifyState(t, State.CANCELLED, "已取消");
                return;
            }
            Mirror m = t.ranked.get(i);
            t.mirrorName = m.name;
            t.attempt = i + 1;
            notifyMirror(t, m.name, i + 1);
            try {
                fetch(t, m, part);
                wipeParts(part);   // 单连接完成时清掉上一次留下的分片
                if (target.exists() && !target.delete()) throw new IOException("同名文件被占用");
                if (!part.renameTo(target)) throw new IOException("重命名失败");
                t.file = target;
                t.got = target.length();
                t.total = t.got;
                t.state = State.DONE;
                notifyDone(t, target, m.name);
                return;
            } catch (CancelledException ce) {
                cleanup(part);
                t.state = State.CANCELLED;
                notifyState(t, State.CANCELLED, "已取消");
                return;
            } catch (Exception e) {
                last = e;
                // 保留分片 / .part，换下一个镜像续传
            }
        }
        if (t.cancelFlag) {
            cleanup(part);
            t.state = State.CANCELLED;
            notifyState(t, State.CANCELLED, "已取消");
        } else {
            fail(t, "镜像均失败：" + (last == null ? "无可用镜像" : last.getMessage()));
        }
    }

    private void cleanup(File part) {
        deleteQuietly(part);
        wipeParts(part);
    }

    private void fail(Task t, String msg) {
        t.message = msg;
        t.state = State.FAILED;
        notifyState(t, State.FAILED, msg);
    }

    /** 先探一次总大小 + 是否支持 Range，再决定单连接还是分段 */
    private void fetch(Task t, Mirror m, File part) throws IOException {
        Probe p = probe(m.buildUrl(t.url));
        if (p.total <= 0) throw new IOException("拿不到文件大小");
        t.total = p.total;

        // 小于 2MB 的文件分段收益有限，反而多几次握手
        boolean split = multiThread && segments > 1 && p.rangeOk
                && p.total > 2L * 1024 * 1024;
        if (split) {
            try {
                segmented(t, m, part, p.total);
                return;
            } catch (RangeUnsupported ru) {
                // 镜像嘴上说支持，实际回 200：清掉分片，整段重来
                wipeParts(part);
                deleteQuietly(part);
            }
        }
        single(t, m, part, p.total, p.rangeOk);
    }

    // ---------------------------------------------------------------- 单连接

    private void single(Task t, Mirror m, File part, long total, boolean rangeOk) throws IOException {
        long existing = rangeOk && part.exists() ? part.length() : 0;
        if (existing >= total && total > 0) {
            // 上次已经拉完，只差改名
            t.got = total;
            return;
        }
        String range = existing > 0 ? "bytes=" + existing + "-" : null;
        HttpURLConnection c = open(m.buildUrl(t.url), range);
        int code = c.getResponseCode();
        if (code == 206) {
            long ct = parseTotal(c.getHeaderField("Content-Range"));
            if (ct > 0) total = ct;
        } else if (code == 200) {
            existing = 0; // 不支持 Range，从头来
            if (total <= 0) total = c.getContentLengthLong();
        } else {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }

        t.total = total;
        t.got = existing;
        t.threads = 1;
        notifyPlan(t, 1);

        FileOutputStream fos = new FileOutputStream(part, existing > 0);
        InputStream in = c.getInputStream();
        byte[] buf = new byte[65536];
        long got = existing;
        long winStart = System.currentTimeMillis();
        long winBytes = 0;
        boolean pauseNotified = false;

        try {
            while (true) {
                if (t.cancelFlag) throw new CancelledException();
                if (t.pauseFlag) {
                    if (!pauseNotified) {
                        pauseNotified = true;
                        t.pausedAt = System.currentTimeMillis();
                        t.speed = 0;
                        notifyProgress(t);
                        t.state = State.PAUSED;
                        notifyState(t, State.PAUSED, "已暂停");
                    }
                    while (t.pauseFlag && !t.cancelFlag) {
                        try {
                            Thread.sleep(120);
                        } catch (InterruptedException ignored) {
                        }
                    }
                    if (t.cancelFlag) throw new CancelledException();
                    pauseNotified = false;
                    if (t.pausedAt > 0) {
                        t.pausedTotal += System.currentTimeMillis() - t.pausedAt;
                        t.pausedAt = 0;
                    }
                    winStart = System.currentTimeMillis();
                    winBytes = 0;
                    t.state = State.RUNNING;
                    notifyState(t, State.RUNNING, "继续");
                }
                int n = in.read(buf);
                if (n < 0) break;
                fos.write(buf, 0, n);
                got += n;
                winBytes += n;
                long now = System.currentTimeMillis();
                if (now - winStart >= 350) {
                    double mbps = (winBytes / 1048576.0) / ((now - winStart) / 1000.0);
                    t.got = got;
                    t.speed = mbps;
                    notifyProgress(t);
                    winStart = now;
                    winBytes = 0;
                }
            }
        } finally {
            try {
                fos.flush();
            } catch (Exception ignored) {
            }
            try {
                fos.close();
            } catch (Exception ignored) {
            }
            try {
                in.close();
            } catch (Exception ignored) {
            }
            c.disconnect();
        }

        if (total > 0 && got < total) throw new IOException("连接提前中断");
        t.got = got;
        t.speed = 0;
        notifyProgress(t);
    }

    // ---------------------------------------------------------------- 分段

    private void segmented(Task t, Mirror m, File part, long total) throws IOException {
        // 段数按文件大小收敛：每段至少 512KB，小文件硬切 10 段只会多几次握手
        int bySize = (int) Math.max(2, Math.min(MAX_SEGMENTS, total / (512 * 1024L)));
        final int n = Math.max(2, Math.min(Math.min(segments, MAX_SEGMENTS), bySize));
        final long per = (total + n - 1) / n;
        File segDir = new File(part.getParentFile(), part.getName() + ".parts");

        if (!metaMatches(segDir, total, n)) wipeDir(segDir);
        if (!segDir.exists() && !segDir.mkdirs()) throw new IOException("无法创建分片目录");
        writeMeta(segDir, total, n);
        deleteQuietly(part);   // 分片模式的数据都在 segDir 里

        final AtomicLong done = new AtomicLong(0);
        final AtomicReference<Throwable> err = new AtomicReference<>();
        final List<Thread> workers = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            final long start = i * per;
            if (start >= total) break;
            final long end = Math.min(total, start + per) - 1;
            final File seg = new File(segDir, "seg" + i);
            long len = end - start + 1;
            long have = seg.exists() ? seg.length() : 0;
            if (have > len) {
                deleteQuietly(seg);
                have = 0;
            }
            done.addAndGet(have);
            if (have >= len) continue;

            final long from = start + have;
            Thread w = new Thread(() -> {
                try {
                    downloadRange(t, m.buildUrl(t.url), seg, start, end, done);
                } catch (Throwable e) {
                    err.compareAndSet(null, e);
                }
            }, "lm-seg-" + i);
            w.setDaemon(true);
            workers.add(w);
            w.start();
        }

        t.threads = Math.max(1, workers.size());
        notifyPlan(t, t.threads);
        if (workers.isEmpty()) {
            merge(segDir, part, n, total);
            return;
        }

        long lastGot = done.get();
        long lastT = System.currentTimeMillis();
        long lastNotify = 0;
        boolean pauseNotified = false;

        while (true) {
            Throwable e = err.get();
            if (e != null) {
                joinAll(workers);
                if (e instanceof CancelledException) throw new CancelledException();
                if (e instanceof RangeUnsupported) throw new RangeUnsupported();
                throw new IOException(e.getMessage() == null ? "分片下载失败" : e.getMessage());
            }
            if (t.cancelFlag) {
                joinAll(workers);
                throw new CancelledException();
            }
            boolean alive = false;
            for (Thread w : workers) if (w.isAlive()) alive = true;
            if (!alive) break;

            long now = System.currentTimeMillis();
            if (t.pauseFlag) {
                if (!pauseNotified) {
                    pauseNotified = true;
                    t.pausedAt = now;
                    t.speed = 0;
                    notifyProgress(t);
                    t.state = State.PAUSED;
                    notifyState(t, State.PAUSED, "已暂停");
                }
                lastT = now;
                lastGot = done.get();
                sleep(120);
                continue;
            }
            if (pauseNotified) {
                pauseNotified = false;
                if (t.pausedAt > 0) {
                    t.pausedTotal += now - t.pausedAt;
                    t.pausedAt = 0;
                }
                t.state = State.RUNNING;
                notifyState(t, State.RUNNING, "继续");
                lastT = now;
                lastGot = done.get();
            }
            long got = done.get();
            t.got = got;
            if (now - lastNotify >= 350) {
                double sec = Math.max((now - lastT) / 1000.0, 0.001);
                t.speed = ((got - lastGot) / 1048576.0) / sec;
                notifyProgress(t);
                lastT = now;
                lastGot = got;
                lastNotify = now;
            }
            sleep(90);
        }
        Throwable e = err.get();
        if (e != null) {
            if (e instanceof CancelledException) throw new CancelledException();
            if (e instanceof RangeUnsupported) throw new RangeUnsupported();
            throw new IOException(e.getMessage() == null ? "分片下载失败" : e.getMessage());
        }
        for (int i = 0; i < n; i++) {
            long start = i * per;
            if (start >= total) break;
            long len = Math.min(total, start + per) - start;
            File seg = new File(segDir, "seg" + i);
            if (!seg.exists() || seg.length() < len) throw new IOException("分片不完整");
        }
        merge(segDir, part, n, total);
    }

    /** 单段下载：断了就从分片文件当前长度续上，最多试 3 次 */
    private void downloadRange(Task t, String url, File seg, long start, long end,
                               AtomicLong done) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (t.cancelFlag) throw new CancelledException();
            long have = seg.exists() ? seg.length() : 0;
            if (start + have > end) return;                 // 这段已经齐了
            try {
                transfer(t, url, seg, start + have, end, done);
                return;
            } catch (RangeUnsupported | CancelledException e) {
                throw e;
            } catch (IOException e) {
                last = e;
                if (t.cancelFlag) throw new CancelledException();
                sleep(300L * (attempt + 1));
            }
        }
        throw last == null ? new IOException("分片下载失败") : last;
    }

    private void transfer(Task t, String url, File seg, long from, long to,
                          AtomicLong done) throws IOException {
        HttpURLConnection c = open(url, "bytes=" + from + "-" + to);
        try {
            int code = c.getResponseCode();
            if (code == 200) throw new RangeUnsupported();
            if (code != 206) throw new IOException("HTTP " + code);

            FileOutputStream fos = new FileOutputStream(seg, true);
            InputStream in = c.getInputStream();
            byte[] buf = new byte[65536];
            long remaining = to - from + 1;
            try {
                while (remaining > 0) {
                    if (t.cancelFlag) throw new CancelledException();
                    if (t.pauseFlag) {
                        sleep(120);
                        continue;
                    }
                    int k = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (k < 0) break;
                    fos.write(buf, 0, k);
                    remaining -= k;
                    done.addAndGet(k);
                }
            } finally {
                try {
                    fos.flush();
                } catch (Exception ignored) {
                }
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (remaining > 0 && !t.cancelFlag) throw new IOException("连接提前中断");
        } finally {
            c.disconnect();
        }
    }

    /** 把 seg0..segN 按顺序拼回一个文件，拼完再删分片 */
    private void merge(File segDir, File part, int n, long total) throws IOException {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(part, false);
            byte[] buf = new byte[65536];
            for (int i = 0; i < n; i++) {
                File seg = new File(segDir, "seg" + i);
                if (!seg.exists()) break;
                InputStream in = new FileInputStream(seg);
                try {
                    int k;
                    while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                } finally {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            deleteQuietly(part);
            throw e;
        } finally {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
        if (part.length() != total) {
            deleteQuietly(part);
            throw new IOException("合并后大小不符");
        }
        wipeDir(segDir);
    }

    // ---------------------------------------------------------------- 分片元数据

    private static boolean metaMatches(File dir, long total, int n) {
        File meta = new File(dir, "meta");
        if (!meta.exists()) return false;
        try {
            byte[] b = new byte[(int) Math.min(64, meta.length())];
            FileInputStream in = new FileInputStream(meta);
            int k;
            try {
                k = in.read(b);
            } finally {
                in.close();
            }
            String s = new String(b, 0, Math.max(0, k), "UTF-8").trim();
            return s.equals(total + " " + n);
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeMeta(File dir, long total, int n) {
        try {
            FileOutputStream out = new FileOutputStream(new File(dir, "meta"), false);
            try {
                out.write((total + " " + n).getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static void wipeParts(File part) {
        wipeDir(new File(part.getParentFile(), part.getName() + ".parts"));
    }

    private static void wipeDir(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) deleteQuietly(k);
        try {
            if (dir.exists()) dir.delete();
        } catch (Exception ignored) {
        }
    }

    private static void joinAll(List<Thread> list) {
        for (Thread th : list) {
            try {
                th.join(2500);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ---------------------------------------------------------------- 回调

    private static void notifyState(Task t, State s, String msg) {
        if (t.listener != null) t.listener.onState(s, msg);
    }

    private static void notifyMirror(Task t, String name, int attempt) {
        if (t.listener != null) t.listener.onMirror(name, attempt);
    }

    private static void notifyPlan(Task t, int threads) {
        if (t.listener != null) t.listener.onPlan(threads);
    }

    private static void notifyProgress(Task t) {
        if (t.listener != null) t.listener.onProgress(t.got, t.total, t.speed);
    }

    private static void notifyDone(Task t, File f, String name) {
        if (t.listener != null) t.listener.onDone(f, name);
    }

    // ---------------------------------------------------------------- 工具

    private static class Probe {
        long total;
        boolean rangeOk;
    }

    private Probe probe(String url) throws IOException {
        HttpURLConnection c = open(url, "bytes=0-0");
        try {
            int code = c.getResponseCode();
            Probe p = new Probe();
            if (code == 206) {
                p.total = parseTotal(c.getHeaderField("Content-Range"));
                p.rangeOk = p.total > 0;
            } else if (code == 200) {
                p.total = c.getContentLengthLong();
                p.rangeOk = false;
            } else {
                throw new IOException("HTTP " + code);
            }
            return p;
        } finally {
            c.disconnect();
        }
    }

    private static long parseTotal(String contentRange) {
        if (contentRange == null) return -1;
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) return -1;
        String s = contentRange.substring(slash + 1).trim();
        if (s.equals("*")) return -1;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private HttpURLConnection open(String url, String range) throws IOException {
        String cur = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(connectTimeout);
            c.setReadTimeout(readTimeout);
            c.setRequestProperty("User-Agent", "LightMirror/1.5 (Android)");
            c.setRequestProperty("Accept-Encoding", "identity");
            if (range != null) c.setRequestProperty("Range", range);
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("重定向缺少地址");
                cur = new URL(new URL(cur), loc).toString();
                continue;
            }
            return c;
        }
        throw new IOException("重定向过多");
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) f.delete();
        } catch (Exception ignored) {
        }
    }

    private static class CancelledException extends IOException {
    }

    /** 服务器对 Range 请求回了 200：这个镜像不能分段 */
    private static class RangeUnsupported extends IOException {
    }
}
