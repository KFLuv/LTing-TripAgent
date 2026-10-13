package util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;


/**
 * MySQL 数据库连接工具类 (适用于 MySQL 5.x)
 * <p>
 * <b>默认已经不再是 MySQL</b>：没有显式配置 {@code db.url} / {@code DB_URL} 时，
 * 走内嵌的 H2（MySQL 兼容模式），库文件落在用户目录下的
 * {@code .ai-travel-assistant/data/demo}。
 * <p>
 * 这样打包给面试官/朋友时，对方机器上不用先装 MySQL 才能用；
 * 自己开发时在 {@code application.properties} 里配了 {@code db.url}，仍然走 MySQL。
 */
public class MySQLUtil {
    // 数据库连接参数
    //
    // 读取顺序：环境变量 → JVM 系统属性 → application.properties → 默认值。
    // 覆盖方式任选其一：
    //   DB_URL / DB_USER / DB_PASSWORD / DB_DRIVER
    //   -Ddb.url=... -Ddb.password=...
    //   application.properties 里写 db.url / db.username / db.password
    //
    // ⚠️ 这里必须也读 application.properties：
    //    本类是普通工具类（不受 Spring 管理），拿不到 Spring 的 Environment，
    //    之前只读环境变量与 JVM 参数，导致配置文件里写的 db.password 被忽略、
    //    打包后连不上数据库（表现为"目的地加载失败""新建会话失败"）。
    private static final java.util.Properties FILE_PROPS = loadFileProps();

    private static java.util.Properties loadFileProps() {
        java.util.Properties p = new java.util.Properties();
        try (java.io.InputStream in = MySQLUtil.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) {
                p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // 读不到就当没有，后面还有默认值兜底
        }
        return p;
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 内嵌数据库的驱动/账号：H2 自带 sa + 空口令，单文件、免安装 */
    public static final String DEFAULT_DRIVER = "org.h2.Driver";
    public static final String DEFAULT_USER = "sa";

    /** 内嵌库的库文件目录（相对用户目录），SessionConfig 也用它，保证会话表落在同一个库里 */
    private static final String EMBEDDED_DIR = ".ai-travel-assistant/data/demo";

    /**
     * 未显式配置数据库时用的连接串：H2 文件库 + MySQL 兼容模式。
     * <p>
     * {@code MODE=MySQL} 让建表语句、{@code on duplicate key update} 这些 MySQL 写法可用；
     * {@code DATABASE_TO_LOWER} / {@code CASE_INSENSITIVE_IDENTIFIERS} 让大小写不敏感，
     * 免得代码里的大小写差异在 H2 上变成"表不存在"。
     */
    public static String defaultUrl() {
        String home = System.getProperty("user.home", ".");
        return "jdbc:h2:file:" + home.replace('\\', '/') + "/" + EMBEDDED_DIR
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
    }

    private static String driver = cfg("DB_DRIVER", DEFAULT_DRIVER, "db.driver");

    private static String url = cfg("DB_URL", defaultUrl(), "db.url");

    private static String username = cfg("DB_USER", DEFAULT_USER, "db.username");

    private static String password = cfg("DB_PASSWORD", "", "db.password");

    /** 环境变量优先，其次 JVM 系统属性，再次 application.properties，最后默认值 */
    private static String cfg(String envKey, String fallback, String propKey) {
        String v = System.getenv(envKey);
        if (blank(v)) {
            v = System.getProperty(propKey);
        }
        if (blank(v)) {
            v = FILE_PROPS.getProperty(propKey);
        }
        return blank(v) ? fallback : v;
    }

    static {
        try {
            // 加载驱动
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError("Failed to load MySQL JDBC driver:"+e.getMessage());
        }
    }

    /**
     * 获取数据库连接
     * @return Connection 对象
     * @throws SQLException
     */
    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    /**
     * 释放资源
     * @param conn 连接
     * @param stmt Statement
     * @param rs 结果集
     */
    public static void closeResources(Connection conn, Statement stmt, ResultSet rs) {
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            try {
                if (stmt != null) {
                    stmt.close();
                }
            } catch (SQLException e) {
                e.printStackTrace();
            } finally {
                try {
                    if (conn != null) {
                        conn.close();
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 执行查询SQL
     * @param sql SQL语句
     * @param params 参数数组
     * @return ResultSet 结果集
     */
    public static ResultSet executeQuery(String sql, Object... params) {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            conn = getConnection();
            pstmt = conn.prepareStatement(sql);

            // 设置参数
            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    pstmt.setObject(i + 1, params[i]);
                }
            }

            rs = pstmt.executeQuery();
            // 注意: 调用者需要自己关闭ResultSet
            return rs;
        } catch (SQLException e) {
            closeResources(conn, pstmt, rs);
            throw new RuntimeException("执行查询失败", e);
        }
    }

    /**
     * 执行更新SQL (INSERT, UPDATE, DELETE)
     * @param sql SQL语句
     * @param params 参数数组
     * @return 影响的行数
     */
    public static int executeUpdate(String sql, Object... params) {
        Connection conn = null;
        PreparedStatement pstmt = null;

        try {
            conn = getConnection();
            pstmt = conn.prepareStatement(sql);

            // 设置参数
            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    pstmt.setObject(i + 1, params[i]);
                }
            }

            return pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("执行更新失败", e);
        } finally {
            closeResources(conn, pstmt, null);
        }
    }

    /**
     * 测试数据库连接
     */
    public static boolean testConnection() {
        Connection conn = null;
        try {
            conn = getConnection();
            return conn != null&&!conn.isClosed();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                    // ignore
                }
            }
        }
    }

    public static void main(String[] args) {
        boolean flag= testConnection();
        if(flag){
            System.out.println("链接成功");
        }
    }
}
