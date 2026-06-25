package com.tms.report.core.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.util.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Converts dates[]=X&dates[]=Y query params (sent by the frontend) into
 * dates[0]=X&dates[1]=Y so Spring's @RequestParam Map can read them.
 */
@Component
@Order(1)
public class DateArrayParamFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;
        String[] dates = httpReq.getParameterValues("dates[]");
        if (dates != null && dates.length >= 2) {
            chain.doFilter(new DatesRequestWrapper(httpReq, dates), response);
        } else {
            chain.doFilter(request, response);
        }
    }

    private static class DatesRequestWrapper extends HttpServletRequestWrapper {
        private final Map<String, String[]> params;

        DatesRequestWrapper(HttpServletRequest request, String[] dates) {
            super(request);
            params = new HashMap<>(request.getParameterMap());
            params.put("dates[0]", new String[]{dates[0]});
            params.put("dates[1]", new String[]{dates[1]});
        }

        @Override
        public String getParameter(String name) {
            String[] vals = params.get(name);
            return vals != null && vals.length > 0 ? vals[0] : null;
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return Collections.unmodifiableMap(params);
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(params.keySet());
        }

        @Override
        public String[] getParameterValues(String name) {
            return params.get(name);
        }
    }
}
