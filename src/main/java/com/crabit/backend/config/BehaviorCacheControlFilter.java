package com.crabit.backend.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 4)
public class BehaviorCacheControlFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.contains("/behavior-metrics")
                || path.endsWith("/profile-visits")
                || path.endsWith("/feed-results")
                || path.endsWith("/feed-events")) response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
