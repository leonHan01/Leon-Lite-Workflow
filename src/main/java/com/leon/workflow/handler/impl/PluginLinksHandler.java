package com.leon.workflow.handler.impl;

import com.leon.workflow.dao.entity.WebCollection;
import com.leon.workflow.dao.entity.WebCollectionGroup;
import com.leon.workflow.dao.repository.ToolboxItemRepository;
import com.leon.workflow.dao.repository.WebCollectionGroupRepository;
import com.leon.workflow.dao.repository.WebCollectionItemRepository;
import com.leon.workflow.dao.repository.WebCollectionRepository;
import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.handler.AbstractEventHandler;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.OPEN_URL;

@Component
public class PluginLinksHandler extends AbstractEventHandler {
    private final WebCollectionRepository collections;
    private final WebCollectionGroupRepository groups;
    private final WebCollectionItemRepository items;
    private final ToolboxItemRepository tools;

    public PluginLinksHandler(WebCollectionRepository collections, WebCollectionGroupRepository groups,
                              WebCollectionItemRepository items, ToolboxItemRepository tools) {
        this.collections = collections;
        this.groups = groups;
        this.items = items;
        this.tools = tools;
    }

    @Override
    public List<AlfredResult> doHandleAlfredEvent(AlfredParam param) {
        List<String> terms = param.getParams().stream().map(term -> term.toLowerCase(Locale.ROOT)).toList();
        List<AlfredResult> results = new ArrayList<>();
        for (var tool : tools.findByDeletedFalseOrderBySortOrderAscIdAsc()) {
            if (isWebUrl(tool.getUrl()) && matches(terms, tool.getName(), tool.getUrl())) {
                results.add(result(tool.getName(), "工具 · " + tool.getUrl(), tool.getUrl()));
            }
        }
        List<WebCollection> active = collections.findByDeletedFalseOrderBySortOrderAscIdAsc();
        if (active.isEmpty()) return results;
        List<Long> ids = active.stream().map(WebCollection::getId).toList();
        Map<Long, WebCollection> byId = active.stream().collect(Collectors.toMap(WebCollection::getId, collection -> collection));
        Map<Long, WebCollectionGroup> groupById = groups.findByCollectionIdInAndDeletedFalseOrderBySortOrderAscIdAsc(ids)
                .stream().collect(Collectors.toMap(WebCollectionGroup::getId, group -> group));
        for (var item : items.findByCollectionIdInAndDeletedFalseOrderBySortOrderAscIdAsc(ids)) {
            WebCollection collection = byId.get(item.getCollectionId());
            WebCollectionGroup group = groupById.get(item.getGroupId());
            if (collection == null || group == null || !item.getCollectionId().equals(group.getCollectionId())) continue;
            if (isWebUrl(item.getUrl()) && matches(terms, item.getName(), item.getUrl(), item.getNote(),
                    collection.getName(), collection.getDescription(), group.getName())) {
                results.add(result(item.getName(), "集合 · " + collection.getName() + " / " + group.getName()
                        + " · " + item.getUrl(), item.getUrl()));
            }
        }
        return results;
    }

    private boolean matches(List<String> terms, String... fields) {
        return terms.stream().allMatch(term -> {
            for (String field : fields) {
                if (field != null && field.toLowerCase(Locale.ROOT).contains(term)) return true;
            }
            return false;
        });
    }

    private boolean isWebUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    private AlfredResult result(String title, String subtitle, String url) {
        return AlfredResult.builder().title(title).subtitle(subtitle).uri(url).msgNeedToCopy(url)
                .type(OPEN_URL).icon("link.png").build();
    }

    @Override
    public Boolean matchEvent(AlfredParam param, Boolean alreadyHaveResult) {
        return !param.getParams().isEmpty();
    }

    @Override
    public Integer getPriority() { return 49; }
}
