package com.epp.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminTokenInterceptor implements HandlerInterceptor {

    public static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    @Value("${epp.admin.token:}")
    private String adminToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        if (!StringUtils.hasText(adminToken)) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "admin token not configured");
            return false;
        }

        String providedToken = request.getHeader(ADMIN_TOKEN_HEADER);
        if (!adminToken.equals(providedToken)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid admin token");
            return false;
        }

        return true;
    }
}
