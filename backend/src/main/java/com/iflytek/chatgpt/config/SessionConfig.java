package com.iflytek.chatgpt.config;

import org.apache.catalina.core.StandardContext;
import org.apache.catalina.session.JDBCStore;
import org.apache.catalina.session.PersistentManager;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会话持久化配置。
 *
 * <p>原来会话只存在 Tomcat 内存里：关掉桌面端（后端进程随之退出）或重启后端，
 * 登录态与「当前是谁」全部消失，用户回来会发现"我的会话直接没了"。
 *
 * <p>这里改用 Tomcat 的 {@link PersistentManager} + {@link JDBCStore}，
 * 把会话写进 MySQL 的 <b>tomcat_sessions</b> 表：只要浏览器还带着 JSESSIONID，
 * 重启后就能把会话恢复回来，登录态和身份都还在。
 *
 * <p>表由 Tomcat 自己建（{@code JDBCStore.createTable}），用的是 Tomcat 9 的五列结构，
 * 与 pom 里的 Spring Boot 2.7.18 对应；Tomcat 10 起改成三列，不能混用。
 *
 * <p>数据源不用自己提供：JDBCStore 支持直接给连接参数，
 * 它会自己构造一个内部 DataSource。这样既不需要连接池依赖，
 * 也不需要 spring-jdbc（项目里都没有）。
 */
@Configuration
public class SessionConfig {

    // 与 util.MySQLUtil 保持一致的连接参数读取方式：
    // 环境变量 → JVM 系统属性 → application.properties → 默认值。
    //
    // ⚠️ 必须也读 application.properties：这几个是 static final 字段，
    //    在类初始化时就求值，拿不到 Spring 的 Environment，
    //    只读环境变量会导致配置里写的 db.password 被忽略。
    private static final java.util.Properties FILE_PROPS = loadFileProps();

    private static java.util.Properties loadFileProps() {
        java.util.Properties p = new java.util.Properties();
        try (java.io.InputStream in = SessionConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) {
                p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // 读不到就靠默认值
        }
        return p;
    }

    private static final String DB_URL = cfg("DB_URL",
            "jdbc:mysql://localhost:3306/user-mannager?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8",
            "db.url");
    private static final String DB_DRIVER = cfg("DB_DRIVER", "com.mysql.jdbc.Driver", "db.driver");
    private static final String DB_USER = cfg("DB_USER", "root", "db.username");
    private static final String DB_PASSWORD = cfg("DB_PASSWORD", "", "db.password");

    /** 环境变量优先，其次 JVM 系统属性，再次 application.properties，最后默认值 */
    private static String cfg(String envKey, String fallback, String propKey) {
        String v = System.getenv(envKey);
        if (v == null || v.trim().isEmpty()) {
            v = System.getProperty(propKey);
        }
        if (v == null || v.trim().isEmpty()) {
            v = FILE_PROPS.getProperty(propKey);
        }
        return (v == null || v.trim().isEmpty()) ? fallback : v;
    }

    private static final String TABLE = "tomcat_sessions";

    /** 会话闲置多久换出到数据库（分钟） */
    private static final int IDLE_SWAP_MINUTES = 1;

    /**
     * 会话有效期（秒）：30 天。
     * <p>必须在这里显式设置：本类手写了 {@code ServletWebServerFactory} Bean，
     * 会绕过 Spring Boot 读取 {@code server.servlet.session.timeout} 的那一步，
     * 不写的话会话仍是容器默认的 30 分钟。
     */
    private static final int SESSION_TIMEOUT_SECONDS = 30 * 24 * 60 * 60;

    @Bean
    public ServletWebServerFactory servletContainer() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory() {
            @Override
            protected void postProcessContext(org.apache.catalina.Context context) {
                StandardContext std = (StandardContext) context;

                JDBCStore store = new JDBCStore();
                // 让 JDBCStore 用这几个参数自己建 DataSource（内部的 localDataSource）
                store.setDriverName(DB_DRIVER);
                store.setConnectionURL(DB_URL);
                store.setConnectionName(DB_USER);
                store.setConnectionPassword(DB_PASSWORD);
                store.setSessionTable(TABLE);
                // 列名必须与 AgentSchemaInitializer 里建的 tomcat_sessions 完全一致，
                // 否则 JDBCStore 生成的 SQL 会报表/列不存在。
                store.setSessionIdCol("session_id");
                store.setSessionDataCol("session_data");
                store.setSessionValidCol("valid_session");
                store.setSessionMaxInactiveCol("max_inactive");
                store.setSessionLastAccessedCol("last_access");
                store.setSessionAppCol("app_name");

                PersistentManager manager = new PersistentManager();
                manager.setStore(store);
                // 闲置超过 1 分钟就换出到数据库：
                // 「关掉软件去干别的事」这段时间，会话实际躺在 MySQL 里
                manager.setMaxIdleSwap(IDLE_SWAP_MINUTES);
                // 定期把会话备份进数据库，防止进程被强杀时丢数据
                manager.setMaxIdleBackup(0);
                // 不要因为内存压力随手换出
                manager.setMinIdleSwap(-1);
                // 后台检查频率
                manager.setProcessExpiresFrequency(6);
                // 关闭时把会话存下来，重启才能恢复
                manager.setSaveOnRestart(true);

                std.setManager(manager);
                std.setUseHttpOnly(true);
                // 30 天：容器级别的会话有效期（秒），JDBCStore 会把它写进 max_inactive 列
                std.setSessionTimeout(SESSION_TIMEOUT_SECONDS);
            }
        };

        // 会话 cookie 的有效期必须在「上下文初始化之前」经由工厂设置。
        // 放到 postProcessContext 里会抛
        // IllegalStateException: Property [max age] cannot be added ... as the context has been initialised。
        // 浏览器不带 JSESSIONID，重启后就恢复不了会话，所以这一步不能省。
        factory.getSession().getCookie().setMaxAge(java.time.Duration.ofSeconds(SESSION_TIMEOUT_SECONDS));
        factory.getSession().getCookie().setHttpOnly(true);
        factory.getSession().getCookie().setPath("/chat");
        factory.getSession().getCookie().setName("JSESSIONID");

        // 诊断：确认 cookie 有效期确实被设上了（排查"Set-Cookie 里没有 Max-Age"）
        System.out.println("[SessionConfig] sessionCookie maxAge="
                + factory.getSession().getCookie().getMaxAge()
                + "s, name=" + factory.getSession().getCookie().getName()
                + ", path=" + factory.getSession().getCookie().getPath());
        return factory;
    }
}
