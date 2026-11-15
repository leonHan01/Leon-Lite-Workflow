package com.leon.workflow.automation;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowHttpAddressTest {
    @Test void qqSmtpPublicIpv6AddressesMustNotBeRejectedAs6to4() throws Exception {
        for (String ip : new String[]{"121.14.22.61", "183.47.102.176", "2402:4e00:1020:24d3:6:5bfc:f8ed:5808", "2402:4e00:1020:24d3:6:5bfc:f92d:eb90", "2602:abcd::1"})
            assertTrue(WorkflowHttp.publicAddress(InetAddress.getByName(ip)), "公网地址被拒绝: " + ip);
    }
    @Test void privateSpecialAndActual6to4AddressesRemainBlocked() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "10.0.0.1", "192.168.1.1", "169.254.169.254", "100.100.100.200", "::", "::1", "fc00::1", "fe80::1", "2001:db8::1", "2001::1", "2002:a00:1::", "2002:7f00:1::", "::ffff:127.0.0.1"})
            assertFalse(WorkflowHttp.publicAddress(InetAddress.getByName(ip)), "受限地址被放行: " + ip);
    }
}
