package com.iflytek.chatgpt.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import util.MySQLUtil;

import javax.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * 工具箱资产：把旅途中容易丢、容易忘的东西统一存下来。
 * <p>
 * 一共三块：
 * <ul>
 *   <li>订单 &amp; 证件存档：车票、酒店、预约码、电子合同、证件、应急记录等</li>
 *   <li>行李清单模板库：出发前照着勾，回来后可按模板复用</li>
 *   <li>预算账本：按行程记流水，支持分类汇总与超支告警</li>
 * </ul>
 * 所有接口都以 session 里的登录用户为准，SQL 一律带 {@code and username = ?}，
 * 别人的存档查不到也删不掉；预算告警阈值复用 user_preference 表按用户存储。
 */
@RestController
public class ToolboxController {

    private static final Logger log = LoggerFactory.getLogger(ToolboxController.class);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 存档类型白名单，非法值统一回退为 tool */
    private static final List<String> DOC_TYPES = Arrays.asList(
            "ticket", "hotel", "booking", "contract", "idcard", "emergency", "tool");

    /** 预算分类白名单，非法值统一回退为 other */
    private static final List<String> BUDGET_CATEGORIES = Arrays.asList(
            "transport", "lodging", "food", "ticket", "other");

    /** 预算告警阈值在 user_preference 里对应的 tag */
    private static final String THRESHOLD_TAG = "预算告警阈值";

    // ==================== A. 订单 & 证件存档 ====================

    /**
     * 存档列表：不传 docType 就返回当前用户的全部存档，最新的排前面。
     */
    @PostMapping(value = "/toolbox/document/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> listDocuments(@RequestParam(required = false) String docType,
                                             HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用工具箱");
        }

        boolean filter = docType != null && !docType.trim().isEmpty();
        List<Map<String, Object>> documents = new ArrayList<>();
        String sql = "select id, doc_type, title, doc_no, detail, expire_time, create_time"
                + " from toolbox_document where username = ?"
                + (filter ? " and doc_type = ?" : "") + " order by id desc";

        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            if (filter) {
                ps.setString(2, normalizeDocType(docType));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("docType", rs.getString("doc_type"));
                    m.put("title", rs.getString("title"));
                    m.put("docNo", rs.getString("doc_no"));
                    m.put("detail", rs.getString("detail"));
                    m.put("expireTime", rs.getString("expire_time"));
                    m.put("createTime", formatTime(rs.getTimestamp("create_time")));
                    documents.add(m);
                }
            }
        } catch (SQLException e) {
            log.error("查询存档列表失败", e);
            return fail(result, "存档读取失败，请稍后重试");
        }

        result.put("status", "success");
        result.put("documents", documents);
        return result;
    }

    /**
     * 新增或修改一条存档：id &gt; 0 时校验归属后更新，否则插入。
     */
    @PostMapping(value = "/toolbox/document/save", produces = "application/json;charset=UTF-8")
    public Map<String, Object> saveDocument(@RequestParam(defaultValue = "0") int id,
                                            @RequestParam(required = false) String docType,
                                            @RequestParam(required = false) String title,
                                            @RequestParam(required = false) String docNo,
                                            @RequestParam(required = false) String detail,
                                            @RequestParam(required = false) String expireTime,
                                            HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用工具箱");
        }
        if (title == null || title.trim().isEmpty()) {
            return fail(result, "标题不能为空");
        }

        String safeType = normalizeDocType(docType);
        String safeTitle = trim(title, 120);
        String safeNo = trim(docNo, 120);
        String safeDetail = trim(detail, 500);
        String safeExpire = trim(expireTime, 40);

        if (id > 0) {
            int rows = MySQLUtil.executeUpdate(
                    "update toolbox_document set doc_type = ?, title = ?, doc_no = ?, detail = ?,"
                            + " expire_time = ? where id = ? and username = ?",
                    safeType, safeTitle, safeNo, safeDetail, safeExpire, id, username);
            if (rows <= 0) {
                return fail(result, "存档不存在");
            }
        } else {
            try (Connection conn = MySQLUtil.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "insert into toolbox_document(username, doc_type, title, doc_no, detail, expire_time, create_time)"
                                 + " values(?, ?, ?, ?, ?, ?, now())",
                         PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, username);
                ps.setString(2, safeType);
                ps.setString(3, safeTitle);
                ps.setString(4, safeNo);
                ps.setString(5, safeDetail);
                ps.setString(6, safeExpire);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    id = keys.next() ? keys.getInt(1) : 0;
                }
            } catch (SQLException e) {
                log.error("保存存档失败", e);
                return fail(result, "存档保存失败，请稍后重试");
            }
        }

        result.put("status", "success");
        result.put("id", id);
        result.put("message", "存档已保存");
        return result;
    }

    /**
     * 删除一条存档（仅能删自己的）
     */
    @PostMapping(value = "/toolbox/document/delete", produces = "application/json;charset=UTF-8")
    public Map<String, Object> deleteDocument(@RequestParam(defaultValue = "0") int id,
                                              HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用工具箱");
        }
        int rows = MySQLUtil.executeUpdate(
                "delete from toolbox_document where id = ? and username = ?", id, username);
        if (rows <= 0) {
            return fail(result, "存档不存在或已被删除");
        }
        result.put("status", "success");
        result.put("message", "已删除");
        return result;
    }

    // ==================== B. 行李清单模板库 ====================

    /**
     * 模板列表：items 为纯文本，多行用 \n 分隔，最新的排前面。
     */
    @PostMapping(value = "/toolbox/packing/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> listPacking(HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用工具箱");
        }

        List<Map<String, Object>> templates = new ArrayList<>();
        String sql = "select id, name, items, create_time from packing_template"
                + " where username = ? order by id desc";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("name", rs.getString("name"));
                    m.put("items", rs.getString("items"));
                    m.put("createTime", formatTime(rs.getTimestamp("create_time")));
                    templates.add(m);
                }
            }
        } catch (SQLException e) {
            log.error("查询行李模板列表失败", e);
            return fail(result, "模板读取失败，请稍后重试");
        }

        result.put("status", "success");
        result.put("templates", templates);
        return result;
    }

    /**
     * 新增或修改一个行李模板：id &gt; 0 时校验归属后更新，否则插入。
     */
    @PostMapping(value = "/toolbox/packing/save", produces = "application/json;charset=UTF-8")
    public Map<String, Object> savePacking(@RequestParam(defaultValue = "0") int id,
                                           @RequestParam(required = false) String name,
                                           @RequestParam(required = false) String items,
                                           HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用工具箱");
        }
        if (name == null || name.trim().isEmpty()) {
            return fail(result, "模板名称不能为空");
        }

        String safeName = trim(name, 60);

        if (id > 0) {
            int rows = MySQLUtil.executeUpdate(
                    "update packing_template set name = ?, items = ? where id = ? and username = ?",
                    safeName, items, id, username);
            if (rows <= 0) {
                return fail(result, "模板不存在");
            }
        } else {
            try (Connection conn = MySQLUtil.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "insert into packing_template(username, name, items, create_time)"
                                 + " values(?, ?, ?, now())",
                         PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, username);
                ps.setString(2, safeName);
                ps.setString(3, items);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    id = keys.next() ? keys.getInt(1) : 0;
                }
            } catch (SQLException e) {
                log.error("保存行李模板失败", e);
                return fail(result, "模板保存失败，请稍后重试");
            }
        }

        result.put("status", "success");
        result.put("id", id);
        result.put("message", "模板已保存");
        return result;
    }

    /**
     * 删除一个行李模板（仅能删自己的）
     */
    @PostMapping(value = "/toolbox/packing/delete", produces = "application/json;charset=UTF-8")
    public Map<String, Object> deletePacking(@RequestParam(defaultValue = "0") int id,
                                             HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用工具箱");
        }
        int rows = MySQLUtil.executeUpdate(
                "delete from packing_template where id = ? and username = ?", id, username);
        if (rows <= 0) {
            return fail(result, "模板不存在或已被删除");
        }
        result.put("status", "success");
        result.put("message", "已删除");
        return result;
    }

    // ==================== C. 预算账本 ====================

    /**
     * 预算流水列表：tripId 为 -1 时不按行程过滤，最新的排前面。
     */
    @PostMapping(value = "/toolbox/budget/list", produces = "application/json;charset=UTF-8")
    public Map<String, Object> listBudget(@RequestParam(defaultValue = "-1") int tripId,
                                          HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用工具箱");
        }

        boolean filter = tripId >= 0;
        List<Map<String, Object>> entries = new ArrayList<>();
        String sql = "select id, trip_id, category, item, amount, entry_date, create_time"
                + " from budget_entry where username = ?"
                + (filter ? " and trip_id = ?" : "") + " order by id desc";

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
                    m.put("category", rs.getString("category"));
                    m.put("item", rs.getString("item"));
                    m.put("amount", rs.getBigDecimal("amount"));
                    m.put("entryDate", rs.getString("entry_date"));
                    m.put("createTime", formatTime(rs.getTimestamp("create_time")));
                    entries.add(m);
                }
            }
        } catch (SQLException e) {
            log.error("查询预算流水失败", e);
            return fail(result, "预算读取失败，请稍后重试");
        }

        result.put("status", "success");
        result.put("entries", entries);
        return result;
    }

    /**
     * 新增或修改一条预算流水：id &gt; 0 时校验归属后更新，否则插入。
     * tripId 传 0 视为不关联行程（存 null）。
     */
    @PostMapping(value = "/toolbox/budget/save", produces = "application/json;charset=UTF-8")
    public Map<String, Object> saveBudget(@RequestParam(defaultValue = "0") int id,
                                          @RequestParam(defaultValue = "0") int tripId,
                                          @RequestParam(required = false) String category,
                                          @RequestParam(required = false) String item,
                                          @RequestParam(defaultValue = "0") Double amount,
                                          @RequestParam(required = false) String entryDate,
                                          HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用工具箱");
        }

        Object tripRef = tripId > 0 ? tripId : null;
        String safeCategory = normalizeCategory(category);
        String safeItem = trim(item, 120);
        BigDecimal safeAmount = amount == null
                ? BigDecimal.ZERO : BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
        String safeDate = trim(entryDate, 30);

        if (id > 0) {
            int rows = MySQLUtil.executeUpdate(
                    "update budget_entry set trip_id = ?, category = ?, item = ?, amount = ?, entry_date = ?"
                            + " where id = ? and username = ?",
                    tripRef, safeCategory, safeItem, safeAmount, safeDate, id, username);
            if (rows <= 0) {
                return fail(result, "流水不存在");
            }
        } else {
            try (Connection conn = MySQLUtil.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "insert into budget_entry(username, trip_id, category, item, amount, entry_date, create_time)"
                                 + " values(?, ?, ?, ?, ?, ?, now())",
                         PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, username);
                ps.setObject(2, tripRef);
                ps.setString(3, safeCategory);
                ps.setString(4, safeItem);
                ps.setBigDecimal(5, safeAmount);
                ps.setString(6, safeDate);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    id = keys.next() ? keys.getInt(1) : 0;
                }
            } catch (SQLException e) {
                log.error("保存预算流水失败", e);
                return fail(result, "预算保存失败，请稍后重试");
            }
        }

        result.put("status", "success");
        result.put("id", id);
        result.put("message", "流水已保存");
        return result;
    }

    /**
     * 删除一条预算流水（仅能删自己的）
     */
    @PostMapping(value = "/toolbox/budget/delete", produces = "application/json;charset=UTF-8")
    public Map<String, Object> deleteBudget(@RequestParam(defaultValue = "0") int id,
                                            HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用工具箱");
        }
        int rows = MySQLUtil.executeUpdate(
                "delete from budget_entry where id = ? and username = ?", id, username);
        if (rows <= 0) {
            return fail(result, "流水不存在或已被删除");
        }
        result.put("status", "success");
        result.put("message", "已删除");
        return result;
    }

    /**
     * 预算汇总：统计当前用户（可选按行程过滤）的总金额与各分类合计，
     * 并带上 user_preference 中配置的告警阈值，判断是否超支。
     * <p>
     * 阈值未设置时返回 0 且 overAlert 恒为 false；金额统一保留两位小数。
     */
    @PostMapping(value = "/toolbox/budget/summary", produces = "application/json;charset=UTF-8")
    public Map<String, Object> budgetSummary(@RequestParam(defaultValue = "-1") int tripId,
                                             HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用工具箱");
        }

        boolean filter = tripId >= 0;
        BigDecimal total = BigDecimal.ZERO;
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        for (String c : BUDGET_CATEGORIES) {
            byCategory.put(c, BigDecimal.ZERO);
        }

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
                    String category = normalizeCategory(rs.getString("category"));
                    BigDecimal amount = rs.getBigDecimal("amount");
                    if (amount == null) {
                        amount = BigDecimal.ZERO;
                    }
                    total = total.add(amount);
                    byCategory.put(category, byCategory.get(category).add(amount));
                }
            }
        } catch (SQLException e) {
            log.error("预算汇总失败", e);
            return fail(result, "预算汇总失败，请稍后重试");
        }

        Map<String, Object> categoryView = new LinkedHashMap<>();
        for (String c : BUDGET_CATEGORIES) {
            categoryView.put(c, byCategory.get(c).setScale(2, RoundingMode.HALF_UP));
        }
        BigDecimal safeTotal = total.setScale(2, RoundingMode.HALF_UP);

        BigDecimal threshold = readThreshold(username);
        boolean overAlert = threshold.compareTo(BigDecimal.ZERO) > 0 && safeTotal.compareTo(threshold) > 0;

        result.put("status", "success");
        result.put("total", safeTotal);
        result.put("byCategory", categoryView);
        result.put("threshold", threshold.setScale(2, RoundingMode.HALF_UP));
        result.put("overAlert", overAlert);
        return result;
    }

    /**
     * 设置预算告警阈值：写进 user_preference，按 (username, tag) 覆盖更新。
     */
    @PostMapping(value = "/toolbox/budget/threshold", produces = "application/json;charset=UTF-8")
    public Map<String, Object> setBudgetThreshold(@RequestParam(defaultValue = "0") Double amount,
                                                  HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) {
            return fail(result, "请先登录后再使用工具箱");
        }
        BigDecimal safeAmount = amount == null
                ? BigDecimal.ZERO : BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);

        MySQLUtil.executeUpdate(
                "insert into user_preference(username, tag, value, update_time) values(?, ?, ?, now())"
                        + " on duplicate key update value = values(value), update_time = now()",
                username, THRESHOLD_TAG, safeAmount.toPlainString());

        result.put("status", "success");
        result.put("message", "已设置预算告警阈值");
        return result;
    }

    // ==================== 内部工具方法 ====================

    /** 读取当前用户的预算告警阈值，没有则返回 0 */
    private BigDecimal readThreshold(String username) {
        String sql = "select value from user_preference where username = ? and tag = ?";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, THRESHOLD_TAG);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String value = rs.getString("value");
                    if (value != null && !value.trim().isEmpty()) {
                        return new BigDecimal(value.trim());
                    }
                }
            }
        } catch (SQLException | NumberFormatException e) {
            log.warn("读取预算告警阈值失败：{}", e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    private String normalizeDocType(String docType) {
        String s = docType == null ? "" : docType.trim();
        return DOC_TYPES.contains(s) ? s : "tool";
    }

    private String normalizeCategory(String category) {
        String s = category == null ? "" : category.trim();
        return BUDGET_CATEGORIES.contains(s) ? s : "other";
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
}
