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
    // 读取顺序：环境变量 → JVM 系统属性 → 默认值。
    // 这样仓库里不出现字面口令，部署时也用同一种方式覆盖：
    //   DB_URL / DB_USER / DB_PASSWORD / DB_DRIVER
    //
    // 说明：原来这里是写死的常量（url / root / 123456），
    // 公开仓库里不该挂字面口令，也难在部署时改名改密。
    private static String driver = cfg("DB_DRIVER", "com.mysql.jdbc.Driver", "db.driver");

    private static String url = cfg("DB_URL",
            "jdbc:mysql://localhost:3306/user-mannager?useSSL=false&allowPublicKeyRetrieval=true",
            "db.url");

    private static String username = cfg("DB_USER", "root", "db.username");

    /** 默认留空 —— 需要口令时用环境变量 DB_PASSWORD 或 -Ddb.password=xxx 提供 */
    private static String password = cfg("DB_PASSWORD", "", "db.password");

    /** 环境变量优先，其次 JVM 系统属性，最后默认值 */
    private static String cfg(String envKey, String fallback, String propKey) {
        String v = System.getenv(envKey);
        if (v == null || v.trim().isEmpty()) {
            v = System.getProperty(propKey);
        }
        return (v == null || v.trim().isEmpty()) ? fallback : v;
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