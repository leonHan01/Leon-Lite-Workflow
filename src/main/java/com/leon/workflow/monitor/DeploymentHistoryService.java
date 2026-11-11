package com.leon.workflow.monitor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Reads atomic release manifests written by the deployment runner, including failed releases. */
@Service
public class DeploymentHistoryService {
    private static final Set<String> STATUSES = Set.of("DEPLOYING", "SUCCESS", "ROLLED_BACK", "FAILED", "ROLLBACK_FAILED");
    private final ObjectMapper mapper;
    private final Path directory;
    private final String currentId;

    public DeploymentHistoryService(ObjectMapper mapper,
            @Value("${leon.deployments.directory:./data/deployments}") String directory,
            @Value("${leon.deployments.current-id:}") String currentId) {
        this.mapper = mapper;
        this.directory = Path.of(directory);
        this.currentId = currentId;
    }

    public History history() {
        if (!Files.exists(directory)) return new History(currentId, List.of(), 0, true, 0);
        List<Deployment> records = new ArrayList<>();
        int unreadable = 0;
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                try {
                    if (Files.size(file) > 16384) throw new IllegalArgumentException();
                    Deployment record = mapper.readValue(Files.readString(file), Deployment.class);
                    if (record.id() == null || record.id().isBlank() || record.startedAt() == null
                            || record.version() == null || !STATUSES.contains(record.status())) throw new IllegalArgumentException();
                    records.add(record);
                } catch (IOException | RuntimeException error) {
                    // A damaged manifest must not take the metrics dashboard down or expose file contents.
                    unreadable++;
                }
            }
        } catch (IOException error) {
            return new History(currentId, List.of(), 0, false, 0);
        }
        records.sort(Comparator.comparing(Deployment::startedAt).reversed().thenComparing(Deployment::id));
        return new History(currentId, records.stream().limit(50).toList(), records.size(), true, unreadable);
    }

    public record Deployment(String id, Instant startedAt, Instant finishedAt, String version, String commit,
                             boolean dirty, String description, String status, String jarSha256) { }
    public record History(String currentId, List<Deployment> records, int total, boolean available, int unreadable) { }
}
