package com.leon.workflow.automation;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import javax.net.ssl.SSLHandshakeException;
import java.net.*;
import java.security.cert.CertificateException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowNetworkErrorsTest {
    @Test void smtpAuthenticationErrorsExposeOnlyStatusCodesAndSafeGuidance() {
        var rejection = new AuthenticationFailedException("535 5.7.8 provider-private-payload test-password <img src=x>\r\n");
        var error = new MailSendException(Map.of(new Object(), rejection));
        String message = WorkflowNetworkErrors.describe(error, true, false);
        assertTrue(message.contains("SMTP 认证失败")); assertTrue(message.contains("535 5.7.8"));
        assertFalse(message.contains("provider-private-payload")); assertFalse(message.contains("test-password")); assertFalse(message.contains("<img"));
    }
    @Test void wrappedTransportFailuresHaveDistinctActionableReasonsWithoutRawMessages() {
        for (var pair : Map.<Exception, String>of(
                new UnknownHostException("private-host"), "DNS 解析失败",
                new ConnectException("raw-connect-message"), "连接被拒绝",
                new SocketTimeoutException("raw-timeout-message"), "超时",
                new UnknownHostException("目标地址不允许访问"), "目标地址不允许访问",
                new SSLHandshakeException("raw-tls-message"), "TLS 握手失败",
                new CertificateException("raw-certificate-message"), "TLS 证书校验失败").entrySet()) {
            String message = WorkflowNetworkErrors.describe(new MessagingException("outer-private-message", pair.getKey()), false, false);
            assertTrue(message.contains(pair.getValue()), message); assertFalse(message.contains("raw-")); assertFalse(message.contains("private-"));
        }
        assertTrue(WorkflowNetworkErrors.describe(new SocketException("Socket closed"), true, true).contains("超时"));
        assertTrue(WorkflowNetworkErrors.describe(new MessagingException("STARTTLS is required"), true, false).contains("未能启用 STARTTLS"));
    }
}
