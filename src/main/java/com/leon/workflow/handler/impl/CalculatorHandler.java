package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.handler.AbstractEventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.COPY_TO_CLIPBOARD_AND_PASTE;

@Component
@Slf4j
public class CalculatorHandler extends AbstractEventHandler {

    @Override
    public List<AlfredResult> doHandleAlfredEvent(AlfredParam alfredParam) {
        List<AlfredResult> results = new ArrayList<>();
        String expression = alfredParam.getOriginParams();
        if (expression == null || expression.trim().isEmpty()) {
            return results;
        }

        try {
            String sanitized = expression.replaceAll("\\s+", "");
            double evalResult = new ArithmeticExpression(sanitized).evaluate();
            if (!Double.isFinite(evalResult)) throw new IllegalArgumentException("Non-finite result");
            // 整数结果去掉小数点
            String resultStr = (evalResult == (long) evalResult) ? String.valueOf((long) evalResult) : String.valueOf(evalResult);
            if (evalResult > 1000) {
                String formattedResultStr = formatWithThousandsSeparator(evalResult);
                results.add(buildCalculatorResult(expression, formattedResultStr, "点击复制千分位结果"));
            }
            if (evalResult > 0 && evalResult < 1) {
                String percentResultStr = formatAsPercent(evalResult);
                results.add(buildCalculatorResult(expression, percentResultStr, "点击复制百分比结果"));
            }
            results.add(buildCalculatorResult(expression, resultStr, "点击复制结果"));
        } catch (IllegalArgumentException e) {
            log.warn("Calculator evaluation failed: {}", e.getClass().getSimpleName());
            results.add(AlfredResult.builder().title("计算出错，请检查表达式").icon("calculator.png").build());
        }
        return results;
    }

    private AlfredResult buildCalculatorResult(String expression, String resultStr, String subtitle) {
        return AlfredResult.builder()
                .title(expression + " = " + resultStr)
                .subtitle(subtitle)
                .type(COPY_TO_CLIPBOARD_AND_PASTE)
                .msgNeedToCopy(resultStr)
                .icon("计算器.png")
                .build();
    }

    private String formatWithThousandsSeparator(double evalResult) {
        DecimalFormat decimalFormat = new DecimalFormat("#,##0.##########", DecimalFormatSymbols.getInstance(Locale.US));
        return decimalFormat.format(evalResult);
    }

    private String formatAsPercent(double evalResult) {
        return String.format(Locale.US, "%.2f%%", evalResult * 100);
    }

    @Override
    public Boolean matchEvent(AlfredParam alfredParam, Boolean alreadyHaveResult) {
        String param = alfredParam.getOriginParams();
        if (param == null || param.trim().isEmpty()) {
            return false;
        }
        String sanitized = param.replaceAll("\\s+", "");
        // 匹配包含数字和运算符的数学表达式，如 1+2, 3*4+(5-6)
        return sanitized.matches("^[0-9(\\-][0-9+\\-*/().]+$");
    }

    @Override
    public Boolean breakNextHandlerIfHaveResult() {
        return true;
    }
}
