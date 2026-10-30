package com.leon.workflow.service;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.handler.AlfredEventHandler;
import com.leon.workflow.handler.impl.DefaultHandler;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.RUN_SCRIPT;

@Service
public class AlfredQueryService {
    private final List<AlfredEventHandler> handlers;
    private final DefaultHandler fallback;

    public AlfredQueryService(List<AlfredEventHandler> handlers, DefaultHandler fallback) {
        this.handlers = handlers.stream().sorted(Comparator.comparing(AlfredEventHandler::getPriority)
                .thenComparing(h -> h.getClass().getName())).toList();
        this.fallback = fallback;
    }

    public List<AlfredResult> query(String raw, String employeeId) {
        String input = raw.trim();
        AlfredParam param = new AlfredParam(input, input.isEmpty() ? List.of() : List.of(input.split("\\s+")));
        param.setEmployeeId(employeeId == null || employeeId.isBlank() ? "DEFAULT" : employeeId);
        List<AlfredResult> results = new ArrayList<>();
        if (!input.isEmpty()) {
            for (AlfredEventHandler handler : handlers) {
                if (!handler.matchEvent(param, !results.isEmpty())) continue;
                List<AlfredResult> found = handler.handleAlfredEvent(param);
                if (found != null && !found.isEmpty()) {
                    results.addAll(found);
                    if (handler.breakNextHandlerIfHaveResult()) break;
                }
            }
        }
        if (results.isEmpty()) results.addAll(fallback.handleAlfredEvent(param));
        return results.stream().limit(20).map(result -> normalize(result, input)).toList();
    }

    private AlfredResult normalize(AlfredResult result, String input) {
        result.setArg(RUN_SCRIPT.equals(result.getType()) ? null : input);
        if (result.getAutoComplete() == null || result.getAutoComplete().isBlank()) result.setAutoComplete(result.getTitle());
        result.setIcon("icons/" + (result.getIcon() == null || result.getIcon().isBlank() ? "default.png" : result.getIcon()));
        if (result.getSubtitle() == null) result.setSubtitle("");
        if (result.getFeatures() == null || result.getFeatures().isBlank()) result.setFeatures("version:20240104");
        return result;
    }
}
