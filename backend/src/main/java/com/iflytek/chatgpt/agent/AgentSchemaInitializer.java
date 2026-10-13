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

    /**
     * 账号表。
     * <p>
     * 登录、注册、改密、取用户信息都读它，但此前全项目<b>没有任何地方创建这张表</b>——
     * 它是原课程项目遗留的表，一直被"假设已存在"。于是换一台干净的 MySQL，
     * 注册会直接报「表不存在」，登录与身份识别也就无从谈起。这里补上 DDL。
     */
    private static final String CREATE_USER =
            "create table if not exists `user` ("
                    + " id int not null auto_increment primary key,"
                    + " username varchar(50) not null,"
                    + " password varchar(50) not null,"
                    + " email varchar(100) null,"
                    + " unique key uk_user_username (username)"
                    + ") default charset=utf8mb4";

    /**
     * 目的地库：侧栏「热门目的地」与工具 {@code searchDestination} 都读它。
     * 同样是遗留表、此前没有建表语句，空库上首页会看起来"没内容"，所以下面还会灌种子数据。
     */
    private static final String CREATE_SPOT =
            "create table if not exists spot ("
                    + " id int not null auto_increment primary key,"
                    + " name varchar(64) not null,"
                    + " city varchar(64) not null,"
                    + " category varchar(32) not null,"
                    + " icon varchar(32) not null,"
                    + " description varchar(255) null,"
                    + " keywords varchar(255) null"
                    + ") default charset=utf8mb4";

    /**
     * 行程本主表：{@code /trips/*} 的全部读写都指向它（节点、版本快照另有两张附表）。
     * 此前只在它存在的前提下补 status / update_time 两列，从未建表。
     */
    private static final String CREATE_TRIP =
            "create table if not exists trip ("
                    + " id int not null auto_increment primary key,"
                    + " username varchar(50) not null,"
                    + " title varchar(128) not null,"
                    + " content text not null,"
                    + " create_time datetime not null,"
                    + " status varchar(20) not null default '规划中',"
                    + " update_time datetime null,"
                    + " key idx_trip_user (username)"
                    + ") default charset=utf8mb4";

    /**
     * 目的地种子数据，仅在 spot 表为空时写入（已有数据一律不动）。
     * <p>
     * 每行字段顺序：名称 / 城市 / 分类 / 图标 / 简介 / 检索关键词。
     */
    private static final String[][] SPOT_SEED = {
            {"张家界", "湖南·张家界", "山水自然", "fa-mountain",
                    "三千奇峰拔地而起，云雾缭绕的仙境", "张家界 森林公园 天门山 玻璃桥 湖南 自然 爬山 仙境"},
            {"黄山", "安徽·黄山", "山水自然", "fa-mountain",
                    "奇松怪石云海温泉，五岳归来不看山", "黄山 迎客松 云海 日出 安徽 自然 爬山"},
            {"九寨沟", "四川·阿坝", "山水自然", "fa-mountain",
                    "五花海与彩林，被称作童话世界", "九寨沟 四川 彩池 瀑布 自然 水 秋色"},
            {"三亚", "海南·三亚", "滨海度假", "fa-umbrella-beach",
                    "阳光沙滩椰林，人称东方夏威夷", "三亚 海南 海岛 蜈支洲 亚龙湾 潜水 度假 海滩 海鲜"},
            {"厦门", "福建·厦门", "滨海度假", "fa-umbrella-beach",
                    "鼓浪屿老别墅与环岛路的海风", "厦门 鼓浪屿 福建 海岛 骑行 度假 文艺 海"},
            {"青岛", "山东·青岛", "滨海度假", "fa-umbrella-beach",
                    "红瓦绿树碧海蓝天，啤酒配蛤蜊", "青岛 山东 海鲜 啤酒 栈桥 八大关 度假 海"},
            {"故宫", "北京·东城", "历史文化", "fa-landmark",
                    "明清两朝皇宫，六百年紫禁城", "故宫 北京 紫禁城 博物馆 历史 古建筑 宫廷"},
            {"兵马俑", "陕西·西安", "历史文化", "fa-landmark",
                    "秦始皇陵地下军阵，世界第八大奇迹", "兵马俑 西安 陕西 秦朝 历史 博物馆 古都"},
            {"丽江古城", "云南·丽江", "历史文化", "fa-landmark",
                    "纳西古城与玉龙雪山相依", "丽江 云南 古城 玉龙雪山 束河 历史 古镇 客栈"},
            {"上海", "上海·黄浦", "都市旅行", "fa-city",
                    "外滩夜景与梧桐区的 citywalk", "上海 外滩 陆家嘴 都市 购物 夜景 迪士尼 citywalk"},
            {"香港", "香港·中西区", "都市旅行", "fa-city",
                    "维港天际线与茶餐厅的烟火气", "香港 维多利亚港 迪士尼 购物 都市 美食 免税"},
            {"成都", "四川·成都", "都市旅行", "fa-city",
                    "宽窄巷子与火锅，熊猫基地看团子", "成都 四川 熊猫 火锅 都江堰 都市 美食 慢生活"},
            {"潮汕美食线", "广东·汕头", "主题线路", "fa-route",
                    "牛肉火锅、粿条、生腌，一天五顿", "潮汕 汕头 美食 牛肉火锅 粿条 主题 线路 吃"},
            {"川西摄影线", "四川·甘孜", "主题线路", "fa-route",
                    "稻城亚丁与新都桥光影长廊", "川西 稻城 亚丁 新都桥 摄影 自驾 主题 线路"},
            {"青海大环线", "青海·西宁", "主题线路", "fa-route",
                    "青海湖、茶卡盐湖、张掖丹霞一路串起来", "青海 自驾 青海湖 茶卡盐湖 大环线 主题 线路"},
            {"日本樱花季", "日本·东京/京都", "海外旅行", "fa-globe-asia",
                    "三月下旬关西赏樱经典线路", "日本 樱花 东京 京都 大阪 海外 出境 赏樱"},
            {"泰国普吉岛", "泰国·普吉", "海外旅行", "fa-globe-asia",
                    "海岛度假的性价比之王", "泰国 普吉 海岛 东南亚 海外 出境 度假 潜水"},
            {"意大利经典线", "意大利·罗马", "海外旅行", "fa-globe-asia",
                    "从罗马斗兽场一路走到威尼斯水城", "意大利 罗马 佛罗伦萨 威尼斯 欧洲 海外 历史 艺术"}
    };

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
                    // `value` 在 H2 里是保留字；反引号在 MySQL 与 H2 上都合法
                    + " `value` varchar(500) null,"
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
                    + " `value` varchar(500) null,"
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

    /** 用户个人资料：头像 / 昵称 / 标签 / 简介 / 兴趣偏好，一人一行 */
    private static final String CREATE_USER_PROFILE =
            "create table if not exists user_profile ("
                    + " id int not null auto_increment primary key,"
                    + " username varchar(64) not null,"
                    + " nickname varchar(60) null,"
                    + " avatar mediumtext null,"
                    + " tags varchar(300) null,"
                    + " bio varchar(500) null,"
                    + " interests varchar(500) null,"
                    + " update_time datetime null,"
                    + " unique key uk_profile_user (username)"
                    + ") default charset=utf8mb4";

    /**
     * 朋友圈动态：正文 + 自动记录的时间与地理位置 + 可选图片。
     * 图片以 data URL 形式存在 {@code images}（JSON 数组，最多 3 张），
     * 省掉对象存储与静态目录映射，演示场景够用；生产应换成对象存储 + URL。
     */
    private static final String CREATE_MOMENT =
            "create table if not exists moment ("
                    + " id int not null auto_increment primary key,"
                    + " username varchar(64) not null,"
                    + " content varchar(2000) not null,"
                    + " place varchar(120) null,"
                    + " lat double null,"
                    + " lng double null,"
                    + " images mediumtext null,"
                    + " create_time datetime not null,"
                    + " key idx_moment_user (username, id)"
                    + ") default charset=utf8mb4";

    /** 商户点评：附近模块自己的评分与点评（数据源给商户与官方评分，点评是用户在这条 POI 上留的） */
    /** 好友关系：双向各存一行（owner → friend），便于"看对方主页"时判断权限 */
    private static final String CREATE_FRIENDSHIP =
            "create table if not exists friendship ("
                    + " id int not null auto_increment primary key,"
                    + " owner varchar(64) not null,"
                    + " friend varchar(64) not null,"
                    + " create_time datetime null,"
                    + " unique key uk_friend (owner, friend),"
                    + " key idx_friend_owner (owner)"
                    + ") default charset=utf8mb4";

    /** 商户菜单：数据源不提供菜品，这里存用户/AI 自己录的招牌与价位，供详情页展示 */
    private static final String CREATE_POI_MENU =
            "create table if not exists poi_menu ("
                    + " id int not null auto_increment primary key,"
                    + " poi_id varchar(120) not null,"
                    + " name varchar(120) not null,"
                    + " price varchar(40) null,"
                    + " note varchar(200) null,"
                    + " username varchar(64) null,"
                    + " create_time datetime null,"
                    + " key idx_menu_poi (poi_id)"
                    + ") default charset=utf8mb4";

    private static final String CREATE_POI_REVIEW =
            "create table if not exists poi_review ("
                    + " id int not null auto_increment primary key,"
                    + " poi_id varchar(120) not null,"
                    + " poi_name varchar(120) null,"
                    + " username varchar(64) not null,"
                    + " rating int not null,"
                    + " content varchar(1000) null,"
                    + " create_time datetime null,"
                    + " key idx_review_poi (poi_id)"
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
        // —— 基础表：账号 / 目的地 / 行程本 ——
        // 这三张是原课程项目遗留的表，此前全项目只"读"、从不"建"：
        // 换一台干净的 MySQL，注册（user）、目的地（spot）、行程本（trip）会同时失效。
        // 这里补齐 DDL（if not exists，对已有库零影响），空库也能一次跑通。
        createIfAbsent("user", CREATE_USER);
        createIfAbsent("spot", CREATE_SPOT);
        createIfAbsent("trip", CREATE_TRIP);
        seedSpotsIfEmpty();
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
        createIfAbsent("toolbox_document", CREATE_TOOLBOX_DOCUMENT);
        createIfAbsent("packing_template", CREATE_PACKING_TEMPLATE);
        createIfAbsent("budget_entry", CREATE_BUDGET_ENTRY);
        // —— 三个新模块：个人资料 / 朋友圈 / 附近商户点评 ——
        createIfAbsent("user_profile", CREATE_USER_PROFILE);
        createIfAbsent("moment", CREATE_MOMENT);
        createIfAbsent("poi_review", CREATE_POI_REVIEW);
        createIfAbsent("friendship", CREATE_FRIENDSHIP);
        createIfAbsent("poi_menu", CREATE_POI_MENU);
        // —— 可见权限：动态/行程/打卡/收藏/点评各自一份，默认公开 ——
        addColumnIfAbsent("moment", "visibility", "varchar(12) not null default 'public'");
        addColumnIfAbsent("trip", "visibility", "varchar(12) not null default 'public'");
        addColumnIfAbsent("travel_log", "visibility", "varchar(12) not null default 'public'");
        addColumnIfAbsent("poi_review", "visibility", "varchar(12) not null default 'public'");
        // 评价配图：与动态/打卡一样存 data URL（本机 UGC）
        addColumnIfAbsent("poi_review", "images", "mediumtext null");
        // 打卡可以打在"附近吃喝玩乐"的某家店上：记下 poi_id，商家详情页就能反查这些打卡
        addColumnIfAbsent("travel_log", "poi_id", "varchar(120) null");
        addColumnIfAbsent("travel_log", "poi_name", "varchar(120) null");
        // 打卡配图：与动态同样存 data URL（photos 那列只有 500 字符，放不下）
        addColumnIfAbsent("travel_log", "images", "mediumtext null");
        // 邀请码：加好友用；首次读取资料时懒生成
        addColumnIfAbsent("user_profile", "invite_code", "varchar(16) null");
        // —— 给老的 trip 表补"状态 / 更新时间"两列（老数据不受影响） ——
        addColumnIfAbsent("trip", "status", "varchar(20) not null default '规划中'");
        addColumnIfAbsent("trip", "update_time", "datetime null");
    }

    private void createIfAbsent(String table, String ddl) {
        try {
            MySQLUtil.executeUpdate(ddl);
            log.info("Agent 附表已就绪：{}", table);
        } catch (Exception e) {
            log.warn("初始化附表 {} 失败（不影响启动，偏好/协同功能暂不可用）：{}", table, why(e));
        }
    }

    /**
     * 取异常链里真正有用的那句。
     * <p>
     * {@code MySQLUtil} 会把 {@code SQLException} 包成 "执行更新失败"，
     * 只打 {@code getMessage()} 会把真正的 SQL 报错整条丢掉，排查时等于没有日志。
     */
    private static String why(Throwable e) {
        StringBuilder sb = new StringBuilder(String.valueOf(e.getMessage()));
        Throwable cause = e.getCause();
        int depth = 0;
        while (cause != null && cause != e && depth++ < 5) {
            sb.append(" ← ").append(cause.getMessage());
            cause = cause.getCause();
        }
        return sb.toString();
    }

    /**
     * 目的地表为空时灌入种子数据。
     * <p>
     * 只在空表时插入：已有数据的库（含用户自己补过的目的地）原样不动，
     * 所以对正在使用的环境是零影响，对全新环境则保证首页有内容可点。
     */
    private void seedSpotsIfEmpty() {
        try {
            try (Connection conn = MySQLUtil.getConnection();
                 PreparedStatement ps = conn.prepareStatement("select count(*) from spot");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return;
                }
            }
            for (String[] row : SPOT_SEED) {
                MySQLUtil.executeUpdate(
                        "insert into spot(name, city, category, icon, description, keywords)"
                                + " values(?, ?, ?, ?, ?, ?)",
                        row[0], row[1], row[2], row[3], row[4], row[5]);
            }
            log.info("目的地库为空，已写入 {} 条种子数据", SPOT_SEED.length);
        } catch (Exception e) {
            log.warn("写入目的地种子数据失败（不影响启动）：{}", why(e));
        }
    }

    /**
     * 给已存在的表补列（MySQL 5.x 的 {@code add column} 不支持 if not exists）。
     * <p>
     * 直接尝试 {@code alter table ... add column}，把「列已存在」当成成功即可：
     * 两种数据库的报错文案都含 "Duplicate column"。
     * 旧的"先查 information_schema 再补"写法在 H2 上会误判——它的 schema 名是 PUBLIC，
     * 与 {@code database()} 对不上，于是每次都以为列不存在、白跑一次 ALTER 并报错。
     */
    private void addColumnIfAbsent(String table, String column, String definition) {
        try {
            MySQLUtil.executeUpdate("alter table " + table + " add column " + column + " " + definition);
            log.info("已为 {} 补充列：{}", table, column);
        } catch (Exception e) {
            if (why(e).toLowerCase().contains("duplicate column")) {
                return;   // 列已存在，正是期望的状态
            }
            log.warn("为 {}.{} 补列失败（不影响启动）：{}", table, column, why(e));
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
            // MySQL 的 update ... join ... set 是专有语法，H2 不认；
            // 相关子查询两边都支持，效果相同。
            MySQLUtil.executeUpdate(
                    "update chat_message set session_id = (select s.id from chat_session s"
                            + " where s.username = chat_message.username and s.title = '历史对话')"
                            + " where session_id is null");
        } catch (Exception e) {
            log.warn("旧对话迁移失败（可忽略）：{}", why(e));
        }
    }
}
