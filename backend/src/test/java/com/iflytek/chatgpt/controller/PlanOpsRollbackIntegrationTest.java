package com.iflytek.chatgpt.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iflytek.chatgpt.agent.CoordConverter;
import com.iflytek.chatgpt.agent.tool.TravelTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import util.MySQLUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PlanOpsRollbackIntegrationTest {

    static {
        System.setProperty("db.url",
                "jdbc:h2:mem:planops_rollback_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                        + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        System.setProperty("db.driver", "org.h2.Driver");
        System.setProperty("db.username", "sa");
        System.setProperty("db.password", "");
        System.setProperty("geo.amap-key", "");
        System.setProperty("weather.enabled", "false");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private PlanDraftController draftController;

    @Autowired
    private PlanOpsController planOpsController;

    @Autowired
    private TravelTools travelTools;

    @Autowired
    private PlanPoolController planPoolController;

    @Autowired
    private PlanDayOptimizer dayOptimizer;

    @Autowired
    private PlanPoiController planPoiController;

    @Autowired
    private PlanSpatialController planSpatialController;

    @Autowired
    private CollaborationController collaborationController;

    @Autowired
    private PlanTodayController planTodayController;

    @Autowired
    private PlanDataSourceHealthController planDataSourceHealthController;

    @Autowired
    private PlanExternalPoiController planExternalPoiController;

    @Autowired
    private PlanCommercialPoiService planCommercialPoiService;

    @Test
    void previewApplyHistoryAndRollbackKeepOneRevisionChain() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ChatController.SESSION_USER, "planops-e2e-user");

        ObjectNode nodeA = JSON.createObjectNode()
                .put("name", "测试公园")
                .put("address", "测试路1号")
                .put("lat", 23.111)
                .put("lng", 113.222)
                .put("category", "景点")
                .put("durationMin", 60);
        ObjectNode nodeB = JSON.createObjectNode()
                .put("name", "测试餐厅")
                .put("address", "测试路2号")
                .put("lat", 23.112)
                .put("lng", 113.223)
                .put("category", "美食")
                .put("durationMin", 60);
        ObjectNode morning = JSON.createObjectNode().put("date", "2026-11-10");
        morning.set("morning", JSON.createArrayNode().add(nodeA));
        morning.set("afternoon", JSON.createArrayNode());
        morning.set("evening", JSON.createArrayNode());
        ObjectNode afternoon = JSON.createObjectNode().put("date", "2026-11-11");
        afternoon.set("morning", JSON.createArrayNode());
        afternoon.set("afternoon", JSON.createArrayNode().add(nodeB));
        afternoon.set("evening", JSON.createArrayNode());

        ObjectNode plan = JSON.createObjectNode();
        plan.set("chosen", JSON.createArrayNode().add(nodeA).add(nodeB));
        plan.set("itinerary", JSON.createArrayNode().add(morning).add(afternoon));
        plan.set("locked", JSON.createObjectNode());
        plan.put("hotelName", "");
        plan.set("quickPrefs", JSON.createArrayNode().add("少走路"));
        plan.set("filter", JSON.createObjectNode());
        plan.put("origin", "广州");
        plan.put("destinationLabel", "厦门");

        Map<String, Object> saved = draftController.save(
                0, "PlanOps 回退测试", "厦门", "厦门", "广州",
                "2026-11-10", "2026-11-11", 2, "3000", "2 人",
                "balanced", 3, plan.toString(), session);
        assertEquals("success", saved.get("status"));
        int tripId = ((Number) saved.get("id")).intValue();
        assertTrue(travelTools.updateTrip("planops-e2e-user", tripId, "不应覆盖", "绕过写入")
                .contains("PlanOps"));

        String nodeKey = "测试公园|测试路1号|23.111|113.222";
        Map<String, Object> context = planOpsController.contextForUser(
                "planops-e2e-user", tripId, "plan", 0, "morning", nodeKey, "");
        @SuppressWarnings("unchecked")
        Map<String, Object> contextData = (Map<String, Object>) context.get("context");
        JsonNode contextNodes = (JsonNode) contextData.get("nodes");
        assertEquals(2, contextNodes.size());
        assertEquals(nodeKey, contextNodes.get(0).path("nodeKey").asText());
        int initialRevision = ((Number) contextData.get("planRevision")).intValue();
        String contextId = String.valueOf(contextData.get("contextId"));

        ObjectNode remove = JSON.createObjectNode()
                .put("type", "REMOVE_NODE")
                .put("expectedRevision", initialRevision)
                .put("nodeKey", nodeKey)
                .put("reason", "自动化删除测试");
        Map<String, Object> preview = planOpsController.previewForUser(
                "planops-e2e-user", contextId, remove.toString(), "click");
        assertEquals("success", preview.get("status"));

        String operationId = String.valueOf(preview.get("operationId"));
        String approvalToken = String.valueOf(preview.get("approvalToken"));
        Map<String, Object> applied = planOpsController.applyForUser(
                "planops-e2e-user", operationId, approvalToken, initialRevision);
        assertEquals("success", applied.get("status"));
        assertEquals(initialRevision + 1, ((Number) applied.get("planRevision")).intValue());

        Map<String, Object> duplicate = planOpsController.applyForUser(
                "planops-e2e-user", operationId, approvalToken, initialRevision);
        assertEquals("error", duplicate.get("status"));

        Map<String, Object> history = planOpsController.history(tripId, session);
        List<Map<String, Object>> items = castList(history.get("items"));
        Map<String, Object> operation = items.stream()
                .filter(item -> operationId.equals(item.get("id")))
                .findFirst()
                .orElseThrow(IllegalStateException::new);
        int beforeVersionId = ((Number) operation.get("beforeVersionId")).intValue();
        assertTrue(beforeVersionId > 0);
        assertEquals(true, operation.get("canUndo"));

        Map<String, Object> context2 = planOpsController.contextForUser(
                "planops-e2e-user", tripId, "plan", 0, "morning", nodeKey, "");
        @SuppressWarnings("unchecked")
        Map<String, Object> context2Data = (Map<String, Object>) context2.get("context");
        ObjectNode rollback = JSON.createObjectNode()
                .put("type", "ROLLBACK_PLAN_VERSION")
                .put("expectedRevision", ((Number) context2Data.get("planRevision")).intValue())
                .put("targetVersionId", beforeVersionId)
                .put("reason", "自动化撤销测试");
        Map<String, Object> rollbackPreview = planOpsController.previewForUser(
                "planops-e2e-user", String.valueOf(context2Data.get("contextId")),
                rollback.toString(), "click");
        assertEquals("success", rollbackPreview.get("status"));

        Map<String, Object> rolledBack = planOpsController.applyForUser(
                "planops-e2e-user", String.valueOf(rollbackPreview.get("operationId")),
                String.valueOf(rollbackPreview.get("approvalToken")),
                ((Number) rollbackPreview.get("expectedRevision")).intValue());
        assertEquals("success", rolledBack.get("status"));

        Map<String, Object> finalDraft = draftController.get(tripId, session);
        @SuppressWarnings("unchecked")
        Map<String, Object> finalDraftData = (Map<String, Object>) finalDraft.get("draft");
        JsonNode finalPlan = JSON.readTree(String.valueOf(finalDraftData.get("planData")));
        assertEquals(2, finalPlan.path("itinerary").get(0).path("morning").size()
                + finalPlan.path("itinerary").get(1).path("afternoon").size());

        Map<String, Object> finalHistory = planOpsController.history(tripId, session);
        assertTrue(((Number) finalHistory.get("currentVersionNo")).intValue() >= 4);
        int latestVersionId = ((Number) castList(finalHistory.get("versions")).get(0).get("id")).intValue();
        assertTrue(travelTools.rollbackTrip("planops-e2e-user", latestVersionId).contains("PlanOps"));
    }

    @Test
    void candidateAndConstraintMutationsUsePlanOps() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ChatController.SESSION_USER, "planops-mutation-user");

        ObjectNode emptyDay1 = JSON.createObjectNode().put("date", "2026-11-10");
        emptyDay1.set("morning", JSON.createArrayNode());
        emptyDay1.set("afternoon", JSON.createArrayNode());
        emptyDay1.set("evening", JSON.createArrayNode());
        ObjectNode emptyDay2 = JSON.createObjectNode().put("date", "2026-11-11");
        emptyDay2.set("morning", JSON.createArrayNode());
        emptyDay2.set("afternoon", JSON.createArrayNode());
        emptyDay2.set("evening", JSON.createArrayNode());

        ObjectNode constraints = JSON.createObjectNode()
                .put("people", "2 人")
                .put("budget", "3000")
                .put("pace", "balanced");
        ObjectNode plan = JSON.createObjectNode();
        plan.set("chosen", JSON.createArrayNode());
        plan.set("itinerary", JSON.createArrayNode().add(emptyDay1).add(emptyDay2));
        plan.set("locked", JSON.createObjectNode());
        plan.put("hotelName", "");
        plan.set("quickPrefs", JSON.createArrayNode());
        plan.set("filter", JSON.createObjectNode());
        plan.set("constraints", constraints);
        plan.put("origin", "广州");
        plan.put("destinationLabel", "厦门");

        Map<String, Object> saved = draftController.save(
                0, "PlanOps 扩展操作测试", "厦门", "厦门", "广州",
                "2026-11-10", "2026-11-11", 2, "3000", "2 人",
                "balanced", 3, plan.toString(), session);
        int tripId = ((Number) saved.get("id")).intValue();

        ObjectNode candidate = JSON.createObjectNode()
                .put("name", "测试咖啡")
                .put("address", "测试路3号")
                .put("lat", 23.113)
                .put("lng", 113.224)
                .put("category", "美食")
                .put("durationMin", 45);

        ObjectNode addCandidate = JSON.createObjectNode();
        addCandidate.set("candidate", candidate);
        Map<String, Object> addCandidateApplied = approve("planops-mutation-user", tripId,
                "ADD_CANDIDATE", addCandidate, session);
        assertEquals("success", addCandidateApplied.get("status"));
        assertEquals(1, castList(planPoolController.list(tripId, "", session).get("items")).size());

        ObjectNode addNode = JSON.createObjectNode()
                .put("targetDay", 0)
                .put("targetSlot", "afternoon");
        addNode.set("node", candidate);
        assertEquals("success", approve("planops-mutation-user", tripId,
                "ADD_NODE", addNode, session).get("status"));

        ObjectNode updateConstraint = JSON.createObjectNode()
                .put("people", "4 人")
                .put("pace", "relaxed");
        updateConstraint.set("quickPrefs", JSON.createArrayNode().add("室内优先"));
        assertEquals("success", approve("planops-mutation-user", tripId,
                "UPDATE_CONSTRAINT", updateConstraint, session).get("status"));

        ObjectNode updateBudget = JSON.createObjectNode().put("budget", "8000");
        assertEquals("success", approve("planops-mutation-user", tripId,
                "UPDATE_BUDGET", updateBudget, session).get("status"));

        ObjectNode stay = JSON.createObjectNode()
                .put("name", "测试住宿区")
                .put("lat", 23.114)
                .put("lng", 113.225);
        ObjectNode updateStay = JSON.createObjectNode().put("hotelName", "测试酒店");
        updateStay.set("stay", stay);
        assertEquals("success", approve("planops-mutation-user", tripId,
                "UPDATE_STAY", updateStay, session).get("status"));

        Map<String, Object> changed = draftController.get(tripId, session);
        @SuppressWarnings("unchecked")
        Map<String, Object> changedDraft = (Map<String, Object>) changed.get("draft");
        JsonNode changedPlan = JSON.readTree(String.valueOf(changedDraft.get("planData")));
        assertEquals(1, changedPlan.path("chosen").size());
        assertEquals("测试咖啡", changedPlan.path("itinerary").get(0)
                .path("afternoon").get(0).path("name").asText());
        assertEquals("4 人", changedDraft.get("people"));
        assertEquals("8000", changedDraft.get("budget"));
        assertEquals("relaxed", changedDraft.get("pace"));
        assertEquals("测试住宿区", changedPlan.path("stay").path("name").asText());
        assertEquals("测试酒店", changedPlan.path("hotelName").asText());

        ObjectNode removeCandidate = JSON.createObjectNode()
                .put("nodeKey", "测试咖啡|测试路3号|23.113|113.224");
        assertEquals("success", approve("planops-mutation-user", tripId,
                "REMOVE_CANDIDATE", removeCandidate, session).get("status"));

        Map<String, Object> removed = draftController.get(tripId, session);
        @SuppressWarnings("unchecked")
        Map<String, Object> removedDraft = (Map<String, Object>) removed.get("draft");
        JsonNode removedPlan = JSON.readTree(String.valueOf(removedDraft.get("planData")));
        assertEquals(0, removedPlan.path("chosen").size());
        assertEquals(0, removedPlan.path("itinerary").get(0).path("afternoon").size());
        assertEquals(0, castList(planPoolController.list(tripId, "", session).get("items")).size());

        ObjectNode rollbackCandidate = JSON.createObjectNode()
                .put("targetVersionId", ((Number) addCandidateApplied.get("versionId")).intValue());
        assertEquals("success", approve("planops-mutation-user", tripId,
                "ROLLBACK_PLAN_VERSION", rollbackCandidate, session).get("status"));
        assertEquals(1, castList(planPoolController.list(tripId, "", session).get("items")).size());
    }

    @Test
    void rebuildItineraryUsesBatchPlanOperation() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ChatController.SESSION_USER, "planops-rebuild-user");

        ObjectNode emptyDay = JSON.createObjectNode().put("date", "2026-11-10");
        emptyDay.set("morning", JSON.createArrayNode());
        emptyDay.set("afternoon", JSON.createArrayNode());
        emptyDay.set("evening", JSON.createArrayNode());
        ObjectNode plan = JSON.createObjectNode();
        plan.set("chosen", JSON.createArrayNode());
        plan.set("itinerary", JSON.createArrayNode().add(emptyDay));
        ObjectNode locked = JSON.createObjectNode();
        locked.put("重建节点|重建路1号|24.1|118.1", true);
        plan.set("locked", locked);
        plan.set("quickPrefs", JSON.createArrayNode());
        plan.set("filter", JSON.createObjectNode());
        plan.put("origin", "广州");
        plan.put("destinationLabel", "厦门");
        Map<String, Object> saved = draftController.save(
                0, "PlanOps 骨架重建测试", "厦门", "厦门", "广州",
                "2026-11-10", "2026-11-11", 2, "3000", "2 人",
                "balanced", 3, plan.toString(), session);
        int tripId = ((Number) saved.get("id")).intValue();

        ObjectNode node = JSON.createObjectNode()
                .put("name", "重建节点")
                .put("address", "重建路1号")
                .put("lat", 24.1)
                .put("lng", 118.1)
                .put("category", "景点")
                .put("durationMin", 60)
                .put("openTime", "09:00-22:00");
        ObjectNode node2 = JSON.createObjectNode()
                .put("name", "重建餐厅")
                .put("address", "重建路2号")
                .put("lat", 24.102)
                .put("lng", 118.103)
                .put("category", "美食")
                .put("durationMin", 60)
                .put("openTime", "18:00-22:00");
        ObjectNode day1 = JSON.createObjectNode().put("date", "2026-11-16");
        day1.set("morning", JSON.createArrayNode().add(node).add(node2));
        day1.set("afternoon", JSON.createArrayNode());
        day1.set("evening", JSON.createArrayNode());
        ObjectNode day2 = JSON.createObjectNode().put("date", "2026-11-11");
        day2.set("morning", JSON.createArrayNode());
        day2.set("afternoon", JSON.createArrayNode());
        day2.set("evening", JSON.createArrayNode());
        ObjectNode rebuild = JSON.createObjectNode().put("variant", "classic");
        rebuild.set("itinerary", JSON.createArrayNode().add(day1).add(day2));
        assertEquals("success", approve("planops-rebuild-user", tripId,
                "REBUILD_ITINERARY", rebuild, session).get("status"));

        Map<String, Object> rebuilt = draftController.get(tripId, session);
        @SuppressWarnings("unchecked")
        Map<String, Object> rebuiltDraft = (Map<String, Object>) rebuilt.get("draft");
        JsonNode rebuiltPlan = JSON.readTree(String.valueOf(rebuiltDraft.get("planData")));
        assertEquals(2, rebuiltPlan.path("itinerary").size());
        assertEquals("重建节点", rebuiltPlan.path("itinerary").get(0)
                .path("morning").get(0).path("name").asText());

        Map<String, Object> optimizeContext = planOpsController.contextForUser(
                "planops-rebuild-user", tripId, "plan", 0, "", "", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> optimizeContextData = (Map<String, Object>) optimizeContext.get("context");
        Map<String, Object> optimized = planOpsController.optimizeDayForUser(
                "planops-rebuild-user",
                String.valueOf(optimizeContextData.get("contextId")), 0);
        assertEquals("success", optimized.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> beforeMetrics = (Map<String, Object>) optimized.get("before");
        @SuppressWarnings("unchecked")
        Map<String, Object> weather = (Map<String, Object>) beforeMetrics.get("weather");
        assertEquals(false, weather.get("available"));
        assertEquals(1, ((Number) beforeMetrics.get("lockedCount")).intValue());
        assertEquals(1, ((Number) beforeMetrics.get("openingConflictCount")).intValue());
        assertTrue(((Number) beforeMetrics.get("fallbackRouteCount")).intValue() >= 1);
        List<Map<String, Object>> options = castList(optimized.get("options"));
        assertTrue(options.size() >= 2);
        assertTrue(((Map<?, ?>) options.get(0).get("metrics")).containsKey("travelMinutes"));
        Map<String, Object> option = options.get(0);
        assertEquals("success", planOpsController.applyForUser(
                "planops-rebuild-user", String.valueOf(option.get("operationId")),
                String.valueOf(option.get("approvalToken")),
                ((Number) option.get("expectedRevision")).intValue()).get("status"));
        Map<String, Object> critic = planOpsController.dayCritic(tripId, 0, session);
        assertEquals("success", critic.get("status"));
        assertTrue(critic.get("critic") instanceof Map);
        Map<String, Object> allDaysCritic = planOpsController.planCritic(tripId, session);
        assertEquals("success", allDaysCritic.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> criticData = (Map<String, Object>) allDaysCritic.get("critic");
        assertEquals("all-days", criticData.get("scope"));
        assertTrue(castList(criticData.get("findings")).size() <= 3);
        assertEquals(2, castList(criticData.get("days")).size());
    }

    @Test
    void planMetaMutationResetsPlanAndRebuildsDates() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ChatController.SESSION_USER, "planops-meta-user");

        ObjectNode day = JSON.createObjectNode().put("date", "2026-11-10");
        day.set("morning", JSON.createArrayNode());
        day.set("afternoon", JSON.createArrayNode());
        day.set("evening", JSON.createArrayNode());
        ObjectNode candidate = JSON.createObjectNode()
                .put("name", "旧候选")
                .put("category", "景点");
        ObjectNode plan = JSON.createObjectNode();
        plan.set("chosen", JSON.createArrayNode().add(candidate));
        plan.set("itinerary", JSON.createArrayNode().add(day));
        plan.set("locked", JSON.createObjectNode());
        plan.set("quickPrefs", JSON.createArrayNode());
        plan.set("filter", JSON.createObjectNode());
        plan.put("origin", "广州");
        plan.put("destinationLabel", "厦门");
        Map<String, Object> saved = draftController.save(
                0, "旧规划", "厦门", "厦门", "广州",
                "2026-11-10", "2026-11-10", 1, "3000", "2 人",
                "balanced", 3, plan.toString(), session);
        int tripId = ((Number) saved.get("id")).intValue();

        ObjectNode addCandidate = JSON.createObjectNode();
        addCandidate.set("candidate", candidate);
        assertEquals("success", approve("planops-meta-user", tripId,
                "ADD_CANDIDATE", addCandidate, session).get("status"));
        assertEquals(1, castList(planPoolController.list(tripId, "", session).get("items")).size());

        ObjectNode meta = JSON.createObjectNode()
                .put("destination", "泉州")
                .put("destinationLabel", "泉州")
                .put("start", "2026-12-01")
                .put("end", "2026-12-03")
                .put("days", 3)
                .put("origin", "深圳")
                .put("title", "泉州 · 3 天旅行规划")
                .put("resetPlan", true);
        assertEquals("success", approve("planops-meta-user", tripId,
                "UPDATE_PLAN_META", meta, session).get("status"));

        Map<String, Object> changed = draftController.get(tripId, session);
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = (Map<String, Object>) changed.get("draft");
        assertEquals("泉州", draft.get("destination"));
        assertEquals("2026-12-01", draft.get("start"));
        assertEquals("2026-12-03", draft.get("end"));
        assertEquals(3, ((Number) draft.get("days")).intValue());
        JsonNode changedPlan = JSON.readTree(String.valueOf(draft.get("planData")));
        assertEquals(3, changedPlan.path("itinerary").size());
        assertEquals(0, changedPlan.path("chosen").size());
        assertEquals("2026-12-03", changedPlan.path("itinerary").get(2).path("date").asText());
        assertEquals("深圳", changedPlan.path("meta").path("origin").asText());
        assertEquals(0, castList(planPoolController.list(tripId, "", session).get("items")).size());
    }

    @Test
    void planMetaCanAddDaysWithoutLosingChosenPois() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ChatController.SESSION_USER, "planops-add-days-user");
        ObjectNode chosen = JSON.createObjectNode()
                .put("name", "保留候选")
                .put("address", "保留路1号")
                .put("lat", 24.1)
                .put("lng", 118.1)
                .put("category", "景点")
                .put("durationMin", 60);
        ObjectNode day = JSON.createObjectNode().put("date", "2026-12-01");
        day.set("morning", JSON.createArrayNode().add(chosen.deepCopy()));
        day.set("afternoon", JSON.createArrayNode());
        day.set("evening", JSON.createArrayNode());
        ObjectNode plan = JSON.createObjectNode();
        plan.set("chosen", JSON.createArrayNode().add(chosen));
        plan.set("itinerary", JSON.createArrayNode().add(day));
        plan.set("locked", JSON.createObjectNode());
        plan.set("quickPrefs", JSON.createArrayNode());
        Map<String, Object> saved = draftController.save(
                0, "增加天数测试", "厦门", "厦门", "广州",
                "2026-12-01", "2026-12-01", 1, "3000", "2 人",
                "balanced", 3, plan.toString(), session);
        int tripId = ((Number) saved.get("id")).intValue();
        ObjectNode meta = JSON.createObjectNode()
                .put("start", "2026-12-01")
                .put("end", "2026-12-03")
                .put("days", 3)
                .put("resetPlan", false);
        assertEquals("success", approve("planops-add-days-user", tripId,
                "UPDATE_PLAN_META", meta, session).get("status"));
        Map<String, Object> draft = draftController.get(tripId, session);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) draft.get("draft");
        JsonNode updated = JSON.readTree(String.valueOf(data.get("planData")));
        assertEquals(3, updated.path("itinerary").size());
        assertEquals(1, updated.path("chosen").size());
        assertEquals("2026-12-03", updated.path("itinerary").get(2).path("date").asText());
    }

    @Test
    void dayOptimizerCanSpillOverflowToNextDayWhileKeepingLocks() {
        ObjectNode day1 = JSON.createObjectNode().put("date", "2026-11-16");
        ObjectNode day2 = JSON.createObjectNode().put("date", "2026-11-11");
        ArrayNode morning1 = JSON.createArrayNode();
        for (int i = 0; i < 7; i++) {
            morning1.add(JSON.createObjectNode()
                    .put("name", "节点" + i)
                    .put("address", "路线" + i)
                    .put("lat", 24.0 + i * 0.01)
                    .put("lng", 118.0 + i * 0.01)
                    .put("category", "景点")
                    .put("type", "公园")
                    .put("durationMin", 90)
                    .put("openTime", i == 0 ? "周一闭馆 09:00-22:00"
                            : "09:00-12:00,13:30-22:00"));
        }
        day1.set("morning", morning1);
        day1.set("afternoon", JSON.createArrayNode());
        day1.set("evening", JSON.createArrayNode());
        day2.set("morning", JSON.createArrayNode());
        day2.set("afternoon", JSON.createArrayNode());
        day2.set("evening", JSON.createArrayNode());
        ObjectNode root = JSON.createObjectNode();
        root.set("itinerary", JSON.createArrayNode().add(day1).add(day2));
        ObjectNode locked = root.putObject("locked");
        locked.put("节点0|路线0|24.0|118.0", true);
        ObjectNode closures = root.putObject("closures");
        closures.put("节点2|路线2|24.02|118.02", true);
        root.putObject("holidayDays").put("2026-11-16", true);

        List<PlanDayOptimizer.Variant> variants = dayOptimizer.buildVariants(root, 0, "balanced");
        PlanDayOptimizer.Variant nextDay = variants.stream()
                .filter(v -> "NEXT_DAY".equals(v.getId())).findFirst()
                .orElseThrow(IllegalStateException::new);
        ArrayNode result = nextDay.getItinerary();
        int day1Minutes = 0;
        for (String slot : new String[]{"morning", "afternoon", "evening"}) {
            for (JsonNode item : result.get(0).path(slot)) {
                day1Minutes += item.path("durationMin").asInt(0);
            }
        }
        assertTrue(day1Minutes <= 540);
        assertTrue(result.get(1).path("morning").size()
                + result.get(1).path("afternoon").size()
                + result.get(1).path("evening").size() > 0);
        assertEquals("节点0", result.get(0).path("morning").get(0).path("name").asText());
        Map<String, Object> walking = dayOptimizer.metrics(root, 0, "walking", "厦门");
        assertEquals("walking", walking.get("travelMode"));
        assertEquals(1, ((Number) walking.get("openingConflictCount")).intValue());
        assertEquals(0, ((Number) walking.get("unknownOpeningCount")).intValue());
        assertEquals(1, ((Number) walking.get("closedCount")).intValue());
        assertTrue(variants.stream().anyMatch(v -> "CLOSURE_SAFE".equals(v.getId())));
        assertEquals(true, walking.get("highCrowdDay"));
        assertTrue(variants.stream().anyMatch(v -> "HOLIDAY_SAFE".equals(v.getId())));
    }

    @Test
    void firstDayTransportCanBeExcludedFromRankingAndCritic() {
        ObjectNode day1 = JSON.createObjectNode().put("date", "2026-11-20");
        day1.set("morning", JSON.createArrayNode().add(JSON.createObjectNode()
                .put("name", "首日景点").put("category", "景点").put("durationMin", 60)));
        day1.set("afternoon", JSON.createArrayNode());
        day1.set("evening", JSON.createArrayNode());
        ObjectNode day2 = JSON.createObjectNode().put("date", "2026-11-21");
        day2.set("morning", JSON.createArrayNode());
        day2.set("afternoon", JSON.createArrayNode());
        day2.set("evening", JSON.createArrayNode());
        ObjectNode root = JSON.createObjectNode();
        root.set("itinerary", JSON.createArrayNode().add(day1).add(day2));
        root.set("locked", JSON.createObjectNode());
        ObjectNode transport = root.putObject("transport");
        ObjectNode outbound = transport.putObject("outbound");
        outbound.put("mode", "高铁");
        outbound.put("arriveAt", "18:00");
        outbound.put("toStayMinutes", 30);
        outbound.put("includeFirstDay", true);

        Map<String, Object> included = dayOptimizer.metrics(root, 0, "driving", "厦门");
        assertTrue(castList(included.get("transportBlockedSlots")).contains("morning"));
        outbound.put("includeFirstDay", false);
        Map<String, Object> excluded = dayOptimizer.metrics(root, 0, "driving", "厦门");
        assertTrue(castList(excluded.get("transportBlockedSlots")).isEmpty());
    }

    @Test
    void transportBudgetAndUgcEnterPlanningConstraints() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ChatController.SESSION_USER, "planops-stage2-user");
        ObjectNode day1 = JSON.createObjectNode().put("date", "2026-11-20");
        ObjectNode morning = JSON.createObjectNode()
                .put("name", "厦门测试店")
                .put("address", "测试路1号")
                .put("lat", 24.1)
                .put("lng", 118.1)
                .put("category", "美食")
                .put("cost", "200")
                .put("durationMin", 90);
        day1.set("morning", JSON.createArrayNode().add(morning));
        day1.set("afternoon", JSON.createArrayNode());
        day1.set("evening", JSON.createArrayNode());
        ObjectNode day2 = JSON.createObjectNode().put("date", "2026-11-21");
        day2.set("morning", JSON.createArrayNode());
        day2.set("afternoon", JSON.createArrayNode().add(JSON.createObjectNode()
                .put("name", "返程前景点")
                .put("category", "景点")
                .put("cost", "300")
                .put("durationMin", 120)));
        day2.set("evening", JSON.createArrayNode());
        ObjectNode plan = JSON.createObjectNode();
        plan.set("chosen", JSON.createArrayNode().add(morning));
        plan.set("itinerary", JSON.createArrayNode().add(day1).add(day2));
        plan.set("locked", JSON.createObjectNode());
        plan.set("quickPrefs", JSON.createArrayNode());
        plan.set("filter", JSON.createObjectNode());
        plan.put("origin", "广州");
        plan.put("destinationLabel", "厦门");
        Map<String, Object> saved = draftController.save(
                0, "阶段 2 约束测试", "厦门", "厦门", "广州",
                "2026-11-20", "2026-11-21", 2, "3000", "2 人",
                "balanced", 3, plan.toString(), session);
        int tripId = ((Number) saved.get("id")).intValue();

        ObjectNode transport = JSON.createObjectNode();
        ObjectNode outbound = transport.putObject("outbound");
        outbound.put("mode", "高铁").put("departAt", "10:00").put("arriveAt", "18:00")
                .put("station", "厦门北站").put("toStayMinutes", 40).put("cost", 500);
        ObjectNode inbound = transport.putObject("inbound");
        inbound.put("mode", "高铁").put("departAt", "12:00").put("arriveAt", "18:00")
                .put("station", "厦门北站").put("fromStayMinutes", 40).put("cost", 500);
        ObjectNode transportOp = JSON.createObjectNode();
        transportOp.set("transport", transport);
        Map<String, Object> transportApplied = approve("planops-stage2-user", tripId,
                "UPDATE_TRANSPORT", transportOp, session);
        assertEquals("success", transportApplied.get("status"));

        ObjectNode noFirstDayTransport = transport.deepCopy();
        noFirstDayTransport.with("outbound").put("includeFirstDay", false);
        ObjectNode noFirstDayTransportOp = JSON.createObjectNode();
        noFirstDayTransportOp.set("transport", noFirstDayTransport);
        Map<String, Object> previewContext = planOpsController.contextForUser(
                "planops-stage2-user", tripId, "plan", 0, "", "", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> previewContextData =
                (Map<String, Object>) previewContext.get("context");
        noFirstDayTransportOp.put("type", "UPDATE_TRANSPORT");
        noFirstDayTransportOp.put("expectedRevision",
                ((Number) previewContextData.get("planRevision")).intValue());
        Map<String, Object> noFirstDayPreview = planOpsController.previewForUser(
                "planops-stage2-user", String.valueOf(previewContextData.get("contextId")),
                noFirstDayTransportOp.toString(), "click");
        @SuppressWarnings("unchecked")
        Map<String, Object> noFirstDayValidation =
                (Map<String, Object>) noFirstDayPreview.get("validation");
        @SuppressWarnings("unchecked")
        Map<String, Object> noFirstDayTransportValidation =
                (Map<String, Object>) noFirstDayValidation.get("transport");
        assertEquals(false, noFirstDayTransportValidation.get("firstDayIncluded"));
        assertEquals("", noFirstDayTransportValidation.get("outboundReadyAt"));

        ObjectNode budgetPlan = JSON.createObjectNode().put("total", 900);
        ObjectNode categories = budgetPlan.putObject("categories");
        categories.put("transport", 700).put("lodging", 0)
                .put("food", 50).put("ticket", 50).put("local", 50);
        ObjectNode budgetOp = JSON.createObjectNode();
        budgetOp.set("budgetPlan", budgetPlan);
        Map<String, Object> budgetApplied = approve("planops-stage2-user", tripId,
                "UPDATE_BUDGET_PLAN", budgetOp, session);
        @SuppressWarnings("unchecked")
        Map<String, Object> validation = (Map<String, Object>) budgetApplied.get("validation");
        assertTrue(validation.get("transport") instanceof Map);
        assertTrue(validation.get("budget") instanceof Map);

        Map<String, Object> optimizeContext = planOpsController.contextForUser(
                "planops-stage2-user", tripId, "plan", 0, "", "", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> optimizeContextData = (Map<String, Object>) optimizeContext.get("context");
        Map<String, Object> optimized = planOpsController.optimizeDayForUser(
                "planops-stage2-user",
                String.valueOf(optimizeContextData.get("contextId")), 0);
        assertEquals("success", optimized.get("status"));
        assertTrue(castList(optimized.get("options")).stream()
                .anyMatch(option -> "TRANSPORT_SAFE".equals(option.get("id"))));
        Map<String, Object> critic = planOpsController.planCritic(tripId, session);
        @SuppressWarnings("unchecked")
        Map<String, Object> criticData = (Map<String, Object>) critic.get("critic");
        assertTrue(castList(criticData.get("days")).stream()
                .anyMatch(day -> !castList(day.get("transportBlockedSlots")).isEmpty()));

        MySQLUtil.executeUpdate(
                "insert into poi_review(poi_id, poi_name, username, rating, content, visibility, create_time)"
                        + " values(?, ?, ?, ?, ?, 'public', now())",
                "test-poi", "厦门测试店", "planops-stage2-user", 5, "本地体验很好");
        MySQLUtil.executeUpdate(
                "insert into travel_log(username, place, rating, note, visibility, log_time, create_time)"
                        + " values(?, ?, 'like', ?, 'public', now(), now())",
                "planops-stage2-user", "厦门测试店", "去过");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", "厦门测试店");
        item.put("poiId", "test-poi");
        item.put("evidence", new ArrayList<>());
        item.put("preferenceMatch", 0);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", new ArrayList<>(List.of(item)));
        Map<String, Object> enriched = planPoiController.enrichUgc(
                payload, "planops-stage2-user");
        Map<String, Object> enrichedItem = castList(enriched.get("items")).get(0);
        assertEquals(1, ((Number) enrichedItem.get("ugcReviewCount")).intValue());
        assertEquals(1, ((Number) enrichedItem.get("ugcCheckinCount")).intValue());
        assertTrue(String.valueOf(enrichedItem.get("evidence")).contains("自建体验"));
    }

    @Test
    void spatialClustersStayScoresAndRouteTimesArePersisted() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ChatController.SESSION_USER, "planops-spatial-user");
        ObjectNode n1 = JSON.createObjectNode().put("name", "空间点1")
                .put("address", "思明区测试路1号").put("lat", 24.10).put("lng", 118.10)
                .put("category", "景点").put("durationMin", 60);
        ObjectNode n2 = JSON.createObjectNode().put("name", "空间点2")
                .put("address", "思明区测试路2号").put("lat", 24.101).put("lng", 118.101)
                .put("category", "美食").put("durationMin", 60);
        ObjectNode n3 = JSON.createObjectNode().put("name", "空间点3")
                .put("address", "湖里区测试路3号").put("lat", 24.50).put("lng", 118.50)
                .put("category", "景点").put("durationMin", 60);
        ObjectNode day = JSON.createObjectNode().put("date", "2026-11-20");
        day.set("morning", JSON.createArrayNode().add(n1).add(n2));
        day.set("afternoon", JSON.createArrayNode());
        day.set("evening", JSON.createArrayNode());
        ObjectNode plan = JSON.createObjectNode();
        plan.set("chosen", JSON.createArrayNode().add(n1).add(n2).add(n3));
        plan.set("itinerary", JSON.createArrayNode().add(day));
        plan.set("locked", JSON.createObjectNode());
        plan.set("quickPrefs", JSON.createArrayNode());
        plan.set("filter", JSON.createObjectNode());
        plan.put("origin", "广州");
        plan.put("destinationLabel", "厦门");
        Map<String, Object> saved = draftController.save(
                0, "空间规划测试", "厦门", "厦门", "广州",
                "2026-11-20", "2026-11-20", 1, "3000", "2 人",
                "balanced", 3, plan.toString(), session);
        int tripId = ((Number) saved.get("id")).intValue();

        Map<String, Object> clusters = planSpatialController.clusters(tripId, session);
        assertEquals("success", clusters.get("status"));
        assertTrue(castList(clusters.get("items")).size() >= 2);
        Map<String, Object> stays = planSpatialController.stayAreas(
                tripId, "walking", session);
        assertEquals("success", stays.get("status"));
        assertTrue(castList(stays.get("items")).size() >= 1);

        Map<String, Object> context = planOpsController.contextForUser(
                "planops-spatial-user", tripId, "plan", 0, "", "", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> contextData = (Map<String, Object>) context.get("context");
        Map<String, Object> preview = planSpatialController.refreshRoutes(
                String.valueOf(contextData.get("contextId")), tripId, "walking", session);
        assertEquals("success", preview.get("status"));
        assertEquals("success", planOpsController.applyForUser(
                "planops-spatial-user", String.valueOf(preview.get("operationId")),
                String.valueOf(preview.get("approvalToken")),
                ((Number) preview.get("expectedRevision")).intValue()).get("status"));
        Map<String, Object> draft = draftController.get(tripId, session);
        @SuppressWarnings("unchecked")
        Map<String, Object> draftData = (Map<String, Object>) draft.get("draft");
        JsonNode routed = JSON.readTree(String.valueOf(draftData.get("planData")));
        assertEquals("estimated", routed.path("itinerary").get(0)
                .path("morning").get(1).path("routeSource").asText());
        Map<String, Object> matrix = planSpatialController.routeMatrix(
                tripId, "walking", session);
        assertEquals("success", matrix.get("status"));
        assertTrue(((Number) matrix.get("totalTravelMinutes")).intValue() >= 1);
    }

    @Test
    void collaboratorsCanJoinVoteAndRecordConflicts() throws Exception {
        String owner = "collab-owner";
        String collaborator = "collab-friend";
        MySQLUtil.executeUpdate(
                "insert into user_profile(username, invite_code, update_time) values(?, ?, now())"
                        + " on duplicate key update invite_code = ?, update_time = now()",
                owner, "INV12345", "INV12345");
        MockHttpSession ownerSession = new MockHttpSession();
        ownerSession.setAttribute(ChatController.SESSION_USER, owner);
        MockHttpSession collaboratorSession = new MockHttpSession();
        collaboratorSession.setAttribute(ChatController.SESSION_USER, collaborator);
        ObjectNode node = JSON.createObjectNode().put("name", "协作测试点")
                .put("address", "协作路1号").put("lat", 24.1).put("lng", 118.1)
                .put("category", "景点").put("durationMin", 60);
        ObjectNode day = JSON.createObjectNode().put("date", "2026-11-20");
        day.set("morning", JSON.createArrayNode().add(node));
        day.set("afternoon", JSON.createArrayNode());
        day.set("evening", JSON.createArrayNode());
        ObjectNode plan = JSON.createObjectNode();
        plan.set("chosen", JSON.createArrayNode().add(node));
        plan.set("itinerary", JSON.createArrayNode().add(day));
        plan.set("locked", JSON.createObjectNode());
        plan.set("quickPrefs", JSON.createArrayNode());
        plan.set("filter", JSON.createObjectNode());
        plan.put("origin", "广州");
        plan.put("destinationLabel", "厦门");
        Map<String, Object> saved = draftController.save(
                0, "多人协作测试", "厦门", "厦门", "广州",
                "2026-11-20", "2026-11-20", 1, "3000", "2 人",
                "balanced", 3, plan.toString(), ownerSession);
        int tripId = ((Number) saved.get("id")).intValue();
        Map<String, Object> ownerInfo = collaborationController.info(tripId, ownerSession);
        String inviteCode = String.valueOf(ownerInfo.get("inviteCode"));
        assertEquals("INV12345", inviteCode);
        assertEquals("success", collaborationController.join(
                tripId, inviteCode, collaboratorSession).get("status"));
        Map<String, Object> joinInfo = collaborationController.info(tripId, collaboratorSession);
        List<Map<String, Object>> nodes = castList(joinInfo.get("nodes"));
        String nodeKey = String.valueOf(nodes.get(0).get("nodeKey"));
        assertEquals("success", collaborationController.vote(
                tripId, nodeKey, "must", "一定要去", ownerSession).get("status"));
        assertEquals("success", collaborationController.vote(
                tripId, nodeKey, "skip", "不想去", collaboratorSession).get("status"));
        Map<String, Object> conflictInfo = collaborationController.info(tripId, ownerSession);
        assertEquals(1, castList(conflictInfo.get("conflicts")).size());
        assertTrue(castList(conflictInfo.get("proposals")).size() >= 1);
        assertEquals("success", collaborationController.decision(
                tripId, nodeKey, "keep", "保留并安排自由活动", ownerSession).get("status"));
        Map<String, Object> finalInfo = collaborationController.info(tripId, ownerSession);
        assertTrue(castList(finalInfo.get("activity")).size() >= 4);

        Map<String, Object> context = planOpsController.contextForUser(
                owner, tripId, "plan", 0, "", "", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> contextData = (Map<String, Object>) context.get("context");
        ObjectNode lock = JSON.createObjectNode().put("nodeKey", nodeKey).put("locked", true);
        Map<String, Object> applied = approve(owner, tripId,
                "LOCK_NODE", lock, ownerSession);
        assertEquals("success", applied.get("status"));
        assertEquals(true, String.valueOf(applied.get("versionNo")).length() > 0);
    }

    @Test
    void todayModeTracksCurrentStopBudgetCheckinOfflineCardAndPreferenceConsent()
            throws Exception {
        String username = "today-mode-user";
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ChatController.SESSION_USER, username);
        ObjectNode first = JSON.createObjectNode().put("name", "今日第一站")
                .put("address", "今日路1号").put("lat", 24.10).put("lng", 118.10)
                .put("category", "景点").put("durationMin", 60).put("routeMinutes", 0);
        ObjectNode second = JSON.createObjectNode().put("name", "今日第二站")
                .put("address", "今日路2号").put("lat", 24.11).put("lng", 118.11)
                .put("category", "美食").put("durationMin", 60).put("routeMinutes", 15);
        ObjectNode day = JSON.createObjectNode().put("date", "2026-11-20");
        day.set("morning", JSON.createArrayNode().add(first).add(second));
        day.set("afternoon", JSON.createArrayNode());
        day.set("evening", JSON.createArrayNode());
        ObjectNode budgetPlan = JSON.createObjectNode().put("total", 1000);
        budgetPlan.putObject("categories").put("transport", 200)
                .put("lodging", 300).put("food", 300)
                .put("ticket", 100).put("local", 100);
        ObjectNode plan = JSON.createObjectNode();
        plan.set("chosen", JSON.createArrayNode().add(first).add(second));
        plan.set("itinerary", JSON.createArrayNode().add(day));
        plan.set("locked", JSON.createObjectNode());
        plan.set("quickPrefs", JSON.createArrayNode());
        plan.set("filter", JSON.createObjectNode());
        plan.set("budgetPlan", budgetPlan);
        plan.put("origin", "广州");
        plan.put("destinationLabel", "厦门");
        Map<String, Object> saved = draftController.save(
                0, "Today Mode 测试", "厦门", "厦门", "广州",
                "2026-11-20", "2026-11-20", 1, "3000", "2 人",
                "balanced", 3, plan.toString(), session);
        int tripId = ((Number) saved.get("id")).intValue();
        MySQLUtil.executeUpdate(
                "insert into budget_entry(username, trip_id, category, item, amount, entry_date, create_time)"
                        + " values(?, ?, 'food', '午餐', 200, '2026-11-20', now())",
                username, tripId);
        MySQLUtil.executeUpdate(
                "insert into budget_entry(username, trip_id, category, item, amount, entry_date, create_time)"
                        + " values(?, ?, 'local', '打车', 100, '2026-11-20', now())",
                username, tripId);

        Map<String, Object> today = planTodayController.getForUser(
                username, tripId, "2026-11-20", "10:30");
        assertEquals("success", today.get("status"));
        Map<String, Object> current = castMap(today.get("currentStop"));
        assertEquals("今日第二站", current.get("name"));
        Map<String, Object> budget = castMap(today.get("budget"));
        assertEquals(true, budget.get("configured"));
        assertEquals(300.0, ((Number) budget.get("spent")).doubleValue(), 0.001);
        assertEquals(700.0, ((Number) budget.get("remaining")).doubleValue(), 0.001);
        Map<String, Object> beforeEnd = castMap(today.get("preferenceProposals"));
        assertEquals(false, beforeEnd.get("eligible"));

        String nodeKey = String.valueOf(current.get("nodeKey"));
        Map<String, Object> checkin = planTodayController.checkin(
                tripId, nodeKey, "like", "现场体验很好", 95, "private", session);
        assertEquals("success", checkin.get("status"));

        Map<String, Object> checked = planTodayController.getForUser(
                username, tripId, "2026-11-20", "10:35");
        Map<String, Object> checkedStop = castMap(checked.get("currentStop"));
        assertEquals(true, checkedStop.get("checkedIn"));
        assertTrue(String.valueOf(checkedStop.get("checkin")).contains("现场体验很好"));

        Map<String, Object> offline = planTodayController.offlineCard(
                tripId, "2026-11-20", "10:35", session);
        assertEquals("success", offline.get("status"));
        assertTrue(String.valueOf(offline.get("html")).contains("今日第二站"));
        assertTrue(String.valueOf(offline.get("html")).contains("剩余 ¥700"));

        Map<String, Object> afterTrip = planTodayController.getForUser(
                username, tripId, "2026-11-21", "09:00");
        Map<String, Object> proposals = castMap(afterTrip.get("preferenceProposals"));
        assertEquals(true, proposals.get("eligible"));
        assertEquals(false, proposals.get("autoApplied"));
        List<Map<String, Object>> proposalItems = castList(proposals.get("proposals"));
        assertTrue(proposalItems.size() >= 1);
        Map<String, Object> proposal = proposalItems.get(0);
        assertEquals(false, proposal.get("autoApply"));

        Map<String, Object> notConfirmed = planTodayController.applyPreference(
                tripId, String.valueOf(proposal.get("tag")), String.valueOf(proposal.get("value")),
                String.valueOf(proposal.get("evidence")), false, session);
        assertEquals("error", notConfirmed.get("status"));
        Map<String, Object> confirmed = planTodayController.applyPreference(
                tripId, String.valueOf(proposal.get("tag")), String.valueOf(proposal.get("value")),
                String.valueOf(proposal.get("evidence")), true, session);
        assertEquals("success", confirmed.get("status"));
    }

    @Test
    void collaboratorCanReorderThroughPlanOpsAndTodayReadsSharedTrip() throws Exception {
        String owner = "today-owner";
        String collaborator = "today-collaborator";
        MockHttpSession ownerSession = new MockHttpSession();
        ownerSession.setAttribute(ChatController.SESSION_USER, owner);
        MockHttpSession collaboratorSession = new MockHttpSession();
        collaboratorSession.setAttribute(ChatController.SESSION_USER, collaborator);
        ObjectNode first = JSON.createObjectNode().put("name", "协作第一站")
                .put("address", "协作路1号").put("lat", 24.10).put("lng", 118.10)
                .put("category", "景点").put("durationMin", 60);
        ObjectNode second = JSON.createObjectNode().put("name", "协作第二站")
                .put("address", "协作路2号").put("lat", 24.11).put("lng", 118.11)
                .put("category", "美食").put("durationMin", 60);
        ObjectNode day = JSON.createObjectNode().put("date", "2026-11-22");
        day.set("morning", JSON.createArrayNode().add(first).add(second));
        day.set("afternoon", JSON.createArrayNode());
        day.set("evening", JSON.createArrayNode());
        ObjectNode plan = JSON.createObjectNode();
        plan.set("chosen", JSON.createArrayNode().add(first).add(second));
        plan.set("itinerary", JSON.createArrayNode().add(day));
        plan.set("locked", JSON.createObjectNode());
        plan.set("quickPrefs", JSON.createArrayNode());
        plan.set("filter", JSON.createObjectNode());
        plan.put("origin", "广州");
        plan.put("destinationLabel", "厦门");
        Map<String, Object> saved = draftController.save(
                0, "协作者 Today 测试", "厦门", "厦门", "广州",
                "2026-11-22", "2026-11-22", 1, "3000", "2 人",
                "balanced", 3, plan.toString(), ownerSession);
        int tripId = ((Number) saved.get("id")).intValue();
        MySQLUtil.executeUpdate(
                "insert into trip_collaborator(trip_id, owner, username, role, status, joined_at)"
                        + " values(?, ?, ?, 'editor', 'active', now())",
                tripId, owner, collaborator);

        Map<String, Object> collaboratorToday = planTodayController.getForUser(
                collaborator, tripId, "2026-11-22", "09:10");
        assertEquals("success", collaboratorToday.get("status"));
        assertEquals("collaborator", collaboratorToday.get("role"));

        Map<String, Object> context = planOpsController.contextForUser(
                collaborator, tripId, "today", 0, "morning", "", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> contextData = (Map<String, Object>) context.get("context");
        assertEquals("collaborator", contextData.get("accessRole"));
        String firstKey = "协作第一站|协作路1号|24.1|118.1";
        ObjectNode reorder = JSON.createObjectNode()
                .put("type", "REORDER_NODE")
                .put("expectedRevision", ((Number) contextData.get("planRevision")).intValue())
                .put("nodeKey", firstKey)
                .put("direction", 1)
                .put("reason", "协作者调整 Today 顺序");
        Map<String, Object> preview = planOpsController.previewForUser(
                collaborator, String.valueOf(contextData.get("contextId")),
                reorder.toString(), "click");
        assertEquals("success", preview.get("status"));
        Map<String, Object> applied = planOpsController.applyForUser(
                collaborator, String.valueOf(preview.get("operationId")),
                String.valueOf(preview.get("approvalToken")),
                ((Number) preview.get("expectedRevision")).intValue());
        assertEquals("success", applied.get("status"));

        Map<String, Object> ownerDraft = draftController.get(tripId, ownerSession);
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = (Map<String, Object>) ownerDraft.get("draft");
        JsonNode changed = JSON.readTree(String.valueOf(draft.get("planData")));
        assertEquals("协作第二站", changed.path("itinerary").get(0)
                .path("morning").get(0).path("name").asText());
    }

    @Test
    void dataSourceHealthExplainsMissingKeysAndQueryContextWithoutSecrets()
            throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ChatController.SESSION_USER, "health-panel-user");
        Map<String, Object> health = planDataSourceHealthController.health(
                "厦门", "景点,美食,商圈",
                "{\"景点\":12,\"美食\":8,\"商圈\":3,\"chosen\":4}",
                false, session);
        assertEquals("success", health.get("status"));
        assertEquals("degraded", health.get("overall"));
        assertTrue(castList(health.get("items")).size() >= 7);
        assertTrue(castList(health.get("warnings")).size() >= 2);
        Map<String, Object> context = castMap(health.get("queryContext"));
        assertEquals("厦门", context.get("city"));
        assertEquals(3, castList(context.get("categories")).size());
        Map<String, Object> counts = castMap(context.get("categoryCounts"));
        assertEquals(12, ((Number) counts.get("景点")).intValue());
        assertTrue(castList(health.get("items")).stream()
                .anyMatch(item -> "amap".equals(item.get("key"))
                        && "missing_key".equals(item.get("status"))));
        assertTrue(castList(health.get("items")).stream()
                .anyMatch(item -> "weather".equals(item.get("key"))
                        && "disabled".equals(item.get("status"))));
        assertTrue(castList(health.get("items")).stream()
                .anyMatch(item -> "baidu_poi".equals(item.get("key"))
                        && "missing_key".equals(item.get("status"))));
        assertTrue(castList(health.get("items")).stream()
                .anyMatch(item -> "tencent_poi".equals(item.get("key"))
                        && "missing_key".equals(item.get("status"))));
        String serialized = JSON.writeValueAsString(health);
        assertTrue(!serialized.contains("api-key="));
        assertTrue(!serialized.contains("jdbc:"));
    }

    @Test
    void commercialPoiProvidersStayOptionalWithoutApiKeys() {
        assertTrue(!planCommercialPoiService.configured("baidu"));
        assertTrue(!planCommercialPoiService.configured("tencent"));
        assertEquals("missing_key", planCommercialPoiService.health("baidu").get("status"));
        assertEquals("missing_key", planCommercialPoiService.health("tencent").get("status"));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ChatController.SESSION_USER, "commercial-poi-user");
        Map<String, Object> baidu = planExternalPoiController.sync(
                "baidu", "厦门", "中山路", 3000, session);
        Map<String, Object> tencent = planExternalPoiController.sync(
                "tencent", "厦门", "中山路", 3000, session);
        assertEquals("error", baidu.get("status"));
        assertEquals("error", tencent.get("status"));
        assertTrue(String.valueOf(baidu.get("message")).contains("geo.baidu-key"));
        assertTrue(String.valueOf(tencent.get("message")).contains("geo.tencent-key"));
    }

    @Test
    void anchorSearchKeepsPlayablePoisAndRejectsLivingFacilities() {
        assertEquals("美食", PlanPoiController.playableCategory(
                "餐饮服务 / 中餐厅", "双廊鱼火锅"));
        assertEquals("景点", PlanPoiController.playableCategory(
                "风景名胜 / 国家级景点", "双廊古镇"));
        assertEquals("商圈", PlanPoiController.playableCategory(
                "购物中心 / 商场", "双廊商业街"));
        assertEquals(null, PlanPoiController.playableCategory(
                "住宿服务 / 旅馆招待所", "双廊海景客栈"));
        assertEquals(null, PlanPoiController.playableCategory(
                "交通设施服务 / 停车场", "双廊古镇停车场"));
        assertEquals(null, PlanPoiController.playableCategory(
                "公共设施 / 公共厕所", "双廊古镇-公共厕所"));
        assertEquals(null, PlanPoiController.playableCategory(
                "购物服务 / 书店", "大理双廊海之书馆客栈"));
        assertEquals(null, PlanPoiController.playableCategory(
                "政府机构 / 政府机关", "双廊古镇管理委员会"));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ChatController.SESSION_USER, "anchor-search-user");
        Map<String, Object> missingKey = planPoiController.nearby(
                "双廊古镇", "大理", 3000, "", session);
        assertEquals("error", missingKey.get("status"));
        assertTrue(String.valueOf(missingKey.get("message")).contains("geo.amap-key"));
    }

    @Test
    void governmentCsvAndGeoJsonCanBeImportedIntoExternalPoiPool() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ChatController.SESSION_USER, "external-poi-user");
        MockMultipartFile csv = new MockMultipartFile(
                "file", "tourism.csv", "text/csv",
                ("名称,类别,纬度,经度,地址,简介\n"
                        + "测试古镇,景点,24.1001,118.1001,测试路1号,地方文旅导入\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> first = planExternalPoiController.importData(
                csv, "测试文旅市", "测试文旅开放数据",
                "CC-BY-4.0", "测试文旅局", "wgs84", session);
        assertEquals("success", first.get("status"));
        assertEquals(1, ((Number) first.get("imported")).intValue());

        MockMultipartFile geo = new MockMultipartFile(
                "file", "tourism.geojson", "application/geo+json",
                ("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\","
                        + "\"properties\":{\"名称\":\"Geo测试博物馆\",\"类别\":\"景点\","
                        + "\"城市\":\"测试文旅市\",\"地址\":\"Geo路2号\",\"简介\":\"GeoJSON导入\"},"
                        + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[118.1002,24.1002]}}]}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> second = planExternalPoiController.importData(
                geo, "测试文旅市", "测试文旅开放数据",
                "CC-BY-4.0", "测试文旅局", "wgs84", session);
        assertEquals("success", second.get("status"));
        assertEquals(1, ((Number) second.get("imported")).intValue());

        double[] canonicalGcj = CoordConverter.wgs84ToGcj02(24.1003, 118.1003);
        String canonicalCsv = "名称,类别,纬度,经度,地址,简介\n"
                + "坐标去重测试点,景点," + canonicalGcj[0] + "," + canonicalGcj[1]
                + ",去重路3号,GCJ 文件\n";
        MockMultipartFile canonicalWgs = new MockMultipartFile(
                "file", "canonical-wgs.csv", "text/csv",
                canonicalCsv.replace(String.valueOf(canonicalGcj[0]), "24.1003")
                        .replace(String.valueOf(canonicalGcj[1]), "118.1003")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> third = planExternalPoiController.importData(
                canonicalWgs, "测试文旅市", "测试文旅开放数据",
                "CC-BY-4.0", "测试文旅局", "wgs84", session);
        assertEquals("success", third.get("status"));
        MockMultipartFile canonicalGcjFile = new MockMultipartFile(
                "file", "canonical-gcj.csv", "text/csv",
                canonicalCsv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> fourth = planExternalPoiController.importData(
                canonicalGcjFile, "测试文旅市", "测试文旅开放数据",
                "CC-BY-4.0", "测试文旅局", "gcj02", session);
        assertEquals("success", fourth.get("status"));

        Map<String, Object> list = planExternalPoiController.list(
                "gov", "测试文旅市", 24.1, 118.1, 5000, session);
        assertEquals("success", list.get("status"));
        assertEquals(3, castList(list.get("items")).size());
        assertTrue(castList(list.get("items")).stream()
                .anyMatch(item -> "Geo测试博物馆".equals(item.get("name"))));
        long deduped = castList(list.get("items")).stream()
                .filter(item -> "坐标去重测试点".equals(item.get("name")))
                .count();
        assertEquals(1, deduped);
        Map<String, Object> imported = castList(list.get("items")).stream()
                .filter(item -> "Geo测试博物馆".equals(item.get("name")))
                .findFirst().orElseThrow();
        double[] expected = CoordConverter.wgs84ToGcj02(24.1002, 118.1002);
        assertEquals(expected[0], ((Number) imported.get("lat")).doubleValue(), 0.000001);
        assertEquals(expected[1], ((Number) imported.get("lng")).doubleValue(), 0.000001);
        assertEquals("GCJ-02", imported.get("coordinateSystem"));

        double[] bd09 = CoordConverter.gcj02ToBd09(expected[0], expected[1]);
        double[] roundTrip = CoordConverter.bd09ToGcj02(bd09[0], bd09[1]);
        assertEquals(expected[0], roundTrip[0], 0.000001);
        assertEquals(expected[1], roundTrip[1], 0.000001);
    }

    private Map<String, Object> approve(String username, int tripId, String type,
                                        ObjectNode operation, MockHttpSession session) {
        Map<String, Object> context = planOpsController.contextForUser(
                username, tripId, "plan", 0, "", "", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> contextData = (Map<String, Object>) context.get("context");
        operation.put("type", type);
        operation.put("expectedRevision", ((Number) contextData.get("planRevision")).intValue());
        Map<String, Object> preview = planOpsController.previewForUser(
                username, String.valueOf(contextData.get("contextId")), operation.toString(), "click");
        assertEquals("success", preview.get("status"));
        return planOpsController.applyForUser(
                username, String.valueOf(preview.get("operationId")),
                String.valueOf(preview.get("approvalToken")),
                ((Number) preview.get("expectedRevision")).intValue());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
