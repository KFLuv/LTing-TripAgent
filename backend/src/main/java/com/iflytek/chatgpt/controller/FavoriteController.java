package com.iflytek.chatgpt.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import util.MySQLUtil;

import javax.servlet.http.HttpSession;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 灵感收藏库：旅行里那些"看着不错，先存着"的点子，分门别类放好。
 * <p>
 * 支持四种清单（list_type）：
 * <ul>
 *   <li>favorite —— 收藏</li>
 *   <li>blacklist —— 避雷黑名单</li>
 *   <li>todo —— 待打卡</li>
 *   <li>wish —— 意向（餐厅 / 展览 / 市集）</li>
 * </ul>
 * 条目可以归进自定义分组（favorite_folder）；删分组只把条目的 folder_id 解除绑定，
 * 条目本身保留下来，不会跟着被删。
 * 所有接口都以 session 里的登录用户为准，SQL 一律用 username 限定，
 * 别人的收藏查不到、改不了、也删不掉。
 */
@RestController
public class FavoriteController {

    private static final Logger log = LoggerFactory.getLogger(FavoriteController.class);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 清单类型，非法值一律回退为 favorite，防止前端塞进乱七八糟的值 */
    private static final List<String> LIST_TYPES = Arrays.asList("favorite", "blacklist", "todo", "wish");

    // ==================== 收藏分组 ====================

    /**
     * 当前用户的分组列表，带上每个分组下的条目数
     */
    @PostMapping(value = "/favorites/folder/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> folderList(HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用灵感收藏库");
        }

        List<Map<String, Object>> folders = new ArrayList<>();
        String sql = "select f.id, f.name, f.create_time,"
                + " (select count(*) from favorite_item i"
                + " where i.folder_id = f.id and i.username = f.username) as item_count"
                + " from favorite_folder f where f.username = ? order by f.id desc";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("name", rs.getString("name"));
                    m.put("createTime", formatTime(rs.getTimestamp("create_time")));
                    m.put("itemCount", rs.getInt("item_count"));
                    folders.add(m);
                }
            }
        } catch (SQLException e) {
            log.error("查询收藏分组失败", e);
            return fail(result, "灵感收藏库读取失败，请稍后重试");
        }

        result.put("status", "success");
        result.put("folders", folders);
        return result;
    }

    /**
     * 新建或重命名一个分组（id &gt; 0 视为重命名，并校验归属）
     */
    @PostMapping(value = "/favorites/folder/save", produces = "application/json;charset=UTF-8")
    public Map<String, Object> folderSave(@RequestParam(defaultValue = "0") int id,
                                          @RequestParam(required = false) String name,
                                          HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用灵感收藏库");
        }
        if (name == null || name.trim().isEmpty()) {
            return fail(result, "分组名不能为空");
        }
        String safeName = trim(name, 60);

        int folderId = id;
        if (id > 0) {
            int rows = MySQLUtil.executeUpdate(
                    "update favorite_folder set name = ? where id = ? and username = ?",
                    safeName, id, username);
            if (rows <= 0) {
                return fail(result, "分组不存在");
            }
        } else {
            try (Connection conn = MySQLUtil.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "insert into favorite_folder(username, name, create_time) values(?, ?, now())",
                         PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, username);
                ps.setString(2, safeName);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    folderId = keys.next() ? keys.getInt(1) : 0;
                }
            } catch (SQLException e) {
                log.error("新建收藏分组失败", e);
                return fail(result, "分组保存失败，请稍后重试");
            }
        }

        result.put("status", "success");
        result.put("id", folderId);
        result.put("message", id > 0 ? "分组已更新" : "分组已创建");
        return result;
    }

    /**
     * 删除分组：只解绑分组下的条目，条目本身保留
     */
    @PostMapping(value = "/favorites/folder/delete", produces = "application/json;charset=UTF-8")
    public Map<String, Object> folderDelete(@RequestParam(defaultValue = "0") int id, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用灵感收藏库");
        }

        int rows = MySQLUtil.executeUpdate(
                "delete from favorite_folder where id = ? and username = ?", id, username);
        if (rows <= 0) {
            return fail(result, "分组不存在或已被删除");
        }
        // 分组没了，条目留着，只把归属清空
        MySQLUtil.executeUpdate(
                "update favorite_item set folder_id = null where folder_id = ? and username = ?",
                id, username);

        result.put("status", "success");
        result.put("message", "分组已删除");
        return result;
    }

    // ==================== 收藏条目 ====================

    /**
     * 收藏条目列表：listType 为空则不过滤类型；folderId 默认 -1 表示不过滤分组
     */
    @PostMapping(value = "/favorites/item/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> itemList(@RequestParam(required = false) String listType,
                                        @RequestParam(defaultValue = "-1") int folderId,
                                        HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用灵感收藏库");
        }

        boolean filterType = listType != null && !listType.trim().isEmpty();
        boolean filterFolder = folderId >= 0;
        String sql = "select id, folder_id, list_type, name, category, note, expect_duration, season, create_time"
                + " from favorite_item where username = ?"
                + (filterType ? " and list_type = ?" : "")
                + (filterFolder ? (folderId == 0 ? " and folder_id is null" : " and folder_id = ?") : "")
                + " order by id desc";

        List<Map<String, Object>> items = new ArrayList<>();
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setString(idx++, username);
            if (filterType) {
                ps.setString(idx++, normalizeListType(listType));
            }
            if (filterFolder && folderId != 0) {
                ps.setInt(idx++, folderId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(itemMap(rs));
                }
            }
        } catch (SQLException e) {
            log.error("查询收藏条目失败", e);
            return fail(result, "灵感收藏库读取失败，请稍后重试");
        }

        result.put("status", "success");
        result.put("items", items);
        return result;
    }

    /**
     * 新增或更新一条收藏（id &gt; 0 视为更新，并校验归属）。
     * folderId 为 0 表示不归组，落库为 null。
     */
    @PostMapping(value = "/favorites/item/save", produces = "application/json;charset=UTF-8")
    public Map<String, Object> itemSave(@RequestParam(defaultValue = "0") int id,
                                        @RequestParam(defaultValue = "0") int folderId,
                                        @RequestParam(required = false) String listType,
                                        @RequestParam(required = false) String name,
                                        @RequestParam(required = false) String category,
                                        @RequestParam(required = false) String note,
                                        @RequestParam(required = false) String expectDuration,
                                        @RequestParam(required = false) String season,
                                        HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用灵感收藏库");
        }
        if (name == null || name.trim().isEmpty()) {
            return fail(result, "收藏名称不能为空");
        }

        String safeName = trim(name, 120);
        String safeType = normalizeListType(listType);
        String safeCategory = trim(category, 40);
        String safeNote = trim(note, 500);
        String safeDuration = trim(expectDuration, 40);
        String safeSeason = trim(season, 40);
        Integer safeFolder = folderId == 0 ? null : folderId;

        int itemId = id;
        if (id > 0) {
            int rows = MySQLUtil.executeUpdate(
                    "update favorite_item set folder_id = ?, list_type = ?, name = ?, category = ?,"
                            + " note = ?, expect_duration = ?, season = ? where id = ? and username = ?",
                    safeFolder, safeType, safeName, safeCategory, safeNote, safeDuration, safeSeason,
                    id, username);
            if (rows <= 0) {
                return fail(result, "收藏不存在");
            }
        } else {
            try (Connection conn = MySQLUtil.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "insert into favorite_item(username, folder_id, list_type, name, category, note,"
                                 + " expect_duration, season, create_time) values(?, ?, ?, ?, ?, ?, ?, ?, now())",
                         PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, username);
                if (safeFolder == null) {
                    ps.setNull(2, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(2, safeFolder);
                }
                ps.setString(3, safeType);
                ps.setString(4, safeName);
                ps.setString(5, safeCategory);
                ps.setString(6, safeNote);
                ps.setString(7, safeDuration);
                ps.setString(8, safeSeason);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    itemId = keys.next() ? keys.getInt(1) : 0;
                }
            } catch (SQLException e) {
                log.error("保存收藏条目失败", e);
                return fail(result, "收藏保存失败，请稍后重试");
            }
        }

        result.put("status", "success");
        result.put("id", itemId);
        result.put("message", id > 0 ? "收藏已更新" : "已加入灵感收藏库");
        return result;
    }

    /**
     * 删除一条收藏（校验归属）
     */
    @PostMapping(value = "/favorites/item/delete", produces = "application/json;charset=UTF-8")
    public Map<String, Object> itemDelete(@RequestParam(defaultValue = "0") int id, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用灵感收藏库");
        }

        int rows = MySQLUtil.executeUpdate(
                "delete from favorite_item where id = ? and username = ?", id, username);
        if (rows <= 0) {
            return fail(result, "收藏不存在或已被删除");
        }

        result.put("status", "success");
        result.put("message", "已删除");
        return result;
    }

    // ==================== 内部工具方法 ====================

    private Map<String, Object> itemMap(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getInt("id"));
        m.put("folderId", rs.getObject("folder_id"));
        m.put("listType", rs.getString("list_type"));
        m.put("name", rs.getString("name"));
        m.put("category", rs.getString("category"));
        m.put("note", rs.getString("note"));
        m.put("expectDuration", rs.getString("expect_duration"));
        m.put("season", rs.getString("season"));
        m.put("createTime", formatTime(rs.getTimestamp("create_time")));
        return m;
    }

    private String formatTime(java.sql.Timestamp ts) {
        return ts == null ? "" : ts.toLocalDateTime().format(TIME_FORMAT);
    }

    /** 清单类型兜底：不在白名单里的一律当收藏 */
    private String normalizeListType(String listType) {
        String s = listType == null ? "" : listType.trim();
        return LIST_TYPES.contains(s) ? s : "favorite";
    }

    private String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    private Map<String, Object> fail(Map<String, Object> result, String message) {
        result.put("status", "error");
        result.put("message", message);
        return result;
    }
}
