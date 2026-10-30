package com.leon.workflow.service;

import com.leon.workflow.dao.entity.ToolboxItem;
import com.leon.workflow.dao.repository.ToolboxItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.leon.workflow.service.Payloads.clip;
import static com.leon.workflow.service.Payloads.normalizeUrl;
import static com.leon.workflow.service.Payloads.requiredString;
import static com.leon.workflow.service.Payloads.stringValue;

@Service
public class ToolboxItemService {
    private final ToolboxItemRepository toolboxItemRepository;

    public ToolboxItemService(ToolboxItemRepository toolboxItemRepository) {
        this.toolboxItemRepository = toolboxItemRepository;
    }

    public List<Map<String, Object>> listItems() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolboxItem item : toolboxItemRepository.findByDeletedFalseOrderBySortOrderAscIdAsc()) {
            result.add(toResponse(item));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> createItem(Map<String, Object> request) {
        String url = normalizeUrl(requiredString(request.get("url"), "网址不能为空"));
        for (ToolboxItem existing : toolboxItemRepository.findByDeletedFalseOrderBySortOrderAscIdAsc()) {
            if (url.equals(existing.getUrl())) {
                return toResponse(existing);
            }
        }
        LocalDateTime now = LocalDateTime.now();
        ToolboxItem item = new ToolboxItem();
        item.setName(clip(requiredString(request.get("name"), "名称不能为空"), 200));
        item.setUrl(url);
        item.setFavIconUrl(stringValue(request.get("favIconUrl")));
        item.setIcon(clip(stringValue(request.get("icon")), 64));
        item.setSortOrder(nextSortOrder());
        item.setDeleted(false);
        item.setCreateTime(now);
        item.setUpdateTime(now);
        return toResponse(toolboxItemRepository.save(item));
    }

    @Transactional
    public Map<String, Object> updateItem(Long id, Map<String, Object> request) {
        ToolboxItem item = requireItem(id);
        if (request.containsKey("name")) {
            item.setName(clip(requiredString(request.get("name"), "名称不能为空"), 200));
        }
        if (request.containsKey("url")) {
            item.setUrl(normalizeUrl(requiredString(request.get("url"), "网址不能为空")));
        }
        if (request.containsKey("favIconUrl")) {
            item.setFavIconUrl(stringValue(request.get("favIconUrl")));
        }
        if (request.containsKey("icon")) {
            item.setIcon(clip(stringValue(request.get("icon")), 64));
        }
        item.setUpdateTime(LocalDateTime.now());
        return toResponse(toolboxItemRepository.save(item));
    }

    @Transactional
    public void deleteItem(Long id) {
        ToolboxItem item = requireItem(id);
        item.setDeleted(true);
        item.setUpdateTime(LocalDateTime.now());
        toolboxItemRepository.save(item);
    }

    private Integer nextSortOrder() {
        return toolboxItemRepository.findByDeletedFalseOrderBySortOrderAscIdAsc()
                .stream()
                .map(ToolboxItem::getSortOrder)
                .max(Integer::compareTo)
                .orElse(-1) + 1;
    }

    private ToolboxItem requireItem(Long id) {
        return toolboxItemRepository.findById(id)
                .filter(item -> !Boolean.TRUE.equals(item.getDeleted()))
                .orElseThrow(() -> new IllegalArgumentException("工具不存在: " + id));
    }

    private Map<String, Object> toResponse(ToolboxItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.getId());
        result.put("name", item.getName());
        result.put("url", item.getUrl());
        result.put("favIconUrl", item.getFavIconUrl());
        result.put("icon", item.getIcon() == null ? "" : item.getIcon());
        result.put("sortOrder", item.getSortOrder());
        result.put("createTime", item.getCreateTime());
        result.put("updateTime", item.getUpdateTime());
        return result;
    }
}
