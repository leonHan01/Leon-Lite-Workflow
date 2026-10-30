package com.leon.workflow.handler.impl;

import com.leon.workflow.dao.entity.SimpleKV;
import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.handler.AbstractEventHandler;
import com.leon.workflow.service.SimpleKVCacheService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.COPY_TO_CLIPBOARD;
import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.OPEN_URL;

@Component
public class DefaultHandler extends AbstractEventHandler {

    private final SimpleKVCacheService simpleKVCacheService;

    public DefaultHandler(SimpleKVCacheService simpleKVCacheService) {
        this.simpleKVCacheService = simpleKVCacheService;
    }

    @Override
    public List<AlfredResult> doHandleAlfredEvent(AlfredParam alfredParam) {
        List<AlfredResult> results = new ArrayList<>();
        List<SimpleKV> homeItems = simpleKVCacheService.getHomeItems();
        for (SimpleKV kv : homeItems) {
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
            results.add(result);
        }
        if (results.isEmpty()) {
            results.add(AlfredResult.builder().title("你好 from Leon Localhost").build());
        }
        return results;
    }

    @Override
    public Boolean matchEvent(AlfredParam param, Boolean alreadyHaveResult) {
        return false;
    }
}
