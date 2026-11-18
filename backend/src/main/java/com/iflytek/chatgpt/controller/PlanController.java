package com.iflytek.chatgpt.controller;

import com.iflytek.chatgpt.service.AiChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划工作台 · 城市推荐（P0 第一块）。
 *
 * <p>固定取数流水线：本地精选城市库（T1，官方文旅整理）→ 六因子归一打分 → 生成证据 → 返回。
 * 权重由前端传入（用户可调），每个因子 0–100，内部归一到 0–1。
 *
 * <p>数据边界：季节为月份代理、天气为季节代理，**未接实时天气**；
 * 热度与预算为人工分级；证据不足时如实标注，不编造。
 */
@RestController
public class PlanController {

    private final AiChatService aiChatService;

    public PlanController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    /** 城市：省, 名, 纬度, 经度, 最佳月份, 节庆月份, 节庆名, 热度0-1, 消费0-1 */
    private static final List<String[]> CITIES = Arrays.asList(
            new String[]{"广东", "潮州", "23.66", "116.62", "11", "11", "潮州工夫茶文化节", "0.72", "0.42"},
            new String[]{"福建", "厦门", "24.48", "118.09", "11", "11", "鼓浪屿音乐周", "0.88", "0.66"},
            new String[]{"云南", "大理", "25.61", "100.27", "11", "11", "白族三月街秋季场", "0.90", "0.55"},
            new String[]{"四川", "成都", "30.57", "104.07", "11", "10", "成都国际美食节", "0.92", "0.60"},
            new String[]{"浙江", "杭州", "30.27", "120.16", "4", "11", "西湖桂花季", "0.85", "0.70"},
            new String[]{"陕西", "西安", "34.34", "108.94", "10", "10", "长安灯会预热", "0.83", "0.50"},
            new String[]{"黑龙江", "哈尔滨", "45.80", "126.53", "1", "1", "冰雪大世界", "0.80", "0.58"},
            new String[]{"海南", "三亚", "18.25", "109.51", "12", "12", "海南国际旅游岛欢乐节", "0.86", "0.78"},
            new String[]{"广西", "桂林", "25.27", "110.29", "10", "10", "漓江渔火节", "0.78", "0.48"},
            new String[]{"山东", "青岛", "36.07", "120.38", "8", "8", "青岛国际啤酒节", "0.76", "0.62"}
    );

    /** 出发地坐标（用于距离因子；未收录的城市按 800km 中性处理） */
    private static final Map<String, double[]> ORIGINS = new LinkedHashMap<>();
    static {
        ORIGINS.put("广州", new double[]{23.13, 113.26});
        ORIGINS.put("深圳", new double[]{22.54, 114.06});
        ORIGINS.put("上海", new double[]{31.23, 121.47});
        ORIGINS.put("北京", new double[]{39.90, 116.41});
        ORIGINS.put("成都", new double[]{30.57, 104.07});
    }

    /**
     * 城市推荐。
     *
     * @param date   出发日期（yyyy-MM-dd，默认今天）
     * @param origin 出发城市（默认广州）
     * @param season,weather,festival,heat,distance,budget 六因子权重（0–100，默认 25/20/15/15/15/10）
     * @param preset 预设：saving / seasonal / popular / nearby（一次性套权重，优先级高于手调）
     */
    @PostMapping(value = "/plan/city-recommend", produces = "application/json;charset=UTF-8")
    public Map<String, Object> cityRecommend(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String origin,
            @RequestParam(defaultValue = "25") double season,
            @RequestParam(defaultValue = "20") double weather,
            @RequestParam(defaultValue = "15") double festival,
            @RequestParam(defaultValue = "15") double heat,
            @RequestParam(defaultValue = "15") double distance,
            @RequestParam(defaultValue = "10") double budget,
            @RequestParam(required = false) String preset) {

        LocalDate day;
        try {
            day = (date == null || date.trim().isEmpty()) ? LocalDate.now() : LocalDate.parse(date.trim());
        } catch (Exception e) {
            day = LocalDate.now();
        }
        String from = (origin == null || origin.trim().isEmpty()) ? "广州" : origin.trim();

        double[] w = weights(preset, season, weather, festival, heat, distance, budget);
        double[] o = ORIGINS.getOrDefault(from, new double[]{23.13, 113.26});

        List<Map<String, Object>> items = new ArrayList<>();
        for (String[] c : CITIES) {
            String province = c[0], city = c[1];
            double lat = Double.parseDouble(c[2]), lng = Double.parseDouble(c[3]);
            int bestMonth = Integer.parseInt(c[4]);
            int festMonth = Integer.parseInt(c[5]);
            String festName = c[6];
            double heatScore = Double.parseDouble(c[7]);
            double costLevel = Double.parseDouble(c[8]);

            int month = day.getMonthValue();
            double seasonScore = seasonProxy(month, bestMonth);
            double weatherScore = seasonScore * 0.9;              // 季节代理，未接实时天气
            double festivalScore = (month == festMonth) ? 1.0 : 0.25;
            double heatN = heatScore;
            double km = haversine(o[0], o[1], lat, lng);
            double distanceScore = Math.max(0, 1 - km / 2500.0);
            double budgetScore = 1 - costLevel;                    // 越便宜越高分

            double score = w[0] * seasonScore + w[1] * weatherScore + w[2] * festivalScore
                    + w[3] * heatN + w[4] * distanceScore + w[5] * budgetScore;

            Map<String, Object> f = new LinkedHashMap<>();
            f.put("season", round(seasonScore));
            f.put("weather", round(weatherScore));
            f.put("festival", round(festivalScore));
            f.put("heat", round(heatN));
            f.put("distance", round(distanceScore));
            f.put("budget", round(budgetScore));

            List<String> evidence = new ArrayList<>();
            evidence.add("应季度：" + month + " 月接近最佳月份 " + bestMonth + " 月（季节代理）");
            evidence.add(month == festMonth
                    ? "本月有活动：" + festName
                    : "本月无重点节庆（最近：" + festMonth + " 月 " + festName + "）");
            evidence.add("距" + from + "约 " + Math.round(km) + " km；消费水平 "
                    + (costLevel < 0.55 ? "偏低" : costLevel < 0.7 ? "中等" : "偏高"));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("city", city);
            item.put("province", province);
            item.put("score", round(score));
            item.put("distanceKm", Math.round(km));
            item.put("factors", f);
            item.put("evidence", evidence);
            item.put("dataNote", "季节/天气为代理值；热度与消费为人工分级（T1 本地城市库）");
            items.add(item);
        }
        items.sort((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "success");
        out.put("date", day.toString());
        out.put("origin", from);
        out.put("preset", preset == null ? "" : preset);
        out.put("weights", weightsMap(w));
        out.put("items", items.subList(0, Math.min(6, items.size())));
        return out;
    }

    /**
     * 只润色已经由用户选中的点位，不允许模型新增或替换地点。
     * 模型不可用时返回可读的规则版行程，不阻塞保存。
     */
    @PostMapping(value = "/plan/polish", produces = "application/json;charset=UTF-8")
    public Map<String, Object> polish(@RequestParam(required = false) String city,
                                      @RequestParam(defaultValue = "1") int days,
                                      @RequestParam(required = false) String people,
                                      @RequestParam(required = false) String start,
                                      @RequestParam(required = false) String preferences,
                                      @RequestParam(required = false) String itinerary,
                                      HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (ChatController.currentUser(session) == null) {
            return polishError(out, "请先登录后再润色行程");
        }
        if (itinerary == null || itinerary.trim().isEmpty()) {
            return polishError(out, "行程为空，无法润色");
        }
        String normalized = itinerary.trim();
        if (normalized.length() > 12000) normalized = normalized.substring(0, 12000);
        if (!aiChatService.isConfigured()) {
            out.put("status", "degraded");
            out.put("configured", false);
            out.put("answer", "");
            out.put("message", "AI 未配置，已保留结构化行程，可继续编辑和保存");
            return out;
        }
        String prompt = "请把下面这份已经确定的旅行行程整理成普通人容易阅读的中文攻略。"
                + "硬规则：不得增加、替换或删除任何地点；只能补充顺序理由、交通建议、"
                + "每条停留时间、适合人群和风险提醒；信息不足时明确写“需现场确认”，不得编造。"
                + "\n格式规则：只输出纯文本，不要Markdown，不要星号、井号、反引号、下划线、"
                + "分隔线、表情或装饰符号；不要使用“首先、其次、总之”等套话。"
                + "\n请固定使用四个小标题：整体节奏、每天怎么走、交通与时间、注意事项。"
                + "每个小标题下用“1.”“2.”编号，每条不超过两句，直接说结论。"
                + "\n目的地：" + value(city)
                + "\n出发日期：" + value(start)
                + "\n天数：" + Math.max(1, days)
                + "\n同行：" + value(people)
                + "\n偏好：" + value(preferences)
                + "\n已选行程：\n" + normalized;
        try {
            String answer = cleanTravelText(aiChatService.chat(prompt));
            out.put("status", "success");
            out.put("configured", true);
            out.put("answer", answer);
            return out;
        } catch (Exception e) {
            out.put("status", "degraded");
            out.put("configured", true);
            out.put("answer", "");
            out.put("message", "AI 润色失败，结构化行程已保留，可继续手动调整");
            return out;
        }
    }

    private static String value(String v) {
        return v == null || v.trim().isEmpty() ? "未填写" : v.trim();
    }

    private static String cleanTravelText(String text) {
        if (text == null) return "";
        return text
                .replace("\r\n", "\n").replace('\r', '\n')
                .replace("```", "")
                .replaceAll("(?m)^\\s*#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s*[-*•]\\s+", "")
                .replaceAll("[*_`~#>|]", "")
                .replace("￥", "¥")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static Map<String, Object> polishError(Map<String, Object> out, String message) {
        out.put("status", "error");
        out.put("message", message);
        return out;
    }

    private static double[] weights(String preset, double s, double w, double f, double h, double d, double b) {
        double[] v;
        if ("saving".equals(preset)) v = new double[]{20, 15, 5, 10, 15, 35};
        else if ("seasonal".equals(preset)) v = new double[]{40, 30, 20, 5, 3, 2};
        else if ("popular".equals(preset)) v = new double[]{10, 10, 20, 50, 5, 5};
        else if ("nearby".equals(preset)) v = new double[]{10, 10, 5, 5, 60, 10};
        else v = new double[]{s, w, f, h, d, b};
        double sum = 0;
        for (double x : v) sum += Math.max(0, x);
        if (sum <= 0) v = new double[]{25, 20, 15, 15, 15, 10};
        sum = 0;
        for (double x : v) sum += x;
        for (int i = 0; i < v.length; i++) v[i] = v[i] / sum;
        return v;
    }

    private static Map<String, Object> weightsMap(double[] v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("season", Math.round(v[0] * 100));
        m.put("weather", Math.round(v[1] * 100));
        m.put("festival", Math.round(v[2] * 100));
        m.put("heat", Math.round(v[3] * 100));
        m.put("distance", Math.round(v[4] * 100));
        m.put("budget", Math.round(v[5] * 100));
        return m;
    }

    private static double seasonProxy(int month, int best) {
        int diff = Math.abs(month - best);
        diff = Math.min(diff, 12 - diff);
        return Math.max(0, 1 - diff / 5.0);
    }

    private static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371, dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
