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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 旅行偏好档案：给一名用户配多套可切换的偏好模板。
 * <p>
 * 一套模板由若干"标签 / 取值"组成（如 步行耐受 / 中等），用户可以随时切换：
 * <ul>
 *   <li>preference_template：模板本体，is_active 标记当前启用的那一套</li>
 *   <li>preference_item：模板下的一个个标签明细</li>
 *   <li>user_preference：Agent 对话实际读取的"当前生效画像"</li>
 * </ul>
 * 关键点：启用模板时，会把该模板下的标签 <b>upsert</b> 进 user_preference，
 * 这样对话立刻就能用上新画像；但只覆盖模板里有的标签，不动别处写入的其它标签。
 * 所有接口都以 session 里的登录用户为准，别人的模板查不到也改不了。
 */
@RestController
public class PreferenceTemplateController {

    private static final Logger log = LoggerFactory.getLogger(PreferenceTemplateController.class);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 字段上限，与建表语句保持一致，防止超长写入报错 */
    private static final int MAX_NAME = 60;
    private static final int MAX_TAG = 64;
    private static final int MAX_VALUE = 500;

    // ==================== 模板 ====================

    /**
     * 列出当前用户的全部偏好模板（含各自的标签明细），按 id 升序
     */
    @PostMapping(value = "/preference/template/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> list(HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用偏好档案");
        }

        List<Map<String, Object>> templates = new ArrayList<>();
        String sql = "select id, name, is_active, create_time from preference_template"
                + " where username = ? order by id";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", id);
                    m.put("name", rs.getString("name"));
                    m.put("isActive", rs.getInt("is_active") == 1);
                    m.put("createTime", formatTime(rs.getTimestamp("create_time")));
                    m.put("items", listItems(username, id));
                    templates.add(m);
                }
            }
        } catch (SQLException e) {
            log.error("查询偏好模板列表失败", e);
            return fail(result, "偏好档案读取失败，请稍后重试");
        }

        result.put("status", "success");
        result.put("templates", templates);
        return result;
    }

    /**
     * 新增或重命名一套偏好模板。
     * 若是该用户的第一套模板，自动设为启用。
     */
    @PostMapping(value = "/preference/template/save", produces = "application/json;charset=UTF-8")
    public Map<String, Object> save(@RequestParam(defaultValue = "0") int id,
                                    @RequestParam(required = false) String name,
                                    HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用偏好档案");
        }
        if (name == null || name.trim().isEmpty()) {
            return fail(result, "模板名不能为空");
        }
        String safeName = trim(name, MAX_NAME);

        try {
            if (id > 0) {
                if (ownTemplate(username, id) == null) {
                    return fail(result, "模板不存在");
                }
                MySQLUtil.executeUpdate(
                        "update preference_template set name = ? where id = ? and username = ?",
                        safeName, id, username);
                result.put("status", "success");
                result.put("id", id);
                result.put("message", "已保存模板");
                return result;
            }

            boolean first = countTemplates(username) == 0;
            int newId;
            try (Connection conn = MySQLUtil.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "insert into preference_template(username, name, is_active, create_time)"
                                 + " values(?, ?, 0, now())",
                         PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, username);
                ps.setString(2, safeName);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    newId = keys.next() ? keys.getInt(1) : 0;
                }
            }
            if (first && newId > 0) {
                MySQLUtil.executeUpdate(
                        "update preference_template set is_active = 1 where id = ? and username = ?",
                        newId, username);
            }
            result.put("status", "success");
            result.put("id", newId);
            result.put("message", "已保存模板");
        } catch (SQLException e) {
            log.error("保存偏好模板失败", e);
            return fail(result, "模板保存失败，请稍后重试");
        }
        return result;
    }

    /**
     * 复制一套模板（连同它下面的全部标签），新模板名字后面加" 副本"
     */
    @PostMapping(value = "/preference/template/copy", produces = "application/json;charset=UTF-8")
    public Map<String, Object> copy(@RequestParam(defaultValue = "0") int id,
                                    HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用偏好档案");
        }
        Map<String, Object> source = ownTemplate(username, id);
        if (source == null) {
            return fail(result, "模板不存在");
        }
        String copyName = trim(String.valueOf(source.get("name")) + " 副本", MAX_NAME);

        try {
            int newId;
            try (Connection conn = MySQLUtil.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "insert into preference_template(username, name, is_active, create_time)"
                                 + " values(?, ?, 0, now())",
                         PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, username);
                ps.setString(2, copyName);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    newId = keys.next() ? keys.getInt(1) : 0;
                }
            }
            for (Map<String, Object> item : listItems(username, id)) {
                MySQLUtil.executeUpdate(
                        "insert into preference_item(template_id, username, tag, `value`) values(?, ?, ?, ?)",
                        newId, username, item.get("tag"), item.get("value"));
            }
            result.put("status", "success");
            result.put("id", newId);
            result.put("message", "已复制模板");
        } catch (SQLException e) {
            log.error("复制偏好模板失败", e);
            return fail(result, "模板复制失败，请稍后重试");
        }
        return result;
    }

    /**
     * 删除一套模板及其全部标签。
     * 若删掉的正是当前启用模板，则把剩余最新的一套设为启用。
     */
    @PostMapping(value = "/preference/template/delete", produces = "application/json;charset=UTF-8")
    public Map<String, Object> delete(@RequestParam(defaultValue = "0") int id,
                                      HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用偏好档案");
        }
        Map<String, Object> target = ownTemplate(username, id);
        if (target == null) {
            return fail(result, "模板不存在或已被删除");
        }
        boolean wasActive = Boolean.TRUE.equals(target.get("isActive"));

        try {
            MySQLUtil.executeUpdate(
                    "delete from preference_item where template_id = ? and username = ?", id, username);
            MySQLUtil.executeUpdate(
                    "delete from preference_template where id = ? and username = ?", id, username);

            if (wasActive) {
                int fallbackId = latestTemplateId(username);
                if (fallbackId > 0) {
                    MySQLUtil.executeUpdate(
                            "update preference_template set is_active = 1 where id = ? and username = ?",
                            fallbackId, username);
                }
            }
            result.put("status", "success");
            result.put("message", "已删除模板");
        } catch (Exception e) {
            log.error("删除偏好模板失败", e);
            return fail(result, "模板删除失败，请稍后重试");
        }
        return result;
    }

    /**
     * 启用一套模板：切换 is_active，并把模板内的标签同步进 user_preference。
     * <p>
     * 同步采用 upsert（on duplicate key update），只覆盖模板里有的标签，
     * user_preference 中其它已有的标签（如别处写入的预算告警阈值等）原样保留。
     */
    @PostMapping(value = "/preference/template/activate", produces = "application/json;charset=UTF-8")
    public Map<String, Object> activate(@RequestParam(defaultValue = "0") int id,
                                        HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用偏好档案");
        }
        Map<String, Object> target = ownTemplate(username, id);
        if (target == null) {
            return fail(result, "模板不存在");
        }

        try {
            MySQLUtil.executeUpdate(
                    "update preference_template set is_active = 0 where username = ?", username);
            MySQLUtil.executeUpdate(
                    "update preference_template set is_active = 1 where id = ? and username = ?",
                    id, username);

            for (Map<String, Object> item : listItems(username, id)) {
                syncToUserPreference(username, (String) item.get("tag"), (String) item.get("value"));
            }
            result.put("status", "success");
            result.put("message", "已启用模板：" + target.get("name") + "（对话已生效）");
        } catch (Exception e) {
            log.error("启用偏好模板失败", e);
            return fail(result, "模板启用失败，请稍后重试");
        }
        return result;
    }

    // ==================== 模板下的标签明细 ====================

    /**
     * 新增或修改模板下的一个标签。
     * 若该模板当前处于启用状态，同步把这条标签 upsert 进 user_preference。
     */
    @PostMapping(value = "/preference/item/save", produces = "application/json;charset=UTF-8")
    public Map<String, Object> saveItem(@RequestParam(defaultValue = "0") int id,
                                        @RequestParam(defaultValue = "0") int templateId,
                                        @RequestParam(required = false) String tag,
                                        @RequestParam(required = false) String value,
                                        HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用偏好档案");
        }
        if (tag == null || tag.trim().isEmpty()) {
            return fail(result, "标签名不能为空");
        }
        Map<String, Object> template = ownTemplate(username, templateId);
        if (template == null) {
            return fail(result, "模板不存在");
        }
        String safeTag = trim(tag, MAX_TAG);
        String safeValue = trim(value, MAX_VALUE);

        try {
            int targetId = id;
            if (id > 0) {
                if (itemTemplateId(username, id) == null) {
                    return fail(result, "偏好项不存在");
                }
                MySQLUtil.executeUpdate(
                        "update preference_item set tag = ?, `value` = ? where id = ? and username = ?",
                        safeTag, safeValue, id, username);
            } else {
                try (Connection conn = MySQLUtil.getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "insert into preference_item(template_id, username, tag, `value`) values(?, ?, ?, ?)",
                             PreparedStatement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, templateId);
                    ps.setString(2, username);
                    ps.setString(3, safeTag);
                    ps.setString(4, safeValue);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        targetId = keys.next() ? keys.getInt(1) : 0;
                    }
                }
            }

            if (Boolean.TRUE.equals(template.get("isActive"))) {
                syncToUserPreference(username, safeTag, safeValue);
            }
            result.put("status", "success");
            result.put("id", targetId);
            result.put("message", "已保存标签");
        } catch (SQLException e) {
            log.error("保存偏好标签失败", e);
            return fail(result, "标签保存失败，请稍后重试");
        }
        return result;
    }

    /**
     * 删除模板下的一个标签（先校验它所属模板归属当前用户）
     */
    @PostMapping(value = "/preference/item/delete", produces = "application/json;charset=UTF-8")
    public Map<String, Object> deleteItem(@RequestParam(defaultValue = "0") int id,
                                          HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用偏好档案");
        }
        Integer templateId = itemTemplateId(username, id);
        if (templateId == null) {
            return fail(result, "偏好项不存在");
        }
        if (ownTemplate(username, templateId) == null) {
            return fail(result, "模板不存在");
        }

        MySQLUtil.executeUpdate(
                "delete from preference_item where id = ? and username = ?", id, username);
        result.put("status", "success");
        result.put("message", "已删除标签");
        return result;
    }

    // ==================== 内部工具方法 ====================

    /** 读取模板归属与启用状态；不属于当前用户则返回 null */
    private Map<String, Object> ownTemplate(String username, int id) {
        String sql = "select name, is_active from preference_template where id = ? and username = ?";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("name"));
                    m.put("isActive", rs.getInt("is_active") == 1);
                    return m;
                }
            }
        } catch (SQLException e) {
            log.warn("读取偏好模板失败：{}", e.getMessage());
        }
        return null;
    }

    /** 列出某模板下的标签；模板归属由调用方先校验，这里再按 username 兜底 */
    private List<Map<String, Object>> listItems(String username, int templateId) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "select id, tag, `value` from preference_item"
                + " where template_id = ? and username = ? order by id";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, templateId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("tag", rs.getString("tag"));
                    m.put("value", rs.getString("value"));
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            log.warn("读取偏好标签失败：{}", e.getMessage());
        }
        return list;
    }

    /** 查某条标签所属模板 id；不属于当前用户则返回 null */
    private Integer itemTemplateId(String username, int itemId) {
        String sql = "select template_id from preference_item where id = ? and username = ?";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, itemId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("template_id");
                }
            }
        } catch (SQLException e) {
            log.warn("读取偏好标签失败：{}", e.getMessage());
        }
        return null;
    }

    /** 该用户现有的模板数量 */
    private int countTemplates(String username) {
        String sql = "select count(*) from preference_template where username = ?";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            log.warn("统计偏好模板失败：{}", e.getMessage());
        }
        return 0;
    }

    /** 该用户剩余模板中 id 最大的那个（即最新一套），没有返回 0 */
    private int latestTemplateId(String username) {
        String sql = "select id from preference_template where username = ? order by id desc limit 1";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("id");
                }
            }
        } catch (SQLException e) {
            log.warn("读取最新偏好模板失败：{}", e.getMessage());
        }
        return 0;
    }

    /**
     * 把一条标签 upsert 进 user_preference（Agent 对话实际读取的当前画像）。
     * 利用唯一键 uk_user_tag(username, tag)，已存在则更新取值与时间，不动其它标签。
     */
    private void syncToUserPreference(String username, String tag, String value) {
        MySQLUtil.executeUpdate(
                "insert into user_preference(username, tag, `value`, update_time) values(?, ?, ?, now())"
                        + " on duplicate key update `value` = ?, update_time = now()",
                username, tag, value, value);
    }

    private String formatTime(java.sql.Timestamp ts) {
        return ts == null ? "" : ts.toLocalDateTime().format(TIME_FORMAT);
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

    // ==================== 当前生效画像（user_preference） ====================
    //
    // 说明：模板（template/item）描述的是"可以启用哪些画像"，
    // 而 Agent 对话实际读取的是 user_preference 里的"当前生效画像"。
    // 此前只有写入口（item/save、template/activate）却没有读接口，
    // 前端因此无法展示用户画像，用户看不到 AI 到底记住了什么。

    /**
     * 列出当前生效画像的全部标签（Agent 对话实际读取的那份）。
     */
    @PostMapping(value = "/preference/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> listCurrent(HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用偏好档案");
        }

        List<Map<String, Object>> items = new ArrayList<>();
        String sql = "select tag, `value`, update_time from user_preference"
                + " where username = ? order by update_time desc";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tag", rs.getString("tag"));
                    m.put("value", rs.getString("value"));
                    m.put("updateTime", formatTime(rs.getTimestamp("update_time")));
                    items.add(m);
                }
            }
        } catch (SQLException e) {
            log.error("查询当前画像失败", e);
            return fail(result, "画像读取失败，请稍后重试");
        }

        result.put("status", "success");
        result.put("items", items);
        return result;
    }

    /**
     * 直接写入 / 更新一条当前生效画像（与 Agent 的 savePreference 是同一张表、同一套 upsert）。
     * 用于用户在「我的偏好」面板里手动改一条。
     */
    @PostMapping(value = "/preference/item/save-current", produces = "application/json;charset=UTF-8")
    public Map<String, Object> saveCurrent(@RequestParam(required = false) String tag,
                                           @RequestParam(required = false) String value,
                                           HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用偏好档案");
        }
        String t = trim(tag, MAX_TAG);
        if (t == null || t.isEmpty()) {
            return fail(result, "标签不能为空");
        }
        String v = trim(value, MAX_VALUE);
        try {
            syncToUserPreference(username, t, v == null ? "" : v);
        } catch (Exception e) {
            log.error("写入当前画像失败", e);
            return fail(result, "画像保存失败，请稍后重试");
        }
        result.put("status", "success");
        result.put("tag", t);
        result.put("value", v == null ? "" : v);
        return result;
    }

    /**
     * 删除一条当前生效画像。用户在面板里点删除时调用。
     */
    @PostMapping(value = "/preference/item/delete-current", produces = "application/json;charset=UTF-8")
    public Map<String, Object> deleteCurrent(@RequestParam(required = false) String tag,
                                             HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用偏好档案");
        }
        String t = trim(tag, MAX_TAG);
        if (t == null || t.isEmpty()) {
            return fail(result, "标签不能为空");
        }
        try {
            // where 同时限定 username：即使前端伪造 tag，也删不到别人的画像
            MySQLUtil.executeUpdate(
                    "delete from user_preference where username = ? and tag = ?", username, t);
        } catch (Exception e) {
            log.error("删除当前画像失败", e);
            return fail(result, "画像删除失败，请稍后重试");
        }
        result.put("status", "success");
        result.put("tag", t);
        return result;
    }
}
