package com.kooo.evdashboard.core;

import java.util.Locale;
import java.util.function.LongSupplier;

/** Percent/ETA describe the current measured step, never simulated whole-job progress. */
public final class TaskProgress {
    private final LongSupplier clock;
    private long started, stageStarted, lastAdvance, ended, done, total;
    private boolean running, successful;
    private String stage = "尚未开始";

    public TaskProgress() {this(() -> System.nanoTime() / 1_000_000);}
    public TaskProgress(LongSupplier clock) {this.clock = clock;}
    public synchronized void start(String label) {
        started = clock.getAsLong(); running = true; successful = false; ended = 0;
        stage(label, 0);
    }
    public synchronized void stage(String label, long bytes) {
        stage = label; total = Math.max(0, bytes); done = 0;
        stageStarted = lastAdvance = clock.getAsLong();
    }
    public synchronized void advance(long bytes) {
        long next = Math.min(total, Math.max(done, bytes));
        if (next > done) lastAdvance = clock.getAsLong();
        done = next;
    }
    public synchronized void finish(boolean success) {
        if (!running) return;
        ended = clock.getAsLong(); running = false; successful = success;
    }
    public synchronized Snapshot snapshot() {
        long now = running ? clock.getAsLong() : ended;
        long elapsed = Math.max(0, now - started), stepElapsed = Math.max(0, now - stageStarted);
        int percent = successful ? 100 : total > 0 ? (int) Math.min(99, done * 100.0 / total) : -1;
        long eta = -1;
        // At least two seconds of data; stale throughput is not a valid countdown.
        if (running && done > 0 && done < total && stepElapsed >= 2000 && now - lastAdvance < 10000)
            eta = (long) Math.ceil((total - done) * (double) stepElapsed / done);
        return new Snapshot(stage, percent, elapsed, eta, running, successful, done, total);
    }
    public record Snapshot(String stage, int percent, long elapsedMs, long remainingMs,
                           boolean running, boolean successful, long bytes, long totalBytes) {
        public String summary() {
            if (!running) return (successful ? "完成 100%" : "未完成") + " · 用时 " + duration(elapsedMs);
            String measured = percent < 0 ? "本步百分比不可计量" : "本步 " + percent + "%";
            String remaining = remainingMs < 0 ? "本步剩余：暂无法估算" : "本步剩余约 " + duration(remainingMs);
            return measured + " · 已用 " + duration(elapsedMs) + " · " + remaining;
        }
    }
    public static String duration(long ms) {
        long seconds = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
}
