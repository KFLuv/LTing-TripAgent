package com.iflytek.chatgpt.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Day Optimize 的天气适配器。Open-Meteo 无 Key；失败时返回 unavailable，不伪造天气。
 */
@Component
public class PlanWeatherService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENDPOINT = "https://api.open-meteo.com/v1/forecast";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    @Value("${weather.enabled:true}")
    private boolean enabled;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private volatile Map<String, Object> lastResult = new LinkedHashMap<>();

    public Map<String, Object> dayWeather(JsonNode day) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!enabled) return remember(unavailable("天气服务已关闭"));
        JsonNode point = firstCoordinate(day);
        String date = day.path("date").asText("");
        if (point == null || date.isEmpty()) return remember(unavailable("当天缺少坐标或日期"));
        try {
            LocalDate.parse(date);
        } catch (Exception e) {
            return remember(unavailable("日期格式无法识别"));
        }
        double lat = point.path("lat").asDouble();
        double lng = point.path("lng").asDouble();
        String key = lat + "," + lng + "," + date;
        CacheEntry cached = cache.get(key);
        if (cached != null && cached.expiresAt.isAfter(LocalDateTime.now())) {
            lastResult = new LinkedHashMap<>(cached.value);
            return cached.value;
        }
        try {
            String url = ENDPOINT + "?latitude=" + lat + "&longitude=" + lng
                    + "&daily=weather_code,precipitation_sum,temperature_2m_max,temperature_2m_min"
                    + "&timezone=auto&start_date=" + enc(date) + "&end_date=" + enc(date);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return remember(unavailable("天气服务返回 HTTP " + response.statusCode()));
            }
            JsonNode daily = JSON.readTree(response.body()).path("daily");
            if (!daily.path("time").isArray() || daily.path("time").size() == 0) {
                return remember(unavailable("该日期没有天气预报"));
            }
            int code = daily.path("weather_code").path(0).asInt(0);
            double rain = daily.path("precipitation_sum").path(0).asDouble(0);
            double max = daily.path("temperature_2m_max").path(0).asDouble(0);
            double min = daily.path("temperature_2m_min").path(0).asDouble(0);
            result.put("available", true);
            result.put("date", date);
            result.put("condition", condition(code, rain, max));
            result.put("weatherCode", code);
            result.put("precipitationMm", rain);
            result.put("maxTempC", max);
            result.put("minTempC", min);
            result.put("source", "Open-Meteo");
            cache.put(key, new CacheEntry(result, LocalDateTime.now().plus(CACHE_TTL)));
            lastResult = new LinkedHashMap<>(result);
            return result;
        } catch (Exception e) {
            return remember(unavailable("天气预报暂不可用"));
        }
    }

    public Map<String, Object> healthSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", "weather");
        out.put("label", "天气");
        out.put("configured", enabled);
        out.put("status", enabled ? "configured" : "disabled");
        out.put("message", enabled ? "Open-Meteo 预报已启用" : "天气服务已关闭，优化会显式降级");
        out.put("cacheEntries", cache.size());
        out.put("lastResult", new LinkedHashMap<>(lastResult));
        return out;
    }

    private Map<String, Object> remember(Map<String, Object> value) {
        lastResult = new LinkedHashMap<>(value);
        return value;
    }

    private JsonNode firstCoordinate(JsonNode day) {
        for (String slot : new String[]{"morning", "afternoon", "evening"}) {
            for (JsonNode item : day.path(slot)) {
                if (item.path("lat").isNumber() && item.path("lng").isNumber()) return item;
            }
        }
        return null;
    }

    private String condition(int code, double rain, double maxTemp) {
        if (rain >= 0.5 || (code >= 51 && code <= 82) || code >= 95) return "rain";
        if (maxTemp >= 35) return "heat";
        if (maxTemp <= 5) return "cold";
        return "clear";
    }

    private Map<String, Object> unavailable(String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        result.put("condition", "unknown");
        result.put("reason", reason);
        return result;
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final class CacheEntry {
        private final Map<String, Object> value;
        private final LocalDateTime expiresAt;

        private CacheEntry(Map<String, Object> value, LocalDateTime expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }
}
