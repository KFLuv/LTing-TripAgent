package com.iflytek.chatgpt.agent.tool;

import org.springframework.stereotype.Component;
import util.MySQLUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.format.DateTimeFormatter;

/**
 * Agent 的"手脚"：工具的真实实现。
 * <p>
 * 每个 public 方法对应一个工具，被 {@code TravelAgentService} 的 dispatch() 按名调用。
 * 方法只负责查库 / 写库，不做任何 AI 判断；返回值交给上层序列化成 JSON 喂回模型。
 * <p>
 * 安全约定：凡是带用户数据的工具，username 一律由上层从 session 取得后传入，
 * 绝不允许模型或前端直接指定（防越权）。
 * <p>
 * 四个方法区块分别对应四大子能力：
 * 行程编排引擎、偏好记忆引擎、位置向导引擎、多人协同引擎。
 */
@Component
public class TravelTools {

    // ==================== 一、动态活行程编排引擎 ====================

    /**
     * 工具：按关键词搜索目的地 / 景点
     * <p>
     * 也用于位置引擎——传入用户当前所在点位名称，取回该点位的资料。
     */
    public List<Map<String, String>> searchDestination(String keyword) {
        String trimmed = keyword == null ? "" : keyword.trim();

        StringBuilder sql = new StringBuilder(
                "select name, city, category, icon, description from spot");
        if (!trimmed.isEmpty()) {
            sql.append(" where name like ? or city like ? or category like ? or keywords like ?");
        }
        sql.append(" order by id limit 20");

        List<Map<String, String>> list = new ArrayList<>();
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            if (!trimmed.isEmpty()) {
                String like = "%" + trimmed + "%";
                for (int i = 1; i <= 4; i++) {
                    ps.setString(i, like);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("name"));
                    m.put("city", rs.getString("city"));
                    m.put("category", rs.getString("category"));
                    m.put("description", rs.getString("description"));
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询目的地失败：" + e.getMessage(), e);
        }
        return list;
    }

    /**
     * 工具：列出当前用户已保存的行程（只给标题等摘要，不含正文，避免撑爆上下文）
     */
    public List<Map<String, Object>> listTrips(String username) {
        List<Map<String, Object>> trips = new ArrayList<>();
        String sql = "select id, title, create_time from trip where username = ? order by id desc limit 50";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("title", rs.getString("title"));
                    m.put("createTime", String.valueOf(rs.getTimestamp("create_time")));
                    trips.add(m);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取行程列表失败：" + e.getMessage(), e);
        }
        return trips;
    }

    /**
     * 工具：读取某条行程的完整正文，用于"基于已有行程做变更/顺延"
     */
    public Map<String, Object> getTripDetail(String username, int id) {
        String sql = "select id, title, content, create_time from trip where id = ? and username = ?";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("title", rs.getString("title"));
                    m.put("content", rs.getString("content"));
                    m.put("createTime", String.valueOf(rs.getTimestamp("create_time")));
                    return m;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取行程失败：" + e.getMessage(), e);
        }
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("error", "行程不存在或不属于当前用户");
        return empty;
    }

    /**
     * 工具：把行程方案保存进行程本
     */
    public String saveTrip(String username, String title, String content) {
        String safeTitle = title == null || title.trim().isEmpty() ? "未命名行程" : title.trim();
        if (safeTitle.length() > 120) {
            safeTitle = safeTitle.substring(0, 120);
        }
        MySQLUtil.executeUpdate(
                "insert into trip(username, title, content, create_time) values(?, ?, ?, now())",
                username, safeTitle, content);
        return "已保存到行程本，标题：" + safeTitle;
    }

    // ==================== 二、用户旅游偏好记忆引擎 ====================

    /**
     * 工具：读取用户长期画像（跨旅行持久保存）
     */
    public List<Map<String, String>> listPreferences(String username) {
        List<Map<String, String>> list = new ArrayList<>();
        // `value` 在 H2 里是保留字，MySQL 与 H2 都认反引号，统一加上
        String sql = "select tag, `value` from user_preference where username = ? order by update_time";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("tag", rs.getString("tag"));
                    m.put("value", rs.getString("value"));
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取用户画像失败：" + e.getMessage(), e);
        }
        return list;
    }

    /**
     * 工具：写入 / 更新一条用户画像标签（同 tag 覆盖）
     */
    public String savePreference(String username, String tag, String value) {
        if (tag == null || tag.trim().isEmpty()) {
            return "标签为空，未记录";
        }
        // 用重复占位符代替 MySQL 专有的 values(value)：H2 不认识 values()，
        // 而 MySQL 8.0.20 起也把这个写法标记为废弃。两边都跑同一句更省心。
        String v = value == null ? "" : value.trim();
        MySQLUtil.executeUpdate(
                "insert into user_preference(username, tag, `value`, update_time) values(?, ?, ?, now())"
                        + " on duplicate key update `value` = ?, update_time = now()",
                username, tag.trim(), v, v);
        return "已更新画像：" + tag.trim() + " = " + (value == null ? "" : value.trim());
    }

    // ==================== 三、GPS 位置触发现场向导引擎 ====================

    /**
     * 工具：获取某个点位的现场资料
     * <p>
     * 返回库中匹配到的景点记录（真实数据）。库中没有的现场信息（实时排队、
     * 卫生间位置等）本工具不会编造，交由模型结合位置与常识给出落地建议，
     * 并明确标注"需现场确认"。
     */
    public Map<String, Object> getLocationGuide(String place) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("place", place);
        result.put("matchedSpots", searchDestination(place));
        result.put("matchedGuides", searchSpotGuide(place));
        result.put("note", "matchedGuides 是库中该点位的真实现场资料，务必优先使用；"
                + "库里没有的现场信息（如实时排队、当日临时活动）标注「需现场确认」，请勿编造。");
        return result;
    }

    /**
     * 查 spot_guide 里的 POI 级现场资料。
     * <p>
     * 双向匹配，兼顾两种输入：
     * 正向——用户说的是点位名（"广州塔"），命中库里的 name/aliases；
     * 反向——位置引擎给的是反查出来的长地址（"广州市黄埔区联和街道起云路"），
     * 用"长地址里是否包含库里的片区"来命中。
     */
    private List<Map<String, String>> searchSpotGuide(String keyword) {
        List<Map<String, String>> list = new ArrayList<>();
        String trimmed = keyword == null ? "" : keyword.trim();
        if (trimmed.isEmpty()) {
            return list;
        }

        String sql = "select name, city, area, open_time, closed_day, booking, photo_spots,"
                + " facilities, transport, pitfalls, tips from spot_guide"
                + " where name like ? or aliases like ? or ? like concat('%', area, '%') or city like ?"
                + " order by id limit 10";

        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            String like = "%" + trimmed + "%";
            ps.setString(1, like);
            ps.setString(2, like);
            ps.setString(3, trimmed);
            ps.setString(4, like);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("name"));
                    m.put("area", rs.getString("area"));
                    m.put("open_time", rs.getString("open_time"));
                    m.put("closed_day", rs.getString("closed_day"));
                    m.put("booking", rs.getString("booking"));
                    m.put("photo_spots", rs.getString("photo_spots"));
                    m.put("facilities", rs.getString("facilities"));
                    m.put("transport", rs.getString("transport"));
                    m.put("pitfalls", rs.getString("pitfalls"));
                    m.put("tips", rs.getString("tips"));
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询现场资料失败：" + e.getMessage(), e);
        }
        return list;
    }

    // ==================== 四、多人行程协同仲裁引擎 ====================

    /**
     * 工具：列出同行人及其偏好、投票诉求
     */
    public List<Map<String, String>> listCompanions(String owner) {
        List<Map<String, String>> list = new ArrayList<>();
        String sql = "select name, preferences, vote from trip_companion where owner = ? order by id";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("name"));
                    m.put("preferences", rs.getString("preferences"));
                    m.put("vote", rs.getString("vote"));
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取同行人失败：" + e.getMessage(), e);
        }
        return list;
    }

    /**
     * 工具：登记 / 更新一名同行人的偏好或投票诉求（同名覆盖）
     */
    public String saveCompanion(String owner, String name, String preferences, String vote) {
        if (name == null || name.trim().isEmpty()) {
            return "同行人姓名为空，未记录";
        }
        String prefs = preferences == null ? "" : preferences.trim();
        String voteText = vote == null ? "" : vote.trim();
        MySQLUtil.executeUpdate(
                "insert into trip_companion(owner, name, preferences, vote, update_time)"
                        + " values(?, ?, ?, ?, now())"
                        + " on duplicate key update preferences = ?, vote = ?, update_time = now()",
                owner, name.trim(), prefs, voteText, prefs, voteText);
        return "已登记同行人：" + name.trim();
    }

    // ==================== 五、五大资产 Tab（行程库 / 轨迹日志 / 收藏 / 偏好模板 / 工具箱） ====================
    // 这些工具让"对话"能真正读写 App 里的资产：这一轮写进去，下一轮能读回来，形成闭环。

    /** 行程允许的状态，和 TripController 保持一致 */
    private static final List<String> TRIP_STATUS = Arrays.asList("规划中", "进行中", "已结束");

    /** 时间格式：与 App 其它模块保持一致 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 去空白 + 截断到字段上限，避免超长写入报错 */
    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }
    private static final List<String> NODE_TYPES = Arrays.asList(
            "timeline", "order", "booking", "risk", "note", "route");
    private static final List<String> BUDGET_CATEGORIES = Arrays.asList(
            "transport", "lodging", "food", "ticket", "other");

    // —— 5.1 行程节点：时间线 / 订单 / 预约 / 风险 / 备注 / 备选路线 ——

    /** 工具：读取某行程的节点（可按 nodeType 过滤） */
    public List<Map<String, Object>> listTripNodes(String username, int tripId, String nodeType) {
        boolean filter = nodeType != null && NODE_TYPES.contains(nodeType.trim());
        String sql = "select id, node_type, title, detail, node_time, status, cost from trip_node"
                + " where trip_id = ? and username = ?" + (filter ? " and node_type = ?" : "")
                + " order by id";
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            if (filter) {
                ps.setString(3, nodeType.trim());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("nodeType", rs.getString("node_type"));
                    m.put("title", rs.getString("title"));
                    m.put("detail", rs.getString("detail"));
                    m.put("nodeTime", rs.getString("node_time"));
                    m.put("status", rs.getString("status"));
                    m.put("cost", rs.getBigDecimal("cost"));
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取行程节点失败：" + e.getMessage(), e);
        }
        return list;
    }

    /** 工具：给行程加一个节点（订单/预约/风险/备注/时间线/备选路线） */
    public String saveTripNode(String username, int tripId, String nodeType, String title,
                               String detail, String nodeTime, String status, Double cost) {
        if (!tripExists(username, tripId)) {
            return "行程不存在或不属于当前用户，节点未保存";
        }
        String type = nodeType == null || !NODE_TYPES.contains(nodeType.trim()) ? "note" : nodeType.trim();
        String nodeTitle = title == null || title.trim().isEmpty() ? "未命名节点" : title.trim();
        MySQLUtil.executeUpdate(
                "insert into trip_node(trip_id, username, node_type, title, detail, node_time,"
                        + " status, cost, create_time) values(?, ?, ?, ?, ?, ?, ?, ?, now())",
                tripId, username, type, nodeTitle, detail, nodeTime, status, cost);
        return "已为行程#" + tripId + "新增节点（" + type + "）：" + nodeTitle;
    }

    /** 工具：变更行程状态（规划中 / 进行中 / 已结束） */
    public String setTripStatus(String username, int tripId, String status) {
        String s = status == null ? "" : status.trim();
        if (!TRIP_STATUS.contains(s)) {
            return "状态只能是：规划中 / 进行中 / 已结束";
        }
        int rows = MySQLUtil.executeUpdate(
                "update trip set status = ?, update_time = now() where id = ? and username = ?",
                s, tripId, username);
        return rows > 0 ? "行程#" + tripId + "状态已改为「" + s + "」" : "行程不存在或不属于当前用户";
    }

    // —— 5.2 版本快照：查看与回退 ——

    /** 工具：查看某行程的版本历史 */
    public List<Map<String, Object>> listTripVersions(String username, int tripId) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "select id, version_no, title, change_note, create_time from trip_version"
                + " where trip_id = ? and username = ? order by version_no desc";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("versionId", rs.getInt("id"));
                    m.put("versionNo", rs.getInt("version_no"));
                    m.put("title", rs.getString("title"));
                    m.put("changeNote", rs.getString("change_note"));
                    m.put("createTime", String.valueOf(rs.getTimestamp("create_time")));
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取版本历史失败：" + e.getMessage(), e);
        }
        return list;
    }

    /** 工具：把行程回退到某个历史版本（回退前自动备份当前状态） */
    public String rollbackTrip(String username, int versionId) {
        String title = null;
        String content = null;
        int tripId = 0;
        int sourceNo = 0;
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select trip_id, version_no, title, content from trip_version where id = ? and username = ?")) {
            ps.setInt(1, versionId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    tripId = rs.getInt("trip_id");
                    sourceNo = rs.getInt("version_no");
                    title = rs.getString("title");
                    content = rs.getString("content");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取版本失败：" + e.getMessage(), e);
        }
        if (tripId == 0) {
            return "版本不存在或不属于当前用户";
        }
        snapshotTrip(username, tripId, "回退前自动备份");
        MySQLUtil.executeUpdate(
                "update trip set title = ?, content = ?, update_time = now() where id = ? and username = ?",
                title, content, tripId, username);
        snapshotTrip(username, tripId, "回退到版本 v" + sourceNo);
        return "已把行程#" + tripId + "回退到 v" + sourceNo + "（回退前已自动备份）";
    }

    /** 存一版快照（内部复用，逻辑与 TripController.snapshot 一致） */
    private void snapshotTrip(String username, int tripId, String changeNote) {
        String title = null;
        String content = null;
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select title, content from trip where id = ? and username = ?")) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    title = rs.getString("title");
                    content = rs.getString("content");
                }
            }
        } catch (SQLException e) {
            return;
        }
        if (content == null) {
            return;
        }
        int nextNo = 1;
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select coalesce(max(version_no), 0) + 1 from trip_version where trip_id = ? and username = ?")) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    nextNo = rs.getInt(1);
                }
            }
        } catch (SQLException ignored) {
            // 取不到就默认 1，不影响保存
        }
        MySQLUtil.executeUpdate(
                "insert into trip_version(trip_id, username, version_no, title, content, change_note, create_time)"
                        + " values(?, ?, ?, ?, ?, ?, now())",
                tripId, username, nextNo, title, content, changeNote);
    }

    // —— 5.3 旅途轨迹日志：打卡 / 评价 / 耗时 / 实拍 ——

    /** 工具：记录一次打卡（喜欢/避雷/一般 + 备注 + 耗时 + 实拍） */
    public String logCheckin(String username, Integer tripId, String place, String rating,
                             String note, Integer durationMin, String photos) {
        if (place == null || place.trim().isEmpty()) {
            return "打卡点位为空，未记录";
        }
        String r = rating == null || rating.trim().isEmpty() ? "neutral" : rating.trim();
        MySQLUtil.executeUpdate(
                "insert into travel_log(username, trip_id, place, rating, note, duration_min, photos,"
                        + " log_time, create_time) values(?, ?, ?, ?, ?, ?, ?, now(), now())",
                username, tripId == null || tripId <= 0 ? null : tripId, place.trim(),
                r, note, durationMin, photos);
        return "已记录打卡：" + place.trim() + "（" + r + "）";
    }

    /** 工具：查看历史游玩记录（可按行程过滤） */
    public List<Map<String, Object>> listTravelLogs(String username, int tripId) {
        boolean filter = tripId > 0;
        String sql = "select id, trip_id, place, rating, note, duration_min, photos, log_time"
                + " from travel_log where username = ?" + (filter ? " and trip_id = ?" : "")
                + " order by id desc";
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            if (filter) {
                ps.setInt(2, tripId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("tripId", rs.getObject("trip_id"));
                    m.put("place", rs.getString("place"));
                    m.put("rating", rs.getString("rating"));
                    m.put("note", rs.getString("note"));
                    m.put("durationMin", rs.getObject("duration_min"));
                    m.put("photos", rs.getString("photos"));
                    m.put("logTime", String.valueOf(rs.getTimestamp("log_time")));
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取游玩记录失败：" + e.getMessage(), e);
        }
        return list;
    }

    /**
     * 工具：行程结束后，把轨迹日志汇总成结构化游记草稿并落库。
     * 返回草稿正文，供模型润色后直接展示给用户。
     */
    public String genTravelog(String username, int tripId) {
        String tripTitle = null;
        String tripContent = null;
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select title, content from trip where id = ? and username = ?")) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    tripTitle = rs.getString("title");
                    tripContent = rs.getString("content");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取行程失败：" + e.getMessage(), e);
        }
        if (tripTitle == null && tripContent == null) {
            return "行程不存在或不属于当前用户，无法生成游记";
        }

        List<Map<String, Object>> logs = listTravelLogs(username, tripId);
        StringBuilder draft = new StringBuilder();
        StringBuilder highlights = new StringBuilder();
        StringBuilder pitfalls = new StringBuilder();
        draft.append("一、行程概览\n");
        draft.append(tripContent == null || tripContent.trim().isEmpty()
                ? "（本行程暂无正文记录）" : tripContent.trim()).append("\n\n");
        draft.append("二、旅途足迹\n");
        if (logs.isEmpty()) {
            draft.append("- 暂无打卡记录\n");
        }
        for (int i = 0; i < logs.size(); i++) {
            Map<String, Object> g = logs.get(i);
            String rate = String.valueOf(g.get("rating"));
            String rateCn = "like".equals(rate) ? "喜欢" : ("avoid".equals(rate) ? "避雷" : "一般");
            draft.append(i + 1).append(". ").append(g.get("logTime")).append(" · ").append(g.get("place"))
                    .append(" · ").append(rateCn);
            Object dur = g.get("durationMin");
            if (dur != null) {
                draft.append(" · 耗时").append(dur).append("分钟");
            }
            Object note = g.get("note");
            if (note != null && !String.valueOf(note).trim().isEmpty()) {
                draft.append(" · ").append(note);
            }
            draft.append("\n");
            if ("like".equals(rate)) {
                highlights.append("- ").append(g.get("place")).append("：").append(note == null ? "" : note).append("\n");
            } else if ("avoid".equals(rate)) {
                pitfalls.append("- ").append(g.get("place")).append("：").append(note == null ? "" : note).append("\n");
            }
        }
        draft.append("\n三、实拍点位\n");
        boolean hasPhoto = false;
        for (Map<String, Object> g : logs) {
            Object photos = g.get("photos");
            if (photos != null && !String.valueOf(photos).trim().isEmpty()) {
                draft.append("- ").append(g.get("place")).append("：").append(photos).append("\n");
                hasPhoto = true;
            }
        }
        if (!hasPhoto) {
            draft.append("- 暂无实拍记录\n");
        }

        String safeTitle = (tripTitle == null || tripTitle.trim().isEmpty() ? "我的旅行" : tripTitle.trim())
                + " · 游记草稿";
        MySQLUtil.executeUpdate(
                "insert into travelog(username, trip_id, title, draft, highlights, pitfalls, create_time)"
                        + " values(?, ?, ?, ?, ?, ?, now())",
                username, tripId, safeTitle, draft.toString(),
                highlights.length() == 0 ? "暂无特别亮点" : highlights.toString().trim(),
                pitfalls.length() == 0 ? "暂未踩坑" : pitfalls.toString().trim());
        return "已生成游记草稿《" + safeTitle + "》：\n" + draft;
    }
    // —— 5.5 偏好模板 ——

    /** 工具：列出用户的多套偏好模板及各自标签 */
    public List<Map<String, Object>> listPreferenceTemplates(String username) {
        List<Map<String, Object>> templates = new ArrayList<>();
        String sql = "select id, name, is_active from preference_template where username = ? order by id";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("name", rs.getString("name"));
                    m.put("isActive", rs.getInt("is_active") == 1);
                    m.put("items", templateItems(username, rs.getInt("id")));
                    templates.add(m);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取偏好模板失败：" + e.getMessage(), e);
        }
        return templates;
    }

    /** 工具：启用某套偏好模板，并把它同步进"当前生效画像" */
    public String activatePreferenceTemplate(String username, int templateId) {
        String name = null;
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select name from preference_template where id = ? and username = ?")) {
            ps.setInt(1, templateId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    name = rs.getString("name");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取偏好模板失败：" + e.getMessage(), e);
        }
        if (name == null) {
            return "偏好模板不存在或不属于当前用户";
        }
        MySQLUtil.executeUpdate("update preference_template set is_active = 0 where username = ?", username);
        MySQLUtil.executeUpdate("update preference_template set is_active = 1 where id = ? and username = ?",
                templateId, username);
        List<Map<String, String>> items = templateItems(username, templateId);
        for (Map<String, String> item : items) {
            MySQLUtil.executeUpdate(
                    "insert into user_preference(username, tag, `value`, update_time) values(?, ?, ?, now())"
                            + " on duplicate key update `value` = ?, update_time = now()",
                    username, item.get("tag"), item.get("value"), item.get("value"));
        }
        return "已启用偏好模板「" + name + "」，共 " + items.size() + " 个标签已生效";
    }

    private List<Map<String, String>> templateItems(String username, int templateId) {
        List<Map<String, String>> list = new ArrayList<>();
        String sql = "select tag, `value` from preference_item where template_id = ? and username = ? order by id";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, templateId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("tag", rs.getString("tag"));
                    m.put("value", rs.getString("value"));
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取模板标签失败：" + e.getMessage(), e);
        }
        return list;
    }

    // —— 5.6 工具箱：预算 / 行李 / 证件 ——

    /** 工具：记一笔预算（交通/住宿/餐饮/门票/其他） */
    public String saveBudgetEntry(String username, Integer tripId, String category, String item,
                                  Double amount, String entryDate) {
        String cat = category == null || !BUDGET_CATEGORIES.contains(category.trim())
                ? "other" : category.trim();
        MySQLUtil.executeUpdate(
                "insert into budget_entry(username, trip_id, category, item, amount, entry_date, create_time)"
                        + " values(?, ?, ?, ?, ?, ?, now())",
                username, tripId == null || tripId <= 0 ? null : tripId,
                cat, item, amount == null ? 0d : amount, entryDate);
        return "已记账：" + budgetCategoryCn(cat) + " " + (amount == null ? 0 : amount) + " 元"
                + (item == null || item.trim().isEmpty() ? "" : "（" + item.trim() + "）");
    }

    /** 工具：查看预算汇总与告警（总花费 / 分类 / 是否超阈值） */
    public Map<String, Object> getBudgetSummary(String username, int tripId) {
        Map<String, Object> byCategory = new LinkedHashMap<>();
        for (String c : BUDGET_CATEGORIES) {
            byCategory.put(c, 0d);
        }
        double total = 0;
        boolean filter = tripId > 0;
        String sql = "select category, amount from budget_entry where username = ?"
                + (filter ? " and trip_id = ?" : "");
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            if (filter) {
                ps.setInt(2, tripId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String cat = rs.getString("category");
                    double amt = rs.getBigDecimal("amount") == null ? 0 : rs.getBigDecimal("amount").doubleValue();
                    total += amt;
                    byCategory.put(cat, ((Number) byCategory.getOrDefault(cat, 0d)).doubleValue() + amt);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取预算失败：" + e.getMessage(), e);
        }
        double threshold = readBudgetThreshold(username);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", round2(total));
        result.put("byCategory", byCategory);
        result.put("threshold", threshold);
        result.put("overAlert", threshold > 0 && total > threshold);
        result.put("note", threshold > 0 && total > threshold
                ? "已超出预算告警阈值 " + threshold + " 元，请注意控制开销"
                : "未超出预算告警阈值");
        return result;
    }

    /** 工具：设置预算告警阈值 */
    public String setBudgetThreshold(String username, double amount) {
        String amountText = String.valueOf(amount);
        MySQLUtil.executeUpdate(
                "insert into user_preference(username, tag, `value`, update_time) values(?, '预算告警阈值', ?, now())"
                        + " on duplicate key update `value` = ?, update_time = now()",
                username, amountText, amountText);
        return "预算告警阈值已设为 " + amount + " 元";
    }

    /** 工具：保存一份行李清单模板（items 用换行分隔） */
    public String savePackingTemplate(String username, String name, String items) {
        if (name == null || name.trim().isEmpty()) {
            return "模板名称为空，未保存";
        }
        MySQLUtil.executeUpdate(
                "insert into packing_template(username, name, items, create_time) values(?, ?, ?, now())",
                username, name.trim(), items);
        return "已保存行李模板：" + name.trim();
    }

    /** 工具：列出用户的行李清单模板 */
    public List<Map<String, String>> listPackingTemplates(String username) {
        List<Map<String, String>> list = new ArrayList<>();
        String sql = "select name, items from packing_template where username = ? order by id desc";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("name"));
                    m.put("items", rs.getString("items"));
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取行李模板失败：" + e.getMessage(), e);
        }
        return list;
    }

    /** 工具：把车票/酒店/预约码/电子合同/证件/应急记录归档到工具箱 */
    public String saveDocument(String username, String docType, String title, String docNo,
                               String detail, String expireTime) {
        if (title == null || title.trim().isEmpty()) {
            return "标题为空，未归档";
        }
        String type = docType == null ? "" : docType.trim();
        if (!Arrays.asList("ticket", "hotel", "booking", "contract", "idcard", "emergency", "tool").contains(type)) {
            type = "tool";
        }
        MySQLUtil.executeUpdate(
                "insert into toolbox_document(username, doc_type, title, doc_no, detail, expire_time, create_time)"
                        + " values(?, ?, ?, ?, ?, ?, now())",
                username, type, title.trim(), docNo, detail, expireTime);
        return "已归档到工具箱（" + documentTypeCn(type) + "）：" + title.trim();
    }

    private String budgetCategoryCn(String category) {
        switch (category) {
            case "transport": return "交通";
            case "lodging": return "住宿";
            case "food": return "餐饮";
            case "ticket": return "门票";
            default: return "其他";
        }
    }

    private String documentTypeCn(String type) {
        switch (type) {
            case "ticket": return "车票/机票";
            case "hotel": return "酒店";
            case "booking": return "预约码";
            case "contract": return "电子合同";
            case "idcard": return "证件";
            case "emergency": return "应急记录";
            default: return "工具模板";
        }
    }

    private double readBudgetThreshold(String username) {
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select `value` from user_preference where username = ? and tag = '预算告警阈值'")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String v = rs.getString("value");
                    return v == null || v.trim().isEmpty() ? 0 : Double.parseDouble(v.trim());
                }
            }
        } catch (Exception ignored) {
            // 读不到按未设置处理
        }
        return 0;
    }

    private double round2(double v) {
        return Math.round(v * 100d) / 100d;
    }

    /** 判断行程是否属于该用户（写节点前的越权校验） */
    private boolean tripExists(String username, int tripId) {
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement("select 1 from trip where id = ? and username = ?")) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    // ==================== 六、朋友圈 / 个人资料（AI 也能读写这两个模块的数据） ====================

    /**
     * 工具：发布一条朋友圈动态。
     * <p>
     * 发布时间由服务端 now() 记录（不由模型或前端指定）；
     * 地点优先用坐标反查，反查不到就留空——不编造地名。
     */
    public String saveMoment(String username, String content, String place, String visibility,
                             Double lat, Double lng) {
        if (content == null || content.trim().isEmpty()) {
            return "动态内容为空，未发布";
        }
        String safeContent = trim(content, 2000);
        String safePlace = trim(place, 120);
        String safeVisibility = com.iflytek.chatgpt.social.Visibility.normalize(visibility);
        MySQLUtil.executeUpdate(
                "insert into moment(username, content, place, lat, lng, visibility, create_time)"
                        + " values(?, ?, ?, ?, ?, ?, now())",
                username, safeContent, safePlace, lat, lng, safeVisibility);
        return "已发布动态（可见：" + safeVisibility + "）"
                + (safePlace == null || safePlace.isEmpty() ? "" : "，地点：" + safePlace);
    }

    /** 工具：读最近的朋友圈动态 */
    public List<Map<String, Object>> listMoments(String username, Integer limit) {
        int take = limit == null || limit <= 0 ? 10 : Math.min(limit, 50);
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "select id, content, place, create_time from moment"
                + " where username = ? order by id desc limit " + take;
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("content", rs.getString("content"));
                    m.put("place", rs.getString("place"));
                    java.sql.Timestamp ts = rs.getTimestamp("create_time");
                    m.put("createTime", ts == null ? "" : ts.toLocalDateTime().format(TIME_FORMAT));
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            // 读不到就返回空列表，由模型如实告知用户
        }
        return list;
    }

    /** 工具：读个人资料 */
    public List<Map<String, Object>> getProfile(String username) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "select nickname, tags, bio, interests from user_profile where username = ?";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nickname", rs.getString("nickname"));
                    m.put("tags", rs.getString("tags"));
                    m.put("bio", rs.getString("bio"));
                    m.put("interests", rs.getString("interests"));
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            // 同上：空资料就让模型说"还没填过"
        }
        return list;
    }

    /**
     * 工具：更新个人资料。
     * <p>
     * 只覆盖传进来的字段（null 或空串都视为"这次不改"），避免模型补一句话就把别的资料清空。
     * 头像不走这里——那是用户在资料页自己传的图。
     */
    public String saveProfile(String username, String nickname, String tags, String bio, String interests) {
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (nickname != null && !nickname.trim().isEmpty()) {
            sets.add(" nickname = ?");
            params.add(trim(nickname, 60));
        }
        if (tags != null && !tags.trim().isEmpty()) {
            sets.add(" tags = ?");
            params.add(trim(tags, 300));
        }
        if (bio != null && !bio.trim().isEmpty()) {
            sets.add(" bio = ?");
            params.add(trim(bio, 500));
        }
        if (interests != null && !interests.trim().isEmpty()) {
            sets.add(" interests = ?");
            params.add(trim(interests, 500));
        }
        if (sets.isEmpty()) {
            return "没有需要更新的资料字段";
        }
        params.add(username);
        MySQLUtil.executeUpdate(
                "update user_profile set" + String.join(",", sets) + ", update_time = now() where username = ?",
                params.toArray());
        return "已更新个人资料：" + String.join("、", sets).replace(" = ?", "").trim();
    }
}
