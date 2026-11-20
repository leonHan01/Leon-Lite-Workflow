package com.leon.workflow.admin;

import java.net.URI;
import java.util.Locale;

/** Only an exact HTTPS origin may own the authenticated workspace. */
public final class ServerAddress {
    public static final String DEFAULT = "https://121.40.254.112";
    private final URI origin;

    public ServerAddress(String value) {
        try {
            URI uri = new URI(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))
                    || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            origin = new URI("https", null, host, uri.getPort() == 443 ? -1 : uri.getPort(), null, null, null);
        } catch (Exception error) {
            throw new IllegalArgumentException("请输入 HTTPS 服务地址，不包含账号、路径或查询参数");
        }
    }

    public String origin() { return origin.toString(); }

    public boolean owns(String value) {
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
                    && origin.getHost().equalsIgnoreCase(uri.getHost()) && port(origin) == port(uri);
        } catch (Exception error) { return false; }
    }

    public String resolve(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.contains("\\"))
            throw new IllegalArgumentException("无效的管理路径");
        String result = origin.resolve(path).toString();
        if (!owns(result)) throw new IllegalArgumentException("管理路径必须属于当前服务");
        return result;
    }

    public static String filename(String value) {
        String name = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "leon-export.dat";
        return name.substring(0, Math.min(160, name.length()));
    }

    private static int port(URI uri) { return uri.getPort() < 0 ? 443 : uri.getPort(); }
}
