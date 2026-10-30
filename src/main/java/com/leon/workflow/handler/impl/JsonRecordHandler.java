package com.leon.workflow.handler.impl;

import com.leon.workflow.constant.HandlerPriority;
import com.leon.workflow.dao.entity.JsonRecord;
import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.handler.AbstractEventHandler;
import com.leon.workflow.service.JsonRecordService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.OPEN_URL;

@Component
public class JsonRecordHandler extends AbstractEventHandler {

    private final JsonRecordService jsonRecordService;

    @Value("${leon.workflow.base-url:http://127.0.0.1:8080}")
    private String baseUrl;

    public JsonRecordHandler(JsonRecordService jsonRecordService) {
        this.jsonRecordService = jsonRecordService;
    }

    @Override
    public List<AlfredResult> doHandleAlfredEvent(AlfredParam alfredParam) {
        List<AlfredResult> results = new ArrayList<>();
        String input = alfredParam.getOriginParams();
        if (StringUtils.isBlank(input)) {
            return results;
        }

        JsonRecord jsonRecord = jsonRecordService.save(input);
        String pageUrl = buildPageUrl(jsonRecord.getId());
        results.add(AlfredResult.builder()
                .title(pageUrl)
                .subtitle("JSON 已保存，打开格式化页面")
                .uri(pageUrl)
                .type(OPEN_URL)
                .msgNeedToCopy(pageUrl)
                .build());
        return results;
    }

    private String buildPageUrl(Long id) {
        return StringUtils.removeEnd(baseUrl, "/") + "/json-record.html?id=" + id;
    }

    @Override
    public Boolean matchEvent(AlfredParam alfredParam, Boolean alreadyHaveResult) {
        return jsonRecordService.parse(alfredParam.getOriginParams()).isValid();
    }

    @Override
    public Integer getPriority() {
        return HandlerPriority.HIGH;
    }

    @Override
    public Boolean breakNextHandlerIfHaveResult() {
        return true;
    }
}
