package util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;


/**
 * MySQL 数据库连接工具类 (适用于 MySQL 5.x)
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

    private static String driver = cfg("DB_DRIVER", "com.mysql.jdbc.Driver", "db.driver");

    private static String url = cfg("DB_URL",
            "jdbc:mysql://localhost:3306/user-mannager?useSSL=false&allowPublicKeyRetrieval=true",
            "db.url");

    private static String username = cfg("DB_USER", "root", "db.username");

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