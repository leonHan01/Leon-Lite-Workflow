package com.leon.workflow.monitor;

import com.sun.management.OperatingSystemMXBean;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class MonitoringService {
    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_SAMPLES = 24 * 60;
    private final JdbcTemplate jdbc;
    private final Deque<Sample> samples = new ArrayDeque<>();
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "monitor-sampler");
        thread.setDaemon(true);
        return thread;
    });

    public MonitoringService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    void start() { sampler.scheduleWithFixedDelay(this::sampleSafely, 0, 1, TimeUnit.MINUTES); }
    @PreDestroy
    void stop() { sampler.shutdownNow(); }

    private void sampleSafely() {
        try { sample(); } catch (RuntimeException error) {
            log.warn("Monitor sampling failed: {}", error.getClass().getSimpleName());
        }
    }

    synchronized void sample() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        Double cpu = null, memory = null;
        Long memoryTotal = null, memoryUsed = null;
        if (bean instanceof OperatingSystemMXBean os) {
            double load = os.getCpuLoad();
            if (load >= 0) cpu = percent(load);
            long total = os.getTotalMemorySize(), free = os.getFreeMemorySize();
            if (total > 0 && free >= 0 && free <= total) {
                memoryTotal = total;
                memoryUsed = total - free;
                memory = percent((double) memoryUsed / total);
            }
        }
        File root = new File("/");
        long total = root.getTotalSpace(), free = root.getFreeSpace();
        Long diskTotal = total > 0 ? total : null;
        Long diskUsed = total > 0 && free >= 0 && free <= total ? total - free : null;
        Double disk = diskUsed != null ? percent((double) diskUsed / total) : null;
        samples.addLast(new Sample(Instant.now(), cpu, memory, memoryUsed, memoryTotal, disk, diskUsed, diskTotal));
        while (samples.size() > MAX_SAMPLES) samples.removeFirst();
    }

    private static double percent(double fraction) { return Math.round(fraction * 1000.0) / 10.0; }

    public synchronized void recordView() {
        LocalDate day = LocalDate.now(ZONE);
        int changed = jdbc.update("UPDATE monitor_page_views SET views = views + 1 WHERE view_date = ?", day);
        if (changed == 0) jdbc.update("INSERT INTO monitor_page_views (view_date, views) VALUES (?, 1)", day);
    }

    public synchronized Dashboard dashboard() {
        LocalDate today = LocalDate.now(ZONE), first = today.minusDays(29);
        Map<LocalDate, Long> totals = new java.util.HashMap<>();
        jdbc.query("SELECT view_date, views FROM monitor_page_views WHERE view_date >= ? AND view_date <= ?", rs -> {
            totals.put(rs.getDate(1).toLocalDate(), rs.getLong(2));
        }, first, today);
        List<DailyViews> days = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            days.add(new DailyViews(day, totals.getOrDefault(day, 0L)));
        }
        return new Dashboard(samples.peekLast(), List.copyOf(samples), days);
    }

    public record Sample(Instant time, Double cpuPercent, Double memoryPercent, Long memoryUsedBytes,
                         Long memoryTotalBytes, Double diskPercent, Long diskUsedBytes, Long diskTotalBytes) { }
    public record DailyViews(LocalDate day, long views) { }
    public record Dashboard(Sample current, List<Sample> samples, List<DailyViews> pageViews) { }
}
