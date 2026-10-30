package io.github.casy82.hub.web;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.github.casy82.hub.config.HubProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * API 보호. compose.yml 로 아무 컨테이너나 띄울 수 있으므로 변경 요청은 Hub PC 를 다루는 것과 같다.
 * <ul>
 * <li>Host 가 localhost, IP 주소, hub.allowed-hosts 중 하나여야 한다 (DNS 리바인딩으로 다른 사이트가 API 를 읽지 못하게)</li>
 * <li>변경 요청(GET 이외)에 Origin 이 있으면 대시보드와 같은 주소여야 한다 (다른 사이트에서 몰래 보내는 요청 차단)</li>
 * <li>변경 요청은 hub.api-token 이 있으면 X-Hub-Token 헤더가 맞아야 하고, 없으면 Hub PC 자신에서 온 요청만 받는다</li>
 * </ul>
 */
@Configuration
public class TokenInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final HubProperties props;

    public TokenInterceptor(HubProperties props) {
        this.props = props;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!allowedHost(request.getServerName())) {
            return reject(response, 403, "허용되지 않은 주소로 접속했습니다: " + request.getServerName()
                    + " (hub.allowed-hosts 에 추가하면 됩니다)");
        }
        if ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())) {
            return true;
        }
        if (!sameOrigin(request)) {
            return reject(response, 403, "다른 사이트에서 보낸 요청은 받지 않습니다");
        }
        if (props.apiToken().isBlank()) {
            if (isLoopback(request.getRemoteAddr())) {
                return true;
            }
            return reject(response, 403,
                    "hub.api-token 이 비어 있어 Hub PC 에서만 변경할 수 있습니다. 다른 PC 에서 쓰려면 hub.yml 에 api-token 을 설정하세요");
        }
        String given = request.getHeader("X-Hub-Token");
        if (given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                props.apiToken().getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        return reject(response, 401, "X-Hub-Token 이 올바르지 않습니다");
    }

    private boolean allowedHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".localhost") || isIpLiteral(h)) {
            return true;
        }
        return props.allowedHosts().stream().anyMatch(a -> a.equalsIgnoreCase(h));
    }

    /** 브라우저는 다른 사이트에서 보내는 POST 등에 Origin 을 붙인다. curl 처럼 Origin 이 없으면 통과 */
    private static boolean sameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null) {
            return !"cross-site".equals(request.getHeader("Sec-Fetch-Site"));
        }
        try {
            URI o = URI.create(origin);
            int port = o.getPort() != -1 ? o.getPort() : "https".equals(o.getScheme()) ? 443 : 80;
            return request.getServerName().equalsIgnoreCase(o.getHost()) && request.getServerPort() == port;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isIpLiteral(String host) {
        return host.matches("[0-9.]+") || host.startsWith("[") || host.contains(":");
    }

    private static boolean isLoopback(String addr) {
        try {
            // 주소 문자열만 해석하므로 DNS 조회는 일어나지 않는다
            return addr != null && InetAddress.getByName(addr).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean reject(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
        return false;
    }
}
