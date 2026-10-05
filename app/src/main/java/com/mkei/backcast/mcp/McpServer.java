package com.mkei.backcast.mcp;

import java.net.URI;

/** Immutable user-authorized remote endpoint; credentials never appear in labels. */
public final class McpServer {
    public final String id, name, endpoint, bearerToken;
    public final boolean enabled;
    public final int timeoutSeconds;

    public McpServer(String id, String name, String endpoint, String bearerToken,
            boolean enabled, int timeoutSeconds) {
        if (id == null || !id.matches("[a-z0-9_-]{1,40}"))
            throw new IllegalArgumentException("MCP 连接 ID 无效");
        String label = name == null ? "" : name.trim();
        if (label.length() == 0 || label.length() > 80)
            throw new IllegalArgumentException("MCP 名称须为 1–80 个字符");
        String address = endpoint == null ? "" : endpoint.trim();
        try {
            URI uri = new URI(address);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || address.length() > 2048)
                throw new IllegalArgumentException();
        } catch (Exception invalid) { throw new IllegalArgumentException("MCP 地址须为 HTTP/HTTPS，不能含用户名、密码或片段"); }
        String token = bearerToken == null ? "" : bearerToken.trim();
        for (int i = 0; i < token.length(); i++) {
            if (token.charAt(i) < 0x21 || token.charAt(i) > 0x7e)
                throw new IllegalArgumentException("MCP Bearer 密钥含无效字符");
        }
        if (token.length() > 8192 || timeoutSeconds < 5 || timeoutSeconds > 300)
            throw new IllegalArgumentException("MCP 超时须为 5–300 秒，密钥不能过长");
        this.id = id; this.name = label; this.endpoint = address; this.bearerToken = token;
        this.enabled = enabled; this.timeoutSeconds = timeoutSeconds;
    }
}
