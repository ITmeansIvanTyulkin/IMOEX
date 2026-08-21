package com.moex.cointegration.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;

/**
 * Client closed the tab / refreshed while we were writing the response.
 * Not an application fault — resolve quietly so DefaultHandlerExceptionResolver
 * does not spam WARN {@code Broken pipe} / {@code AsyncRequestNotUsableException}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ClientDisconnectExceptionResolver implements HandlerExceptionResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientDisconnectExceptionResolver.class);

    @Override
    public ModelAndView resolveException(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex
    ) {
        if (!isClientGone(ex)) {
            return null;
        }
        if (log.isDebugEnabled()) {
            String uri = request != null ? request.getRequestURI() : "?";
            log.debug("Client disconnected during {}: {}", uri, rootMessage(ex));
        }
        return new ModelAndView();
    }

    static boolean isClientGone(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String name = t.getClass().getName();
            if (name.endsWith("ClientAbortException")
                    || name.endsWith("AsyncRequestNotUsableException")
                    || name.endsWith("ClientHttpResponseClosedException")) {
                return true;
            }
            if (t instanceof IOException) {
                String m = t.getMessage();
                if (m != null) {
                    String lower = m.toLowerCase();
                    if (lower.contains("broken pipe")
                            || lower.contains("connection reset")
                            || lower.contains("aborted")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.toString();
    }
}
