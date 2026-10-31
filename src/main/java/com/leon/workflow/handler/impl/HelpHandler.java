package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.handler.AbstractEventHandler;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.COPY_TO_CLIPBOARD;

@Component
public class HelpHandler extends AbstractEventHandler {

    private static final String ICON = "201_指南.png";
    private static final List<Topic> TOPICS = List.of(
            topic("当前时间", "输入 now、time、时间或 shijian，得到北京时间、秒级和毫秒时间戳。可接 +1d、-30m，单位还有 s、h", "now", "now time 时间 shijian 偏移"),
            topic("时间戳", "输入 10 位秒或 13 位毫秒，例如 1735689600，得到北京时间和 UTC", "1735689600", "时间戳 timestamp unix utc"),
            topic("日期转时间戳", "输入北京时间，格式 yyyy-MM-dd HH:mm:ss，例如 2026-10-31 12:00:00", "2026-10-31 12:00:00", "日期 datetime 北京时间"),
            topic("日历", "输入 cal 或 日历。一行一周，周一开头，今天用方括号，最后一行是今天", "cal", "cal 日历 calendar 今天"),
            topic("Markdown", "输入 md 列出语法和 callout。md 表格、md 代码、md 链接 按关键词筛选，回车复制", "md", "md markdown 语法 表格 代码"),
            topic("计算", "输入算式，例如 1+2。支持加、减、乘、除和括号；较大的结果显示千分位，小于 1 时附带百分比", "1+2", "计算 calculator 加减"),
            topic("Git 提交", "输入 git，或 git 后面写提交说明。复制 add、commit、push", "git", "git commit push 提交"),
            topic("Maven 打包", "输入 mvn。复制 mvn clean install -Dmaven.test.skip=true", "mvn", "mvn maven 打包"),
            topic("全部片段", "输入 .. 或 。。，同时列出 git 和 mvn 片段", "..", "片段 snippet 。。"),
            topic("驼峰名称", "输入带连字符的英文，例如 sort-list。含 leetcode 的题目链接同样转成驼峰", "sort-list", "leetcode 驼峰 连字符"),
            topic("JSON 记录", "输入 JSON，例如 {\"a\":1}。保存记录并打开格式化页面", "{\"a\":1}", "json 记录 保存"),
            topic("有道翻译", "输入 fy 加内容，例如 fy hello。需要配置有道密钥", "fy hello", "fy 翻译 youdao translate"),
            topic("快捷项", "输入 all 列出全部，all2 翻页。直接输入关键词搜索键和值", "all", "all kv 快捷项 搜索 翻页"),
            topic("书签", "输入 tag 或 tags 查看标签。输入标题、拼音或标签前缀打开链接", "tag", "tag tags 书签 url 链接 拼音"),
            topic("配置", "输入配置项的标签或拼音前缀，复制值或打开其中的链接", "", "配置 diamond 拼音 标签"),
            topic("网页和工具", "直接输入名称、网址、集合、分组或备注。多个词要同时匹配，回车打开网页", "", "网页 工具 集合 备注 plugin"),
            topic("首页", "不输入内容时，显示设为首页的快捷项", "", "首页 空 home"),
            topic("重启", "输入 restart。只提示改用服务管理工具，不会重启服务", "restart", "restart 重启")
    );

    @Override
    public List<AlfredResult> doHandleAlfredEvent(AlfredParam alfredParam) {
        String query = remainder(alfredParam.getOriginParams());
        if (query == null) {
            return List.of();
        }
        List<String> terms = query.isEmpty() ? List.of() : List.of(query.toLowerCase(Locale.ROOT).split("\\s+"));
        List<AlfredResult> results = new ArrayList<>();
        for (Topic topic : TOPICS) {
            if (topic.matches(terms)) {
                results.add(topic.toResult());
            }
        }
        if (results.isEmpty()) {
            results.add(AlfredResult.builder()
                    .title("没有匹配的功能")
                    .subtitle("试试 help 时间、help 日历、help 翻译")
                    .autoComplete("help ")
                    .icon(ICON)
                    .build());
        }
        return results;
    }

    @Override
    public Boolean matchEvent(AlfredParam param, Boolean alreadyHaveResult) {
        return remainder(param.getOriginParams()) != null;
    }

    @Override
    public Integer getPriority() {
        return 45;
    }

    @Override
    public Boolean breakNextHandlerIfHaveResult() {
        return true;
    }

    private static String remainder(String origin) {
        String raw = StringUtils.trimToEmpty(origin);
        if (raw.isEmpty()) {
            return null;
        }
        int split = 0;
        while (split < raw.length() && !Character.isWhitespace(raw.charAt(split))) {
            split++;
        }
        String command = raw.substring(0, split);
        if (!"help".equalsIgnoreCase(command) && !"帮助".equals(command)) {
            return null;
        }
        return raw.substring(split).trim();
    }

    private static Topic topic(String title, String subtitle, String command, String keywords) {
        return new Topic(title, subtitle, command, keywords);
    }

    private record Topic(String title, String subtitle, String command, String keywords) {
        private boolean matches(List<String> terms) {
            String haystack = (title + " " + subtitle + " " + command + " " + keywords).toLowerCase(Locale.ROOT);
            for (String term : terms) {
                if (!haystack.contains(term)) {
                    return false;
                }
            }
            return true;
        }

        private AlfredResult toResult() {
            AlfredResult.AlfredResultBuilder builder = AlfredResult.builder()
                    .title(title)
                    .subtitle(subtitle)
                    .icon(ICON)
                    .type(COPY_TO_CLIPBOARD);
            if (!command.isEmpty()) {
                builder.msgNeedToCopy(command).autoComplete(command);
            }
            return builder.build();
        }
    }
}
