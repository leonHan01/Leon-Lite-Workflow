package com.leon.workflow.service;

import com.github.promeg.pinyinhelper.Pinyin;
import com.leon.workflow.dao.entity.SimpleKV;
import com.leon.workflow.dao.repository.SimpleKVRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class SimpleKVCacheService {

    private volatile List<SimpleKV> cache = List.of();

    private final SimpleKVRepository simpleKVRepository;

    public SimpleKVCacheService(SimpleKVRepository simpleKVRepository) {
        this.simpleKVRepository = simpleKVRepository;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    public synchronized void refresh() {
        cache = List.copyOf(simpleKVRepository.findAll());
        log.info("simple_kv cache refreshed, size: {}", cache.size());
    }

    public List<SimpleKV> getAll() {
        return cache;
    }

    public List<SimpleKV> getAllPaged(int page, int pageSize) {
        List<SimpleKV> snapshot = cache;
        int total = snapshot.size();
        long start = ((long) Math.max(1, page) - 1) * pageSize;
        if (start >= total) {
            return new ArrayList<>();
        }
        int fromIndex = (int) start;
        int toIndex = Math.min(fromIndex + pageSize, total);
        return snapshot.subList(fromIndex, toIndex);
    }

    public int getTotalPages(int pageSize) {
        return (int) Math.ceil((double) cache.size() / pageSize);
    }

    public List<SimpleKV> getHomeItems() {
        List<SimpleKV> homeItems = new ArrayList<>();
        for (SimpleKV kv : cache) {
            if (kv.getKvAction() != null && kv.getKvAction().contains("首页")) {
                homeItems.add(kv);
            }
        }
        return homeItems;
    }

    /**
     * 模糊匹配 key、tags 和 category
     */
    public List<SimpleKV> fuzzyMatch(String input) {
        if (StringUtils.isBlank(input)) {
            return new ArrayList<>();
        }
        String lowerInput = input.toLowerCase();
        List<SimpleKV> matched = new ArrayList<>();
        for (SimpleKV kv : cache) {
            if (matchKeyOrTags(kv, lowerInput)) {
                matched.add(kv);
            }
        }
        return matched;
    }

    private boolean matchKeyOrTags(SimpleKV kv, String lowerInput) {
        if (kv.getKvKey() != null && matchText(kv.getKvKey(), lowerInput)) {
            return true;
        }
        if (StringUtils.isNotBlank(kv.getKvCategory()) && matchText(kv.getKvCategory(), lowerInput)) {
            return true;
        }
        if (StringUtils.isNotBlank(kv.getKvTags())) {
            String[] tags = kv.getKvTags().split(",");
            for (String tag : tags) {
                if (matchText(tag.trim(), lowerInput)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchText(String text, String lowerInput) {
        if (text.toLowerCase().contains(lowerInput)) {
            return true;
        }
        // 拼音匹配
        String pinyin = toPinyin(text);
        return pinyin.contains(lowerInput);
    }

    private String toPinyin(String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Pinyin.isChinese(c)) {
                sb.append(Pinyin.toPinyin(c).toLowerCase());
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}
