package com.wren.agent.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class DebugTokenInterceptor implements HandlerInterceptor {

    @Value("${wren.debug.token:wren-debug-secret-2026}")
    private String debugToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String tokenHeader = request.getHeader("X-Debug-Token");
        if (tokenHeader == null || !tokenHeader.equals(debugToken)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Unauthorized: Invalid or missing X-Debug-Token\"}");
            return false;
        }
        return true;
    }
}
