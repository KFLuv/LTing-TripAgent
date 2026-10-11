package com.iflytek.chatgpt.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import util.MySQLUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 目的地：给前端侧边栏提供真实数据，并支持关键词搜索
 * <p>
 * 原来侧边栏那 6 个卡片是写死在 HTML 里的，现在全部来自 spot 表。
 */
@RestController
public class DestinationController {

    private static final Logger log = LoggerFactory.getLogger(DestinationController.class);

    /**
     * 目的地列表；带 keyword 时按 名称 / 城市 / 分类 / 关键词 模糊搜索
     */
    @PostMapping(value = "/destinations", produces = "application/json;charset=UTF-8")
    public Map<String, Object> list(@RequestParam(required = false) String keyword) {
        String trimmed = keyword == null ? "" : keyword.trim();

        StringBuilder sql = new StringBuilder(
                "select name, city, category, icon, description from spot");
        if (!trimmed.isEmpty()) {
            sql.append(" where name like ? or city like ? or category like ? or keywords like ?");
        }
        sql.append(" order by id");

        List<Map<String, String>> spots = new ArrayList<>();

        try (Connection conn = MySQLUtil.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            if (!trimmed.isEmpty()) {
                String like = "%" + trimmed + "%";
                for (int i = 1; i <= 4; i++) {
                    pstmt.setString(i, like);
                }
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> spot = new LinkedHashMap<>();
                    spot.put("name", rs.getString("name"));
                    spot.put("city", rs.getString("city"));
                    spot.put("category", rs.getString("category"));
                    spot.put("icon", rs.getString("icon"));
                    spot.put("description", rs.getString("description"));
                    spots.add(spot);
                }
            }
        } catch (SQLException e) {
            log.error("查询目的地失败", e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "error");
            error.put("message", "目的地读取失败，请稍后重试");
            return error;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("spots", spots);
        return result;
    }
}
