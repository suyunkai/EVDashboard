package com.kooo.evdashboard.core;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class TaskProgressTest {
    @Test public void estimatesOnlyFromMeasuredBytes() {
        AtomicLong time=new AtomicLong(1000);TaskProgress p=new TaskProgress(time::get);
        p.start("导出");p.stage("复制",1000);p.advance(250);
        assertEquals(25,p.snapshot().percent());assertEquals(-1,p.snapshot().remainingMs());
        time.addAndGet(4000);p.advance(500);
        assertEquals(50,p.snapshot().percent());assertEquals(4000,p.snapshot().remainingMs());
        assertEquals(4000,p.snapshot().elapsedMs());
    }
    @Test public void staleOrUnmeasuredStepsNeverInventCountdown() {
        AtomicLong time=new AtomicLong();TaskProgress p=new TaskProgress(time::get);
        p.start("测试");p.stage("复制",1000);p.advance(500);time.set(15000);
        assertEquals(-1,p.snapshot().remainingMs());
        p.stage("重建",0);assertEquals(-1,p.snapshot().percent());assertEquals(-1,p.snapshot().remainingMs());
        assertEquals(15000,p.snapshot().elapsedMs());
    }
    @Test public void completionRequiresSuccessfulJobAndFailureFreezesClock() {
        AtomicLong time=new AtomicLong();TaskProgress p=new TaskProgress(time::get);
        p.start("复制");p.stage("复制",100);p.advance(100);
        assertEquals(99,p.snapshot().percent());time.set(5000);p.finish(false);time.set(9000);
        assertFalse(p.snapshot().successful());assertEquals(5000,p.snapshot().elapsedMs());assertEquals(99,p.snapshot().percent());
        p.start("检查");p.finish(true);assertEquals(100,p.snapshot().percent());assertTrue(p.snapshot().successful());
    }
    @Test public void countsDoNotGoBackwardsWithinStep() {
        TaskProgress p=new TaskProgress();p.start("任务");p.stage("复制",100);p.advance(50);p.advance(10);
        assertEquals(50,p.snapshot().bytes());p.advance(Long.MAX_VALUE);assertEquals(100,p.snapshot().bytes());
        assertEquals("01:01:01",TaskProgress.duration(3661000));
    }
    @Test public void unmeasuredWorkShowsElapsedTimeWithoutUnknownLabelsOrPercent() {
        AtomicLong time=new AtomicLong();TaskProgress p=new TaskProgress(time::get);p.start("连接 CP");time.set(7000);
        assertEquals("已用 00:00:07",p.snapshot().timing());assertEquals("已用 00:00:07",p.snapshot().summary());
        p.stage("复制",100);p.advance(25);
        assertEquals("当前步骤 25% · 已用 00:00:07",p.snapshot().summary());
        time.set(11000);p.advance(50);
        assertEquals("已用 00:00:11 · 预计剩余 00:00:04",p.snapshot().timing());
    }
    @Test public void failureAndCompletionUseFrozenElapsedTimeWithoutRemainingEstimate() {
        AtomicLong time=new AtomicLong();TaskProgress p=new TaskProgress(time::get);p.start("读取");time.set(5000);p.finish(false);time.set(10000);
        assertEquals("未完成 · 用时 00:00:05",p.snapshot().summary());
        p.start("读取");time.set(12000);p.finish(true);time.set(20000);
        assertEquals("已完成 · 用时 00:00:02",p.snapshot().summary());assertEquals(100,p.snapshot().percent());
    }
}
