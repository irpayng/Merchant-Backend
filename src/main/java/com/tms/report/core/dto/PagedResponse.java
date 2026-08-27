package com.tms.report.core.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;

/**
 * Builds a Laravel-compatible paginated response with data, links, meta, code,
 * message. Matches Laravel's LengthAwarePaginator JSON output exactly.
 */
public class PagedResponse {

    public static <T> Map<String, Object> from(Page<T> page, String path, Map<String, Object> extra) {
        Map<String, Object> response = new LinkedHashMap<>();

        int currentPage = page.getNumber() + 1;
        int lastPage = Math.max(page.getTotalPages(), 1);

        response.put("data", page.getContent());

        // Links — always include prev/next even when null
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("first", path + "?page=1");
        links.put("last", path + "?page=" + lastPage);
        links.put("prev", currentPage > 1 ? path + "?page=" + (currentPage - 1) : null);
        links.put("next", currentPage < lastPage ? path + "?page=" + (currentPage + 1) : null);
        response.put("links", links);

        // Meta with links array
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("current_page", currentPage);
        meta.put("from", page.getTotalElements() > 0 ? page.getNumber() * page.getSize() + 1 : null);
        meta.put("last_page", lastPage);
        meta.put("links", buildMetaLinks(currentPage, lastPage, path));
        meta.put("path", path);
        meta.put("per_page", page.getSize());
        meta.put("to",
                page.getTotalElements() > 0
                        ? Math.min((long) currentPage * page.getSize(), page.getTotalElements())
                        : null);
        meta.put("total", page.getTotalElements());
        response.put("meta", meta);

        // Inline extra data (filters, stats, etc.)
        if (extra != null) {
            response.putAll(extra);
        }

        response.put("code", 200);
        response.put("message", "Successful");

        return response;
    }

    public static <T> Map<String, Object> from(Page<T> page, String path) {
        return from(page, path, null);
    }

    public static <T> Map<String, Object> from(Page<T> page) {
        return from(page, "", null);
    }

    /**
     * Build a paged response from a list with explicit pagination params. Used when
     * the data is fetched from an external service (gRPC) and not via Spring Data.
     */
    public static Map<String, Object> from(List<? extends Object> data, long total, int currentPage, int perPage,
            String path, Map<String, Object> extra) {
        Map<String, Object> response = new LinkedHashMap<>();

        int lastPage = Math.max((int) Math.ceil((double) total / perPage), 1);

        response.put("data", data);

        // Links
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("first", path + "?page=1");
        links.put("last", path + "?page=" + lastPage);
        links.put("prev", currentPage > 1 ? path + "?page=" + (currentPage - 1) : null);
        links.put("next", currentPage < lastPage ? path + "?page=" + (currentPage + 1) : null);
        response.put("links", links);

        // Meta
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("current_page", currentPage);
        meta.put("from", total > 0 ? (currentPage - 1) * perPage + 1 : null);
        meta.put("last_page", lastPage);
        meta.put("links", buildMetaLinks(currentPage, lastPage, path));
        meta.put("path", path);
        meta.put("per_page", perPage);
        meta.put("to", total > 0 ? Math.min((long) currentPage * perPage, total) : null);
        meta.put("total", total);
        response.put("meta", meta);

        if (extra != null) {
            response.putAll(extra);
        }

        response.put("code", 200);
        response.put("message", "Successful");

        return response;
    }

    /**
     * Empty paged response — returns the structure with no data.
     */
    public static Map<String, Object> empty(String path) {
        return from(List.of(), 0, 1, 10, path, null);
    }

    /**
     * Build the meta.links array matching Laravel's LengthAwarePaginator. Format:
     * [Previous, 1, 2, ..., N, Next]
     */
    static List<Map<String, Object>> buildMetaLinks(int currentPage, int lastPage, String path) {
        List<Map<String, Object>> metaLinks = new ArrayList<>();

        // Previous link
        Map<String, Object> prev = new LinkedHashMap<>();
        prev.put("url", currentPage > 1 ? path + "?page=" + (currentPage - 1) : null);
        prev.put("label", "&laquo; Previous");
        prev.put("active", false);
        metaLinks.add(prev);

        // Page number links
        for (int i = 1; i <= lastPage; i++) {
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("url", path + "?page=" + i);
            link.put("label", String.valueOf(i));
            link.put("active", i == currentPage);
            metaLinks.add(link);
        }

        // Next link
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("url", currentPage < lastPage ? path + "?page=" + (currentPage + 1) : null);
        next.put("label", "Next &raquo;");
        next.put("active", false);
        metaLinks.add(next);

        return metaLinks;
    }
}
