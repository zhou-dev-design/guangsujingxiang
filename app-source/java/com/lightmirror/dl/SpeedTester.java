package com.lightmirror.dl;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 顺序探测：一次只连一个镜像，拉一小段分片，量延迟与吞吐。
 * 分片大小与单镜像超时都由设置页给出，这里只负责执行。
 * 纯 Java，不依赖 Android，便于在桌面上跑单元验证。
 */
public class SpeedTester {

    public static final int PROBE_BYTES = 256 * 1024;   // 默认分片
    public static final int TIMEOUT_MS = 8000;          // 默认单镜像超时
    public static final double REF_SPEED_MB = 12.0;     // 速度满分线
    private static final int CONNECT_TIMEOUT = 4000;

    private final int probeBytes;
    private final int timeoutMs;
    private volatile boolean cancelled;

    public interface Listener {
        void onStart(int index, int total, Mirror m);

        void onResult(int index, int total, Result r);

        void onFinish(List<Result> results);
    }

    public static class Result {
        public final Mirror mirror;
        public boolean ok;
        public int latencyMs;
        public double speed; // MB/s
        public long bytes;
        public String error = "";

        public Result(Mirror m) {
            this.mirror = m;
        }

        /** 速度 70% + 延迟 30%（延迟超过 800ms 记 0 分） */
        public double score() {
            if (!ok) return 0;
            double s = Math.min(speed / REF_SPEED_MB, 1.0) * 70.0;
            double l = (1.0 - Math.min(latencyMs, 800) / 800.0) * 30.0;
            double score = s + l;
            // 首字节都要等好几秒的镜像，速度再好看也不敢用
            if (latencyMs > 2500) score *= 0.5;
            if (latencyMs > 4500) score *= 0.4;
            return score;
        }
    }

    public SpeedTester() {
        this(PROBE_BYTES, TIMEOUT_MS);
    }

    public SpeedTester(int probeBytes, int timeoutMs) {
        this.probeBytes = Math.max(16 * 1024, Math.min(8 * 1024 * 1024, probeBytes));
        this.timeoutMs = Math.max(1500, Math.min(60000, timeoutMs));
    }

    public void cancel() {
        cancelled = true;
    }

    public void run(List<Mirror> mirrors, String url, Listener l) {
        List<Result> out = new ArrayList<>();
        for (int i = 0; i < mirrors.size(); i++) {
            if (cancelled) break;
            Mirror m = mirrors.get(i);
            l.onStart(i + 1, mirrors.size(), m);
            Result r = probe(m, url, probeBytes, timeoutMs);
            out.add(r);
            l.onResult(i + 1, mirrors.size(), r);
        }
        l.onFinish(out);
    }

    public static Result probe(Mirror m, String target) {
        return probe(m, target, PROBE_BYTES, TIMEOUT_MS);
    }

    public static Result probe(Mirror m, String target, int probeBytes, int timeoutMs) {
        Result r = new Result(m);
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            long t0 = System.nanoTime();
            c = open(m.buildUrl(target), "bytes=0-" + (probeBytes - 1), timeoutMs);
            int code = c.getResponseCode();
            long tFirst = System.nanoTime();
            if (code != 200 && code != 206) {
                r.error = "HTTP " + code;
                return r;
            }
            r.latencyMs = (int) ((tFirst - t0) / 1000000L);
            in = c.getInputStream();
            byte[] buf = new byte[16384];
            long got = 0;
            int n;
            // 单镜像总时长兜底：超时立刻判负，不拖慢整轮测速
            long deadline = tFirst + timeoutMs * 1000000L;
            while (got < probeBytes && System.nanoTime() < deadline
                    && (n = in.read(buf)) > 0) {
                got += n;
            }
            long tEnd = System.nanoTime();
            r.bytes = got;
            double sec = Math.max((tEnd - tFirst) / 1000000000.0, 0.001);
            r.speed = (got / 1048576.0) / sec;
            r.ok = got >= Math.min(32 * 1024, probeBytes / 2);   // 太少视为无效（多为错误页）
            if (!r.ok) r.error = got == 0 ? "无数据返回" : "数据不完整";
        } catch (java.net.SocketTimeoutException e) {
            r.error = "超时";
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName();
        } finally {
            close(in);
            if (c != null) c.disconnect();
        }
        return r;
    }

    /** 手动跟随重定向（代理常见 302 → 对象存储），保持 Range 头。 */
    static HttpURLConnection open(String url, String range) throws IOException {
        return open(url, range, TIMEOUT_MS);
    }

    static HttpURLConnection open(String url, String range, int timeoutMs) throws IOException {
        int read = Math.max(1500, Math.min(60000, timeoutMs));
        int conn = Math.min(CONNECT_TIMEOUT, read);
        String cur = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(conn);
            c.setReadTimeout(read);
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

    static void close(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }
}
