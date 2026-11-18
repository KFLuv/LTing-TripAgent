package com.iflytek.chatgpt.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iflytek.chatgpt.agent.NavigationService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Day Optimize 的纯算法层：只生成候选日程和 Critic，不写数据库。
 */
@Component
public class PlanDayOptimizer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] SLOTS = {"morning", "afternoon", "evening"};
    private static final int MAX_ROUTE_CALLS = 24;

    private final PlanPoolController validator;
    private final NavigationService navigationService;

    public PlanDayOptimizer(PlanPoolController validator, NavigationService navigationService) {
        this.validator = validator;
        this.navigationService = navigationService;
    }

    public List<Variant> buildVariants(ObjectNode root, int dayIndex) {
        return buildVariants(root, dayIndex, "balanced", "driving", "");
    }

    public List<Variant> buildVariants(ObjectNode root, int dayIndex, String pace) {
        return buildVariants(root, dayIndex, pace, "driving", "");
    }

    public List<Variant> buildVariants(ObjectNode root, int dayIndex, String pace,
                                       String travelMode, String city) {
        return buildVariants(root, dayIndex, pace, travelMode, city,
                new LinkedHashMap<>());
    }

    public List<Variant> buildVariants(ObjectNode root, int dayIndex, String pace,
                                       String travelMode, String city,
                                       Map<String, Object> weather) {
        String mode = normalizeMode(travelMode);
        ArrayNode itinerary = root.withArray("itinerary");
        if (dayIndex < 0 || dayIndex >= itinerary.size()) {
            throw new IllegalArgumentException("目标日期无效");
        }
        ArrayNode original = itinerary.deepCopy();
        Map<String, RouteMetric> routeCache = new LinkedHashMap<>();
        ArrayNode route = itinerary.deepCopy();
        sortByRoute((ObjectNode) route.get(dayIndex), root.path("locked"),
                routeCache, mode, city);

        ArrayNode shorter = itinerary.deepCopy();
        shortenStays((ObjectNode) shorter.get(dayIndex), root.path("locked"));

        ArrayNode nextDay = itinerary.deepCopy();
        spillToNextDay(nextDay, dayIndex, effectiveDayLimit(root, dayIndex, pace), root.path("locked"));

        ArrayNode closureSafe = itinerary.deepCopy();
        removeClosedNodes((ObjectNode) closureSafe.get(dayIndex), root.path("closures"));

        ArrayNode weatherSafe = itinerary.deepCopy();
        shortenOutdoorForWeather((ObjectNode) weatherSafe.get(dayIndex),
                root.path("locked"), String.valueOf(weather.getOrDefault("condition", "unknown")));

        ArrayNode holidaySafe = itinerary.deepCopy();
        String dayDate = itinerary.get(dayIndex).path("date").asText("");
        if (isHighCrowdDay(root, dayDate)) {
            shortenOutdoorByFactor((ObjectNode) holidaySafe.get(dayIndex),
                    root.path("locked"), 0.85);
        }

        ArrayNode transportSafe = itinerary.deepCopy();
        removeTransportBlockedNodes((ObjectNode) transportSafe.get(dayIndex),
                root, dayIndex);

        ArrayNode combined = shorter.deepCopy();
        sortByRoute((ObjectNode) combined.get(dayIndex), root.path("locked"),
                routeCache, mode, city);

        List<Variant> variants = new ArrayList<>();
        addVariant(variants, "ROUTE", "少折返",
                modeLabel(mode) + "路线优先，减少当天来回移动。", route, root, dayIndex,
                routeCache, mode, city);
        addVariant(variants, "SHORTER", "缩短停留",
                "每个未锁定节点缩短约 20% 停留时间，最低保留 30 分钟。",
                shorter, root, dayIndex, routeCache, mode, city);
        addVariant(variants, "NEXT_DAY", "顺延到次日",
                "把当天放不下的未锁定节点移到次日空余时段。",
                nextDay, root, dayIndex, routeCache, mode, city);
        addVariant(variants, "CLOSURE_SAFE", "移除临时闭馆项",
                "把已标记临时闭馆的节点移出正式行程，候选仍保留。",
                closureSafe, root, dayIndex, routeCache, mode, city);
        addVariant(variants, "WEATHER_SAFE", "天气稳妥",
                "缩短户外节点的预计停留，降低降雨、高温或低温暴露。",
                weatherSafe, root, dayIndex, routeCache, mode, city);
        addVariant(variants, "HOLIDAY_SAFE", "节假日避峰",
                "周末或节假日缩短户外节点停留，降低排队和人流暴露。",
                holidaySafe, root, dayIndex, routeCache, mode, city);
        addVariant(variants, "TRANSPORT_SAFE", "适配城际交通",
                "移除与到达或返程时间冲突的时段节点，候选仍保留。",
                transportSafe, root, dayIndex, routeCache, mode, city);
        addVariant(variants, "COMBINED", "少折返 + 缩短停留",
                "同时减少折返和单点停留，适合时间偏满的一天。",
                combined, root, dayIndex, routeCache, mode, city);
        return variants;
    }

    public Map<String, Object> critic(ObjectNode root, int dayIndex,
                                      String budget, String people,
                                      String preferences, String pace) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> validation = validator.validateItinerary(
                new LinkedHashMap<>(), root.path("itinerary").toString(),
                budget, people, preferences, dayMinutesOf(pace));
        List<Map<String, Object>> findings = new ArrayList<>();
        List<Map<String, Object>> conflicts = castList(validation.get("conflicts"));
        for (Map<String, Object> conflict : conflicts) {
            int day = numeric(conflict.get("day"));
            if (day != 0 && day != dayIndex + 1) continue;
            Map<String, Object> finding = new LinkedHashMap<>();
            finding.put("kind", conflict.getOrDefault("type", "PLAN"));
            finding.put("message", conflict.getOrDefault("message", "发现行程冲突"));
            findings.add(finding);
            if (findings.size() >= 3) break;
        }
        List<String> suggestions = new ArrayList<>();
        for (Map<String, Object> finding : findings) {
            String kind = String.valueOf(finding.get("kind"));
            if ("TIME".equals(kind)) suggestions.add("选择一个减少停留或顺延节点的方案");
            else if ("DISTANCE".equals(kind)) suggestions.add("按同区域顺序重排当天点位");
            else if ("BUDGET".equals(kind)) suggestions.add("更换低成本点位或调整预算约束");
            else if ("PREFERENCE".equals(kind)) suggestions.add("替换与偏好冲突的节点");
            else suggestions.add("查看当天节点并调整冲突项");
        }
        result.put("day", dayIndex + 1);
        result.put("severity", findings.isEmpty() ? "low"
                : ("TIME".equals(findings.get(0).get("kind")) ? "high" : "medium"));
        result.put("findings", findings);
        result.put("suggestions", suggestions);
        result.put("validation", validation);
        Map<String, Object> metrics = metrics(root, dayIndex);
        result.put("metrics", metrics);
        int locked = numeric(metrics.get("lockedCount"));
        if (locked > 0 && findings.size() < 3) {
            findings.add(finding("LOCK", locked + " 个锁定节点保持原时段和停留时间"));
        }
        int openingConflicts = numeric(metrics.get("openingConflictCount"));
        if (openingConflicts > 0 && findings.size() < 3) {
            findings.add(finding("OPENING", openingConflicts
                    + " 个节点在当前时段可能未营业"));
        }
        int unknownOpening = numeric(metrics.get("unknownOpeningCount"));
        if (unknownOpening > 0 && findings.size() < 3) {
            findings.add(finding("OPENING_UNKNOWN", unknownOpening
                    + " 个节点缺少可解析营业时间，需现场或官方确认"));
        }
        int fallbackRoutes = numeric(metrics.get("fallbackRouteCount"));
        if (fallbackRoutes > 0 && findings.size() < 3) {
            findings.add(finding("ROUTE_FALLBACK", fallbackRoutes
                    + " 段通勤使用直线估算，未获得真实道路耗时"));
        }
        int closed = numeric(metrics.get("closedCount"));
        if (closed > 0 && findings.size() < 3) {
            findings.add(finding("CLOSED", closed + " 个节点已标记临时闭馆"));
        }
        if (Boolean.TRUE.equals(metrics.get("highCrowdDay")) && findings.size() < 3) {
            findings.add(finding("CROWD", "周末或节假日人流较高，建议缩短户外停留"));
        }
        if (metrics.get("transportBlockedSlots") instanceof List
                && !((List<?>) metrics.get("transportBlockedSlots")).isEmpty()
                && findings.size() < 3) {
            findings.add(finding("TRANSPORT", "城际交通占用部分时段，需要移除或顺延冲突节点"));
        }
        return result;
    }

    public Map<String, Object> criticAll(ObjectNode root,
                                         String budget, String people,
                                         String preferences, String pace) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> validation = validator.validateItinerary(
                new LinkedHashMap<>(), root.path("itinerary").toString(),
                budget, people, preferences, dayMinutesOf(pace));
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Map<String, Object> conflict : castList(validation.get("conflicts"))) {
            findings.add(finding(
                    String.valueOf(conflict.getOrDefault("type", "PLAN")),
                    numeric(conflict.get("day")),
                    String.valueOf(conflict.getOrDefault("message", "发现行程冲突"))));
        }
        findings.addAll(budgetFindings(root));
        Map<String, RouteMetric> routeCache = new LinkedHashMap<>();
        List<Map<String, Object>> days = new ArrayList<>();
        for (int day = 0; day < root.withArray("itinerary").size(); day++) {
            Map<String, Object> metrics = metrics(root, day, routeCache, "driving", "");
            days.add(metrics);
            int opening = numeric(metrics.get("openingConflictCount"));
            if (opening > 0) {
                findings.add(finding("OPENING", day + 1,
                        opening + " 个节点在当前时段可能未营业"));
            }
            int unknown = numeric(metrics.get("unknownOpeningCount"));
            if (unknown > 0) {
                findings.add(finding("OPENING_UNKNOWN", day + 1,
                        unknown + " 个节点缺少可解析营业时间"));
            }
            int fallback = numeric(metrics.get("fallbackRouteCount"));
            if (fallback > 0) {
                findings.add(finding("ROUTE_FALLBACK", day + 1,
                        fallback + " 段通勤使用直线估算"));
            }
            int closed = numeric(metrics.get("closedCount"));
            if (closed > 0) {
                findings.add(finding("CLOSED", day + 1,
                        closed + " 个节点已标记临时闭馆"));
            }
            if (Boolean.TRUE.equals(metrics.get("highCrowdDay"))) {
                findings.add(finding("CROWD", day + 1,
                        "周末或节假日人流较高"));
            }
            if (metrics.get("transportBlockedSlots") instanceof List
                    && !((List<?>) metrics.get("transportBlockedSlots")).isEmpty()) {
                findings.add(finding("TRANSPORT", day + 1,
                        "城际交通占用部分时段"));
            }
        }
        findings.sort((a, b) -> Integer.compare(
                severityRank(String.valueOf(b.get("kind"))),
                severityRank(String.valueOf(a.get("kind")))));
        List<Map<String, Object>> top = findings.size() > 3
                ? new ArrayList<>(findings.subList(0, 3)) : findings;
        List<String> suggestions = new ArrayList<>();
        for (Map<String, Object> finding : top) {
            String suggestion = suggestionFor(String.valueOf(finding.get("kind")));
            if (!suggestions.contains(suggestion)) suggestions.add(suggestion);
        }
        result.put("scope", "all-days");
        result.put("severity", top.isEmpty() ? "low" : severityOf(String.valueOf(top.get(0).get("kind"))));
        result.put("findings", top);
        result.put("suggestions", suggestions);
        result.put("days", days);
        result.put("validation", validation);
        return result;
    }

    public Map<String, Object> metrics(ObjectNode root, int dayIndex) {
        return metrics(root, dayIndex, new LinkedHashMap<>(), "driving", "");
    }

    public Map<String, Object> metrics(ObjectNode root, int dayIndex,
                                       String travelMode, String city) {
        return metrics(root, dayIndex, new LinkedHashMap<>(),
                normalizeMode(travelMode), city);
    }

    private Map<String, Object> metrics(ObjectNode root, int dayIndex,
                                        Map<String, RouteMetric> routeCache,
                                        String travelMode, String city) {
        ArrayNode itinerary = root.withArray("itinerary");
        JsonNode day = itinerary.get(dayIndex);
        String date = day.path("date").asText("");
        JsonNode locked = root.path("locked");
        int count = 0;
        int minutes = 0;
        int travelMinutes = 0;
        int lockedCount = 0;
        int unknownOpeningCount = 0;
        int openingConflictCount = 0;
        int fallbackRouteCount = 0;
        int closedCount = 0;
        List<String> blockedSlots = new ArrayList<>();
        for (String slot : SLOTS) {
            if (transportBlocked(root, dayIndex, slot)) blockedSlots.add(slot);
        }
        double distance = 0;
        for (String slot : SLOTS) {
            JsonNode previous = null;
            for (JsonNode item : day.path(slot)) {
                count++;
                minutes += item.path("durationMin").asInt(90);
                if (locked.path(poiKey(item)).asBoolean(false)) lockedCount++;
                if (root.path("closures").path(poiKey(item)).asBoolean(false)) closedCount++;
                if (item.path("openTime").asText("").trim().isEmpty()) {
                    unknownOpeningCount++;
                } else {
                    Boolean open = openAt(item, slot, date);
                    if (open == null) unknownOpeningCount++;
                    else if (!open) openingConflictCount++;
                }
                if (previous != null) {
                    RouteMetric route = routeMetric(previous, item, routeCache,
                            travelMode, city);
                    distance += route.distanceMeters / 1000.0;
                    travelMinutes += route.durationMinutes;
                    if ("estimated".equals(route.source)) fallbackRouteCount++;
                }
                previous = item;
            }
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("day", dayIndex + 1);
        metrics.put("date", day.path("date").asText(""));
        metrics.put("count", count);
        metrics.put("minutes", minutes);
        metrics.put("travelMinutes", travelMinutes);
        metrics.put("distanceKm", Math.round(distance * 10) / 10.0);
        metrics.put("lockedCount", lockedCount);
        metrics.put("unknownOpeningCount", unknownOpeningCount);
        metrics.put("openingConflictCount", openingConflictCount);
        metrics.put("fallbackRouteCount", fallbackRouteCount);
        metrics.put("closedCount", closedCount);
        metrics.put("travelMode", travelMode);
        metrics.put("highCrowdDay", isHighCrowdDay(root, date));
        metrics.put("transportBlockedSlots", blockedSlots);
        return metrics;
    }

    private void addVariant(List<Variant> variants, String id, String title,
                            String summary, ArrayNode itinerary, ObjectNode sourceRoot,
                            int dayIndex, Map<String, RouteMetric> routeCache,
                            String travelMode, String city) {
        for (Variant variant : variants) {
            if (variant.itinerary.equals(itinerary)) return;
        }
        ObjectNode variantRoot = sourceRoot.deepCopy();
        variantRoot.set("itinerary", itinerary.deepCopy());
        variants.add(new Variant(id, title, summary, itinerary,
                metrics(variantRoot, dayIndex, routeCache, travelMode, city)));
    }

    private void sortByRoute(ObjectNode day, JsonNode locked,
                             Map<String, RouteMetric> routeCache,
                             String travelMode, String city) {
        for (String slot : SLOTS) {
            ArrayNode items = day.withArray(slot);
            ArrayNode sorted = JSON.createArrayNode();
            List<JsonNode> segment = new ArrayList<>();
            for (JsonNode item : items) {
                if (locked.path(poiKey(item)).asBoolean(false)) {
                    flushSegment(segment, sorted, routeCache, travelMode, city);
                    sorted.add(item);
                } else {
                    segment.add(item);
                }
            }
            flushSegment(segment, sorted, routeCache, travelMode, city);
            items.removeAll();
            items.addAll(sorted);
        }
    }

    private void flushSegment(List<JsonNode> segment, ArrayNode target,
                              Map<String, RouteMetric> routeCache,
                              String travelMode, String city) {
        if (segment.isEmpty()) return;
        List<JsonNode> remaining = new ArrayList<>(segment);
        JsonNode current = remaining.remove(0);
        target.add(current);
        while (!remaining.isEmpty()) {
            int nearest = 0;
            double nearestDistance = Double.MAX_VALUE;
            for (int i = 0; i < remaining.size(); i++) {
                double distance = routeMetric(current, remaining.get(i), routeCache,
                        travelMode, city).distanceMeters;
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = i;
                }
            }
            current = remaining.remove(nearest);
            target.add(current);
        }
        segment.clear();
    }

    private void shortenStays(ObjectNode day, JsonNode locked) {
        for (String slot : SLOTS) {
            for (JsonNode item : day.withArray(slot)) {
                ObjectNode node = (ObjectNode) item;
                if (locked.path(poiKey(node)).asBoolean(false)) continue;
                int duration = node.path("durationMin").asInt(90);
                node.put("durationMin", Math.max(30, (int) Math.round(duration * 0.8)));
            }
        }
    }

    private void removeClosedNodes(ObjectNode day, JsonNode closures) {
        for (String slot : SLOTS) {
            ArrayNode items = day.withArray(slot);
            for (int i = items.size() - 1; i >= 0; i--) {
                if (closures.path(poiKey(items.get(i))).asBoolean(false)) {
                    items.remove(i);
                }
            }
        }
    }

    private void removeTransportBlockedNodes(ObjectNode day, ObjectNode root, int dayIndex) {
        for (String slot : SLOTS) {
            if (!transportBlocked(root, dayIndex, slot)) continue;
            day.withArray(slot).removeAll();
        }
    }

    private int effectiveDayLimit(ObjectNode root, int dayIndex, String pace) {
        int limit = dayMinutesOf(pace);
        if (transportBlocked(root, dayIndex, "morning")) limit -= 240;
        if (transportBlocked(root, dayIndex, "afternoon")) limit -= 240;
        if (transportBlocked(root, dayIndex, "evening")) limit -= 120;
        return Math.max(120, limit);
    }

    private boolean transportBlocked(ObjectNode root, int dayIndex, String slot) {
        int lastDay = root.withArray("itinerary").size() - 1;
        JsonNode transport = root.path("transport");
        if (dayIndex == 0 && transport.path("outbound").path("includeFirstDay").asBoolean(true)) {
            int ready = parseClock(transport.path("outbound").path("arriveAt").asText(""))
                    + transport.path("outbound").path("toStayMinutes").asInt(0);
            int start = slotStart(slot);
            if (ready > 0 && ready > start) return true;
        }
        if (dayIndex == lastDay) {
            int depart = parseClock(transport.path("inbound").path("departAt").asText(""));
            int cutoff = depart - transport.path("inbound").path("fromStayMinutes").asInt(0);
            if (depart > 0 && cutoff < slotStart(slot)) return true;
        }
        return false;
    }

    private int parseClock(String value) {
        if (value == null || !value.matches("\\d{1,2}:\\d{2}")) return 0;
        String[] parts = value.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private int slotStart(String slot) {
        if ("morning".equals(slot)) return 9 * 60;
        if ("afternoon".equals(slot)) return 14 * 60;
        return 19 * 60;
    }

    private void shortenOutdoorForWeather(ObjectNode day, JsonNode locked, String condition) {
        if (!"rain".equals(condition) && !"heat".equals(condition) && !"cold".equals(condition)) {
            return;
        }
        shortenOutdoorByFactor(day, locked, 0.7);
    }

    private void shortenOutdoorByFactor(ObjectNode day, JsonNode locked, double factor) {
        for (String slot : SLOTS) {
            for (JsonNode item : day.withArray(slot)) {
                if (locked.path(poiKey(item)).asBoolean(false) || !isOutdoor(item)) continue;
                ObjectNode node = (ObjectNode) item;
                int duration = node.path("durationMin").asInt(90);
                node.put("durationMin", Math.max(30, (int) Math.round(duration * factor)));
            }
        }
    }

    private boolean isHighCrowdDay(ObjectNode root, String date) {
        if (date == null || date.isEmpty()) return false;
        if (root.path("holidayDays").path(date).asBoolean(false)) return true;
        try {
            java.time.DayOfWeek day = java.time.LocalDate.parse(date).getDayOfWeek();
            return day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isOutdoor(JsonNode item) {
        String text = item.path("name").asText("") + " "
                + item.path("type").asText("") + " "
                + item.path("category").asText("");
        return java.util.regex.Pattern.compile(
                "公园|景区|山|湖|海滩|岛|广场|步行街|古镇|观景|植物园|动物园|游乐园")
                .matcher(text).find();
    }

    private void spillToNextDay(ArrayNode itinerary, int dayIndex,
                                int dailyLimit, JsonNode locked) {
        if (dayIndex + 1 >= itinerary.size()) return;
        ObjectNode current = (ObjectNode) itinerary.get(dayIndex);
        ObjectNode next = (ObjectNode) itinerary.get(dayIndex + 1);
        while (dayMinutes(current) > dailyLimit) {
            JsonNode candidate = null;
            String candidateSlot = null;
            for (int s = SLOTS.length - 1; s >= 0 && candidate == null; s--) {
                ArrayNode items = current.withArray(SLOTS[s]);
                for (int i = items.size() - 1; i >= 0; i--) {
                    JsonNode item = items.get(i);
                    if (!locked.path(poiKey(item)).asBoolean(false)) {
                        candidate = item;
                        candidateSlot = SLOTS[s];
                        break;
                    }
                }
            }
            if (candidate == null) break;
            int duration = candidate.path("durationMin").asInt(90);
            String targetSlot = nextSlot(next, duration, dailyLimit, candidateSlot);
            if (targetSlot == null) break;
            ArrayNode source = current.withArray(candidateSlot);
            for (int i = source.size() - 1; i >= 0; i--) {
                if (source.get(i) == candidate) {
                    source.remove(i);
                    break;
                }
            }
            next.withArray(targetSlot).add(candidate);
        }
    }

    private String nextSlot(ObjectNode day, int duration, int dailyLimit, String preferred) {
        List<String> order = new ArrayList<>();
        order.add(preferred);
        for (String slot : SLOTS) if (!slot.equals(preferred)) order.add(slot);
        for (String slot : order) {
            int minutes = 0;
            for (String each : SLOTS) {
                for (JsonNode item : day.path(each)) minutes += item.path("durationMin").asInt(90);
            }
            if (minutes + duration <= dailyLimit) return slot;
        }
        return null;
    }

    private int dayMinutes(ObjectNode day) {
        int minutes = 0;
        for (String slot : SLOTS) {
            for (JsonNode item : day.path(slot)) minutes += item.path("durationMin").asInt(90);
        }
        return minutes;
    }

    private RouteMetric routeMetric(JsonNode a, JsonNode b,
                                    Map<String, RouteMetric> cache,
                                    String travelMode, String city) {
        if (!hasCoord(a) || !hasCoord(b)) return RouteMetric.unknown();
        String key = travelMode + ":" + poiKey(a) + "->" + poiKey(b);
        RouteMetric cached = cache.get(key);
        if (cached != null) return cached;
        RouteMetric metric = null;
        if (cache.size() < MAX_ROUTE_CALLS) {
            Map<String, Object> route = "transit".equals(travelMode)
                    ? navigationService.planTransit(
                        a.path("lat").asDouble(), a.path("lng").asDouble(),
                        b.path("lat").asDouble(), b.path("lng").asDouble(),
                        city == null ? "" : city, true)
                    : navigationService.planRoute(
                        a.path("lat").asDouble(), a.path("lng").asDouble(),
                        b.path("lat").asDouble(), b.path("lng").asDouble(),
                        travelMode, true);
            if (Boolean.TRUE.equals(route.get("ok"))) {
                int meters = numeric(route.get("distanceMeters"));
                int seconds = numeric(route.get("durationSeconds"));
                if (meters > 0 && seconds > 0) {
                    metric = new RouteMetric(meters, Math.max(1, (int) Math.ceil(seconds / 60.0)),
                            "amap-" + travelMode);
                }
            }
        }
        if (metric == null) {
            double meters = haversine(
                    a.path("lat").asDouble(), a.path("lng").asDouble(),
                    b.path("lat").asDouble(), b.path("lng").asDouble()) * 1.28;
            metric = new RouteMetric((int) Math.round(meters),
                    Math.max(1, (int) Math.ceil(meters / 250.0)), "estimated");
        }
        cache.put(key, metric);
        return metric;
    }

    private Boolean openAt(JsonNode item, String slot, String date) {
        String text = item.path("openTime").asText("").trim();
        Integer weekday = weekdayOf(date);
        if (weekday != null) {
            String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
            String name = names[weekday - 1];
            if (java.util.regex.Pattern.compile(name + "\\s*(闭馆|休息|不开放)")
                    .matcher(text).find()) {
                return false;
            }
        }
        if (text.contains("全天")) return true;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d{1,2}):(\\d{2})\\s*[-~至]\\s*(\\d{1,2}):(\\d{2})")
                .matcher(text);
        int visitMinute = "morning".equals(slot) ? 9 * 60
                : ("afternoon".equals(slot) ? 14 * 60 : 19 * 60);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            int startMinute = Integer.parseInt(matcher.group(1)) * 60 + Integer.parseInt(matcher.group(2));
            int endMinute = Integer.parseInt(matcher.group(3)) * 60 + Integer.parseInt(matcher.group(4));
            if (endMinute <= startMinute) continue;
            if (visitMinute >= startMinute && visitMinute < endMinute) return true;
        }
        return found ? false : null;
    }

    private Integer weekdayOf(String date) {
        if (date == null || date.trim().isEmpty()) return null;
        try {
            return java.time.LocalDate.parse(date.trim()).getDayOfWeek().getValue();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeMode(String mode) {
        String value = mode == null ? "" : mode.trim().toLowerCase();
        if ("walking".equals(value) || "bicycling".equals(value) || "transit".equals(value)) {
            return value;
        }
        return "driving";
    }

    private String modeLabel(String mode) {
        if ("walking".equals(mode)) return "步行";
        if ("bicycling".equals(mode)) return "骑行";
        if ("transit".equals(mode)) return "公交";
        return "驾车";
    }

    private Map<String, Object> finding(String kind, String message) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("kind", kind);
        finding.put("message", message);
        return finding;
    }

    private Map<String, Object> finding(String kind, int day, String message) {
        Map<String, Object> finding = finding(kind, message);
        finding.put("day", day);
        finding.put("severity", severityOf(kind));
        return finding;
    }

    private List<Map<String, Object>> budgetFindings(ObjectNode root) {
        List<Map<String, Object>> findings = new ArrayList<>();
        JsonNode plan = root.path("budgetPlan");
        JsonNode allocated = plan.path("categories");
        double transport = root.path("transport").path("outbound").path("cost").asDouble(0)
                + root.path("transport").path("inbound").path("cost").asDouble(0);
        Map<String, Double> estimated = new LinkedHashMap<>();
        estimated.put("transport", transport);
        estimated.put("food", 0d);
        estimated.put("ticket", 0d);
        estimated.put("local", 0d);
        for (JsonNode day : root.withArray("itinerary")) {
            for (String slot : SLOTS) {
                for (JsonNode item : day.path(slot)) {
                    double cost = money(item.path("cost").asText(""));
                    String category = "food".equals(item.path("budgetCategory").asText(""))
                            ? "food" : ("景点".equals(item.path("category").asText(""))
                            ? "ticket" : "local");
                    estimated.put(category, estimated.getOrDefault(category, 0d) + cost);
                }
            }
        }
        for (String category : new String[]{"transport", "food", "ticket", "local"}) {
            double limit = allocated.path(category).asDouble(0);
            if (limit > 0 && estimated.getOrDefault(category, 0d) > limit) {
                findings.add(finding("BUDGET_CATEGORY", 0,
                        category + " 预算估计 ¥" + round(estimated.get(category))
                                + "，超过分项 ¥" + round(limit)));
            }
        }
        double total = plan.path("total").asDouble(0);
        double estimate = estimated.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total > 0 && estimate > total) {
            findings.add(finding("BUDGET", 0,
                    "当前方案预计 ¥" + round(estimate) + "，超过总预算 ¥" + round(total)));
        }
        return findings;
    }

    private double money(String value) {
        if (value == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)?)").matcher(value);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0;
    }

    private double round(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private int severityRank(String kind) {
        if ("TIME".equals(kind)) return 100;
        if ("BUDGET".equals(kind)) return 95;
        if ("BUDGET_CATEGORY".equals(kind)) return 88;
        if ("CLOSED".equals(kind)) return 90;
        if ("CROWD".equals(kind)) return 65;
        if ("TRANSPORT".equals(kind)) return 85;
        if ("DISTANCE".equals(kind)) return 80;
        if ("OPENING".equals(kind)) return 75;
        if ("PREFERENCE".equals(kind)) return 70;
        if ("LOCK".equals(kind)) return 55;
        if ("OPENING_UNKNOWN".equals(kind)) return 45;
        if ("ROUTE_FALLBACK".equals(kind)) return 35;
        return 20;
    }

    private String severityOf(String kind) {
        int rank = severityRank(kind);
        return rank >= 90 ? "high" : (rank >= 60 ? "medium" : "low");
    }

    private String suggestionFor(String kind) {
        if ("TIME".equals(kind)) return "选择减少停留、顺延次日或拆分当天的方案";
        if ("BUDGET".equals(kind)) return "替换高消费点位或调整预算分项";
        if ("BUDGET_CATEGORY".equals(kind)) return "调整分项预算或替换高消费节点";
        if ("DISTANCE".equals(kind)) return "按同区域重排并检查真实路线";
        if ("OPENING".equals(kind)) return "调整时段或替换当前未营业节点";
        if ("CLOSED".equals(kind)) return "移除或替换临时闭馆节点";
        if ("CROWD".equals(kind)) return "缩短户外停留并预留排队时间";
        if ("TRANSPORT".equals(kind)) return "移除或顺延与到达、返程冲突的节点";
        if ("PREFERENCE".equals(kind)) return "替换与长期偏好冲突的节点";
        if ("OPENING_UNKNOWN".equals(kind)) return "出发前向官方确认营业时间";
        if ("ROUTE_FALLBACK".equals(kind)) return "恢复地图路线服务后重新校验通勤";
        return "逐项检查行程冲突";
    }

    private boolean hasCoord(JsonNode item) {
        return item.path("lat").isNumber() && item.path("lng").isNumber();
    }

    private double haversine(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private String poiKey(JsonNode node) {
        return node.path("name").asText("") + "|"
                + node.path("address").asText("") + "|"
                + node.path("lat").asText("") + "|"
                + node.path("lng").asText("");
    }

    private int dayMinutesOf(String pace) {
        if ("relaxed".equals(pace)) return 360;
        if ("compact".equals(pace)) return 720;
        return 540;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : new ArrayList<>();
    }

    private int numeric(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    public static final class Variant {
        private final String id;
        private final String title;
        private final String summary;
        private final ArrayNode itinerary;
        private final Map<String, Object> metrics;

        Variant(String id, String title, String summary, ArrayNode itinerary,
                Map<String, Object> metrics) {
            this.id = id;
            this.title = title;
            this.summary = summary;
            this.itinerary = itinerary;
            this.metrics = metrics;
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public String getSummary() {
            return summary;
        }

        public ArrayNode getItinerary() {
            return itinerary;
        }

        public Map<String, Object> getMetrics() {
            return metrics;
        }
    }

    private static final class RouteMetric {
        private final int distanceMeters;
        private final int durationMinutes;
        private final String source;

        private RouteMetric(int distanceMeters, int durationMinutes, String source) {
            this.distanceMeters = distanceMeters;
            this.durationMinutes = durationMinutes;
            this.source = source;
        }

        private static RouteMetric unknown() {
            return new RouteMetric(Integer.MAX_VALUE / 2, 0, "unknown");
        }
    }
}
