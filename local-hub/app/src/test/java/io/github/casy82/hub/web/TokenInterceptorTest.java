package io.github.casy82.hub.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.github.casy82.hub.config.HubProperties;

class TokenInterceptorTest {

    static TokenInterceptor interceptor(String token, List<String> allowedHosts) {
        return new TokenInterceptor(new HubProperties(".", "data", List.of("docker-compose.yml"), "t", "t", List.of(),
                Duration.ofSeconds(60), Duration.ofMinutes(1), token,
                new HubProperties.Issues(false, "", "", List.of("local-hub"), true), allowedHosts));
    }

    static MockHttpServletRequest request(String method, String host, String remote) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, "/api/projects");
        r.setServerName(host);
        r.setServerPort(8090);
        r.setRemoteAddr(remote);
        return r;
    }

    static int status(TokenInterceptor i, MockHttpServletRequest r) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        return i.preHandle(r, res, null) ? 200 : res.getStatus();
    }

    @Test
    void 토큰이_없으면_Hub_PC_에서만_변경할_수_있다() throws Exception {
        TokenInterceptor i = interceptor("", List.of());
        assertThat(status(i, request("POST", "localhost", "127.0.0.1"))).isEqualTo(200);
        assertThat(status(i, request("POST", "localhost", "0:0:0:0:0:0:0:1"))).isEqualTo(200);
        assertThat(status(i, request("POST", "192.168.0.10", "192.168.0.23"))).isEqualTo(403);
        // 조회는 다른 PC 에서도 된다
        assertThat(status(i, request("GET", "192.168.0.10", "192.168.0.23"))).isEqualTo(200);
    }

    @Test
    void 토큰이_있으면_맞는_토큰만_받는다() throws Exception {
        TokenInterceptor i = interceptor("s3cret", List.of());
        MockHttpServletRequest ok = request("POST", "192.168.0.10", "192.168.0.23");
        ok.addHeader("X-Hub-Token", "s3cret");
        assertThat(status(i, ok)).isEqualTo(200);
        MockHttpServletRequest bad = request("POST", "localhost", "127.0.0.1");
        bad.addHeader("X-Hub-Token", "nope");
        assertThat(status(i, bad)).isEqualTo(401);
    }

    @Test
    void 다른_사이트에서_보낸_변경_요청은_막는다() throws Exception {
        TokenInterceptor i = interceptor("", List.of());
        MockHttpServletRequest csrf = request("POST", "localhost", "127.0.0.1");
        csrf.addHeader("Origin", "https://evil.example");
        assertThat(status(i, csrf)).isEqualTo(403);

        MockHttpServletRequest same = request("POST", "localhost", "127.0.0.1");
        same.addHeader("Origin", "http://localhost:8090");
        assertThat(status(i, same)).isEqualTo(200);

        MockHttpServletRequest noOrigin = request("POST", "localhost", "127.0.0.1");
        noOrigin.addHeader("Sec-Fetch-Site", "cross-site");
        assertThat(status(i, noOrigin)).isEqualTo(403);
    }

    @Test
    void 모르는_호스트_이름으로_들어온_요청은_조회도_막는다() throws Exception {
        // DNS 리바인딩: evil.example 이 127.0.0.1 을 가리키게 해서 브라우저로 API 를 읽으려는 경우
        assertThat(status(interceptor("", List.of()), request("GET", "evil.example", "127.0.0.1"))).isEqualTo(403);
        assertThat(status(interceptor("", List.of("hub-pc.local")), request("GET", "hub-pc.local", "192.168.0.2")))
                .isEqualTo(200);
    }
}
