package com.leon.workflow.monitor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

@Service
public class DeploymentControlService {
    private static final Set<String> RUNNING = Set.of("QUEUED", "FETCHING", "BUILDING", "DEPLOYING", "VERIFYING");
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final Path directory;
    private final Path lock;

    public DeploymentControlService(ObjectMapper mapper,
            @Value("${leon.deployments.enabled:false}") boolean enabled,
            @Value("${leon.deployments.control-directory:./data/deploy-control}") String directory,
            @Value("${leon.deployments.lock-file:/opt/leon-lite-workflow/.deployment-lock}") String lock) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.directory = Path.of(directory);
        this.lock = Path.of(lock);
    }

    public Control control() {
        Job job = null;
        try {
            Path state = directory.resolve("job.json");
            if (Files.isRegularFile(state) && Files.size(state) <= 16384) job = mapper.readValue(Files.readString(state), Job.class);
        } catch (IOException | RuntimeException ignored) { /* Availability is independent of metrics. */ }
        boolean busy = Files.exists(directory.resolve("request.json")) || Files.exists(lock)
                || (job != null && job.stage() != null && RUNNING.contains(job.stage()));
        return new Control(enabled, busy, "https://github.com/leonHan01/Leon-Lite-Workflow", "main", job);
    }

    public synchronized Job request() throws IOException {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "服务器尚未启用一键部署");
        if (control().busy()) throw new ResponseStatusException(HttpStatus.CONFLICT, "已有部署正在执行");
        Files.createDirectories(directory);
        Instant time = Instant.now();
        String version = DateTimeFormatter.ofPattern("yyyy.MM.dd.HHmmss").withZone(ZoneId.of("Asia/Shanghai")).format(time);
        String id = "web-release-" + version + "-" + UUID.randomUUID().toString().substring(0, 8);
        Job job = new Job(id, "QUEUED", "部署已排队，等待服务器开始执行。", time, null);
        Path request = directory.resolve("request.json");
        Path temporary = Files.createTempFile(directory, ".request-", ".tmp");
        try {
            Files.writeString(temporary, mapper.writeValueAsString(new Request(id, time, version)));
            writeJob(job);
            // Publish a complete request atomically, without replacing an existing request.
            Files.createLink(request, temporary);
        } catch (IOException error) {
            writeJob(new Job(id, "FAILED", "部署请求提交失败，请稍后重试。", time, Instant.now()));
            throw error;
        } finally { Files.deleteIfExists(temporary); }
        return job;
    }

    private void writeJob(Job job) throws IOException {
        Path temporary = Files.createTempFile(directory, ".job-", ".tmp");
        try {
            Files.writeString(temporary, mapper.writeValueAsString(job));
            Files.move(temporary, directory.resolve("job.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    public record Request(String id, Instant requestedAt, String version) { }
    public record Job(String id, String stage, String message, Instant startedAt, Instant finishedAt) { }
    public record Control(boolean enabled, boolean busy, String repository, String branch, Job job) { }
}
