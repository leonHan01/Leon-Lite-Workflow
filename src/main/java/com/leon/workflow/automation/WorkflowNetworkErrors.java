package com.leon.workflow.automation;

import jakarta.mail.*;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import javax.net.ssl.SSLException;
import java.net.*;
import java.security.cert.CertificateException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.util.*;
import java.util.regex.Pattern;

/** Describe transport failures without returning provider replies, AUTH data, URLs or message bodies. */
final class WorkflowNetworkErrors {
    private static final Pattern SMTP_STATUS = Pattern.compile("(?m)^\\s*([45]\\d{2})(?:[ -]+([245]\\.\\d{1,3}\\.\\d{1,3}))?(?=\\s|$)");
    private WorkflowNetworkErrors() {}
    static String describe(Throwable error, boolean smtp, boolean timedOut) {
        var errors = causes(error);
        String reason;
        if (timedOut || errors.stream().anyMatch(e -> e instanceof SocketTimeoutException)) reason = "连接或响应超时。请检查网络可达性、服务器负载和节点超时设置。";
        else if (errors.stream().anyMatch(e -> "目标地址不允许访问".equals(e.getMessage()))) reason = "目标地址不允许访问。默认禁止内网、回环和云元数据地址，请检查服务器地址。";
        else if (errors.stream().anyMatch(e -> e instanceof UnknownHostException)) reason = "DNS 解析失败。请检查服务器域名是否正确以及服务器的 DNS 配置。";
        else if (errors.stream().anyMatch(e -> e instanceof ConnectException)) reason = "连接被拒绝或无法建立。请检查服务器地址、端口、防火墙及服务监听状态。";
        else if (errors.stream().anyMatch(e -> e instanceof NoRouteToHostException)) reason = "网络不可达。请检查服务器路由和防火墙配置。";
        else if (errors.stream().anyMatch(e -> e instanceof CertificateException || e instanceof CertPathBuilderException || e instanceof CertPathValidatorException)) reason = "TLS 证书校验失败。请检查证书有效期、信任链和服务器域名是否匹配。";
        else if (errors.stream().anyMatch(e -> e instanceof SSLException)) reason = "TLS 握手失败。请检查加密方式、端口、证书和服务器支持的 TLS 协议。";
        else if (smtp && errors.stream().anyMatch(e -> e instanceof AuthenticationFailedException || e instanceof MailAuthenticationException)) reason = "SMTP 认证失败。请检查用户名、邮箱授权码，以及邮箱是否已开启 SMTP 服务。";
        else if (smtp && errors.stream().anyMatch(e -> String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("starttls"))) reason = "SMTP 服务器未能启用 STARTTLS。请核对加密方式和端口，或使用服务器支持的 SSL/TLS 连接。";
        else if (smtp && errors.stream().anyMatch(e -> e instanceof SendFailedException)) reason = "SMTP 服务器拒绝邮件提交或收件人。请检查发信权限、收件人、邮箱配额及服务器限制。";
        else reason = smtp ? "SMTP 连接或邮件提交被服务器中断。请检查服务器发信策略、连接限制和邮箱配置。" : "HTTP 连接被关闭或响应读取失败。请检查目标服务状态和网络连接。";
        String status = "";
        if (smtp) for (var cause : errors) {
            var match = SMTP_STATUS.matcher(String.valueOf(cause.getMessage()));
            if (match.find()) { status = "\nSMTP 响应码：" + match.group(1) + (match.group(2) == null ? "" : " " + match.group(2)); break; }
        }
        return reason + status;
    }
    private static List<Throwable> causes(Throwable error) {
        List<Throwable> result = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> queue = new ArrayDeque<>(); queue.add(error);
        while (!queue.isEmpty() && result.size() < 32) {
            var current = queue.removeFirst(); if (!seen.add(current)) continue;
            result.add(current);
            if (current.getCause() != null) queue.addLast(current.getCause());
            if (current instanceof MessagingException mail && mail.getNextException() != null) queue.addLast(mail.getNextException());
            if (current instanceof MailSendException mail) for (var failed : mail.getFailedMessages().values()) if (failed != null) queue.addLast(failed);
        }
        return result;
    }
}
