package com.leon.workflow.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class AlfredIconService {
    private final Path directory;
    private final List<String> bundledIcons;

    public AlfredIconService(@Value("${leon.alfred.icons-dir:${user.home}/Library/Application Support/Alfred/Alfred.alfredpreferences/workflows/leon_workflow/icons}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        try (var input = new ClassPathResource("alfred-icons/index.txt").getInputStream()) {
            this.bundledIcons = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .lines().filter(AlfredIconService::isIconName).toList();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load bundled Alfred icon index", e);
        }
    }

    public List<String> list() {
        List<String> names = new ArrayList<>(bundledIcons);
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                    .filter(AlfredIconService::isIconName).forEach(names::add);
        } catch (IOException ignored) { }
        return names.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo)).toList();
    }

    public Optional<byte[]> read(String name) {
        if (!isIconName(name)) return Optional.empty();
        Path file = directory.resolve(name).normalize();
        if (!directory.equals(file.getParent())) return Optional.empty();
        try {
            if (file.toRealPath().startsWith(directory.toRealPath()) && Files.isRegularFile(file)) {
                return Optional.of(Files.readAllBytes(file));
            }
        } catch (IOException ignored) { }
        if (bundledIcons.contains(name)) {
            try (var input = new ClassPathResource("alfred-icons/" + name).getInputStream()) {
                return Optional.of(input.readAllBytes());
            } catch (IOException ignored) { }
        }
        return Optional.empty();
    }

    private static boolean isIconName(String name) {
        return name != null && !name.contains("/") && !name.contains("\\")
                && name.toLowerCase(Locale.ROOT).endsWith(".png");
    }
}
