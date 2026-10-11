package com.iflytek.chatgpt.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.iflytek.chatgpt.agent.ChatMemoryRepository;
import com.iflytek.chatgpt.agent.CoordConverter;
import com.iflytek.chatgpt.agent.NavigationService;
import com.iflytek.chatgpt.agent.ReverseGeocoder;
import com.iflytek.chatgpt.agent.TravelAgentService;
import com.iflytek.chatgpt.service.AiChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 智能旅行管家（Agent）对外接口
 * <p>
 * 与 {@code /Aichat} 的区别：这里走的是带工具的 Agent 循环（能查景点、读写行程本、
 * 记画像、登记同行人），而不是一问一答。前端只要把 fetch('Aichat') 换成 fetch('agent/chat') 即可。
 */
@RestController
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    /** 用户实时位置存放在 session 里的 key */
    private static final String SESSION_LOCATION = "userLocation";

    /** 单轮对话默认回放的历史条数（user/assistant 成对，20 条约 10 轮） */
    private static final int DEFAULT_HISTORY_LIMIT = 20;

    /**
     * 同一"点位名"的推送去重窗口（分钟）。
     * <p>
     * 前端 GPS 抖动会让同一地点每次上报的经纬度都有细微差异，
     * 手动连点也会重复上报，若只看坐标是否变化就会反复触发大模型播报。
     * 这里以「点位名相同 + 窗口内」为去重条件，位置照常记录，只是不再重复推送。
     */
    private static final long PUSH_DEDUP_MINUTES = 10L;

    private final TravelAgentService travelAgentService;
    private final AiChatService aiChatService;
    private final ReverseGeocoder reverseGeocoder;
    private final NavigationService navigationService;
    private final ChatMemoryRepository chatMemoryRepository;

    /** 页面内嵌地图用的高德 JS API 凭证（与 geo.amap-key 的「Web服务」Key 不是同一个，类型不同不能混用） */
    @Value("${map.js-key:}")
    private String jsMapKey;

    @Value("${map.js-security-code:}")
    private String jsMapSecurityCode;

    public AgentController(TravelAgentService travelAgentService, AiChatService aiChatService,
                           ReverseGeocoder reverseGeocoder, NavigationService navigationService,
                           ChatMemoryRepository chatMemoryRepository) {
        this.travelAgentService = travelAgentService;
        this.aiChatService = aiChatService;
        this.reverseGeocoder = reverseGeocoder;
        this.navigationService = navigationService;
        this.chatMemoryRepository = chatMemoryRepository;
    }

    /**
     * 路径规划：内嵌地图画「我 → 目的地」线时调用，支持驾车/步行/公交。
     * <p>
     * 前端 JS API 缺安全密钥时自带的路线插件会失败，所以改由后端用「Web 服务」Key 算好折线，
     * 前端只负责画。起点是浏览器定位的 WGS-84，终点是高德的 GCJ-02，转换在服务里做。
     * 公交（transit）接口必须带起点城市，这里先逆地理解析出来再规划。
     */
    @PostMapping(value = "/agent/route", produces = "application/json;charset=UTF-8")
    public Map<String, Object> route(@RequestParam double fromLat, @RequestParam double fromLng,
                                     @RequestParam double toLat, @RequestParam double toLng,
                                     @RequestParam(required = false) String mode) {
        if ("transit".equalsIgnoreCase(mode == null ? "" : mode.trim())) {
            String city = reverseGeocoder.resolveCity(fromLat, fromLng);
            return navigationService.planTransit(fromLat, fromLng, toLat, toLng, city);
        }
        return navigationService.planRoute(fromLat, fromLng, toLat, toLng, mode);
    }

    /**
     * 逆地理：内嵌地图上点击某点时用，把 GCJ-02 坐标换成地址。
     * <p>
     * 注意这里收的是高德地图坐标（GCJ-02），不是浏览器定位的 WGS-84，所以不能走 {@code resolve}，
     * 否则会再纠偏一次、地址偏几百米。
     */
    @PostMapping(value = "/agent/reverse", produces = "application/json;charset=UTF-8")
    public Map<String, Object> reverse(@RequestParam double lng, @RequestParam double lat) {
        Map<String, Object> result = new LinkedHashMap<>();
        String place = reverseGeocoder.resolveGcj(lat, lng);
        boolean ok = place != null && !place.isEmpty();
        result.put("ok", ok);
        if (ok) {
            result.put("place", place);
        } else {
            result.put("error", "该点附近没解析出地址");
        }
        return result;
    }

    /**
     * 地点搜索：内嵌地图搜索框用，把地名解析成经纬度（GCJ-02）后直接定位。
     */
    @PostMapping(value = "/agent/geocode", produces = "application/json;charset=UTF-8")
    public Map<String, Object> geocode(@RequestParam String address,
                                       @RequestParam(required = false) String city) {
        return navigationService.geocodePlace(address, city);
    }

    /**
     * 内嵌地图的上下文：JS API 凭证 + 用户当前位置（已转成高德用的 GCJ-02）。
     * <p>
     * 前端点「导航」时不再跳转，而是拿这份数据在聊天页里画一张可拖动的地图：
     * 红点是你、蓝点是目的地。坐标在这里纠偏，前端不用管坐标系。
     */
    @PostMapping(value = "/agent/map-context", produces = "application/json;charset=UTF-8")
    public Map<String, Object> mapContext(HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();

        String key = jsMapKey == null ? "" : jsMapKey.trim();
        String securityCode = jsMapSecurityCode == null ? "" : jsMapSecurityCode.trim();
        result.put("configured", !key.isEmpty());

        // JS API 的 Key 最终必然出现在浏览器里（高德的设计如此），这里只回给已登录用户
        if (ChatController.currentUser(session) != null && !key.isEmpty()) {
            result.put("key", key);
            result.put("securityCode", securityCode);
        }

        Double[] coords = currentCoords(session);
        if (coords[0] != null && coords[1] != null) {
            // 高德地图是 GCJ-02，浏览器定位是 WGS-84：不纠偏红点会偏几百米
            double[] gcj = CoordConverter.wgs84ToGcj02(coords[0], coords[1]);
            result.put("hasLocation", true);
            result.put("originLng", gcj[1]);
            result.put("originLat", gcj[0]);
            Object attr = session.getAttribute(SESSION_LOCATION);
            if (attr instanceof Map) {
                result.put("place", ((Map<?, ?>) attr).get("place"));
            }
        } else {
            result.put("hasLocation", false);
        }
        return result;
    }

    /**
     * 智能管家对话。
     * <p>
     * 返回 JSON：{@code answer} 给用户看的文本，{@code actions} 是本轮**真正写库**的操作摘要，
     * 前端据此渲染「本次为你做了 N 件事」的卡片。原本返回纯文本时，
     * Agent 即便真的改了数据，用户在界面上也只看到一个气泡，无法确认它做了什么。
     * <p>
     * 对话历史改为从 {@code chat_message} 表读取（原先存 session，关浏览器即丢）。
     */
    @PostMapping(value = "/agent/chat", produces = "application/json;charset=UTF-8")
    public Map<String, Object> chat(@RequestBody(required = false) String userMessage,
                                    @RequestParam(required = false) Long sessionId,
                                    HttpSession session) {
        // ★ 身份只从 session 取，绝不由前端或模型传入
        String username = ChatController.currentUser(session);
        if (username == null) {
            return chatReply("请先登录后再使用智能旅行管家", null);
        }

        String message = userMessage == null ? "" : userMessage.trim();
        if (message.isEmpty()) {
            return chatReply("请说说您的出行需求～", null);
        }
        if (!aiChatService.isConfigured()) {
            return chatReply("「智能旅行管家」需要先在 application.properties 里配置 ai.api-key 才能使用；"
                    + "在此之前可继续使用普通对话。", null);
        }

        // 前端会带当前会话 id；没带就接到最近一个会话（没有则新建）
        long sid = (sessionId != null && sessionId > 0) ? sessionId : chatMemoryRepository.ensureLatestSession(username);

        try {
            // 历史从库里读：只读当前会话，切会话即换上下文
            List<JsonNode> history = chatMemoryRepository.loadRecent(username, sid, DEFAULT_HISTORY_LIMIT);

            // 带上 App 上报的实时位置，让管家锚定当前位置；
            // 坐标另外传一份（供 searchNearby 周边搜索用），模型拿不到也改不了这两个值
            Double[] coords = currentCoords(session);
            TravelAgentService.AgentResult r = travelAgentService.runWithActions(
                    username, message, history, currentLocationContext(session), coords[0], coords[1]);

            // 落库：只存纯文本，不存工具调用链
            chatMemoryRepository.append(username, sid, "user", message);
            chatMemoryRepository.append(username, sid, "assistant", r.answer);
            // 会话还没有标题时，用第一条消息当小标题（用户手动改过的不动）
            chatMemoryRepository.autoTitle(username, sid, message);

            Map<String, Object> reply = chatReply(r.answer, r.actions);
            reply.put("sessionId", sid);
            return reply;
        } catch (Exception e) {
            log.error("智能管家处理失败", e);
            return chatReply("抱歉，智能旅行管家暂时不可用：" + e.getMessage(), null);
        }
    }

    /** 统一 Agent 对话返回体：answer 必有，actions 无写操作时为 null */
    private Map<String, Object> chatReply(String answer, List<Map<String, String>> actions) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answer", answer == null ? "" : answer);
        out.put("actions", actions == null ? java.util.Collections.emptyList() : actions);
        return out;
    }

    /**
     * 读取历史对话，供前端进页面时回放气泡。
     * <p>
     * 只有后端记住历史、界面却空白，用户仍然会觉得"断了"——连续感需要后端与界面同时具备。
     */
    @PostMapping(value = "/agent/history", produces = "application/json;charset=UTF-8")
    public Map<String, Object> history(@RequestParam(required = false) Integer limit,
                                       @RequestParam(required = false) Long sessionId,
                                       HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            out.put("login", false);
            out.put("messages", java.util.Collections.emptyList());
            return out;
        }
        int take = limit == null ? DEFAULT_HISTORY_LIMIT : Math.max(1, Math.min(limit, ChatMemoryRepository.MAX_LOAD));
        // 指定会话就回放指定会话；没指定就回放最近一个会话
        Long sid = (sessionId != null && sessionId > 0) ? sessionId : chatMemoryRepository.latestSessionId(username);
        out.put("login", true);
        out.put("sessionId", sid == null ? 0L : sid);
        out.put("title", sid == null ? "" : chatMemoryRepository.sessionTitle(username, sid));
        out.put("messages", sid == null ? java.util.Collections.emptyList()
                : chatMemoryRepository.loadRecent(username, sid, take));
        return out;
    }

    /**
     * 会话列表：侧栏「对话」区用。
     */
    @PostMapping(value = "/agent/session/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> sessionList(HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            out.put("login", false);
            out.put("sessions", java.util.Collections.emptyList());
            return out;
        }
        out.put("login", true);
        out.put("sessions", chatMemoryRepository.listSessions(username));
        return out;
    }

    /**
     * 新建会话：标题可空（默认「新对话」，发首条消息后自动改成摘要）。
     */
    @PostMapping(value = "/agent/session/new", produces = "application/json;charset=UTF-8")
    public Map<String, Object> sessionNew(@RequestParam(required = false) String title, HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            out.put("status", "error");
            out.put("message", "请先登录");
            return out;
        }
        long id = chatMemoryRepository.createSession(username, title);
        if (id <= 0) {
            out.put("status", "error");
            out.put("message", "新建会话失败");
            return out;
        }
        out.put("status", "success");
        out.put("sessionId", id);
        out.put("title", chatMemoryRepository.sessionTitle(username, id));
        return out;
    }

    /** 重命名会话 */
    @PostMapping(value = "/agent/session/rename", produces = "application/json;charset=UTF-8")
    public Map<String, Object> sessionRename(@RequestParam long id, @RequestParam String title, HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            out.put("status", "error");
            out.put("message", "请先登录");
            return out;
        }
        out.put("status", chatMemoryRepository.renameSession(username, id, title) ? "success" : "error");
        return out;
    }

    /** 删除会话（连同其消息） */
    @PostMapping(value = "/agent/session/delete", produces = "application/json;charset=UTF-8")
    public Map<String, Object> sessionDelete(@RequestParam long id, HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            out.put("status", "error");
            out.put("message", "请先登录");
            return out;
        }
        chatMemoryRepository.deleteSession(username, id);
        out.put("status", "success");
        return out;
    }

    /**
     * 定位上报接口：App / 前端把用户实时 GPS 或点位名上报到后端。
     * <p>
     * 两种触发方式：
     * <ul>
     *   <li>被动触发：上报的是"新点位"（与上次不同）且管家可用时，直接返回该点位的现场指引（guide 字段）；
     *       同一点位在 {@link #PUSH_DEDUP_MINUTES} 分钟内重复上报（GPS 抖动、手动连点）只记录不重复推送；</li>
     *   <li>主动询问：上报只做记录，之后用户在对话里问"附近有什么"，/agent/chat 会带着这个位置走位置引擎。</li>
     * </ul>
     * 位置数据存在 session 里（随会话存活），不落库。
     */
    @PostMapping(value = "/agent/location", produces = "application/json;charset=UTF-8")
    public Map<String, Object> reportLocation(@RequestParam(required = false) Double lat,
                                              @RequestParam(required = false) Double lng,
                                              @RequestParam(required = false) String place,
                                              @RequestParam(required = false) Double accuracy,
                                              @RequestParam(required = false, defaultValue = "false") boolean force,
                                              HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String placeName = place == null ? "" : place.trim();

        // 至少要有点位名或完整经纬度，否则这次上报没有意义
        if (placeName.isEmpty() && (lat == null || lng == null)) {
            result.put("status", "error");
            result.put("message", "请至少上报 place（点位名）或完整的 lat/lng 坐标");
            return result;
        }

        // 前端通常只给坐标，这里补一次"坐标 → 地名"，让后续指引能锚定具体位置；失败则退回坐标
        if (placeName.isEmpty()) {
            String resolved = reverseGeocoder.resolve(lat, lng);
            if (resolved != null && !resolved.isEmpty()) {
                placeName = resolved;
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> previous = (Map<String, Object>) session.getAttribute(SESSION_LOCATION);

        long now = System.currentTimeMillis();

        Map<String, Object> location = new LinkedHashMap<>();
        location.put("place", placeName);
        location.put("lat", lat);
        location.put("lng", lng);
        // 定位精度（米）：浏览器给的是"置信半径"，桌面端走 WiFi/基站定位时会有几百米误差
        location.put("accuracy", accuracy);
        location.put("time", LocalDateTime.now().toString());
        location.put("timeMillis", now);
        session.setAttribute(SESSION_LOCATION, location);

        // 是否是新点位：点位名或坐标与上次不同即视为切换
        boolean changed = previous == null
                || !Objects.equals(previous.get("place"), placeName)
                || !Objects.equals(previous.get("lat"), lat)
                || !Objects.equals(previous.get("lng"), lng);

        // 推送去重：同一点位名在窗口内重复上报（GPS 抖动 / 手动连点）不再触发大模型。
        // 但用户主动点「我的位置」时（force=true）跳过去重，保证每次点击都有新鲜的周边播报。
        boolean deduped = force ? false : (!changed || samePlaceWithin(previous, placeName, now));

        result.put("status", "success");
        result.put("place", placeName);
        result.put("lat", lat);
        result.put("lng", lng);
        result.put("accuracy", accuracy);
        result.put("changed", changed);
        result.put("deduped", deduped);

        // 被动触发：切到新点位（且不在去重窗口内）+ 已登录 + 管家可用 → 自动推一条现场指引
        String username = ChatController.currentUser(session);
        if (!deduped && username != null && aiChatService.isConfigured()) {
            try {
                result.put("guide", travelAgentService.locationBriefing(username, placeName, lat, lng, accuracy));
                result.put("pushed", true);
            } catch (Exception e) {
                log.error("位置触发现场指引失败", e);
                result.put("pushed", false);
            }
        } else {
            result.put("pushed", false);
        }
        return result;
    }

    /**
     * 同一点位名是否在去重窗口内重复出现。
     * 点位名为空（坐标反查失败）时不启用该去重，退回按坐标是否变化判断。
     */
    private boolean samePlaceWithin(Map<String, Object> previous, String placeName, long now) {
        if (previous == null || placeName.isEmpty() || !placeName.equals(previous.get("place"))) {
            return false;
        }
        Object last = previous.get("timeMillis");
        if (!(last instanceof Long)) {
            return false;
        }
        return now - (Long) last < PUSH_DEDUP_MINUTES * 60_000L;
    }

    /**
     * 取 session 里记录的 GPS 坐标，供周边搜索用；没有上报过定位时返回 {null, null}。
     * 坐标只从 session 取，绝不由前端或模型指定——否则"附近"就成了模型随便挑的一个点。
     */
    @SuppressWarnings("unchecked")
    private Double[] currentCoords(HttpSession session) {
        Object attr = session.getAttribute(SESSION_LOCATION);
        if (!(attr instanceof Map)) {
            return new Double[]{null, null};
        }
        Map<String, Object> loc = (Map<String, Object>) attr;
        Object lat = loc.get("lat");
        Object lng = loc.get("lng");
        return new Double[]{
                lat instanceof Number ? ((Number) lat).doubleValue() : null,
                lng instanceof Number ? ((Number) lng).doubleValue() : null};
    }

    /** 把 session 里记录的位置拼成一段可注入系统提示词的文本；没有则返回 null */
    @SuppressWarnings("unchecked")
    private String currentLocationContext(HttpSession session) {
        Object attr = session.getAttribute(SESSION_LOCATION);
        if (!(attr instanceof Map)) {
            return null;
        }
        Map<String, Object> loc = (Map<String, Object>) attr;

        String place = loc.get("place") == null ? "" : loc.get("place").toString();
        Object lat = loc.get("lat");
        Object lng = loc.get("lng");
        Object accuracy = loc.get("accuracy");
        String time = loc.get("time") == null ? "" : loc.get("time").toString();

        StringBuilder sb = new StringBuilder();
        if (!place.isEmpty()) {
            sb.append("当前点位：").append(place);
        }
        if (lat != null && lng != null) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append("经纬度 ").append(lat).append(", ").append(lng);
        }
        // 精度如实带上：桌面浏览器的定位来自 WiFi/基站，常常有几百米误差，
        // 不告知精度就等于让用户以为这个点位是准的（"我在绿地中央，它说我到了起云路"）。
        int acc = accuracy instanceof Number ? (int) Math.round(((Number) accuracy).doubleValue()) : -1;
        if (acc > 0) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append("浏览器定位精度约 ±").append(acc).append(" 米");
        }
        if (!time.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append("上报时间 ").append(time);
        }
        if (sb.length() == 0) {
            return null;
        }

        // ★ 位置只能来自 GPS 上报。坐标反查失败时地名是空的，这种情况下必须明说"未知"，
        //   否则模型会拿坐标去"猜"一个区/商圈，出现"人在黄埔、AI 说在天河"的严重误导。
        if (place.isEmpty()) {
            sb.append("。（注意：本次只拿到坐标，地名未解析成功。禁止推断该坐标所在的区/街道/商圈，"
                    + "只能说明「有坐标但地名未知」，可建议用户点一次「我的位置」重试或直接告知所在位置）");
        } else {
            sb.append("。这是系统 GPS 上报的唯一权威位置：回答中涉及用户所在位置的描述必须与之一致，"
                    + "禁止根据检索结果、攻略内容或常识把用户位置改写成其它区/商圈/街道。");
            if (acc > 300) {
                sb.append(" 但本次定位精度只有 ±").append(acc).append(" 米（WiFi/基站级定位，非手机 GPS），"
                        + "所以这个位置只能算「片区级」：不得声称用户已精确到达某个具体楼栋或门店，"
                        + "提到距离时要说明存在该定位误差；若用户说自己在别处，以用户说的为准。");
            }
        }
        return sb.toString();
    }

    /**
     * 探测智能管家当前是否可用（需同时满足：已登录 + 已配置大模型 Key）。
     * <p>
     * 前端据此决定：可用就走 agent/chat，否则自动回退到原来的 /Aichat，
     * 避免未登录 / 未配 Key 时体验变差（也不需要靠匹配回复文案来降级）。
     */
    @PostMapping(value = "/agent/available", produces = "application/json;charset=UTF-8")
    public Map<String, Object> available(HttpSession session) {
        boolean loggedIn = ChatController.currentUser(session) != null;
        boolean configured = aiChatService.isConfigured();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", loggedIn && configured);
        result.put("loggedIn", loggedIn);
        result.put("configured", configured);
        return result;
    }
}
