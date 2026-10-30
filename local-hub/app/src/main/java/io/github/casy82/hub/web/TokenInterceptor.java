package io.github.casy82.hub.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.github.casy82.hub.config.HubProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** hub.api-token 이 있으면 조회(GET) 이외의 API 호출에 X-Hub-Token 헤더를 요구한다 */
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
        if ("GET".equals(request.getMethod()) || props.apiToken().isBlank()
                || props.apiToken().equals(request.getHeader("X-Hub-Token"))) {
            return true;
        }
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"X-Hub-Token 이 올바르지 않습니다\"}");
        return false;
    }
}
