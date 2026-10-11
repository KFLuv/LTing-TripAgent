package com.iflytek.chatgpt.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import util.MySQLUtil;

import javax.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Agent 附表初始化
 * <p>
 * 偏好记忆引擎、多人协同引擎、GPS 位置引擎需要持久化，分别用到
 * user_preference / trip_companion / spot_guide 三张表；
 * 另外 App 五大资产 Tab（行程库 / 偏好档案 / 轨迹日志 / 灵感收藏 / 工具箱）
 * 又需要 trip_version、trip_node、preference_template 等一批表。
 * 这里在启动时用 {@code create table if not exists} 建好：
 * 只做"新增"，已存在的表和数据都不动；数据库不可用时仅告警，不影响应用启动。
 */
@Component
public class AgentSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(AgentSchemaInitializer.class);

    /** 位置引擎的 POI 现场资料：目的地级的 spot 表不适合放这些细粒度字段 */
    private static final String CREATE_SPOT_GUIDE =
            "create table if not exists spot_guide ("
                    + " id int not null auto_increment primary key,"
                    + " name varchar(100) not null,"
                    + " city varchar(50) null,"
                    + " area varchar(50) null,"
                    + " aliases varchar(255) null,"
                    + " open_time varchar(200) null,"
                    + " closed_day varchar(100) null,"
                    + " booking varchar(300) null,"
                    + " photo_spots varchar(300) null,"
                    + " facilities varchar(300) null,"
                    + " transport varchar(300) null,"
                    + " pitfalls varchar(300) null,"
                    + " tips varchar(300) null,"
                    + " update_time datetime null,"
                    + " unique key uk_guide_name_city (name, city)"
                    + ") default charset=utf8mb4";

    private static final String CREATE_PREFERENCE =
            "create table if not exists user_preference ("
                    + " id int not null auto_increment primary key,"
                    + " username varchar(64) not null,"
                    + " tag varchar(64) not null,"
                    + " value varchar(500) null,"
                    + " update_time datetime null,"
                    + " unique key uk_user_tag (username, tag)"
                    + ") default charset=utf8mb4";

    private static final String CREATE_COMPANION =
            "create table if not exists trip_companion ("
                    + " id int not null auto_increment primary key,"
                    + " owner varchar(64) not null,"
                    + " name varchar(64) not null,"
                    + " preferences varchar(500) null,"
                    + " vote varchar(255) null,"
                    + " update_time datetime null,"
                    + " unique key uk_owner_name (owner, name)"
                    + ") default charset=utf8mb4";

    // ==================== 五大资产 Tab 所需表 ====================

    /** 行程版本快照：每次修改行程自动存一版，支持回退 */
    private static final String CREATE_TRIP_VERSION =
            "create table if not exists trip_version ("
                    + " id int not null auto_increment primary key,"
                    + " trip_id int not null,"
                    + " username varchar(64) not null,"
                    + " version_no int not null,"
                    + " title varchar(120) null,"
                    + " content mediumtext null,"
                    + " change_note varchar(255) null,"
                    + " create_time datetime null,"
                    + " key idx_trip_version (trip_id, username)"
                    + ") default charset=utf8mb4";

    /**
     * 行程节点：时间线节点 / 订单信息 / 预约记录 / 风险标记 / 节点备注 / 备选路线存档
     * 统一用 {@code node_type} 区分，避免为每种小对象单独建表。
     */
    private static final String CREATE_TRIP_NODE =
            "create table if not exists trip_node ("
                    + " id int not null auto_increment primary key,"
                    + " trip_id int not null,"
                    + " username varchar(64) not null,"
                    + " node_type varchar(20) not null,"
                    + " title varchar(120) null,"
                    + " detail text null,"
                    + " node_time varchar(60) null,"
                    + " status varchar(20) null,"
                    + " cost decimal(10,2) null,"
                    + " create_time datetime null,"
                    + " key idx_trip_node (trip_id, username, node_type)"
                    + ") default charset=utf8mb4";

    /** 偏好模板主表：一名用户可有多套可切换的偏好模板 */
    private static final String CREATE_PREFERENCE_TEMPLATE =
            "create table if not exists preference_template ("
                    + " id int not null auto_increment primary key,"
                    + " username varchar(64) not null,"
                    + " name varchar(60) not null,"
                    + " is_active tinyint not null default 0,"
                    + " create_time datetime null,"
                    + " key idx_pref_template_user (username)"
                    + ") default charset=utf8mb4";

    /** 偏好模板明细：模板下的一个个标签 */
    private static final String CREATE_PREFERENCE_ITEM =
            "create table if not exists preference_item ("
                    + " id int not null auto_increment primary key,"
                    + " template_id int not null,"
                    + " username varchar(64) not null,"
                    + " tag varchar(64) not null,"
                    + " value varchar(500) null,"
                    + " key idx_pref_item_template (template_id),"
                    + " key idx_pref_item_user (username)"
                    + ") default charset=utf8mb4";

    /** 旅途轨迹日志：打卡点位 / 喜欢避雷评价 / 随手备注 / 游玩耗时 / 实拍标记 */
    private static final String CREATE_TRAVEL_LOG =
            "create table if not exists travel_log ("
                    + " id int not null auto_increment primary key,"
                    + " username varchar(64) not null,"
                    + " trip_id int null,"
                    + " place varchar(120) null,"
                    + " lat double null,"
                    + " lng double null,"
                    + " rating varchar(20) null,"
                    + " note text null,"
                    + " duration_min int null,"
                    + " photos varchar(500) null,"
                    + " log_time datetime null,"
                    + " create_time datetime null,"
                    + " key idx_travel_log_user (username)"
                    + ") default charset=utf8mb4";

    /** 结构化游记：行程结束后自动生成的草稿 / 亮点总结 / 踩坑汇总 */
    private static final String CREATE_TRAVELOG =
            "create table if not exists travelog ("
                    + " id int not null auto_increment primary key,"
                    + " username varchar(64) not null,"
                    + " trip_id int null,"
                    + " title varchar(120) null,"
                    + " draft mediumtext null,"
                    + " highlights text null,"
                    + " pitfalls text null,"
                    + " create_time datetime null,"
                    + " key idx_travelog_user (username)"
                    + ") default charset=utf8mb4";

    /** 灵感收藏库：自定义分组文件夹 */
    private static final String CREATE_FAVORITE_FOLDER =
            "create table if not exists favorite_folder ("
                    + " id int not null auto_increment primary key,"
                    + " username varchar(64) not null,"
                    + " name varchar(60) not null,"
                    + " create_time datetime null,"
                    + " key idx_fav_folder_user (username)"
                    + ") default charset=utf8mb4";

    /**
     * 灵感收藏条目：收藏 / 避雷黑名单 / 待打卡 / 意向清单统一放这里，
     * 用 {@code list_type} 区分（favorite / blacklist / todo / wish）。
     */
    private static final String CREATE_FAVORITE_ITEM =
            "create table if not exists favorite_item ("
                    + " id int not null auto_increment primary key,"
                    + " username varchar(64) not null,"
                    + " folder_id int null,"
                    + " list_type varchar(20) not null default 'favorite',"
                    + " name varchar(120) not null,"
                    + " category varchar(40) null,"
                    + " note varchar(500) null,"
                    + " expect_duration varchar(40) null,"
                    + " season varchar(40) null,"
                    + " create_time datetime null,"
                    + " key idx_fav_item_user (username)"
                    + ") default charset=utf8mb4";

    /** 工具箱：订单 & 证件 & 应急记录 & 当地工具模板 存档 */
    private static final String CREATE_TOOLBOX_DOCUMENT =
            "create table if not exists toolbox_document ("
                    + " id int not null auto_increment primary key,"
                    + " username varchar(64) not null,"
                    + " doc_type varchar(20) not null,"
                    + " title varchar(120) not null,"
                    + " doc_no varchar(120) null,"
                    + " detail varchar(500) null,"
                    + " expire_time varchar(40) null,"
                    + " create_time datetime null,"
                    + " key idx_toolbox_doc_user (username)"
                    + ") default charset=utf8mb4";

    /** 行李清单自定义模板库 */
    private static final String CREATE_PACKING_TEMPLATE =
            "create table if not exists packing_template ("
                    + " id int not null auto_increment primary key,"
                    + " username varchar(64) not null,"
                    + " name varchar(60) not null,"
                    + " items text null,"
                    + " create_time datetime null,"
                    + " key idx_packing_user (username)"
                    + ") default charset=utf8mb4";

    /** 预算账本：分项记账（交通 / 住宿 / 餐饮 / 门票 / 其他） */
    private static final String CREATE_BUDGET_ENTRY =
            "create table if not exists budget_entry ("
                    + " id int not null auto_increment primary key,"
                    + " username varchar(64) not null,"
                    + " trip_id int null,"
                    + " category varchar(20) not null,"
                    + " item varchar(120) null,"
                    + " amount decimal(10,2) not null default 0,"
                    + " entry_date varchar(30) null,"
                    + " create_time datetime null,"
                    + " key idx_budget_user (username)"
                    + ") default charset=utf8mb4";

    /**
     * 对话历史（Agent 记忆）。
     * <p>
     * 原先历史只存 HttpSession，关浏览器 / 重启后端即丢，用户每次进来都像第一次对话。
     * 落库后跨会话可续。只存 user / assistant 的纯文本，不存工具调用链——
     * 回放 tool_calls 容易触发「没有对应 tool 响应」的 400。
     */
    private static final String CREATE_CHAT_MESSAGE =
            "create table if not exists chat_message ("
                    + " id bigint not null auto_increment primary key,"
                    + " username varchar(64) not null,"
                    + " role varchar(16) not null,"
                    + " content mediumtext null,"
                    + " create_time datetime not null,"
                    + " key idx_chat_user (username, id)"
                    + ") default charset=utf8mb4";

    /**
     * 对话会话（多会话）。
     * <p>
     * 原先一个用户只有一条连续对话流，进页面永远接在上一次后面，无法"开新话题"。
     * 加这张表后，每次"新建对话"产出一个会话，{@code chat_message.session_id} 归属到各自会话，
     * 侧栏可列出、切换、改名、删除，重开页面也能接着上次那个会话继续。
     */
    private static final String CREATE_CHAT_SESSION =
            "create table if not exists chat_session ("
                    + " id bigint not null auto_increment primary key,"
                    + " username varchar(64) not null,"
                    + " title varchar(120) not null default '新对话',"
                    + " create_time datetime not null,"
                    + " update_time datetime not null,"
                    + " key idx_session_user (username, update_time)"
                    + ") default charset=utf8mb4";

    /**
     * 会话持久化表（Tomcat JDBCStore 用）。
     * <p>
     * 原来会话只在内存里，重启即丢，用户回来发现"会话直接没了"。
     * {@code config/SessionConfig} 把 Tomcat 的会话仓库改成 JDBCStore 后，
     * 会话会写进这张表，重启可恢复。JDBCStore 只负责读写、不会自建表，
     * 所以这里建好。
     * <p>
     * 五列结构对应 Tomcat 9（本项目是 Spring Boot 2.7.18 / Tomcat 9.0.83）；
     * Tomcat 10 起改成 session_id / session_data / is_valid 三列，不能混用。
     * {@code session_data} 用 mediumblob 存序列化后的会话对象。
     */
    private static final String CREATE_TOMCAT_SESSIONS =
            "create table if not exists tomcat_sessions ("
                    + " session_id varchar(100) not null primary key,"
                    + " valid_session char(1) not null,"
                    + " max_inactive int not null,"
                    + " last_access bigint not null,"
                    + " app_name varchar(255) null,"
                    + " session_data mediumblob null,"
                    + " key kapp_name (app_name)"
                    + ") default charset=utf8mb4";

    @PostConstruct
    public void init() {
        createIfAbsent("user_preference", CREATE_PREFERENCE);
        createIfAbsent("trip_companion", CREATE_COMPANION);
        createIfAbsent("spot_guide", CREATE_SPOT_GUIDE);
        createIfAbsent("chat_message", CREATE_CHAT_MESSAGE);
        createIfAbsent("chat_session", CREATE_CHAT_SESSION);
        createIfAbsent("tomcat_sessions", CREATE_TOMCAT_SESSIONS);
        // 给老的 chat_message 补 session_id 列，并把无归属的历史消息收进一个「历史对话」会话（老数据不丢）
        addColumnIfAbsent("chat_message", "session_id", "bigint null");
        migrateLegacyMessages();
        // —— 五大资产 Tab ——
        createIfAbsent("trip_version", CREATE_TRIP_VERSION);
        createIfAbsent("trip_node", CREATE_TRIP_NODE);
        createIfAbsent("preference_template", CREATE_PREFERENCE_TEMPLATE);
        createIfAbsent("preference_item", CREATE_PREFERENCE_ITEM);
        createIfAbsent("travel_log", CREATE_TRAVEL_LOG);
        createIfAbsent("travelog", CREATE_TRAVELOG);
        createIfAbsent("favorite_folder", CREATE_FAVORITE_FOLDER);
        createIfAbsent("favorite_item", CREATE_FAVORITE_ITEM);
        createIfAbsent("toolbox_document", CREATE_TOOLBOX_DOCUMENT);
        createIfAbsent("packing_template", CREATE_PACKING_TEMPLATE);
        createIfAbsent("budget_entry", CREATE_BUDGET_ENTRY);
        // —— 给老的 trip 表补"状态 / 更新时间"两列（老数据不受影响） ——
        addColumnIfAbsent("trip", "status", "varchar(20) not null default '规划中'");
        addColumnIfAbsent("trip", "update_time", "datetime null");
    }

    private void createIfAbsent(String table, String ddl) {
        try {
            MySQLUtil.executeUpdate(ddl);
            log.info("Agent 附表已就绪：{}", table);
        } catch (Exception e) {
            log.warn("初始化附表 {} 失败（不影响启动，偏好/协同功能暂不可用）：{}", table, e.getMessage());
        }
    }

    /**
     * 给已存在的表补列（MySQL 5.x 的 {@code add column} 不支持 if not exists）。
     * 先查 information_schema 判断列是否已存在，缺了才加；表不存在或数据库不可用时仅告警。
     */
    private void addColumnIfAbsent(String table, String column, String definition) {
        String checkSql = "select count(*) from information_schema.columns"
                + " where table_schema = database() and table_name = ? and column_name = ?";
        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            boolean exists;
            try (ResultSet rs = ps.executeQuery()) {
                exists = rs.next() && rs.getInt(1) > 0;
            }
            if (exists) {
                return;
            }
        } catch (Exception e) {
            log.warn("检查 {}.{} 列是否存在失败（跳过补列）：{}", table, column, e.getMessage());
            return;
        }
        try {
            MySQLUtil.executeUpdate("alter table " + table + " add column " + column + " " + definition);
            log.info("已为 {} 补充列：{}", table, column);
        } catch (Exception e) {
            log.warn("为 {}.{} 补列失败（不影响启动）：{}", table, column, e.getMessage());
        }
    }

    /**
     * 把加 {@code session_id} 之前的旧对话收拢到一个「历史对话」会话里，避免老消息变成孤儿。
     * <p>
     * 幂等：执行一次后 {@code session_id} 不再为空，下次启动查不到空行，不会重复建会话。
     * 任何异常仅告警——迁移失败不应阻断启动。
     */
    private void migrateLegacyMessages() {
        try {
            MySQLUtil.executeUpdate(
                    "insert into chat_session(username, title, create_time, update_time)"
                            + " select m.username, '历史对话', now(), now() from chat_message m"
                            + " where m.session_id is null group by m.username");
            MySQLUtil.executeUpdate(
                    "update chat_message m join chat_session s"
                            + " on s.username = m.username and s.title = '历史对话'"
                            + " set m.session_id = s.id where m.session_id is null");
        } catch (Exception e) {
            log.warn("旧对话迁移失败（可忽略）：{}", e.getMessage());
        }
    }
}
