package com.iflytek.chatgpt.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iflytek.chatgpt.agent.tool.TravelTools;
import com.iflytek.chatgpt.service.AiChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 循环主体：想 → 调工具 → 看结果 → 再想，直到模型给出最终答案。
 * <p>
 * 公式：Agent = LLM（大脑） + Tools（手脚） + Loop（循环） + Memory（记忆）。
 * <ul>
 *   <li>LLM：{@link AiChatService#chatWithTools}</li>
 *   <li>Tools：{@link TravelTools}，由 {@link #dispatch} 按名分发</li>
 *   <li>Loop：{@link #run} 里的 for 循环，{@link #MAX_ROUNDS} 封顶防死循环</li>
 *   <li>Memory：由调用方传入的 history（会话内记忆，存 HttpSession）</li>
 * </ul>
 */
@Service
public class TravelAgentService {

    private static final Logger log = LoggerFactory.getLogger(TravelAgentService.class);

    /**
     * 必须设上限：模型可能反复调工具，不封顶会一直循环、一直烧钱。
     * <p>
     * 取 12 的原因：一次「行程变更」的完整链路是
     * listPreferences → listTripNodes → 查开放/预约时间 → 算通勤是否来得及 →
     * saveTripNode 写回 → 输出风险清单，共 6 步；再加上可能的中途重试，
     * 原值 6 刚好用完，会出现「读得到但写不回去」——模型只能给文字建议而无法落库。
     */
    private static final int MAX_ROUNDS = 12;

    /** 会话内记忆最多保留的消息条数（user/assistant 成对，保持为偶数） */
    private static final int MAX_HISTORY = 12;

    /** 回答里可能出现的高德链接（uri.amap.com / ditu.amap.com / www.amap.com 等） */
    private static final Pattern AMAP_URL =
            Pattern.compile("https?://[^\\s)\\]\"'>]*amap\\.com[^\\s)\\]\"'>]*", Pattern.CASE_INSENSITIVE);

    /**
     * 旅游管家系统人设：约束四大子能力联动、禁止答成百科、强制带时间与风险。
     */
    private static final String SYSTEM_PROMPT =
            "你是「AI旅游管家」，一款垂直旅行全链路管家 Agent，内嵌于 AI 旅行助手 App。"
                    + "你的目标不是回答百科、景点科普或通用游记，而是提供可落地、可执行的旅行管家服务。\n"
                    + "【最高优先级铁律 —— 违反任意一条即为回答失败】\n"
                    + "铁律1 凡涉及行程变更、画像更新、同行人记录、收藏、预算、清单、打卡、游记的需求，"
                    + "必须先调用对应工具把改动写入真实数据，再给出文字说明；"
                    + "禁止只用文字给出建议而不落库——用户会在对应页面里发现「你根本没做」。\n"
                    + "铁律2 每次回答前，先用 listTrips / listTripNodes / listPreferences 读取当前真实状态，"
                    + "不要凭记忆或凭想象回答用户已有的行程与画像。\n"
                    + "铁律3 写入成功后必须回报「已写入什么」，让用户能在对应页面看到变化，不要长篇复述工具返回值。\n"
                    + "铁律4 凡涉及空间距离，必须基于真实坐标算出的距离作答（报出米数），禁止用「附近」这类模糊说法，"
                    + "更不得把几公里外的地点说成身边；用户还没上报过定位时，先请其点一次「我的位置」。\n"
                    + "铁律5 本地库查不到的事实信息必须用 searchWeb 检索并标注来源；检索不到就换更宽的词"
                    + "（地名逐级放宽）至少再试一次；仍没有才如实说没查到。任何情况下都不得编造具体店名、机位、排队时长或开放时间。\n"
                    + "你内置四大子能力，必须联动生效、不能孤立运行（详细定义见本提示词末尾的【四大子能力】段）：\n"
                    + "1) 动态活行程编排引擎：行程是带时间硬约束的动态对象，而非一次性攻略。"
                    + "用户给出变动指令（起晚/临时加项/下雨/堵车/景点临时闭园/换交通方式）时，"
                    + "必须校验景点开放时间、预约与放票截止时间、交通耗时、餐厅营业时段、门票退改规则，"
                    + "自动剔除无法按时抵达的项目、替换同风格备选点位、顺延后续全部节点、标记变更项，"
                    + "并输出风险清单：哪些票需退改、哪些新点位需预约、预约截止时间、超时风险。\n"
                    + "2) 用户旅游偏好记忆引擎：从对话/行程选择/投票中提取旅行相关偏好，"
                    + "用 savePreference 写入画像（禁止记忆与旅行无关的信息）；出方案前先 listPreferences 对照画像；"
                    + "写入后必须在回答末尾用一行短句回显，格式固定：「📌 已记住：维度=取值 · 维度=取值」"
                    + "（例如「📌 已记住：步行耐受=低 · 偏好品类=逛吃 · 忌口=不吃辣」）；"
                    + "一条回答最多回显 1 次，没有新增或更新偏好时不要回显，禁止每轮都刷；"
                    + "当诉求内部冲突（如想省钱同时不想走路），输出至少两套折中方案并写明各自取舍代价。\n"
                    + "3) GPS 位置触发现场向导引擎：结合用户当前点位，优先输出现场落地指引——"
                    + "最佳拍照机位、卫生间/饮水点、推荐出入口、打车上车优选点位、周边避雷商铺、"
                    + "实时排队预估、临时限定活动、本地宰客风险预警；信息精简强实用；点位切换后只讲新点位。"
                    + "可用 getLocationGuide/searchDestination 查证，库中没有的现场数据不要编造，须标注「需现场确认」。"
                    + "凡是「附近 / 周边 / 身边有什么吃的好玩的」必须先调用 searchNearby，按真实直线距离作答"
                    + "（报出米数，并据此说明步行可达还是需打车/骑行）；禁止用「附近」这种模糊说法，"
                    + "更不得把几公里外的店说成身边；用户还没上报过定位时，先请其点一次「我的位置」。"
                    + "注意区分：用户点名了具体地名/区域/城市（如「丰泽区城东街道」「泉州」「汕头小公园」）时，"
                    + "这是【具名目的地】，与「附近」无关——不得以「你的 GPS 不在该地 / 周边查询锚不到」为由拒绝或反问定位，"
                    + "必须直接用 searchWeb 查该地真实店名。"
                    + "凡是本地库（searchDestination / getLocationGuide / spot 表）查不到的实体信息——包括当日活动、新开店铺、"
                    + "临时交通调整，以及【某城市/某地的好吃好玩、餐厅美食、老字号、具体店名、店铺推荐】——都必须调用 searchWeb 检索，"
                    + "并标注来源站点、提醒「以实地为准」；检索不到就换更宽的词（地名逐级放宽，如「泉州丰泽区城东街道美食」→"
                    + "「泉州丰泽区美食」→「泉州 美食」）至少再试一次，仍没有才如实说没查到并给出该地公共商圈信息；"
                    + "严禁因为定位不符、或第一次检索没命中就放弃给店名。\n"
                    + "凡是「吃喝玩乐」类推荐（吃/喝/美食/餐厅/小吃/老字号/景点/玩乐），无论问的是某地名还是附近，"
                    + "第一次回答都必须逐条给出【地名 + 具体店名/点位名】（一行一个，含所在片区、招牌菜/品类、人均或价位、来源）；"
                    + "只写「鱼丸/粿条/牛肉火锅」「某某街道餐饮密集」这类没有具体名字的模糊表述一律算违规——"
                    + "品类只能当分类标题、区域只能当分组标签，都不能拿它当答案；"
                    + "一个具体名字都给不出时才允许说明检索不到，并给出可执行的下一步办法。\n"
                    + "用户问「怎么去 / 导航到 / 带我去 某地」时，必须调用 navigateTo 生成导航链接，"
                    + "并用 Markdown 链接形式输出（如 [🚗 导航到广州塔](链接)），不要自己编路线、也不要只贴纯文本地址；"
                    + "只要用户表达了想去某地/怎么去的意思，就一定要调 navigateTo，不得只给文字描述跳过导航链接。\n"
                    + "4) 多人行程协同仲裁引擎：接收全体同行人的偏好与诉求（用 listCompanions/saveCompanion 记录），"
                    + "识别冲突后给出带投票项的备选方案，标注每套方案满足的人群、妥协项、牺牲内容；"
                    + "任何人改动即重算并同步全员；禁止为每人生成互不兼容的独立路线，目标产出一份全员可对齐、可共同执行的公共行程。\n"
                    + "5) 五大资产管理员（可读可写，必须用工具而不是空谈）："
                    + "「行程库」——用 setTripStatus 变更行程状态（规划中/进行中/已结束），用 saveTripNode/listTripNodes 管理时间线节点"
                    + "（订单 order、预约 booking、风险 risk、备注 note、备选路线 route），用 listTripVersions/rollbackTrip 查看并回退版本快照；"
                    + "用户说「出发了/玩完了/结束了/撤销/回退上一个版本」时必须落到这些工具。"
                    + "「旅途轨迹日志」——用户报告在某地打卡、喜欢或避雷某处、花了多少时间时，用 logCheckin 记录；"
                    + "用户说「生成游记/总结这次旅行」或行程标记「已结束」时，用 genTravelog 生成结构化游记草稿（亮点/踩坑）。"
                    + "「灵感收藏库」——用户说「收藏/记下/想去/别去/避雷/待打卡」某地时，用 saveFavorite（listType 取 favorite/blacklist/todo/wish）；"
                    + "规划行程前可用 listFavorites 参考其收藏与黑名单，避开用户已标避雷的地方。"
                    + "「偏好档案」——涉及用户画像时先 listPreferences，也可用 listPreferenceTemplates/activatePreferenceTemplate 切换模板。"
                    + "「工具箱」——用户报账用 saveBudgetEntry，问花销/超支用 getBudgetSummary（含告警阈值），设阈值用 setBudgetThreshold，"
                    + "整行李用 savePackingTemplate/listPackingTemplates，归档票据证件用 saveDocument。"
                    + "写入成功后，只需一句话确认存到了哪个资产，不要长篇复述工具返回值。\n"
                    + "全局强制规则：\n"
                    + "- 禁止把景点介绍/历史百科/网上游记当主体；用户若提出这类问题，先结合其行程、位置、画像给落地建议，科普仅作附属补充。\n"
                    + "- 所有方案必须带时间约束与风险提醒，点出潜在坑点：预约名额紧张、周一闭馆、停车困难、傍晚限流等。\n"
                    + "- 优先级：行程硬规则 > 用户画像偏好 > 用户临时口头需求。若临时需求与开放/预约规则冲突，先告知风险，不生成无法落地的路线。\n"
                    + "- 缺少必要上下文（行程、画像、当前所在位置）时主动精准提问收集信息，不要编造数据；"
                    + "但只要用户已给出【目的地 + 天数 + 主题/偏好】（如「汕头4天游 只去吃好吃的」），"
                    + "就必须先用工具取到真实资料、先交付一版带具体店名/时间点的可执行草稿，"
                    + "把出发地、忌口、预算等待确认项集中放在回答最末尾一次性追问；"
                    + "严禁用「先跟你确认两件事」代替方案、只回一个没有店名的空框架。\n"
                    + "- 默认输出中文文本，按此顺序并保留小标题：【变更摘要/方案结论】→【时间&执行清单】→【风险&提醒】→【可选备选】。\n"
                    + "- 禁止用 || 或 | 之类的符号拼表格；需要列表就用「- 」「1. 」列表或小标题，需要对照就用「分类 · 名称 · 距离 · 地址」这类单行描述，保证普通聊天界面一眼能看懂。\n"
                    + "- 仅当用户或系统明确要求 JSON 时，才改用固定字段："
                    + "summary / action_list[{\"time\":\"\",\"content\":\"\",\"remark\":\"\"}] / risk_notice / alternative_plan / need_ask。\n"
                    + "全程保持管家视角，以帮用户顺利出行、避坑、省心为目标。\n"
                    + "需要事实数据（行程本、用户画像、同行人、景点库）时，先调用工具获取，不要凭空猜测。\n"
                    + "【四大子能力 —— 什么情况触发、必须调哪些工具】（放在末尾，请优先遵守）\n"
                    + "① 动态活行程编排：用户说「起晚了/加个地方/下雨了/堵车/景点闭园/换交通方式」，"
                    + "或要求修改既有行程时触发。必调：listPreferences（先看画像）→ listTripNodes（先看现有时序）→ "
                    + "（需要外部信息时）searchWeb/getLocationGuide 查开放与预约 → saveTripNode（写回调整后的节点）→ "
                    + "必要时 setTripStatus。输出必须含风险清单（哪些票要退改、哪些新点位要预约、预约截止时间、超时风险）。\n"
                    + "② 用户旅游偏好记忆引擎：用户提到任何旅行相关的习惯/限制/口味/预算/作息时触发。"
                    + "必调：savePreference 写入，并在回答末尾按固定格式回显「📌 已记住：…」。"
                    + "出方案前必调 listPreferences；用户说「设置/我的偏好」时也应先 listPreferences 再答复。\n"
                    + "③ GPS 位置触发现场向导：涉及「在哪/附近/周边/怎么走/导航」时触发。"
                    + "必调：附近类先 searchNearby（按真实米数作答）；问某地现场情况先 getLocationGuide；"
                    + "问怎么去必调 navigateTo 并输出 Markdown 链接。库中没有的现场数据标注「需现场确认」，不得编造。\n"
                    + "④ 多人行程协同仲裁：用户提到同行人、家庭出游、多人偏好、投票、有人不满意时触发。"
                    + "必调：listCompanions 看现有记录 → saveCompanion 登记新偏好或投票 → 重新计算后给出"
                    + "带投票项的备选方案（标注每套满足谁、妥协什么、牺牲什么）。"
                    + "禁止给每个人单独生成互不兼容的路线，最终只交付一份全员可对齐的公共行程。";

    /**
     * 到达播报专用输出格式：覆盖 {@link #SYSTEM_PROMPT} 的默认四段长结构。
     * <p>
     * 现场播报是"到达即弹"的短消息，套完整四段结构会又长又空；这里要求精简成
     * 「已到达 + 能确认的现场信息逐条 + 一行汇总未确认项」。
     */
    private static final String LOCATION_BRIEFING_OVERRIDE =
            "\n【本次为 GPS 到达现场播报，以下格式要求覆盖上面的默认四段结构】"
                    + "不要输出【变更摘要/方案结论】【时间&执行清单】【风险&提醒】【可选备选】这套长结构。"
                    + "按精简现场指引输出，必须同时包含「现场资料」和「周边吃喝玩乐」两个板块："
                    + "第一行只写「已到达：<点位名>」；"
                    + "随后「现场」板块：一行一条，列库中确实有的现场资料（开放时间/闭馆日/预约要求、拍照机位、卫生间/出入口/打车点、避雷提醒等）；库里没有就写「现场资料暂缺」，不要编造；"
                    + "然后「周边」板块：必须覆盖「吃、喝、玩、乐」四类（美食/饮品咖啡、景点/文化、娱乐/休闲、购物/商圈），每类至少给 1 家、整体 6~8 家，"
                    + "一行一条写「分类 · 名称 · 距你多少米 · 地址」，并按距离说明步行可达还是需打车；"
                    + "列出的每家都要带距离，别用「附近」这种模糊词，也别只报吃的；"
                    + "最后用一行「实时排队、当日活动等需现场确认。」收尾。"
                    + "不写引言、客套话、总结段，不反问行程规划问题；禁止用 || 之类的符号拼表格，一律用「分类 · 名称 · 距离 · 地址」的单行列表。";

    private final AiChatService aiChatService;
    private final TravelTools travelTools;
    private final WebSearchService webSearchService;
    private final NavigationService navigationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 一轮 Agent 对话的完整结果。
     * <p>
     * {@code answer} 是给用户看的文本；{@code actions} 是这一轮里**真正改动了数据**的工具调用摘要，
     * 供前端渲染成「本次为你做了 N 件事」的小卡片。
     * <p>
     * 存在的意义：Agent 通过工具真实写库后，前端原本只显示一个聊天气泡，
     * 用户无法确认「它到底做没做」。actions 让写操作可见。
     */
    public static class AgentResult {
        /** 给用户看的回答文本 */
        public final String answer;
        /** 写操作摘要，每项形如 {"tool":"savePreference","summary":"记住偏好：饮食忌口=不吃辣"} */
        public final List<Map<String, String>> actions;

        public AgentResult(String answer, List<Map<String, String>> actions) {
            this.answer = answer;
            this.actions = actions;
        }
    }

    /**
     * 只对这些「写操作」生成动作摘要；读类工具返回 null，避免刷屏。
     * 与 {@link #dispatch} 的 case 名保持一致。
     */
    private static final List<String> WRITE_TOOLS = Arrays.asList(
            "saveTrip", "savePreference", "saveCompanion", "setTripStatus", "saveTripNode",
            "rollbackTrip", "logCheckin", "genTravelog", "saveFavorite",
            "activatePreferenceTemplate", "saveBudgetEntry", "setBudgetThreshold",
            "savePackingTemplate", "saveDocument");

    public TravelAgentService(AiChatService aiChatService, TravelTools travelTools,
                              WebSearchService webSearchService, NavigationService navigationService) {
        this.aiChatService = aiChatService;
        this.travelTools = travelTools;
        this.webSearchService = webSearchService;
        this.navigationService = navigationService;
    }

    /**
     * 跑一轮 Agent 对话（不带位置上下文）。
     */
    public String run(String username, String userInput, List<JsonNode> history) {
        return run(username, userInput, history, null, null, null);
    }

    /**
     * 跑一轮 Agent 对话。
     *
     * @param username        当前登录用户名（由后端从 session 取，绝不由模型/前端传）
     * @param userInput       用户这一句话
     * @param history         会话内记忆（会被就地更新：追加本轮 user + assistant）
     * @param locationContext 用户实时位置（来自 App 上报，可为 null），会注入系统提示词
     * @param lat             用户纬度（session 里记录的 GPS 坐标，供周边搜索使用，可为 null）
     * @param lng             用户经度（同上）
     * @return 给用户看的纯文本回答
     */
    public String run(String username, String userInput, List<JsonNode> history,
                      String locationContext, Double lat, Double lng) {
        return runWithActions(username, userInput, history, locationContext, lat, lng).answer;
    }

    /**
     * 跑一轮 Agent 对话，并额外返回「这一轮真正改动了哪些数据」。
     * <p>
     * 与 {@link #run} 的唯一区别是多带出 {@link AgentResult#actions}，
     * 供前端渲染动作卡片。给不了 actions 的调用方继续用 {@link #run} 即可。
     */
    public AgentResult runWithActions(String username, String userInput, List<JsonNode> history,
                                      String locationContext, Double lat, Double lng) {
        List<JsonNode> messages = new ArrayList<>();
        messages.add(textMessage("system", buildSystemPrompt(locationContext)));
        messages.addAll(history);
        messages.add(textMessage("user", userInput));

        List<Map<String, String>> actions = new ArrayList<>();
        String answer = runLoop(username, messages, lat, lng, actions);
        remember(history, userInput, answer);
        return new AgentResult(answer, actions);
    }

    /**
     * GPS 位置触发现场向导：用户到达新点位后由系统上报位置触发的"被动推送"。
     * <p>
     * 与 {@link #run} 的区别：这里不追加会话记忆（避免每次到达都撑爆历史），
     * 并且用一条固定的"到达"指令驱动模型只输出该点位的现场落地指引。
     *
     * @param username 当前登录用户名
     * @param place    点位名称，可为空（为空时用经纬度描述）
     * @param lat      纬度
     * @param lng      经度
     * @param accuracy 浏览器上报的定位精度（米，radius of confidence），可为 null
     * @return 该点位的现场指引文本
     */
    public String locationBriefing(String username, String place, Double lat, Double lng, Double accuracy) {
        String where = place == null ? "" : place.trim();

        // 地名没解析出来时，不给模型任何"猜地点"的机会：直接给确定性的诚实回复（也不烧 token）
        if (where.isEmpty()) {
            return "已记录你的位置（坐标 " + lat + ", " + lng + "），但这次没能解析出具体地名，"
                    + "所以暂时给不了现场指引。可以点一次「我的位置」重试，"
                    + "或直接告诉我你在哪条路 / 哪个点，我立刻查。";
        }

        // 定位精度必须如实带出去：桌面浏览器走的是 WiFi/基站定位（几百米甚至公里级误差），
        // 不是手机 GPS。不说明精度，"我在绿地中央、它却说我到了起云路"就是误导。
        int acc = accuracy == null ? -1 : (int) Math.round(accuracy);
        String accuracyNote = "";
        if (acc > 300) {
            accuracyNote = "（注意：本次上报的定位精度只有 ±" + acc + " 米，属于 WiFi/基站级定位而非手机 GPS，"
                    + "用户可能并不在「" + where + "」这个点上。首行必须写成「你在「" + where + "」一带（定位精度约 ±"
                    + acc + " 米）」，并在末尾提醒：精度有限、若与实际不符请点「我的位置」重试或直接告知所在位置；"
                    + "你给出的每条距离都要说明存在该定位误差。）";
        } else if (acc > 0) {
            accuracyNote = "（本次定位精度约 ±" + acc + " 米，属正常范围。）";
        }

        List<JsonNode> messages = new ArrayList<>();
        messages.add(textMessage("system", buildSystemPrompt("用户当前位置：" + where
                + "（经纬度 " + lat + ", " + lng + "，由坐标反查得到，是唯一权威位置）" + accuracyNote)
                + LOCATION_BRIEFING_OVERRIDE));
        messages.add(textMessage("user",
                "我刚刚抵达「" + where + "」。请立刻按 GPS 现场向导引擎的规则处理：\n"
                        + "1) 先调用 getLocationGuide，把「" + where + "」原样传进去（工具会自动兼容点位名和长地址两种输入）；\n"
                        + "2) 若 matchedGuides / matchedSpots 有资料，把其中的开放时间、闭馆日、预约要求、拍照机位、卫生间/出入口/打车点、避雷提醒，转成「现场」板块的落地指引；没有就写「现场资料暂缺」；\n"
                        + "3) 无论库里有没有资料，都必须再调用 searchNearby 拿周边真实门店：keywords 用「美食|咖啡|景点|购物|娱乐」、radius 先 2000，"
                        + "覆盖「吃、喝、玩、乐」四类，每类至少 1 家、整体 6~8 家（别把返回结果全列出来），"
                        + "一行一条写「分类 · 名称 · 距你多少米 · 地址」，并按距离说清是步行可达还是需打车；"
                        + "若 2 公里内结果不足四类，把 radius 放宽到 5000 再试；仍不足才用 searchWeb 检索，并注明来源站点与「以实地为准」；\n"
                        + "4) 位置只能原样引用「" + where + "」，禁止推断或改写成其它区/商圈/街道；\n"
                        + "5) 信息精简、一条一行；实时排队、当日活动、车位这类查不到的，合并成一行「其余现场信息需现场确认」，不要逐条重复，也不要编造具体店名、机位或排队时长；\n"
                        + "6) 禁止用 || 之类的符号拼表格，一律用「分类 · 名称 · 距离 · 地址」的单行列表；不要反问行程规划问题。"));

        // 到达播报是系统被动触发，不面向动作卡片，actions 传 null 丢弃即可
        return runLoop(username, messages, lat, lng, null);
    }

    /** Agent 循环主体：想 → 调工具 → 看结果 → 再想，直到模型给出最终答案 */
    private String runLoop(String username, List<JsonNode> messages, Double lat, Double lng,
                           List<Map<String, String>> actions) {
        String toolsJson = toolsJson();
        // navigateTo 生成的链接是唯一权威来源，记下来；回答返回前用它覆盖模型自己写的链接
        String navUri = null;
        String navName = null;

        for (int round = 0; round < MAX_ROUNDS; round++) {
            String raw;
            try {
                raw = aiChatService.chatWithTools(messages, toolsJson);
            } catch (Exception e) {
                log.error("Agent 调用大模型失败", e);
                return "抱歉，AI 服务暂时不可用：" + e.getMessage();
            }

            JsonNode message = parse(raw).path("choices").path(0).path("message");
            if (message.isMissingNode()) {
                return "抱歉，AI 未返回有效内容，请稍后重试。";
            }

            JsonNode toolCalls = message.path("tool_calls");
            // 情况 A：模型没调工具，直接给了最终答案 → 结束本轮
            if (!toolCalls.isArray() || toolCalls.size() == 0) {
                String content = message.path("content").asText("");
                if (content.isEmpty()) {
                    content = "抱歉，我没能整理出方案，请补充更具体的条件再试一次。";
                }
                return alignNavigationLink(content, navUri, navName, lat, lng);
            }

            // 情况 B：模型要调工具 → 把它的"调用请求"原样放进历史
            messages.add(message);

            for (JsonNode call : toolCalls) {
                String toolName = call.path("function").path("name").asText();
                String callId = call.path("id").asText();
                String arguments = call.path("function").path("arguments").asText("{}");

                // ★ 这里才是"真正干活"，结果作为 role=tool 塞回，模型下一轮才能看到
                String result = dispatch(username, toolName, arguments, lat, lng);

                // 写操作摘要：供前端渲染「本次为你做了 N 件事」，读操作不记录（避免刷屏）
                if (actions != null && WRITE_TOOLS.contains(toolName)) {
                    String summary = describeAction(toolName, arguments);
                    if (summary != null) {
                        Map<String, String> item = new java.util.LinkedHashMap<>();
                        item.put("tool", toolName);
                        item.put("summary", summary);
                        actions.add(item);
                    }
                }

                // 记住导航链接（含降级的高德搜索链接），稍后覆盖模型转述时可能写错的 URL
                if ("navigateTo".equals(toolName)) {
                    JsonNode nav = parse(result);
                    String uri = nav.path("uri").asText("");
                    if (!uri.isEmpty()) {
                        navUri = uri;
                        navName = nav.path("destination").asText("");
                    }
                }

                ObjectNode toolMessage = textMessage("tool", result);
                toolMessage.put("tool_call_id", callId);   // 必须回填，否则厂商报 400
                messages.add(toolMessage);
            }
            // 循环继续：带着工具结果再问一次模型
        }

        return alignNavigationLink("这次需求涉及的步骤较多，我先给出已确认的部分；"
                + "你可以补充更具体的条件（城市、天数、同行人、预算）我再继续安排。", navUri, navName, lat, lng);
    }

    /**
     * 让回答里的高德链接始终指向工具生成的权威地址。
     * <p>
     * 模型经常"凭记忆"重写链接，最典型的是把系统提示里给它的「你当前位置的经纬度」当成目的地坐标，
     * 于是高德把终点标到了自己脚下（几米~几百米的假路线）。这里在回答返回前，把模型中写的第一个
     * 高德链接替换成 navigateTo 刚刚生成的 uri；回答里没链接时补上一条，保证点开/内嵌打开的永远是正确路线。
     * <p>
     * 若本轮压根没调 navigateTo（模型自己拼了链接），则从它写的链接里还原目的地名，重新生成权威链接；
     * 还原不出来就保持原文不动，绝不让"修链接"本身把回答改坏。
     */
    private String alignNavigationLink(String answer, String uri, String destinationName,
                                       Double lat, Double lng) {
        String text = answer == null ? "" : answer;
        Matcher matcher = AMAP_URL.matcher(text);
        boolean hasLink = matcher.find();

        String finalUri = uri;
        if (finalUri == null || finalUri.isEmpty()) {
            if (hasLink) {
                String guess = destinationFromNavigationUrl(text.substring(matcher.start(), matcher.end()));
                if (guess != null && !guess.isEmpty()) {
                    // 只认通过地名重新解析出来的“真导航链接”，解析失败降级成的搜索链接不替换
                    String rebuilt = String.valueOf(
                            navigationService.buildNavigation(guess, "", "", lat, lng).get("uri"));
                    // 新版深链是 www.amap.com/dir，老版是 uri.amap.com/navigation，两种都算真导航
                    if (rebuilt.contains("/dir") || rebuilt.contains("/navigation")) {
                        finalUri = rebuilt;
                    }
                }
            }
            if (finalUri == null || finalUri.isEmpty()) {
                return text;
            }
        }

        if (hasLink) {
            return text.substring(0, matcher.start()) + finalUri + text.substring(matcher.end());
        }
        String name = destinationName == null || destinationName.isEmpty() ? "目的地" : destinationName;
        String prefix = text.isEmpty() ? "" : text + "\n\n";
        return prefix + "[🚗 导航到" + name + "](" + finalUri + ")";
    }

    /**
     * 从模型自己写的高德导航链接里还原目的地名，供 {@link #alignNavigationLink} 重新生成权威链接。
     * 支持 {@code ...&to=经度,纬度,名称} 与 {@code ...&to[name]=名称} 两种常见写法；还原不出返回 null。
     */
    private String destinationFromNavigationUrl(String url) {
        try {
            // 先整串解码，把新版深链里的 to%5Bname%5D / to%5Blnglat%5D 还原成 to[name] / to[lnglat] 再解析
            String decoded = URLDecoder.decode(url, "UTF-8");
            Matcher named = Pattern.compile("[?&]to\\[name\\]=([^&]+)").matcher(decoded);
            if (named.find()) {
                String value = named.group(1).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
            Matcher pos = Pattern.compile("[?&]to(?:\\[lnglat\\])?=([^&]+)").matcher(decoded);
            if (pos.find()) {
                String[] parts = pos.group(1).split(",");
                for (int i = parts.length - 1; i >= 0; i--) {
                    String part = parts[i].trim();
                    // 末段里挑非纯数字的那个：纯数字是经纬度，剩下的是地名
                    if (!part.isEmpty() && !part.matches("[-+0-9.]+")) {
                        return part;
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败按“还原不出”处理，保持原文
        }
        return null;
    }

    /** 把实时位置注入系统提示词，让位置相关问题优先锚定当前位置 */
    private String buildSystemPrompt(String locationContext) {
        if (locationContext == null || locationContext.trim().isEmpty()) {
            return SYSTEM_PROMPT;
        }
        return SYSTEM_PROMPT
                + "\n【用户实时位置】" + locationContext.trim()
                + "。位置相关问题（在哪、周边、附近吃什么/玩什么）必须锚定该位置作答，不要反问用户在哪儿。"
                + "该位置来自系统 GPS 上报，是唯一权威来源：禁止根据检索结果、攻略内容或自己的常识"
                + "把它改写成其它区/商圈/街道；若上文说明地名未解析成功，就如实说「拿到了坐标但没解析出地名」，"
                + "绝对不要猜一个地点。";
    }

    // ==================== 工具声明（给模型看的说明书） ====================

    /** 把工具清单序列化成 OpenAI 兼容的 tools 数组 JSON */
    private String toolsJson() {
        ArrayNode tools = objectMapper.createArrayNode();
        for (AgentTool tool : buildTools()) {
            ObjectNode function = objectMapper.createObjectNode();
            function.put("name", tool.getName());
            function.put("description", tool.getDescription());
            function.set("parameters", tool.getParameters());

            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.put("type", "function");
            wrapper.set("function", function);
            tools.add(wrapper);
        }
        return tools.toString();
    }

    /** 四大子能力对应的全部工具 */
    private List<AgentTool> buildTools() {
        return Arrays.asList(
                // —— 行程编排引擎 ——
                new AgentTool("searchDestination",
                        "按关键词（城市名/景点名/分类/特色词，如“三亚”“古镇”“滨海度假”）搜索目的地景点，返回名称、城市、分类、简介。",
                        schema("keyword", "string", "搜索关键词，可为城市、景点或分类")),
                new AgentTool("listTrips",
                        "列出当前用户已保存的行程（含行程ID、标题、创建时间），用于基于已有行程做变更或顺延。",
                        schema()),
                new AgentTool("getTripDetail",
                        "读取某条行程的完整正文，用于在其基础上修改、顺延或补排。",
                        schema("id", "integer", "行程ID，来自 listTrips 的返回")),
                new AgentTool("saveTrip",
                        "把最终确定（或已变更）的行程方案保存到用户的行程本。",
                        schema("title", "string", "行程标题，30字以内",
                               "content", "string", "行程正文，按天/按时间列出")),
                // —— 偏好记忆引擎 ——
                new AgentTool("listPreferences",
                        "读取用户长期画像标签（步行耐受、预算、作息、偏好品类、忌口、出行限制等），出方案前应先读。",
                        schema()),
                new AgentTool("savePreference",
                        "静默更新一条用户画像标签（同标签覆盖），仅记录与旅行相关的偏好。",
                        schema("tag", "string", "画像维度，如“步行耐受”“预算区间”“偏好品类”“饮食忌口”",
                               "value", "string", "该维度的取值")),
                // —— 位置向导引擎 ——
                new AgentTool("getLocationGuide",
                        "获取用户当前或指定点位的现场资料（库中真实景点记录 + 提示）。实时排队/停车等库中没有的数据不要编造。",
                        schema("place", "string", "用户当前所在点位或想了解的地点名称")),
                // —— 周边搜索（"附近"的唯一可信来源：真实距离） ——
                new AgentTool("searchNearby",
                        "以用户当前定位为圆心，按距离由近及远返回周边真实门店/景点，每条带直线距离（米）与地址。"
                                + "凡涉及「附近 / 周边 / 身边有什么吃的好玩的」，必须先调这个，用真实距离作答；"
                                + "用户没上报过定位时该工具会返回错误，此时应请用户点一次「我的位置」。",
                        schema("keywords", "string", "搜索关键词，如「美食」「餐厅|咖啡」「景点」「商场」，多个用 | 分隔",
                               "radius", "integer", "搜索半径（米），默认 2000；近处没有结果可放宽到 5000",
                               "limit", "integer", "返回条数，默认 10，最多 25")),
                // —— 联网检索（本地库兜底） ——
                new AgentTool("searchWeb",
                        "联网检索真实网页资料（美食小店、当日活动、最新攻略、交通临时调整等本地库没有的信息）。"
                                + "仅当本地库查不到、且需要最新或长尾信息时调用；返回标题 / 摘要 / 链接。",
                        schema("query", "string", "检索关键词，尽量具体，如「广州塔附近 好吃 餐厅」")),
                // —— 到指定位置（一键导航） ——
                new AgentTool("navigateTo",
                        "为目的地生成可点击的导航链接（点击即在高德里以当前位置为起点规划到该地的路线）。"
                                + "用户问「怎么去 / 导航到 / 带我去 某个地方」时调用，"
                                + "然后把返回的 uri 用 Markdown 链接给出，如 [🚗 导航到广州塔](uri)。",
                        schema("destination", "string", "目的地名称，如「广州塔」「星巴克科学城高德汇店」",
                               "city", "string", "所在地城市，用于同名地点消歧，如「广州」；不确定可传空字符串",
                               "mode", "string", "出行方式，可选 car(驾车，默认)/walk(步行)/bus(公交)/ride(骑行)")),
                // —— 多人协同引擎 ——
                new AgentTool("listCompanions",
                        "列出同行人及其偏好标签、投票诉求，用于识别诉求冲突。",
                        schema()),
                new AgentTool("saveCompanion",
                        "登记或更新一名同行人的偏好与投票诉求（同名覆盖）。",
                        schema("name", "string", "同行人称呼",
                               "preferences", "string", "其偏好标签，如“爱拍照”“不吃辣”“带娃”",
                               "vote", "string", "其对备选方案的投票或诉求")),
                // —— 5.1 行程库：状态 / 时间线节点 / 版本回退 ——
                new AgentTool("setTripStatus",
                        "把某条行程的状态改为「规划中 / 进行中 / 已结束」。用户说“出发了/玩完了/结束了”时调用。",
                        schema("tripId", "integer", "行程ID，来自 listTrips",
                               "status", "string", "只能是：规划中 / 进行中 / 已结束")),
                new AgentTool("listTripNodes",
                        "读取某行程的时间线节点（订单、预约、风险、备注、备选路线）。",
                        schema("tripId", "integer", "行程ID",
                               "nodeType", "string", "可选过滤：timeline/order/booking/risk/note/route；不填返回全部")),
                new AgentTool("saveTripNode",
                        "给某行程新增一个节点：下单/预约/风险提醒/备注/备选路线都记在这里。",
                        schema("tripId", "integer", "行程ID",
                               "nodeType", "string", "节点类型：timeline(时间线)/order(订单)/booking(预约)/risk(风险)/note(备注)/route(备选路线)",
                               "title", "string", "节点标题",
                               "detail", "string", "节点详情",
                               "nodeTime", "string", "节点时间，如“第一天 09:00”，可空",
                               "status", "string", "节点状态，如“已确认/待预约”，可空",
                               "cost", "number", "金额（如订单花费），可空")),
                new AgentTool("listTripVersions",
                        "查看某行程的版本历史（每次修改都会自动存一版），拿到 versionId 可用于回退。",
                        schema("tripId", "integer", "行程ID")),
                new AgentTool("rollbackTrip",
                        "把行程回退到某个历史版本（回退前会自动备份当前状态）。用户说“撤销/回退上一个版本”时调用。",
                        schema("versionId", "integer", "版本ID，来自 listTripVersions")),
                // —— 5.2 旅途轨迹日志 ——
                new AgentTool("logCheckin",
                        "记录一次游玩打卡：点位、喜欢/避雷评价、随手备注、耗时、实拍标记。用户说“打卡/我到了/这家避雷”时调用。",
                        schema("place", "string", "打卡点位名称",
                               "rating", "string", "评价：like(喜欢)/avoid(避雷)/neutral(一般)",
                               "note", "string", "现场随手备注",
                               "durationMin", "integer", "游玩耗时（分钟）",
                               "photos", "string", "实拍标记说明，如“拍了塔顶夜景”，可空",
                               "tripId", "integer", "关联行程ID，可空")),
                new AgentTool("listTravelLogs",
                        "查看历史游玩记录（打卡/评价/耗时/实拍），行程结束后用来总结。",
                        schema("tripId", "integer", "可选，按行程过滤；不填返回全部")),
                new AgentTool("genTravelog",
                        "行程结束后，把该行程的轨迹日志自动汇总成结构化游记草稿（含亮点总结、踩坑汇总）并保存。",
                        schema("tripId", "integer", "行程ID")),
                // —— 5.3 灵感收藏库 ——
                new AgentTool("saveFavorite",
                        "把点位/餐厅/展览/市集加入收藏库的某个清单：favorite(收藏)/blacklist(避雷黑名单)/todo(待打卡)/wish(意向)。",
                        schema("name", "string", "收藏对象名称",
                               "listType", "string", "清单类型：favorite/blacklist/todo/wish，默认 favorite",
                               "category", "string", "类别，如“餐厅/展览/市集/景点”，可空",
                               "note", "string", "自定义备注，可空",
                               "expectDuration", "string", "期望游玩时长，如“2小时”，可空",
                               "season", "string", "想去的季节，如“秋天”，可空")),
                new AgentTool("listFavorites",
                        "查看收藏库内容，可按清单类型过滤（favorite/blacklist/todo/wish）。",
                        schema("listType", "string", "可选：favorite/blacklist/todo/wish；不填返回全部")),
                // —— 5.4 偏好档案（多套模板） ——
                new AgentTool("listPreferenceTemplates",
                        "列出用户的多套偏好模板（步行耐受、预算、忌口、同行人群等标签），看哪套在生效、有哪些可选。",
                        schema()),
                new AgentTool("activatePreferenceTemplate",
                        "切换启用某套偏好模板，启用后立即成为对话依据（同步到当前画像）。",
                        schema("templateId", "integer", "模板ID，来自 listPreferenceTemplates")),
                // —— 5.5 工具箱：预算 / 行李 / 证件 ——
                new AgentTool("saveBudgetEntry",
                        "记一笔预算开销：交通/住宿/餐饮/门票/其他。用户说“花了多少钱”时调用。",
                        schema("category", "string", "分类：transport/lodging/food/ticket/other",
                               "amount", "number", "金额（元）",
                               "item", "string", "开销项目说明，如“高铁票”，可空",
                               "tripId", "integer", "关联行程ID，可空",
                               "entryDate", "string", "日期，如“2026-05-01”，可空")),
                new AgentTool("getBudgetSummary",
                        "查看预算汇总：总花费、各类别花费、是否超出告警阈值。",
                        schema("tripId", "integer", "可选，按行程过滤；不填统计全部")),
                new AgentTool("setBudgetThreshold",
                        "设置预算告警阈值（超过就提醒）。",
                        schema("amount", "number", "阈值金额（元）")),
                new AgentTool("savePackingTemplate",
                        "保存一份行李清单模板到工具箱（可复用）。",
                        schema("name", "string", "模板名称，如“海岛度假”",
                               "items", "string", "清单条目，一行一条")),
                new AgentTool("listPackingTemplates",
                        "列出已保存的行李清单模板。",
                        schema()),
                new AgentTool("saveDocument",
                        "把车票/酒店/预约码/电子合同/证件/应急记录归档到工具箱。",
                        schema("docType", "string", "类型：ticket/hotel/booking/contract/idcard/emergency/tool",
                               "title", "string", "标题",
                               "docNo", "string", "单号/凭证号，可空",
                               "detail", "string", "备注详情，可空",
                               "expireTime", "string", "有效期/截止时间，可空"))
        );
    }

    /**
     * 构造参数的 JSON Schema：triples 按 “参数名, 类型, 说明” 三个一组重复传入。
     * 不传参数时得到 required 为空的空对象，适用于无参工具。
     */
    private ObjectNode schema(String... triples) {
        ObjectNode properties = objectMapper.createObjectNode();
        ArrayNode required = objectMapper.createArrayNode();
        for (int i = 0; i + 2 < triples.length; i += 3) {
            ObjectNode prop = properties.putObject(triples[i]);
            prop.put("type", triples[i + 1]);
            prop.put("description", triples[i + 2]);
            required.add(triples[i]);
        }
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", required);
        return schema;
    }

    // ==================== 工具分发（工具名 → 真正的 Java 方法） ====================

    /**
     * 按工具名执行对应实现。任何异常都会被吞掉并转成 JSON 错误结果返回给模型，
     * 让模型自己纠正（比如换个关键词再查一次），而不是整条接口 500。
     *
     * @param lat 用户纬度，来自 session 的 GPS 上报（周边搜索用，模型无权指定）
     * @param lng 用户经度
     */
    private String dispatch(String username, String toolName, String arguments, Double lat, Double lng) {
        try {
            JsonNode args = objectMapper.readTree(
                    arguments == null || arguments.trim().isEmpty() ? "{}" : arguments);

            switch (toolName) {
                case "searchDestination":
                    return toJson(travelTools.searchDestination(args.path("keyword").asText("")));
                case "listTrips":
                    return toJson(travelTools.listTrips(username));
                case "getTripDetail":
                    return toJson(travelTools.getTripDetail(username, args.path("id").asInt(0)));
                case "saveTrip":
                    return travelTools.saveTrip(username,
                            args.path("title").asText(""), args.path("content").asText(""));
                case "listPreferences":
                    return toJson(travelTools.listPreferences(username));
                case "savePreference":
                    return travelTools.savePreference(username,
                            args.path("tag").asText(""), args.path("value").asText(""));
                case "getLocationGuide":
                    return toJson(travelTools.getLocationGuide(args.path("place").asText("")));
                case "searchWeb":
                    return toJson(webSearchService.search(args.path("query").asText(""), 5));
                case "searchNearby":
                    // 坐标只认 session 里上报的 GPS，模型传什么都不作数（防越权/防编造坐标）
                    return toJson(navigationService.searchNearby(
                            args.path("keywords").asText(""),
                            lat, lng,
                            args.path("radius").asInt(2000),
                            args.path("limit").asInt(10)));
                case "navigateTo":
                    // 起点也来自 session 上报的 GPS（模型无权指定），这样内嵌地图才认得到「我的位置」
                    return toJson(navigationService.buildNavigation(
                            args.path("destination").asText(""),
                            args.path("city").asText(""),
                            args.path("mode").asText(""),
                            lat, lng));
                case "listCompanions":
                    return toJson(travelTools.listCompanions(username));
                case "saveCompanion":
                    return toJson(travelTools.saveCompanion(username,
                            args.path("name").asText(""), args.path("preferences").asText(""),
                            args.path("vote").asText("")));
                // —— 五大资产 Tab：行程库 ——
                case "setTripStatus":
                    return toJson(travelTools.setTripStatus(username,
                            args.path("tripId").asInt(0), args.path("status").asText("")));
                case "listTripNodes":
                    return toJson(travelTools.listTripNodes(username,
                            args.path("tripId").asInt(0), args.path("nodeType").asText("")));
                case "saveTripNode":
                    return toJson(travelTools.saveTripNode(username,
                            args.path("tripId").asInt(0), args.path("nodeType").asText(""),
                            args.path("title").asText(""), args.path("detail").asText(""),
                            args.path("nodeTime").asText(""), args.path("status").asText(""),
                            args.path("cost").isNumber() ? args.path("cost").asDouble() : null));
                case "listTripVersions":
                    return toJson(travelTools.listTripVersions(username, args.path("tripId").asInt(0)));
                case "rollbackTrip":
                    return toJson(travelTools.rollbackTrip(username, args.path("versionId").asInt(0)));
                // —— 五大资产 Tab：旅途轨迹日志 ——
                case "logCheckin":
                    return toJson(travelTools.logCheckin(username,
                            args.path("tripId").asInt(0), args.path("place").asText(""),
                            args.path("rating").asText(""), args.path("note").asText(""),
                            args.path("durationMin").isNumber() ? args.path("durationMin").asInt() : null,
                            args.path("photos").asText("")));
                case "listTravelLogs":
                    return toJson(travelTools.listTravelLogs(username, args.path("tripId").asInt(0)));
                case "genTravelog":
                    return toJson(travelTools.genTravelog(username, args.path("tripId").asInt(0)));
                // —— 五大资产 Tab：灵感收藏库 ——
                case "saveFavorite":
                    return toJson(travelTools.saveFavorite(username,
                            args.path("listType").asText(""), args.path("name").asText(""),
                            args.path("category").asText(""), args.path("note").asText(""),
                            args.path("expectDuration").asText(""), args.path("season").asText("")));
                case "listFavorites":
                    return toJson(travelTools.listFavorites(username, args.path("listType").asText("")));
                // —— 五大资产 Tab：偏好档案 ——
                case "listPreferenceTemplates":
                    return toJson(travelTools.listPreferenceTemplates(username));
                case "activatePreferenceTemplate":
                    return toJson(travelTools.activatePreferenceTemplate(username,
                            args.path("templateId").asInt(0)));
                // —— 五大资产 Tab：工具箱 ——
                case "saveBudgetEntry":
                    return toJson(travelTools.saveBudgetEntry(username,
                            args.path("tripId").asInt(0), args.path("category").asText(""),
                            args.path("item").asText(""),
                            args.path("amount").isNumber() ? args.path("amount").asDouble() : 0d,
                            args.path("entryDate").asText("")));
                case "getBudgetSummary":
                    return toJson(travelTools.getBudgetSummary(username, args.path("tripId").asInt(0)));
                case "setBudgetThreshold":
                    return toJson(travelTools.setBudgetThreshold(username, args.path("amount").asDouble(0)));
                case "savePackingTemplate":
                    return toJson(travelTools.savePackingTemplate(username,
                            args.path("name").asText(""), args.path("items").asText("")));
                case "listPackingTemplates":
                    return toJson(travelTools.listPackingTemplates(username));
                case "saveDocument":
                    return toJson(travelTools.saveDocument(username,
                            args.path("docType").asText(""), args.path("title").asText(""),
                            args.path("docNo").asText(""), args.path("detail").asText(""),
                            args.path("expireTime").asText("")));
                default:
                    return errorJson("未知工具：" + toolName);
            }
        } catch (Exception e) {
            log.warn("工具 {} 执行失败：{}", toolName, e.getMessage());
            return errorJson("工具执行失败：" + e.getMessage());
        }
    }

    // ==================== 动作摘要（给前端的「本次为你做了 N 件事」） ====================

    /**
     * 把一个写操作工具调用翻译成一行中文摘要，供前端动作卡片展示。
     * <p>
     * 只处理 {@link #WRITE_TOOLS} 里的工具；其余（含读类工具）返回 null，不产生卡片。
     * 任何解析失败都返回 null —— 宁可少显示一条，也不要因为摘要逻辑把回答流程弄坏。
     */
    private String describeAction(String toolName, String arguments) {
        try {
            JsonNode args = objectMapper.readTree(
                    arguments == null || arguments.trim().isEmpty() ? "{}" : arguments);
            switch (toolName) {
                case "saveTrip":
                    return "保存行程：" + arg(args, "title");
                case "savePreference":
                    return "记住偏好：" + arg(args, "tag") + " = " + arg(args, "value");
                case "saveCompanion":
                    return "记录同行人：" + arg(args, "name")
                            + (args.path("preferences").asText("").isEmpty()
                               ? "" : "（" + arg(args, "preferences") + "）");
                case "setTripStatus":
                    return "行程状态 → " + arg(args, "status");
                case "saveTripNode":
                    return "更新行程节点：" + arg(args, "title");
                case "rollbackTrip":
                    return "回滚到版本 " + args.path("versionId").asInt(0);
                case "logCheckin":
                    return "打卡：" + arg(args, "place");
                case "genTravelog":
                    return "生成游记草稿（行程 " + args.path("tripId").asInt(0) + "）";
                case "saveFavorite":
                    return "收藏：" + arg(args, "name");
                case "activatePreferenceTemplate":
                    return "启用偏好模板 " + args.path("templateId").asInt(0);
                case "saveBudgetEntry":
                    return "记一笔：" + arg(args, "category") + " " + arg(args, "item")
                            + " " + args.path("amount").asDouble(0);
                case "setBudgetThreshold":
                    return "设置预算告警阈值 " + args.path("amount").asDouble(0);
                case "savePackingTemplate":
                    return "保存行李清单：" + arg(args, "name");
                case "saveDocument":
                    return "归档证件：" + arg(args, "title");
                default:
                    return null;
            }
        } catch (Exception e) {
            log.debug("生成动作摘要失败：tool={}, args={}", toolName, arguments);
            return null;
        }
    }

    /** 取参数值，空串时给一个占位，保证摘要里不会出现「：」后面空着 */
    private String arg(JsonNode args, String field) {
        String v = args.path(field).asText("").trim();
        return v.isEmpty() ? "（未填）" : v;
    }

    // ==================== 小工具 ====================

    private ObjectNode textMessage(String role, String content) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", role);
        node.put("content", content == null ? "" : content);
        return node;
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("解析模型响应失败", e);
            return objectMapper.createObjectNode();
        }
    }

    private String toJson(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String errorJson(String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("error", message);
        return node.toString();
    }

    /** 追加本轮问答到会话记忆，并做长度裁剪（整体删除最旧的整轮） */
    private void remember(List<JsonNode> history, String userInput, String answer) {
        history.add(textMessage("user", userInput));
        history.add(textMessage("assistant", answer));
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }
}
