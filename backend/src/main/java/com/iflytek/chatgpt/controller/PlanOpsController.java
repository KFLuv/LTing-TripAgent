package com.iflytek.chatgpt.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LTing 2.0 PlanOps 第一阶段。
 *
 * <p>点击和 AI 都只能先生成 Operation Preview，再凭 approvalToken 应用。
 * 第一阶段覆盖草稿行程的节点移动、删除、替换、天内排序和锁定。
 */
@RestController
public class PlanOpsController {

    private static final Logger log = LoggerFactory.getLogger(PlanOpsController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int PREVIEW_TTL_MINUTES = 10;
    private static final List<String> SLOTS = Arrays.asList("morning", "afternoon", "evening");
    private static final List<String> TYPES = Arrays.asList(
            "MOVE_NODE", "REMOVE_NODE", "REPLACE_NODE", "REORDER_NODE", "LOCK_NODE",
            "TOGGLE_NODE_CLOSURE",
            "ADD_NODE", "ADD_CANDIDATE", "REMOVE_CANDIDATE",
            "UPDATE_CONSTRAINT", "UPDATE_BUDGET", "UPDATE_STAY",
            "UPDATE_PLAN_META", "UPDATE_TRANSPORT", "UPDATE_BUDGET_PLAN",
            "REBUILD_ITINERARY", "UPDATE_ROUTE_TIMES",
            "ROLLBACK_PLAN_VERSION");

    private final PlanPoolController validator;
    private final PlanDayOptimizer dayOptimizer;
    private final PlanWeatherService weatherService;

    public PlanOpsController(PlanPoolController validator, PlanDayOptimizer dayOptimizer,
                             PlanWeatherService weatherService) {
        this.validator = validator;
        this.dayOptimizer = dayOptimizer;
        this.weatherService = weatherService;
    }

    /**
     * 创建并保存当前页面上下文。AI 和点击操作后续都只引用 contextId。
     */
    @PostMapping(value = "/plan/context/get", produces = "application/json;charset=UTF-8")
    public Map<String, Object> context(@RequestParam int tripId,
                                       @RequestParam(defaultValue = "plan") String page,
                                       @RequestParam(defaultValue = "0") int selectedDay,
                                       @RequestParam(required = false) String selectedSlot,
                                       @RequestParam(required = false) String selectedNodeKey,
                                       @RequestParam(required = false) String selectedPoiId,
                                       HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        return contextForUser(username, tripId, page, selectedDay, selectedSlot, selectedNodeKey, selectedPoiId);
    }

    public Map<String, Object> contextForUser(String username, int tripId, String page,
                                              int selectedDay, String selectedSlot,
                                              String selectedNodeKey, String selectedPoiId) {
        Map<String, Object> out = new LinkedHashMap<>();
        TripState trip = loadTrip(username, tripId);
        if (trip == null) return fail(out, "规划不存在或无权访问");

        String contextId = "ctx_" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime expires = LocalDateTime.now().plusMinutes(15);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("contextId", contextId);
        context.put("tripId", tripId);
        context.put("page", page);
        context.put("accessRole", username.equals(trip.owner) ? "owner" : "collaborator");
        context.put("selectedDay", selectedDay);
        context.put("selectedSlot", selectedSlot);
        context.put("selectedNodeKey", selectedNodeKey);
        context.put("selectedPoiId", selectedPoiId);
        context.put("planRevision", trip.planRevision);
        context.put("destination", trip.destination);
        context.put("days", trip.days);
        context.put("budget", trip.budget);
        context.put("people", trip.people);
        context.put("pace", trip.pace);
        try {
            ObjectNode planRoot = parsePlanData(trip.planData);
            context.put("itinerary", planRoot.path("itinerary"));
            context.put("chosen", planRoot.path("chosen"));
            context.put("locked", planRoot.path("locked"));
            context.put("stay", planRoot.path("stay"));
            context.put("hotelName", planRoot.path("hotelName").asText(""));
            context.put("quickPrefs", planRoot.path("quickPrefs"));
            context.put("constraints", planRoot.path("constraints"));
            context.put("meta", planRoot.path("meta"));
            context.put("transport", planRoot.path("transport"));
            context.put("budgetPlan", planRoot.path("budgetPlan"));
            context.put("closures", planRoot.path("closures"));
            context.put("nodes", itineraryNodes(planRoot.path("itinerary")));
        } catch (Exception e) {
            log.warn("读取 Context 草稿节点失败：{}", e.getMessage());
            context.put("itinerary", JSON.createArrayNode());
            context.put("chosen", JSON.createArrayNode());
            context.put("locked", JSON.createObjectNode());
            context.put("stay", JSON.nullNode());
            context.put("hotelName", "");
            context.put("quickPrefs", JSON.createArrayNode());
            context.put("constraints", JSON.createObjectNode());
            context.put("meta", JSON.createObjectNode());
            context.put("transport", JSON.createObjectNode());
            context.put("budgetPlan", JSON.createObjectNode());
            context.put("closures", JSON.createObjectNode());
            context.put("nodes", JSON.createArrayNode());
        }

        try {
            MySQLUtil.executeUpdate(
                    "insert into plan_context(id, username, trip_id, page, selected_day, selected_slot,"
                            + " selected_node_key, context_json, plan_revision, expires_at, create_time)"
                            + " values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())",
                    contextId, username, tripId, trim(page, 32), Math.max(0, selectedDay),
                    trim(selectedSlot, 20), trim(selectedNodeKey, 255), JSON.writeValueAsString(context),
                    trip.planRevision, Timestamp.valueOf(expires));
            out.put("status", "success");
            out.put("context", context);
            out.put("expiresAt", format(expires));
            return out;
        } catch (Exception e) {
            log.error("创建 PlanContext 失败", e);
            return fail(out, "规划上下文创建失败");
        }
    }

    /**
     * 只计算操作结果和规则校验，不写 trip.plan_data。
     */
    @PostMapping(value = "/plan/ops/preview", produces = "application/json;charset=UTF-8")
    public Map<String, Object> preview(@RequestParam String contextId,
                                       @RequestParam String operation,
                                       @RequestParam(defaultValue = "click") String source,
                                       HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        return previewForUser(username, contextId, operation, source);
    }

    public Map<String, Object> previewForUser(String username, String contextId,
                                              String operation, String source) {
        Map<String, Object> out = new LinkedHashMap<>();
        ContextState context = loadContext(username, contextId);
        if (context == null) return fail(out, "规划上下文不存在");
        if (context.expiresAt.isBefore(LocalDateTime.now())) {
            out.put("status", "error");
            out.put("code", "CONTEXT_EXPIRED");
            out.put("message", "当前页面上下文已过期，请刷新后重试");
            return out;
        }
        TripState trip = loadTrip(username, context.tripId);
        if (trip == null) return fail(out, "规划不存在或无权访问");
        if (trip.planRevision != context.planRevision) {
            return conflict(out, "VERSION_CONFLICT", "规划已在其他页面更新，请刷新后再操作", trip.planRevision);
        }

        try {
            ObjectNode operationNode = (ObjectNode) JSON.readTree(operation);
            String type = operationNode.path("type").asText("").trim().toUpperCase();
            if (!TYPES.contains(type)) return fail(out, "不支持的规划操作：" + type);
            int expectedRevision = operationNode.path("expectedRevision").asInt(context.planRevision);
            if (expectedRevision != trip.planRevision) {
                return conflict(out, "VERSION_CONFLICT", "规划版本已变化，请刷新后再操作", trip.planRevision);
            }
            ObjectNode root;
            Map<String, Object> diff;
            if ("ROLLBACK_PLAN_VERSION".equals(type)) {
                int targetVersionId = operationNode.path("targetVersionId").asInt(0);
                if (targetVersionId <= 0) throw new IllegalArgumentException("缺少目标版本");
                VersionState target = loadVersion(trip.owner, context.tripId, targetVersionId);
                if (target == null) throw new IllegalArgumentException("目标版本不存在或无权访问");
                if (target.planData == null || target.planData.trim().isEmpty()) {
                    throw new IllegalArgumentException("该版本没有保存规划草稿快照，不能用于回退");
                }
                root = parsePlanData(target.planData);
                diff = new LinkedHashMap<>();
                diff.put("action", "ROLLBACK_PLAN_VERSION");
                diff.put("fromRevision", trip.planRevision);
                diff.put("toRevision", target.planRevision);
                diff.put("targetVersionId", target.versionId);
                diff.put("targetVersionNo", target.versionNo);
                diff.put("changeNote", "恢复到版本 v" + target.versionNo);
            } else {
                root = parsePlanData(trip.planData);
                diff = applyOperation(root, type, operationNode, context);
            }
            String afterPlanData = JSON.writeValueAsString(root);
            ConstraintState proposed = constraintsOf(root, trip);
            Map<String, Object> validation = validator.validateItinerary(
                    new LinkedHashMap<>(), root.path("itinerary").toString(),
                    proposed.budget, proposed.people,
                    preferencesOf(root, username), dayMinutesOf(proposed.pace));
            enrichValidation(root, validation, proposed.people);
            String risk = (("LOCK_NODE".equals(type) || "TOGGLE_NODE_CLOSURE".equals(type)) ? "L1"
                    : ("ADD_CANDIDATE".equals(type)
                    || "UPDATE_CONSTRAINT".equals(type) || "UPDATE_BUDGET".equals(type)
                    || "UPDATE_STAY".equals(type) || "UPDATE_TRANSPORT".equals(type)
                    || "UPDATE_BUDGET_PLAN".equals(type)) ? "L1"
                    : (("ROLLBACK_PLAN_VERSION".equals(type) || "REBUILD_ITINERARY".equals(type)
                        || "UPDATE_PLAN_META".equals(type)) ? "L3" : "L2"));
            boolean requiresApproval = true;
            String operationId = "op_" + UUID.randomUUID().toString().replace("-", "");
            String approvalToken = "apr_" + UUID.randomUUID().toString().replace("-", "");
            LocalDateTime expires = LocalDateTime.now().plusMinutes(PREVIEW_TTL_MINUTES);

            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("operationId", operationId);
            preview.put("contextId", contextId);
            preview.put("type", type);
            preview.put("source", source);
            preview.put("riskLevel", risk);
            preview.put("requiresApproval", requiresApproval);
            preview.put("expectedRevision", trip.planRevision);
            preview.put("diff", diff);
            preview.put("validation", validation);
            preview.put("approvalToken", approvalToken);
            preview.put("expiresAt", format(expires));

            MySQLUtil.executeUpdate(
                    "insert into plan_operation(id, context_id, username, trip_id, operation_type, risk_level,"
                            + " source, expected_revision, operation_json, after_plan_data, preview_json,"
                            + " approval_token, status, expires_at, create_time)"
                            + " values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'preview', ?, now())",
                    operationId, contextId, username, context.tripId, type, risk, trim(source, 16),
                    trip.planRevision, JSON.writeValueAsString(operationNode), afterPlanData,
                    JSON.writeValueAsString(preview), approvalToken, Timestamp.valueOf(expires));
            out.put("status", "success");
            out.putAll(preview);
            return out;
        } catch (IllegalArgumentException e) {
            return fail(out, e.getMessage());
        } catch (Exception e) {
            log.error("PlanOperation Preview 失败", e);
            return fail(out, "操作预览失败");
        }
    }

    /**
     * 用户确认后应用预览结果。使用 expectedRevision 和 approvalToken 双重校验。
     */
    @PostMapping(value = "/plan/ops/apply", produces = "application/json;charset=UTF-8")
    public Map<String, Object> apply(@RequestParam String operationId,
                                     @RequestParam String approvalToken,
                                     @RequestParam(defaultValue = "-1") int expectedRevision,
                                     HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        return applyForUser(username, operationId, approvalToken, expectedRevision);
    }

    public Map<String, Object> applyForUser(String username, String operationId,
                                            String approvalToken, int expectedRevision) {
        Map<String, Object> out = new LinkedHashMap<>();
        String select = "select o.trip_id, o.operation_type, o.operation_json, o.expected_revision,"
                + " o.after_plan_data, o.preview_json,"
                + " o.status, o.approval_token, o.expires_at, c.expires_at as context_expires_at"
                + " from plan_operation o left join plan_context c on c.id = o.context_id"
                + " where o.id = ? and o.username = ?";
        int tripId;
        int operationRevision;
        String operationType;
        String operationJson;
        String afterPlanData;
        String previewJson;
        Timestamp expiresAt;
        Timestamp contextExpiresAt;
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setString(1, operationId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return fail(out, "操作不存在");
                String status = rs.getString("status");
                if (!"preview".equals(status)) return fail(out, "该操作已经处理，不能重复应用");
                if (!approvalToken.equals(rs.getString("approval_token"))) {
                    return fail(out, "审批令牌不匹配");
                }
                expiresAt = rs.getTimestamp("expires_at");
                if (expiresAt == null || expiresAt.toLocalDateTime().isBefore(LocalDateTime.now())) {
                    return conflict(out, "APPROVAL_EXPIRED", "操作确认已过期，请重新预览", 0);
                }
                contextExpiresAt = rs.getTimestamp("context_expires_at");
                if (contextExpiresAt == null
                        || contextExpiresAt.toLocalDateTime().isBefore(LocalDateTime.now())) {
                    out.put("status", "error");
                    out.put("code", "CONTEXT_EXPIRED");
                    out.put("message", "当前页面上下文已过期，请刷新后重试");
                    return out;
                }
                tripId = rs.getInt("trip_id");
                operationType = rs.getString("operation_type");
                operationJson = rs.getString("operation_json");
                operationRevision = rs.getInt("expected_revision");
                afterPlanData = rs.getString("after_plan_data");
                previewJson = rs.getString("preview_json");
            }
        } catch (Exception e) {
            log.error("读取 PlanOperation 失败", e);
            return fail(out, "操作读取失败");
        }
        if (expectedRevision >= 0 && expectedRevision != operationRevision) {
            return conflict(out, "VERSION_CONFLICT", "操作版本与页面版本不一致", operationRevision);
        }

        try (Connection conn = MySQLUtil.getConnection()) {
            conn.setAutoCommit(false);
            try {
                TripState currentTrip = loadTripForUpdate(conn, username, tripId);
                if (currentTrip == null) {
                    conn.rollback();
                    return fail(out, "规划不存在或无权修改");
                }
                if (currentTrip.planRevision != operationRevision) {
                    conn.rollback();
                    return conflict(out, "VERSION_CONFLICT", "规划已被更新，请重新预览",
                            currentTrip.planRevision);
                }

                ObjectNode afterRoot = parsePlanData(afterPlanData);
                ObjectNode operationNode = parseJsonObject(operationJson);
                ConstraintState proposed = constraintsOf(afterRoot, currentTrip);
                MetaState meta = metaOf(afterRoot, currentTrip, operationNode);
                Map<String, Object> validation = validator.validateItinerary(
                        new LinkedHashMap<>(), afterRoot.path("itinerary").toString(),
                        proposed.budget, proposed.people,
                        preferencesOf(afterRoot, conn, username), dayMinutesOf(proposed.pace));
                enrichValidation(afterRoot, validation, proposed.people);
                if (!"success".equals(validation.get("status"))) {
                    conn.rollback();
                    markOperationFailed(operationId, username,
                            String.valueOf(validation.get("message")), "VALIDATION_FAILED");
                    out.put("status", "error");
                    out.put("code", "VALIDATION_FAILED");
                    out.put("message", "操作应用前校验失败，规划未修改");
                    out.put("validation", validation);
                    return out;
                }
                String finalPreviewJson = withValidation(previewJson, validation);
                String changeNote = operationNote(operationType, operationNode);
                SnapshotState beforeSnapshot = createSnapshot(
                        conn, currentTrip, currentTrip.owner,
                        "PlanOps[" + username + "] 操作前：" + changeNote);

                int rows;
                if ("ROLLBACK_PLAN_VERSION".equals(operationType)) {
                    int targetVersionId = operationNode.path("targetVersionId").asInt(0);
                    VersionState target = loadVersion(conn, currentTrip.owner, tripId, targetVersionId);
                    if (target == null || target.planData == null || target.planData.trim().isEmpty()) {
                        conn.rollback();
                        markOperationFailed(operationId, username, "目标版本不可用", "VERSION_NOT_FOUND");
                        return fail(out, "目标版本不可用，规划未修改");
                    }
                    String restoredTitle = target.title == null ? currentTrip.title : target.title;
                    String restoredContent = target.content == null ? currentTrip.content : target.content;
                    try (PreparedStatement ps = conn.prepareStatement(
                            "update trip set title = ?, content = ?, plan_data = ?,"
                                    + " plan_revision = plan_revision + 1, update_time = now()"
                                    + " where id = ? and username = ? and plan_revision = ?")) {
                        ps.setString(1, restoredTitle);
                        ps.setString(2, restoredContent);
                        ps.setString(3, target.planData);
                        ps.setInt(4, tripId);
                        ps.setString(5, currentTrip.owner);
                        ps.setInt(6, operationRevision);
                        rows = ps.executeUpdate();
                    }
                    if (rows > 0) {
                        restoreTripNodes(conn, currentTrip.owner, tripId, target.nodesJson);
                        restoreTripCandidates(conn, currentTrip.owner, tripId, target.candidatesJson);
                        currentTrip.title = restoredTitle;
                        currentTrip.content = restoredContent;
                        currentTrip.planData = target.planData;
                        currentTrip.planRevision = operationRevision + 1;
                    }
                    changeNote = "回退到 v" + target.versionNo;
                } else {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "update trip set title = ?, destination = ?, start_date = ?, end_date = ?, days = ?,"
                                    + " plan_data = ?, budget = ?, people = ?, pace = ?,"
                                    + " plan_revision = plan_revision + 1, update_time = now()"
                                    + " where id = ? and username = ? and plan_revision = ?")) {
                        ps.setString(1, meta.title);
                        ps.setString(2, meta.destination);
                        ps.setString(3, meta.start);
                        ps.setString(4, meta.end);
                        ps.setInt(5, meta.days);
                        ps.setString(6, afterPlanData);
                        ps.setString(7, proposed.budget);
                        ps.setString(8, proposed.people);
                        ps.setString(9, proposed.pace);
                        ps.setInt(10, tripId);
                        ps.setString(11, currentTrip.owner);
                        ps.setInt(12, operationRevision);
                        rows = ps.executeUpdate();
                    }
                    if (rows > 0) {
                        currentTrip.title = meta.title;
                        currentTrip.destination = meta.destination;
                        currentTrip.startDate = meta.start;
                        currentTrip.endDate = meta.end;
                        currentTrip.days = meta.days;
                        currentTrip.planData = afterPlanData;
                        currentTrip.budget = proposed.budget;
                        currentTrip.people = proposed.people;
                        currentTrip.pace = proposed.pace;
                        currentTrip.planRevision = operationRevision + 1;
                        if ("ADD_CANDIDATE".equals(operationType) || "ADD_NODE".equals(operationType)) {
                            upsertCandidate(conn, currentTrip.owner, tripId,
                                    afterRoot.path("chosen"));
                        } else if ("REMOVE_CANDIDATE".equals(operationType)) {
                            deleteCandidateProjection(conn, currentTrip.owner, tripId, operationNode);
                        } else if ("UPDATE_PLAN_META".equals(operationType)
                                && operationNode.path("resetPlan").asBoolean(false)) {
                            deleteCandidateProjection(conn, currentTrip.owner, tripId);
                        }
                    }
                }
                if (rows <= 0) {
                    conn.rollback();
                    TripState current = loadTrip(username, tripId);
                    return conflict(out, "VERSION_CONFLICT", "规划已被更新，请重新预览", current == null ? 0 : current.planRevision);
                }
                SnapshotState resultSnapshot = createSnapshot(
                        conn, currentTrip, currentTrip.owner,
                        "PlanOps[" + username + "]：" + changeNote);
                try (PreparedStatement ps = conn.prepareStatement(
                        "update plan_operation set status = 'applied', preview_json = ?,"
                                + " before_version_id = ?, result_version_id = ?, applied_at = now()"
                                + " where id = ? and username = ? and status = 'preview'")) {
                    ps.setString(1, finalPreviewJson);
                    ps.setInt(2, beforeSnapshot.versionId);
                    ps.setInt(3, resultSnapshot.versionId);
                    ps.setString(4, operationId);
                    ps.setString(5, username);
                    if (ps.executeUpdate() <= 0) {
                        conn.rollback();
                        return fail(out, "操作已处理，不能重复应用");
                    }
                }
                conn.commit();
                out.put("status", "success");
                out.put("operationId", operationId);
                out.put("planRevision", currentTrip.planRevision);
                out.put("planData", currentTrip.planData);
                out.put("versionNo", resultSnapshot.versionNo);
                out.put("versionId", resultSnapshot.versionId);
                out.put("beforeVersionId", beforeSnapshot.versionId);
                out.put("rollback", "ROLLBACK_PLAN_VERSION".equals(operationType));
                out.put("validation", validation);
                out.put("preview", JSON.readTree(finalPreviewJson));
                return out;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            log.error("应用 PlanOperation 失败", e);
            return fail(out, "操作应用失败");
        }
    }

    @PostMapping(value = "/plan/ops/history", produces = "application/json;charset=UTF-8")
    public Map<String, Object> history(@RequestParam(defaultValue = "0") int tripId,
                                       HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> versions = new ArrayList<>();
        int currentRevision = 0;
        int currentVersion = 0;
        String sql = "select id, context_id, operation_type, risk_level, source, expected_revision,"
                + " status, error_message, before_version_id, result_version_id,"
                + " create_time, applied_at from plan_operation"
                + " where username = ? and trip_id = ? order by create_time desc limit 100";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setInt(2, tripId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("contextId", rs.getString("context_id"));
                    item.put("type", rs.getString("operation_type"));
                    item.put("riskLevel", rs.getString("risk_level"));
                    item.put("source", rs.getString("source"));
                    item.put("expectedRevision", rs.getInt("expected_revision"));
                    item.put("status", rs.getString("status"));
                    item.put("errorMessage", rs.getString("error_message"));
                    item.put("createTime", formatTime(rs.getTimestamp("create_time")));
                    item.put("appliedAt", formatTime(rs.getTimestamp("applied_at")));
                    int beforeVersionId = rs.getInt("before_version_id");
                    int resultVersionId = rs.getInt("result_version_id");
                    item.put("beforeVersionId", beforeVersionId);
                    item.put("resultVersionId", resultVersionId);
                    item.put("canUndo", "applied".equals(item.get("status")) && beforeVersionId > 0);
                    items.add(item);
                }
            }
            try (PreparedStatement tripPs = conn.prepareStatement(
                    "select version, plan_revision from trip where id = ? and username = ?")) {
                tripPs.setInt(1, tripId);
                tripPs.setString(2, username);
                try (ResultSet rs = tripPs.executeQuery()) {
                    if (rs.next()) {
                        currentVersion = rs.getInt("version");
                        currentRevision = rs.getInt("plan_revision");
                    }
                }
            }
            try (PreparedStatement versionPs = conn.prepareStatement(
                    "select id, version_no, change_note, create_time, plan_data, plan_revision"
                            + " from trip_version where username = ? and trip_id = ?"
                            + " order by version_no desc limit 100")) {
                versionPs.setString(1, username);
                versionPs.setInt(2, tripId);
                try (ResultSet rs = versionPs.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> version = new LinkedHashMap<>();
                        String planData = rs.getString("plan_data");
                        version.put("id", rs.getInt("id"));
                        version.put("versionNo", rs.getInt("version_no"));
                        version.put("changeNote", rs.getString("change_note"));
                        version.put("createTime", formatTime(rs.getTimestamp("create_time")));
                        version.put("planRevision", rs.getInt("plan_revision"));
                        version.put("hasPlanData", planData != null && !planData.trim().isEmpty());
                        versions.add(version);
                    }
                }
            }
        } catch (Exception e) {
            log.error("读取 PlanOperation 历史失败", e);
            return fail(out, "操作历史读取失败");
        }
        out.put("status", "success");
        out.put("items", items);
        out.put("versions", versions);
        out.put("currentRevision", currentRevision);
        out.put("currentVersionNo", currentVersion);
        return out;
    }

    @PostMapping(value = "/plan/day/optimize/preview", produces = "application/json;charset=UTF-8")
    public Map<String, Object> optimizeDay(@RequestParam String contextId,
                                           @RequestParam int dayIndex,
                                           @RequestParam(defaultValue = "driving") String travelMode,
                                           HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        return optimizeDayForUser(username, contextId, dayIndex, travelMode);
    }

    public Map<String, Object> optimizeDayForUser(String username, String contextId, int dayIndex) {
        return optimizeDayForUser(username, contextId, dayIndex, "driving");
    }

    public Map<String, Object> optimizeDayForUser(String username, String contextId,
                                                  int dayIndex, String travelMode) {
        Map<String, Object> out = new LinkedHashMap<>();
        ContextState context = loadContext(username, contextId);
        if (context == null) return fail(out, "规划上下文不存在");
        TripState trip = loadTrip(username, context.tripId);
        if (trip == null) return fail(out, "规划不存在或无权访问");
        if (trip.planRevision != context.planRevision) {
            return conflict(out, "VERSION_CONFLICT", "规划已在其他页面更新，请刷新后再优化", trip.planRevision);
        }
        try {
            ObjectNode root = parsePlanData(trip.planData);
            ConstraintState constraints = constraintsOf(root, trip);
            if (dayIndex < 0 || dayIndex >= root.withArray("itinerary").size()) {
                return fail(out, "目标日期无效");
            }
            JsonNode dayNode = root.withArray("itinerary").get(dayIndex);
            Map<String, Object> weather = weatherService.dayWeather(dayNode);
            List<PlanDayOptimizer.Variant> variants = dayOptimizer.buildVariants(
                    root, dayIndex, constraints.pace, travelMode, trip.destination, weather);
            if (variants.isEmpty()) return fail(out, "当前日期没有可生成的优化方案");
            List<Map<String, Object>> options = new ArrayList<>();
            for (PlanDayOptimizer.Variant variant : variants) {
                ObjectNode operation = JSON.createObjectNode();
                operation.put("type", "REBUILD_ITINERARY");
                operation.put("expectedRevision", trip.planRevision);
                operation.put("variant", variant.getId());
                operation.put("reason", "Day Optimize：" + variant.getTitle());
                operation.set("itinerary", variant.getItinerary());
                Map<String, Object> preview = previewForUser(
                        username, contextId, operation.toString(), "system");
                if (!"success".equals(preview.get("status"))) continue;
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("id", variant.getId());
                option.put("title", variant.getTitle());
                option.put("summary", variant.getSummary());
                option.put("metrics", variant.getMetrics());
                @SuppressWarnings("unchecked")
                Map<String, Object> optionMetrics = (Map<String, Object>) option.get("metrics");
                optionMetrics.put("weather", weather);
                option.putAll(preview);
                options.add(option);
            }
            out.put("status", options.isEmpty() ? "error" : "success");
            if (options.isEmpty()) out.put("message", "没有可用的优化方案");
            out.put("options", options);
            Map<String, Object> before = dayOptimizer.metrics(
                    root, dayIndex, travelMode, trip.destination);
            before.put("weather", weather);
            out.put("before", before);
            out.put("weather", weather);
            out.put("critic", dayOptimizer.critic(
                    root, dayIndex, constraints.budget, constraints.people,
                    preferencesOf(root, username), constraints.pace));
            return out;
        } catch (IllegalArgumentException e) {
            return fail(out, e.getMessage());
        } catch (Exception e) {
            log.error("Day Optimize Preview 失败", e);
            return fail(out, "优化方案生成失败");
        }
    }

    @PostMapping(value = "/plan/day/critic", produces = "application/json;charset=UTF-8")
    public Map<String, Object> dayCritic(@RequestParam int tripId,
                                         @RequestParam int dayIndex,
                                         HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        TripState trip = loadTrip(username, tripId);
        if (trip == null) return fail(out, "规划不存在或无权访问");
        try {
            ObjectNode root = parsePlanData(trip.planData);
            ConstraintState constraints = constraintsOf(root, trip);
            out.put("status", "success");
            out.put("critic", dayOptimizer.critic(
                    root, dayIndex, constraints.budget, constraints.people,
                    preferencesOf(root, username), constraints.pace));
            return out;
        } catch (Exception e) {
            log.error("Plan Critic 读取失败", e);
            return fail(out, "行程检查失败");
        }
    }

    @PostMapping(value = "/plan/critic", produces = "application/json;charset=UTF-8")
    public Map<String, Object> planCritic(@RequestParam int tripId, HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String username = ChatController.currentUser(session);
        if (username == null) return fail(out, "请先登录");
        TripState trip = loadTrip(username, tripId);
        if (trip == null) return fail(out, "规划不存在或无权访问");
        try {
            ObjectNode root = parsePlanData(trip.planData);
            ConstraintState constraints = constraintsOf(root, trip);
            out.put("status", "success");
            out.put("critic", dayOptimizer.criticAll(
                    root, constraints.budget, constraints.people,
                    preferencesOf(root, username), constraints.pace));
            return out;
        } catch (Exception e) {
            log.error("全行程 Plan Critic 读取失败", e);
            return fail(out, "全行程检查失败");
        }
    }

    private Map<String, Object> applyOperation(ObjectNode root, String type,
                                                ObjectNode operation, ContextState context) {
        if ("ADD_CANDIDATE".equals(type)) return addCandidate(root, operation);
        if ("ADD_NODE".equals(type)) return addNode(root, operation);
        if ("REMOVE_CANDIDATE".equals(type)) return removeCandidate(root, operation);
        if ("UPDATE_CONSTRAINT".equals(type)) return updateConstraint(root, operation);
        if ("UPDATE_BUDGET".equals(type)) return updateBudget(root, operation);
        if ("UPDATE_STAY".equals(type)) return updateStay(root, operation);
        if ("UPDATE_PLAN_META".equals(type)) return updatePlanMeta(root, operation);
        if ("UPDATE_TRANSPORT".equals(type)) return updateTransport(root, operation);
        if ("UPDATE_BUDGET_PLAN".equals(type)) return updateBudgetPlan(root, operation);
        if ("REBUILD_ITINERARY".equals(type)) return rebuildItinerary(root, operation);
        if ("UPDATE_ROUTE_TIMES".equals(type)) return updateRouteTimes(root, operation);

        String nodeKey = operation.path("nodeKey").asText(
                context.selectedNodeKey == null ? "" : context.selectedNodeKey);
        if (nodeKey.isEmpty()) throw new IllegalArgumentException("缺少 nodeKey");
        if ("TOGGLE_NODE_CLOSURE".equals(type)) {
            ObjectNode closures = root.with("closures");
            boolean value = operation.path("closed").asBoolean(!closures.path(nodeKey).asBoolean(false));
            closures.put(nodeKey, value);
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("action", value ? "MARK_NODE_CLOSED" : "CLEAR_NODE_CLOSED");
            diff.put("nodeKey", nodeKey);
            return diff;
        }
        if ("LOCK_NODE".equals(type)) {
            ObjectNode locked = root.with("locked");
            boolean value = operation.path("locked").asBoolean(!locked.path(nodeKey).asBoolean(false));
            locked.put(nodeKey, value);
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("action", value ? "LOCK_NODE" : "UNLOCK_NODE");
            diff.put("nodeKey", nodeKey);
            return diff;
        }

        ArrayNode itinerary = root.withArray("itinerary");
        ItemPosition position = findNode(itinerary, nodeKey);
        if (position == null) throw new IllegalArgumentException("找不到当前行程节点");
        if ("MOVE_NODE".equals(type)) {
            int targetDay = operation.path("targetDay").asInt(-1);
            String targetSlot = operation.path("targetSlot").asText("");
            if (targetDay < 0 || targetDay >= itinerary.size() || !SLOTS.contains(targetSlot)) {
                throw new IllegalArgumentException("目标日期或时段无效");
            }
            position.container.remove(position.index);
            ObjectNode targetDayNode = (ObjectNode) itinerary.get(targetDay);
            ArrayNode target = targetDayNode.withArray(targetSlot);
            target.add(position.node);
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("action", "MOVE_NODE");
            diff.put("node", position.node.path("name").asText("未命名地点"));
            diff.put("fromDay", position.day + 1);
            diff.put("fromSlot", position.slot);
            diff.put("toDay", targetDay + 1);
            diff.put("toSlot", targetSlot);
            return diff;
        }
        if ("REMOVE_NODE".equals(type)) {
            position.container.remove(position.index);
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("action", "REMOVE_NODE");
            diff.put("node", position.node.path("name").asText("未命名地点"));
            diff.put("day", position.day + 1);
            diff.put("slot", position.slot);
            return diff;
        }
        if ("REPLACE_NODE".equals(type)) {
            JsonNode replacement = operation.path("replacement");
            if (!replacement.isObject() || replacement.path("name").asText("").trim().isEmpty()) {
                throw new IllegalArgumentException("替换地点数据无效");
            }
            position.container.set(position.index, replacement.deepCopy());
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("action", "REPLACE_NODE");
            diff.put("before", position.node.path("name").asText("未命名地点"));
            diff.put("after", replacement.path("name").asText("未命名地点"));
            diff.put("day", position.day + 1);
            diff.put("slot", position.slot);
            return diff;
        }
        if ("REORDER_NODE".equals(type)) {
            int direction = operation.path("direction").asInt(1);
            int targetIndex = position.index + (direction < 0 ? -1 : 1);
            if (targetIndex < 0 || targetIndex >= position.container.size()) {
                throw new IllegalArgumentException("节点已经在当前时段边界");
            }
            JsonNode other = position.container.get(targetIndex);
            position.container.set(targetIndex, position.node);
            position.container.set(position.index, other);
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("action", "REORDER_NODE");
            diff.put("node", position.node.path("name").asText("未命名地点"));
            diff.put("direction", direction < 0 ? "up" : "down");
            return diff;
        }
        throw new IllegalArgumentException("不支持的规划操作");
    }

    private Map<String, Object> addCandidate(ObjectNode root, ObjectNode operation) {
        ObjectNode candidate = requiredCandidate(operation, "candidate");
        String key = poiKey(candidate);
        ArrayNode chosen = root.withArray("chosen");
        boolean duplicate = containsKey(chosen, key);
        if (!duplicate) chosen.add(candidate);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("action", "ADD_CANDIDATE");
        diff.put("candidate", candidate.path("name").asText("未命名地点"));
        diff.put("nodeKey", key);
        diff.put("duplicate", duplicate);
        return diff;
    }

    private Map<String, Object> addNode(ObjectNode root, ObjectNode operation) {
        ObjectNode candidate = requiredCandidate(operation, "node");
        int targetDay = operation.path("targetDay").asInt(-1);
        String targetSlot = operation.path("targetSlot").asText("").trim();
        ArrayNode itinerary = root.withArray("itinerary");
        if (targetDay < 0 || targetDay >= itinerary.size() || !SLOTS.contains(targetSlot)) {
            throw new IllegalArgumentException("目标日期或时段无效");
        }
        String key = poiKey(candidate);
        if (findNode(itinerary, key) != null) {
            throw new IllegalArgumentException("该地点已经在正式行程中，请使用移动操作");
        }
        ensureChosen(root, candidate);
        ObjectNode targetDayNode = (ObjectNode) itinerary.get(targetDay);
        targetDayNode.withArray(targetSlot).add(candidate);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("action", "ADD_NODE");
        diff.put("node", candidate.path("name").asText("未命名地点"));
        diff.put("day", targetDay + 1);
        diff.put("slot", targetSlot);
        return diff;
    }

    private Map<String, Object> removeCandidate(ObjectNode root, ObjectNode operation) {
        String nodeKey = operation.path("nodeKey").asText("").trim();
        if (nodeKey.isEmpty() && operation.path("candidate").isObject()) {
            nodeKey = poiKey(operation.path("candidate"));
        }
        if (nodeKey.isEmpty()) throw new IllegalArgumentException("缺少候选节点键");
        int removed = 0;
        ArrayNode chosen = root.withArray("chosen");
        for (int i = chosen.size() - 1; i >= 0; i--) {
            if (nodeKey.equals(poiKey(chosen.get(i)))) {
                chosen.remove(i);
                removed++;
            }
        }
        ArrayNode itinerary = root.withArray("itinerary");
        for (int day = 0; day < itinerary.size(); day++) {
            ObjectNode dayNode = (ObjectNode) itinerary.get(day);
            for (String slot : SLOTS) {
                ArrayNode items = dayNode.withArray(slot);
                for (int i = items.size() - 1; i >= 0; i--) {
                    if (nodeKey.equals(poiKey(items.get(i)))) {
                        items.remove(i);
                        removed++;
                    }
                }
            }
        }
        if (removed == 0) throw new IllegalArgumentException("找不到要移除的候选或行程节点");
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("action", "REMOVE_CANDIDATE");
        diff.put("nodeKey", nodeKey);
        diff.put("removed", removed);
        return diff;
    }

    private Map<String, Object> updateConstraint(ObjectNode root, ObjectNode operation) {
        JsonNode constraints = operation.path("constraints");
        if (!constraints.isObject()) constraints = operation;
        ObjectNode target = root.with("constraints");
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();

        if (constraints.has("people")) {
            String people = trim(constraints.path("people").asText(""), 50);
            if (people == null || people.isEmpty()) throw new IllegalArgumentException("同行人数不能为空");
            before.put("people", target.path("people").asText(""));
            target.put("people", people);
            after.put("people", people);
        }
        if (constraints.has("pace")) {
            String pace = constraints.path("pace").asText("").trim();
            if (!Arrays.asList("relaxed", "balanced", "compact").contains(pace)) {
                throw new IllegalArgumentException("行程节奏无效");
            }
            before.put("pace", target.path("pace").asText(""));
            target.put("pace", pace);
            after.put("pace", pace);
        }
        if (constraints.has("quickPrefs")) {
            ArrayNode next = JSON.createArrayNode();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            JsonNode prefs = constraints.path("quickPrefs");
            if (prefs.isArray()) {
                for (JsonNode pref : prefs) {
                    String value = pref.asText("").trim();
                    if (!value.isEmpty() && seen.add(value)) next.add(value);
                }
            }
            before.put("quickPrefs", root.path("quickPrefs"));
            root.set("quickPrefs", next);
            after.put("quickPrefs", next);
        }
        if (after.isEmpty()) throw new IllegalArgumentException("没有可更新的约束");
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("action", "UPDATE_CONSTRAINT");
        diff.put("before", before);
        diff.put("after", after);
        return diff;
    }

    private Map<String, Object> updateBudget(ObjectNode root, ObjectNode operation) {
        String budget = trim(operation.path("budget").asText(""), 100);
        if (budget == null) budget = "";
        ObjectNode constraints = root.with("constraints");
        String before = constraints.path("budget").asText(root.path("budget").asText(""));
        constraints.put("budget", budget);
        root.put("budget", budget);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("action", "UPDATE_BUDGET");
        diff.put("before", before);
        diff.put("after", budget);
        return diff;
    }

    private Map<String, Object> updateStay(ObjectNode root, ObjectNode operation) {
        JsonNode stay = operation.path("stay");
        String before = root.path("stay").path("name").asText("");
        if (stay.isObject()) {
            ObjectNode value = (ObjectNode) stay.deepCopy();
            String name = value.path("name").asText("").trim();
            if (name.isEmpty()) throw new IllegalArgumentException("住宿区域名称不能为空");
            value.put("name", name);
            root.set("stay", value);
        } else {
            root.putNull("stay");
        }
        if (operation.has("hotelName")) {
            root.put("hotelName", trim(operation.path("hotelName").asText(""), 160));
        }
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("action", "UPDATE_STAY");
        diff.put("before", before);
        diff.put("after", stay.isObject() ? stay.path("name").asText("") : "");
        return diff;
    }

    private Map<String, Object> rebuildItinerary(ObjectNode root, ObjectNode operation) {
        JsonNode itinerary = operation.path("itinerary");
        if (!itinerary.isArray() || itinerary.isEmpty()) {
            throw new IllegalArgumentException("新的行程骨架不能为空");
        }
        int nodeCount = 0;
        for (JsonNode day : itinerary) {
            if (!day.isObject()) throw new IllegalArgumentException("行程骨架日期格式无效");
            for (String slot : SLOTS) {
                JsonNode items = day.path(slot);
                if (!items.isArray()) throw new IllegalArgumentException("行程骨架时段格式无效");
                nodeCount += items.size();
            }
        }
        root.set("itinerary", itinerary.deepCopy());
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("action", "REBUILD_ITINERARY");
        diff.put("days", itinerary.size());
        diff.put("nodes", nodeCount);
        diff.put("variant", operation.path("variant").asText("classic"));
        return diff;
    }

    private Map<String, Object> updateRouteTimes(ObjectNode root, ObjectNode operation) {
        JsonNode itinerary = operation.path("itinerary");
        if (!itinerary.isArray()) throw new IllegalArgumentException("路线耗时数据无效");
        int segments = 0;
        for (JsonNode day : itinerary) {
            for (String slot : SLOTS) {
                for (JsonNode item : day.path(slot)) {
                    if (item.path("routeSource").asText("").equals("amap")
                            || item.path("routeSource").asText("").equals("estimated")) {
                        segments++;
                    }
                }
            }
        }
        root.set("itinerary", itinerary.deepCopy());
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("action", "UPDATE_ROUTE_TIMES");
        diff.put("segments", segments);
        diff.put("travelMode", operation.path("travelMode").asText("driving"));
        return diff;
    }

    private Map<String, Object> updatePlanMeta(ObjectNode root, ObjectNode operation) {
        ObjectNode meta = root.with("meta");
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        String destination = operation.path("destination").asText("").trim();
        String destinationLabel = operation.path("destinationLabel").asText("").trim();
        String start = operation.path("start").asText("").trim();
        String end = operation.path("end").asText("").trim();
        String origin = operation.path("origin").asText("").trim();
        String title = operation.path("title").asText("").trim();
        int days = operation.path("days").asInt(0);
        boolean resetPlan = operation.path("resetPlan").asBoolean(false);

        if (!destination.isEmpty()) {
            before.put("destination", meta.path("destination").asText(root.path("_destinationLabel").asText("")));
            meta.put("destination", trim(destination, 100));
            meta.put("destinationLabel", trim(destinationLabel.isEmpty() ? destination : destinationLabel, 120));
            root.put("destinationLabel", trim(destinationLabel.isEmpty() ? destination : destinationLabel, 120));
            after.put("destination", destination);
        }
        if (!start.isEmpty()) {
            before.put("start", meta.path("start").asText(""));
            meta.put("start", trim(start, 20));
            after.put("start", start);
        }
        if (!end.isEmpty()) {
            before.put("end", meta.path("end").asText(""));
            meta.put("end", trim(end, 20));
            after.put("end", end);
        }
        if (days > 0) {
            before.put("days", meta.path("days").asInt(root.path("itinerary").size()));
            meta.put("days", days);
            after.put("days", days);
        }
        if (!origin.isEmpty()) {
            before.put("origin", meta.path("origin").asText(""));
            meta.put("origin", trim(origin, 100));
            root.put("origin", trim(origin, 100));
            root.put("_origin", trim(origin, 100));
            after.put("origin", origin);
        }
        if (!title.isEmpty()) root.put("title", trim(title, 120));
        if (!operation.path("destinationLabel").asText("").trim().isEmpty()) {
            root.put("_destinationLabel", trim(destinationLabel, 120));
        }

        if (resetPlan) {
            root.withArray("chosen").removeAll();
            root.withArray("itinerary").removeAll();
            root.with("locked").removeAll();
            root.putNull("stay");
            after.put("resetPlan", true);
        }
        int targetDays = days > 0 ? days : root.path("itinerary").size();
        if (targetDays > 0) {
            reconcileItinerary(root, targetDays, start);
        }
        if (after.isEmpty()) throw new IllegalArgumentException("没有可更新的规划元数据");
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("action", "UPDATE_PLAN_META");
        diff.put("before", before);
        diff.put("after", after);
        return diff;
    }

    private Map<String, Object> updateTransport(ObjectNode root, ObjectNode operation) {
        JsonNode transport = operation.path("transport");
        if (!transport.isObject()) throw new IllegalArgumentException("城际交通数据无效");
        ObjectNode value = (ObjectNode) transport.deepCopy();
        validateTransportLeg(value.path("outbound"), "去程");
        validateTransportLeg(value.path("inbound"), "返程");
        root.set("transport", value);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("action", "UPDATE_TRANSPORT");
        diff.put("outbound", transportSummary(value.path("outbound")));
        diff.put("inbound", transportSummary(value.path("inbound")));
        return diff;
    }

    private void validateTransportLeg(JsonNode leg, String label) {
        if (leg == null || leg.isMissingNode() || leg.isNull()) return;
        if (!leg.isObject()) throw new IllegalArgumentException(label + "交通格式无效");
        String depart = leg.path("departAt").asText("").trim();
        String arrive = leg.path("arriveAt").asText("").trim();
        for (String time : new String[]{depart, arrive}) {
            if (!time.isEmpty() && !time.matches("\\d{1,2}:\\d{2}")) {
                throw new IllegalArgumentException(label + "时间格式应为 HH:mm");
            }
        }
    }

    private String transportSummary(JsonNode leg) {
        if (leg == null || !leg.isObject()) return "";
        return leg.path("mode").asText("") + " "
                + leg.path("departAt").asText("") + "-" + leg.path("arriveAt").asText("")
                + (leg.path("station").asText("").isEmpty() ? "" : " " + leg.path("station").asText(""))
                + (leg.has("includeFirstDay") && !leg.path("includeFirstDay").asBoolean(true)
                    ? "（第一天不计到达交通）" : "");
    }

    private Map<String, Object> updateBudgetPlan(ObjectNode root, ObjectNode operation) {
        ObjectNode value = operation.path("budgetPlan").isObject()
                ? (ObjectNode) operation.path("budgetPlan").deepCopy()
                : JSON.createObjectNode();
        double total = value.path("total").asDouble(0);
        ObjectNode categories = value.with("categories");
        String[] names = {"transport", "lodging", "food", "ticket", "local"};
        for (String name : names) {
            if (categories.path(name).asDouble(0) < 0) {
                throw new IllegalArgumentException("预算分项不能为负数");
            }
        }
        if (total < 0) throw new IllegalArgumentException("总预算不能为负数");
        root.set("budgetPlan", value);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("action", "UPDATE_BUDGET_PLAN");
        diff.put("total", total);
        diff.put("categories", categories);
        return diff;
    }

    private void reconcileItinerary(ObjectNode root, int targetDays, String start) {
        ArrayNode itinerary = root.withArray("itinerary");
        while (itinerary.size() < targetDays) {
            ObjectNode day = itinerary.addObject();
            int offset = itinerary.size() - 1;
            day.put("date", dateAt(start, offset));
            day.set("morning", JSON.createArrayNode());
            day.set("afternoon", JSON.createArrayNode());
            day.set("evening", JSON.createArrayNode());
        }
        while (itinerary.size() > targetDays) itinerary.remove(itinerary.size() - 1);
        for (int i = 0; i < itinerary.size(); i++) {
            ObjectNode day = (ObjectNode) itinerary.get(i);
            if (!start.isEmpty()) day.put("date", dateAt(start, i));
            for (String slot : SLOTS) day.withArray(slot);
        }
    }

    private String dateAt(String start, int offset) {
        if (start == null || start.trim().isEmpty()) return "";
        try {
            return LocalDate.parse(start.trim()).plusDays(offset).toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private ObjectNode requiredCandidate(ObjectNode operation, String field) {
        JsonNode source = operation.path(field);
        if (!source.isObject()) source = operation.path("candidate");
        if (!source.isObject()) throw new IllegalArgumentException("缺少候选地点数据");
        ObjectNode candidate = (ObjectNode) source.deepCopy();
        String name = candidate.path("name").asText("").trim();
        if (name.isEmpty()) throw new IllegalArgumentException("候选地点名称不能为空");
        candidate.put("name", name);
        if (!candidate.has("category") || candidate.path("category").asText("").trim().isEmpty()) {
            candidate.put("category", candidate.path("type").asText("自己添加"));
        }
        return candidate;
    }

    private void ensureChosen(ObjectNode root, ObjectNode candidate) {
        ArrayNode chosen = root.withArray("chosen");
        if (!containsKey(chosen, poiKey(candidate))) chosen.add(candidate);
    }

    private boolean containsKey(ArrayNode items, String key) {
        for (JsonNode item : items) {
            if (key.equals(poiKey(item))) return true;
        }
        return false;
    }

    private ItemPosition findNode(ArrayNode itinerary, String nodeKey) {
        for (int day = 0; day < itinerary.size(); day++) {
            ObjectNode dayNode = (ObjectNode) itinerary.get(day);
            for (String slot : SLOTS) {
                ArrayNode container = dayNode.withArray(slot);
                for (int index = 0; index < container.size(); index++) {
                    JsonNode node = container.get(index);
                    if (nodeKey.equals(poiKey(node))) {
                        return new ItemPosition(day, slot, index, node, container);
                    }
                }
            }
        }
        return null;
    }

    private ArrayNode itineraryNodes(JsonNode itinerary) {
        ArrayNode nodes = JSON.createArrayNode();
        if (itinerary == null || !itinerary.isArray()) return nodes;
        for (int day = 0; day < itinerary.size(); day++) {
            JsonNode dayNode = itinerary.get(day);
            for (String slot : SLOTS) {
                JsonNode items = dayNode.path(slot);
                if (!items.isArray()) continue;
                for (JsonNode item : items) {
                    ObjectNode row = nodes.addObject();
                    row.put("day", day + 1);
                    row.put("dayIndex", day);
                    row.put("slot", slot);
                    row.put("nodeKey", poiKey(item));
                    row.set("node", item);
                }
            }
        }
        return nodes;
    }

    private String poiKey(JsonNode node) {
        return node.path("name").asText("") + "|"
                + node.path("address").asText("") + "|"
                + node.path("lat").asText("") + "|"
                + node.path("lng").asText("");
    }

    private ObjectNode parsePlanData(String planData) throws Exception {
        if (planData == null || planData.trim().isEmpty()) return JSON.createObjectNode();
        JsonNode root = JSON.readTree(planData);
        if (!root.isObject()) throw new IllegalArgumentException("规划草稿格式无效");
        return (ObjectNode) root;
    }

    private ConstraintState constraintsOf(JsonNode root, TripState trip) {
        JsonNode constraints = root.path("constraints");
        ConstraintState state = new ConstraintState();
        state.people = firstNonBlank(
                constraints.path("people").asText(""),
                root.path("people").asText(""),
                trip.people,
                "2 人");
        state.budget = firstNonBlank(
                constraints.path("budget").asText(""),
                root.path("budget").asText(""),
                trip.budget,
                "");
        state.pace = firstNonBlank(
                constraints.path("pace").asText(""),
                root.path("pace").asText(""),
                trip.pace,
                "balanced");
        if (!Arrays.asList("relaxed", "balanced", "compact").contains(state.pace)) {
            state.pace = "balanced";
        }
        return state;
    }

    private MetaState metaOf(JsonNode root, TripState trip, ObjectNode operation) {
        JsonNode meta = root.path("meta");
        MetaState state = new MetaState();
        String operationTitle = operation.path("title").asText("").trim();
        state.title = operationTitle.isEmpty()
                ? firstNonBlank(root.path("title").asText(""), trip.title, "未命名旅行规划")
                : operationTitle;
        state.destination = firstNonBlank(
                meta.path("destination").asText(""), trip.destination, "");
        state.start = firstNonBlank(meta.path("start").asText(""), trip.startDate, "");
        state.end = firstNonBlank(meta.path("end").asText(""), trip.endDate, "");
        state.days = meta.path("days").asInt(root.path("itinerary").size());
        if (state.days <= 0) state.days = Math.max(1, trip.days);
        return state;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private String preferencesOf(ObjectNode root, String username) {
        try (Connection conn = MySQLUtil.getConnection()) {
            return preferencesOf(root, conn, username);
        } catch (Exception e) {
            log.warn("读取长期偏好失败，将仅使用本次临时偏好：{}", e.getMessage());
            return preferencesOf(root, null, username);
        }
    }

    private String preferencesOf(ObjectNode root, Connection conn, String username) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        JsonNode quick = root.path("quickPrefs");
        if (quick.isArray()) quick.forEach(x -> values.add(x.asText()));
        if (conn != null && username != null) {
            String sql = "select tag, `value` from user_preference"
                    + " where username = ? order by update_time";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String tag = rs.getString("tag");
                        String value = rs.getString("value");
                        values.add((tag == null ? "" : tag) + "：" + (value == null ? "" : value));
                    }
                }
            } catch (Exception e) {
                log.warn("读取长期偏好失败，将仅使用本次临时偏好：{}", e.getMessage());
            }
        }
        return String.join("；", values);
    }

    @SuppressWarnings("unchecked")
    private void enrichValidation(ObjectNode root, Map<String, Object> validation,
                                  String peopleText) {
        if (!"success".equals(validation.get("status"))) return;
        List<Map<String, Object>> conflicts = validation.get("conflicts") instanceof List
                ? (List<Map<String, Object>>) validation.get("conflicts") : new ArrayList<>();
        Map<String, Object> transport = transportConflicts(root);
        List<Map<String, Object>> transportConflicts =
                (List<Map<String, Object>>) transport.getOrDefault("conflicts", new ArrayList<>());
        conflicts.addAll(transportConflicts);
        Map<String, Object> budget = budgetBreakdown(root, parsePeople(peopleText));
        List<Map<String, Object>> budgetConflicts =
                (List<Map<String, Object>>) budget.getOrDefault("conflicts", new ArrayList<>());
        conflicts.addAll(budgetConflicts);
        validation.put("conflicts", conflicts);
        validation.put("ok", conflicts.isEmpty());
        validation.put("transport", transport);
        validation.put("budget", budget);
    }

    private Map<String, Object> transportConflicts(ObjectNode root) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        ArrayNode itinerary = root.withArray("itinerary");
        JsonNode transport = root.path("transport");
        JsonNode outbound = transport.path("outbound");
        JsonNode inbound = transport.path("inbound");
        boolean includeFirstDay = outbound.path("includeFirstDay").asBoolean(true);
        int outboundReady = includeFirstDay
                ? parseTime(outbound.path("arriveAt").asText(""))
                    + outbound.path("toStayMinutes").asInt(0)
                : 0;
        if (!itinerary.isEmpty() && outboundReady > 0) {
            JsonNode firstDay = itinerary.get(0);
            if (outboundReady > 9 * 60 && firstDay.path("morning").size() > 0) {
                conflicts.add(conflict("TRANSPORT", 1,
                        "去程约 " + clock(outboundReady) + " 才能开始游玩，上午节点不可行"));
            }
            if (outboundReady > 14 * 60 && firstDay.path("afternoon").size() > 0) {
                conflicts.add(conflict("TRANSPORT", 1,
                        "去程到达较晚，下午节点需要顺延"));
            }
        }
        int inboundDepart = parseTime(inbound.path("departAt").asText(""));
        if (inboundDepart > 0 && !itinerary.isEmpty()) {
            int cutoff = inboundDepart - inbound.path("fromStayMinutes").asInt(0);
            JsonNode lastDay = itinerary.get(itinerary.size() - 1);
            if (cutoff < 14 * 60 && lastDay.path("afternoon").size() > 0) {
                conflicts.add(conflict("TRANSPORT", itinerary.size(),
                        "返程需要提前出发，最后一天下午安排不可行"));
            }
            if (cutoff < 19 * 60 && lastDay.path("evening").size() > 0) {
                conflicts.add(conflict("TRANSPORT", itinerary.size(),
                        "返程前可用时间不足，晚上节点需移除或提前"));
            }
        }
        result.put("firstDayIncluded", includeFirstDay);
        result.put("outboundReadyAt", outboundReady > 0 ? clock(outboundReady) : "");
        result.put("inboundCutoffAt", inboundDepart > 0
                ? clock(inboundDepart - inbound.path("fromStayMinutes").asInt(0)) : "");
        result.put("conflicts", conflicts);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> budgetBreakdown(ObjectNode root, int peopleCount) {
        JsonNode plan = root.path("budgetPlan");
        JsonNode allocated = plan.path("categories");
        JsonNode transport = root.path("transport");
        double transportCost = transport.path("outbound").path("cost").asDouble(0)
                + transport.path("inbound").path("cost").asDouble(0);
        Map<String, Double> estimated = new LinkedHashMap<>();
        String[] categories = {"transport", "lodging", "food", "ticket", "local"};
        for (String category : categories) estimated.put(category, 0d);
        estimated.put("transport", transportCost);
        for (JsonNode day : root.withArray("itinerary")) {
            for (String slot : SLOTS) {
                for (JsonNode item : day.path(slot)) {
                    double cost = parseMoney(item.path("cost").asText("")) * Math.max(1, peopleCount);
                    String category = budgetCategory(item);
                    estimated.put(category, estimated.getOrDefault(category, 0d) + cost);
                }
            }
        }
        double totalEstimated = estimated.values().stream().mapToDouble(Double::doubleValue).sum();
        double totalAllocated = plan.path("total").asDouble(0);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (String category : categories) {
            double limit = allocated.path(category).asDouble(0);
            if (limit > 0 && estimated.get(category) > limit) {
                conflicts.add(conflict("BUDGET_CATEGORY", 0,
                        budgetCategoryName(category) + "预计 ¥" + round(estimated.get(category))
                                + "，超过分项 ¥" + round(limit)));
            }
        }
        if (totalAllocated > 0 && totalEstimated > totalAllocated) {
            conflicts.add(conflict("BUDGET", 0,
                    "当前方案预计 ¥" + round(totalEstimated) + "，超过总预算 ¥" + round(totalAllocated)));
        }
        List<Map<String, Object>> alternatives = new ArrayList<>();
        root.path("chosen").forEach(item -> {
            double cost = parseMoney(item.path("cost").asText(""));
            if (cost <= 0 || alternatives.size() >= 5) return;
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("name", item.path("name").asText(""));
            option.put("category", budgetCategory(item));
            option.put("cost", cost);
            alternatives.add(option);
        });
        alternatives.sort((a, b) -> Double.compare(
                ((Number) a.get("cost")).doubleValue(),
                ((Number) b.get("cost")).doubleValue()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalAllocated", round(totalAllocated));
        result.put("totalEstimated", round(totalEstimated));
        result.put("estimated", roundMap(estimated));
        result.put("allocated", roundNodeMap(allocated, categories));
        result.put("remaining", round(totalAllocated - totalEstimated));
        result.put("conflicts", conflicts);
        result.put("lowerCostCandidates", alternatives);
        return result;
    }

    private String budgetCategory(JsonNode item) {
        String category = item.path("category").asText("");
        if ("美食".equals(category) || "咖啡".equals(category)) return "food";
        if ("景点".equals(category)) return "ticket";
        return "local";
    }

    private String budgetCategoryName(String category) {
        if ("transport".equals(category)) return "交通";
        if ("lodging".equals(category)) return "住宿";
        if ("food".equals(category)) return "餐饮";
        if ("ticket".equals(category)) return "门票";
        return "市内其他";
    }

    private Map<String, Object> conflict(String type, int day, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("day", day);
        item.put("message", message);
        return item;
    }

    private int parseTime(String value) {
        if (value == null || !value.matches("\\d{1,2}:\\d{2}")) return 0;
        String[] parts = value.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private String clock(int minutes) {
        int safe = Math.max(0, Math.min(23 * 60 + 59, minutes));
        return String.format("%02d:%02d", safe / 60, safe % 60);
    }

    private int parsePeople(String people) {
        if (people == null) return 1;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(people);
        return matcher.find() ? Math.max(1, Integer.parseInt(matcher.group(1))) : 1;
    }

    private double parseMoney(String value) {
        if (value == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)?)").matcher(value);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0;
    }

    private double round(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private Map<String, Object> roundMap(Map<String, Double> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, round(value)));
        return result;
    }

    private Map<String, Object> roundNodeMap(JsonNode values, String[] names) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : names) result.put(name, round(values.path(name).asDouble(0)));
        return result;
    }

    private TripState loadTripForUpdate(Connection conn, String username, int tripId) throws Exception {
        String sql = "select t.username as owner, t.title, t.content, t.plan_data, t.plan_revision,"
                + " t.version, t.budget, t.people, t.pace, t.destination, t.start_date,"
                + " t.end_date, t.days"
                + " from trip t where t.id = ? and (t.username = ? or exists ("
                + " select 1 from trip_collaborator c where c.trip_id = t.id"
                + " and c.username = ? and c.status = 'active')) for update";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            ps.setString(3, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                TripState t = new TripState();
                t.tripId = tripId;
                t.owner = rs.getString("owner");
                t.title = rs.getString("title");
                t.content = rs.getString("content");
                t.planData = rs.getString("plan_data");
                t.planRevision = rs.getInt("plan_revision");
                t.version = rs.getInt("version");
                t.budget = rs.getString("budget");
                t.people = rs.getString("people");
                t.pace = rs.getString("pace");
                t.destination = rs.getString("destination");
                t.startDate = rs.getString("start_date");
                t.endDate = rs.getString("end_date");
                t.days = rs.getInt("days");
                return t;
            }
        }
    }

    private ObjectNode parseJsonObject(String value) throws Exception {
        if (value == null || value.trim().isEmpty()) return JSON.createObjectNode();
        JsonNode node = JSON.readTree(value);
        return node != null && node.isObject() ? (ObjectNode) node : JSON.createObjectNode();
    }

    private String operationNote(String type, ObjectNode operation) {
        String reason = operation.path("reason").asText("").trim();
        if (!reason.isEmpty()) return trim(reason, 220);
        if ("MOVE_NODE".equals(type)) return "移动节点";
        if ("REMOVE_NODE".equals(type)) return "移出节点";
        if ("REPLACE_NODE".equals(type)) return "替换节点";
        if ("REORDER_NODE".equals(type)) return "调整节点顺序";
        if ("LOCK_NODE".equals(type)) return "更新节点锁定状态";
        if ("TOGGLE_NODE_CLOSURE".equals(type)) return "更新临时闭馆状态";
        if ("ADD_NODE".equals(type)) return "新增行程节点";
        if ("ADD_CANDIDATE".equals(type)) return "加入候选";
        if ("REMOVE_CANDIDATE".equals(type)) return "移除候选";
        if ("UPDATE_CONSTRAINT".equals(type)) return "更新人数、节奏或偏好";
        if ("UPDATE_BUDGET".equals(type)) return "更新预算";
        if ("UPDATE_STAY".equals(type)) return "更新住宿区域";
        if ("UPDATE_PLAN_META".equals(type)) return "更新目的地、日期或天数";
        if ("UPDATE_TRANSPORT".equals(type)) return "更新城际交通";
        if ("UPDATE_BUDGET_PLAN".equals(type)) return "更新预算分项";
        if ("REBUILD_ITINERARY".equals(type)) return "重建整份行程骨架";
        if ("UPDATE_ROUTE_TIMES".equals(type)) return "刷新逐段路线耗时";
        if ("ROLLBACK_PLAN_VERSION".equals(type)) return "回退规划版本";
        return "更新规划";
    }

    /**
     * 在调用方事务内生成一版完整快照，并同步 trip.version。
     */
    private SnapshotState createSnapshot(Connection conn, TripState trip, String username,
                                         String changeNote) throws Exception {
        int nextNo;
        try (PreparedStatement ps = conn.prepareStatement(
                "select coalesce(max(version_no), 0) + 1 from trip_version"
                        + " where trip_id = ? and username = ?")) {
            ps.setInt(1, trip.tripId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("无法计算规划版本号");
                nextNo = rs.getInt(1);
            }
        }
        String nodesJson = tripNodesJson(conn, username, trip.tripId);
        int versionId;
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into trip_version(trip_id, username, version_no, title, content, nodes_json,"
                        + " candidates_json, plan_data, plan_revision, change_note, create_time)"
                        + " values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, trip.tripId);
            ps.setString(2, username);
            ps.setInt(3, nextNo);
            ps.setString(4, trip.title);
            ps.setString(5, trip.content);
            ps.setString(6, nodesJson);
            ps.setString(7, tripCandidatesJson(conn, username, trip.tripId));
            ps.setString(8, trip.planData);
            ps.setInt(9, trip.planRevision);
            ps.setString(10, trim(changeNote, 255));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                versionId = keys.next() ? keys.getInt(1) : 0;
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "update trip set version = ?, update_time = now()"
                        + " where id = ? and username = ?")) {
            ps.setInt(1, nextNo);
            ps.setInt(2, trip.tripId);
            ps.setString(3, username);
            if (ps.executeUpdate() <= 0) throw new IllegalStateException("同步规划版本失败");
        }
        trip.version = nextNo;
        return new SnapshotState(versionId, nextNo);
    }

    private String tripNodesJson(Connection conn, String username, int tripId) throws Exception {
        List<Map<String, Object>> nodes = new ArrayList<>();
        String sql = "select node_type, title, detail, node_time, status, cost, photo, lat, lng, sort_order"
                + " from trip_node where trip_id = ? and username = ? order by sort_order, id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("nodeType", rs.getString("node_type"));
                    node.put("title", rs.getString("title"));
                    node.put("detail", rs.getString("detail"));
                    node.put("nodeTime", rs.getString("node_time"));
                    node.put("status", rs.getString("status"));
                    node.put("cost", rs.getBigDecimal("cost"));
                    node.put("photo", rs.getString("photo"));
                    node.put("lat", rs.getObject("lat"));
                    node.put("lng", rs.getObject("lng"));
                    node.put("sortOrder", rs.getInt("sort_order"));
                    nodes.add(node);
                }
            }
        }
        return JSON.writeValueAsString(nodes);
    }

    private String tripCandidatesJson(Connection conn, String username, int tripId) throws Exception {
        List<Map<String, Object>> candidates = new ArrayList<>();
        String sql = "select poi_id, name, type, city, address, lat, lng, duration_min, cost, evidence, photo"
                + " from trip_candidate where trip_id = ? and username = ? order by id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("poiId", rs.getString("poi_id"));
                    item.put("name", rs.getString("name"));
                    item.put("type", rs.getString("type"));
                    item.put("city", rs.getString("city"));
                    item.put("address", rs.getString("address"));
                    item.put("lat", rs.getObject("lat"));
                    item.put("lng", rs.getObject("lng"));
                    item.put("durationMin", rs.getInt("duration_min"));
                    item.put("cost", rs.getString("cost"));
                    item.put("evidence", rs.getString("evidence"));
                    item.put("photo", rs.getString("photo"));
                    candidates.add(item);
                }
            }
        }
        return JSON.writeValueAsString(candidates);
    }

    private void upsertCandidate(Connection conn, String username, int tripId,
                                 JsonNode chosen) throws Exception {
        if (chosen == null || !chosen.isArray()) return;
        for (JsonNode candidate : chosen) {
            String name = candidate.path("name").asText("").trim();
            if (name.isEmpty()) continue;
            String address = candidate.path("address").asText("").trim();
            int existingId = 0;
            try (PreparedStatement find = conn.prepareStatement(
                    "select id from trip_candidate where username = ? and trip_id = ? and name = ?"
                            + " and coalesce(address, '') = ? order by id limit 1")) {
                find.setString(1, username);
                find.setInt(2, tripId);
                find.setString(3, name);
                find.setString(4, address);
                try (ResultSet rs = find.executeQuery()) {
                    if (rs.next()) existingId = rs.getInt(1);
                }
            }
            if (existingId > 0) {
                try (PreparedStatement update = conn.prepareStatement(
                        "update trip_candidate set poi_id = ?, type = ?, city = ?, lat = ?, lng = ?,"
                                + " duration_min = ?, cost = ?, evidence = ?, photo = ? where id = ? and username = ?")) {
                    update.setString(1, candidate.path("poiId").asText(candidate.path("id").asText(null)));
                    update.setString(2, candidate.path("category").asText(candidate.path("type").asText(null)));
                    update.setString(3, candidate.path("city").asText(null));
                    if (candidate.path("lat").isNumber()) update.setDouble(4, candidate.path("lat").asDouble());
                    else update.setNull(4, java.sql.Types.DOUBLE);
                    if (candidate.path("lng").isNumber()) update.setDouble(5, candidate.path("lng").asDouble());
                    else update.setNull(5, java.sql.Types.DOUBLE);
                    update.setInt(6, candidate.path("durationMin").asInt(90));
                    update.setString(7, candidate.path("cost").asText(null));
                    update.setString(8, candidate.path("evidence").isArray()
                            ? candidate.path("evidence").toString()
                            : candidate.path("evidence").asText(candidate.path("description").asText(null)));
                    update.setString(9, candidate.path("photo").asText(null));
                    update.setInt(10, existingId);
                    update.setString(11, username);
                    update.executeUpdate();
                }
                continue;
            }
            try (PreparedStatement insert = conn.prepareStatement(
                    "insert into trip_candidate(username, trip_id, poi_id, name, type, city, address,"
                            + " lat, lng, duration_min, cost, evidence, photo, create_time)"
                            + " values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())")) {
                insert.setString(1, username);
                insert.setInt(2, tripId);
                insert.setString(3, candidate.path("poiId").asText(candidate.path("id").asText(null)));
                insert.setString(4, name);
                insert.setString(5, candidate.path("category").asText(candidate.path("type").asText(null)));
                insert.setString(6, candidate.path("city").asText(null));
                insert.setString(7, address);
                if (candidate.path("lat").isNumber()) insert.setDouble(8, candidate.path("lat").asDouble());
                else insert.setNull(8, java.sql.Types.DOUBLE);
                if (candidate.path("lng").isNumber()) insert.setDouble(9, candidate.path("lng").asDouble());
                else insert.setNull(9, java.sql.Types.DOUBLE);
                insert.setInt(10, candidate.path("durationMin").asInt(90));
                insert.setString(11, candidate.path("cost").asText(null));
                insert.setString(12, candidate.path("evidence").isArray()
                        ? candidate.path("evidence").toString()
                        : candidate.path("evidence").asText(candidate.path("description").asText(null)));
                insert.setString(13, candidate.path("photo").asText(null));
                insert.executeUpdate();
            }
        }
    }

    private void deleteCandidateProjection(Connection conn, String username, int tripId,
                                           ObjectNode operation) throws Exception {
        JsonNode candidate = operation.path("candidate");
        String name = candidate.path("name").asText("").trim();
        String address = candidate.path("address").asText("").trim();
        if (name.isEmpty()) {
            String nodeKey = operation.path("nodeKey").asText("");
            String[] parts = nodeKey.split("\\|", 3);
            if (parts.length >= 2) {
                name = parts[0].trim();
                address = parts[1].trim();
            }
        }
        if (name.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "delete from trip_candidate where username = ? and trip_id = ? and name = ?"
                        + " and coalesce(address, '') = ?")) {
            ps.setString(1, username);
            ps.setInt(2, tripId);
            ps.setString(3, name);
            ps.setString(4, address);
            ps.executeUpdate();
        }
    }

    private void deleteCandidateProjection(Connection conn, String username, int tripId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "delete from trip_candidate where username = ? and trip_id = ?")) {
            ps.setString(1, username);
            ps.setInt(2, tripId);
            ps.executeUpdate();
        }
    }

    private boolean restoreTripCandidates(Connection conn, String username, int tripId,
                                          String candidatesJson) throws Exception {
        if (candidatesJson == null || candidatesJson.trim().isEmpty()) return false;
        JsonNode candidates = JSON.readTree(candidatesJson);
        if (!candidates.isArray()) return false;
        try (PreparedStatement del = conn.prepareStatement(
                "delete from trip_candidate where trip_id = ? and username = ?")) {
            del.setInt(1, tripId);
            del.setString(2, username);
            del.executeUpdate();
        }
        try (PreparedStatement ins = conn.prepareStatement(
                "insert into trip_candidate(username, trip_id, poi_id, name, type, city, address,"
                        + " lat, lng, duration_min, cost, evidence, photo, create_time)"
                        + " values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())")) {
            for (JsonNode item : candidates) {
                ins.setString(1, username);
                ins.setInt(2, tripId);
                ins.setString(3, item.path("poiId").asText(null));
                ins.setString(4, item.path("name").asText(""));
                ins.setString(5, item.path("type").asText(null));
                ins.setString(6, item.path("city").asText(null));
                ins.setString(7, item.path("address").asText(null));
                if (item.path("lat").isNumber()) ins.setDouble(8, item.path("lat").asDouble());
                else ins.setNull(8, java.sql.Types.DOUBLE);
                if (item.path("lng").isNumber()) ins.setDouble(9, item.path("lng").asDouble());
                else ins.setNull(9, java.sql.Types.DOUBLE);
                ins.setInt(10, item.path("durationMin").asInt(0));
                ins.setString(11, item.path("cost").asText(null));
                ins.setString(12, item.path("evidence").asText(null));
                ins.setString(13, item.path("photo").asText(null));
                ins.addBatch();
            }
            ins.executeBatch();
        }
        return true;
    }

    private boolean restoreTripNodes(Connection conn, String username, int tripId,
                                     String nodesJson) throws Exception {
        if (nodesJson == null || nodesJson.trim().isEmpty()) return false;
        JsonNode nodes = JSON.readTree(nodesJson);
        if (!nodes.isArray()) return false;
        try (PreparedStatement del = conn.prepareStatement(
                "delete from trip_node where trip_id = ? and username = ?")) {
            del.setInt(1, tripId);
            del.setString(2, username);
            del.executeUpdate();
        }
        try (PreparedStatement ins = conn.prepareStatement(
                "insert into trip_node(trip_id, username, node_type, title, detail, node_time,"
                        + " status, cost, photo, lat, lng, sort_order, create_time)"
                        + " values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())")) {
            for (JsonNode node : nodes) {
                ins.setInt(1, tripId);
                ins.setString(2, username);
                ins.setString(3, node.path("nodeType").asText("note"));
                ins.setString(4, node.path("title").asText(""));
                ins.setString(5, node.path("detail").asText(""));
                ins.setString(6, node.path("nodeTime").asText(""));
                ins.setString(7, node.path("status").asText(""));
                if (node.path("cost").isNumber()) {
                    ins.setBigDecimal(8, node.path("cost").decimalValue());
                } else {
                    ins.setNull(8, java.sql.Types.DECIMAL);
                }
                ins.setString(9, node.path("photo").asText(null));
                if (node.path("lat").isNumber()) ins.setDouble(10, node.path("lat").asDouble());
                else ins.setNull(10, java.sql.Types.DOUBLE);
                if (node.path("lng").isNumber()) ins.setDouble(11, node.path("lng").asDouble());
                else ins.setNull(11, java.sql.Types.DOUBLE);
                ins.setInt(12, node.path("sortOrder").asInt(0));
                ins.addBatch();
            }
            ins.executeBatch();
        }
        return true;
    }

    private VersionState loadVersion(String username, int tripId, int versionId) {
        try (Connection conn = MySQLUtil.getConnection()) {
            return loadVersion(conn, username, tripId, versionId);
        } catch (Exception e) {
            return null;
        }
    }

    private VersionState loadVersion(Connection conn, String username, int tripId,
                                     int versionId) throws Exception {
        String sql = "select id, trip_id, version_no, title, content, nodes_json, candidates_json,"
                + " plan_data, plan_revision"
                + " from trip_version where id = ? and trip_id = ? and username = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, versionId);
            ps.setInt(2, tripId);
            ps.setString(3, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                VersionState version = new VersionState();
                version.versionId = rs.getInt("id");
                version.tripId = rs.getInt("trip_id");
                version.versionNo = rs.getInt("version_no");
                version.title = rs.getString("title");
                version.content = rs.getString("content");
                version.nodesJson = rs.getString("nodes_json");
                version.candidatesJson = rs.getString("candidates_json");
                version.planData = rs.getString("plan_data");
                version.planRevision = rs.getInt("plan_revision");
                return version;
            }
        }
    }

    private String withValidation(String previewJson, Map<String, Object> validation) throws Exception {
        ObjectNode preview = (ObjectNode) JSON.readTree(previewJson);
        preview.set("validation", JSON.valueToTree(validation));
        return JSON.writeValueAsString(preview);
    }

    private void markOperationFailed(String operationId, String username, String message, String code) {
        try {
            MySQLUtil.executeUpdate(
                    "update plan_operation set status = 'failed', error_message = ?"
                            + " where id = ? and username = ? and status = 'preview'",
                    trim(code + "：" + (message == null ? "" : message), 500), operationId, username);
        } catch (Exception e) {
            log.warn("标记 PlanOperation 失败状态时出错：{}", e.getMessage());
        }
    }

    private int dayMinutesOf(String pace) {
        if ("relaxed".equals(pace)) return 360;
        if ("compact".equals(pace)) return 720;
        return 540;
    }

    private ContextState loadContext(String username, String contextId) {
        String sql = "select trip_id, selected_node_key, plan_revision, expires_at from plan_context"
                + " where id = ? and username = ?";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, contextId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                ContextState c = new ContextState();
                c.contextId = contextId;
                c.tripId = rs.getInt("trip_id");
                c.selectedNodeKey = rs.getString("selected_node_key");
                c.planRevision = rs.getInt("plan_revision");
                c.expiresAt = rs.getTimestamp("expires_at").toLocalDateTime();
                return c;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private TripState loadTrip(String username, int tripId) {
        String sql = "select t.username as owner, t.title, t.content, t.plan_data, t.plan_revision,"
                + " t.version, t.budget, t.people, t.pace, t.destination, t.start_date,"
                + " t.end_date, t.days"
                + " from trip t where t.id = ? and (t.username = ? or exists ("
                + " select 1 from trip_collaborator c where c.trip_id = t.id"
                + " and c.username = ? and c.status = 'active'))";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ps.setString(2, username);
            ps.setString(3, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                TripState t = new TripState();
                t.tripId = tripId;
                t.owner = rs.getString("owner");
                t.title = rs.getString("title");
                t.content = rs.getString("content");
                t.planData = rs.getString("plan_data");
                t.planRevision = rs.getInt("plan_revision");
                t.version = rs.getInt("version");
                t.budget = rs.getString("budget");
                t.people = rs.getString("people");
                t.pace = rs.getString("pace");
                t.destination = rs.getString("destination");
                t.startDate = rs.getString("start_date");
                t.endDate = rs.getString("end_date");
                t.days = rs.getInt("days");
                return t;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> conflict(Map<String, Object> out, String code,
                                         String message, int revision) {
        out.put("status", "conflict");
        out.put("code", code);
        out.put("message", message);
        if (revision > 0) out.put("planRevision", revision);
        return out;
    }

    private Map<String, Object> fail(Map<String, Object> out, String message) {
        out.put("status", "error");
        out.put("message", message);
        return out;
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        String s = value.trim();
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String format(LocalDateTime value) {
        return value == null ? "" : value.format(TIME_FORMAT);
    }

    private String formatTime(Timestamp value) {
        return value == null ? "" : value.toLocalDateTime().format(TIME_FORMAT);
    }

    private static final class ContextState {
        private String contextId;
        private int tripId;
        private String selectedNodeKey;
        private int planRevision;
        private LocalDateTime expiresAt;
    }

    private static final class TripState {
        private int tripId;
        private String owner;
        private String title;
        private String content;
        private String planData;
        private int planRevision;
        private int version;
        private String budget;
        private String people;
        private String pace;
        private String destination;
        private String startDate;
        private String endDate;
        private int days;
    }

    private static final class SnapshotState {
        private final int versionId;
        private final int versionNo;

        private SnapshotState(int versionId, int versionNo) {
            this.versionId = versionId;
            this.versionNo = versionNo;
        }
    }

    private static final class ConstraintState {
        private String people;
        private String budget;
        private String pace;
    }

    private static final class MetaState {
        private String title;
        private String destination;
        private String start;
        private String end;
        private int days;
    }

    private static final class VersionState {
        private int versionId;
        private int tripId;
        private int versionNo;
        private String title;
        private String content;
        private String nodesJson;
        private String candidatesJson;
        private String planData;
        private int planRevision;
    }

    private static final class ItemPosition {
        private final int day;
        private final String slot;
        private final int index;
        private final JsonNode node;
        private final ArrayNode container;

        private ItemPosition(int day, String slot, int index, JsonNode node, ArrayNode container) {
            this.day = day;
            this.slot = slot;
            this.index = index;
            this.node = node;
            this.container = container;
        }
    }
}
