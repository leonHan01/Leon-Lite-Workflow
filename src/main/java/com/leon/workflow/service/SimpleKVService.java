package com.leon.workflow.service;

import com.leon.workflow.dao.entity.SimpleKV;
import com.leon.workflow.dao.repository.SimpleKVRepository;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class SimpleKVService {
    private final SimpleKVRepository repository;
    private final SimpleKVCacheService cache;

    public SimpleKVService(SimpleKVRepository repository, SimpleKVCacheService cache) {
        this.repository = repository;
        this.cache = cache;
    }

    public void save(SimpleKV request) {
        if (isUrl(request.getKvValue())) request.setKvType("url");
        if (request.getId() == null) {
            repository.save(request);
        } else {
            // Preserve the existing no-op behavior for unknown IDs.
            repository.findById(request.getId()).ifPresent(existing -> {
                existing.setKvKey(request.getKvKey());
                existing.setKvValue(request.getKvValue());
                existing.setKvType(request.getKvType());
                existing.setKvTags(request.getKvTags());
                existing.setKvCategory(request.getKvCategory());
                existing.setKvAction(request.getKvAction());
                existing.setKvIcon(request.getKvIcon());
                repository.save(existing);
            });
        }
        // Repository writes commit before the refreshed data is published.
        cache.refresh();
    }

    public void delete(Long id) {
        if (id != null) repository.deleteById(id);
        cache.refresh();
    }

    private boolean isUrl(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("http://") || normalized.startsWith("https://") || normalized.startsWith("ftp://");
    }
}
