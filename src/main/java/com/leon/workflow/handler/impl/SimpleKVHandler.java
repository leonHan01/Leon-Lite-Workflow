package com.leon.workflow.handler.impl;

import com.leon.workflow.dao.entity.SimpleKV;
import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.handler.AbstractEventHandler;
import com.leon.workflow.service.SimpleKVCacheService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.COPY_TO_CLIPBOARD;
import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.OPEN_URL;

@Component
@Slf4j
public class SimpleKVHandler extends AbstractEventHandler {

    private static final int PAGE_SIZE = 9;

    private final SimpleKVCacheService simpleKVCacheService;

    public SimpleKVHandler(SimpleKVCacheService simpleKVCacheService) {
        this.simpleKVCacheService = simpleKVCacheService;
    }

    @Override
    public List<AlfredResult> doHandleAlfredEvent(AlfredParam alfredParam) {
        List<AlfredResult> results = new ArrayList<>();
        String input = alfredParam.getOriginParams();
        if (StringUtils.isBlank(input)) {
            return results;
        }

        // all 命令：展示所有 KV，支持翻页
        if (isAllCommand(input)) {
            int page = parseAllPage(input);
            return buildAllResults(page);
        }

        List<SimpleKV> matched = simpleKVCacheService.fuzzyMatch(input);
        // 置顶的排在前面
        matched.sort((a, b) -> {
            boolean aTop = a.getKvAction() != null && a.getKvAction().contains("置顶");
            boolean bTop = b.getKvAction() != null && b.getKvAction().contains("置顶");
            if (aTop && !bTop) return -1;
            if (!aTop && bTop) return 1;
            return 0;
        });
        for (SimpleKV kv : matched) {
            results.add(buildKVResult(kv));
        }
        return results;
    }

    private boolean isAllCommand(String input) {
        String trimmed = input.trim().toLowerCase();
        return trimmed.equals("all") || trimmed.matches("all\\d+");
    }

    private int parseAllPage(String input) {
        String trimmed = input.trim().toLowerCase();
        if (trimmed.equals("all")) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(trimmed.substring(3)));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private List<AlfredResult> buildAllResults(int page) {
        List<AlfredResult> results = new ArrayList<>();
        int totalPages = simpleKVCacheService.getTotalPages(PAGE_SIZE);
        List<SimpleKV> items = simpleKVCacheService.getAllPaged(page, PAGE_SIZE);

        // 页码提示
        AlfredResult header = AlfredResult.builder().build();
        header.setTitle("全部 KV（第 " + page + " / " + totalPages + " 页，共 " + simpleKVCacheService.getAll().size() + " 条）");
        header.setSubtitle("输入 all1 / all2 / all3 切换页码");
        header.setType(COPY_TO_CLIPBOARD);
        header.setMsgNeedToCopy("all" + page);
        results.add(header);

        for (SimpleKV kv : items) {
            results.add(buildKVResult(kv));
        }
        return results;
    }

    private AlfredResult buildKVResult(SimpleKV kv) {
        AlfredResult result = AlfredResult.builder().build();
        result.setTitle(kv.getKvKey());
        if (StringUtils.isNotBlank(kv.getKvIcon())) {
            result.setIcon(kv.getKvIcon());
        }
        if ("url".equals(kv.getKvType()) && StringUtils.isNotBlank(kv.getKvValue())) {
            result.setSubtitle(kv.getKvValue());
            result.setUri(kv.getKvValue());
            result.setType(OPEN_URL);
            result.setMsgNeedToCopy(kv.getKvValue());
        } else {
            result.setSubtitle(kv.getKvValue());
            result.setType(COPY_TO_CLIPBOARD);
            result.setMsgNeedToCopy(StringUtils.isNotBlank(kv.getKvValue()) ? kv.getKvValue() : kv.getKvKey());
        }
        return result;
    }

    @Override
    public Boolean matchEvent(AlfredParam alfredParam, Boolean alreadyHaveResult) {
        String input = alfredParam.getOriginParams();
        if (StringUtils.isBlank(input)) {
            return false;
        }
        return true;
    }

    @Override
    public Boolean breakNextHandlerIfHaveResult() {
        return true;
    }
}
