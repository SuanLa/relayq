package com.suanla.relayq.example.api;

import com.suanla.relayq.core.support.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final int MAX_TRACE_ID_LENGTH = 64;
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]+");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TRACE_ID_HEADER));
        response.setHeader(TRACE_ID_HEADER, traceId);
        try (TraceContext.Scope ignored = TraceContext.withTraceId(traceId)) {
            filterChain.doFilter(request, response);
        }
    }

    private String resolveTraceId(String candidate) {
        if (candidate == null
                || candidate.isBlank()
                || candidate.length() > MAX_TRACE_ID_LENGTH
                || !SAFE_TRACE_ID.matcher(candidate).matches()) {
            return TraceContext.generateTraceId();
        }
        return candidate;
    }
}
