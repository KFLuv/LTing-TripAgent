package com.iflytek.chatgpt.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 「系统设置 → API 配置」页的后端。
 *
 * <p>Key 只写到本机 {@code ~/.ai-travel-assistant/application.properties}，
 * 由 {@code spring.config.additional-location} 在下次启动时覆盖 jar 里的空值，
 * 所以：<b>不进仓库、不进 jar、不打印日志</b>，保存后重启应用生效。
 *
 * <p>安全：这两个接口只接受来自本机的请求（remoteAddr 为回环地址），
 * 因为后端为了手机调试会监听 0.0.0.0，不能让同一 WiFi 的人改你的 Key。
 */
@RestController
public class KeyConfigController {

    private static final Logger log = LoggerFactory.getLogger(KeyConfigController.class);

    /** 允许在这里配置的键（白名单，防止被当成任意配置写入器） */
    private static final List<String> ALLOWED = Arrays.asList(
            "ai.base-url", "ai.model", "ai.api-key",
            "geo.amap-key", "search.api-key", "map.js-key", "map.js-security-code");

    /** 需要打码展示的键 */
    private static final List<String> SECRETS = Arrays.asList(
            "ai.api-key", "geo.amap-key", "search.api-key",
            "map.js-key", "map.js-security-code");

    @Value("${ai.base-url:}")
    private String aiBaseUrl;
    @Value("${ai.model:}")
    private String aiModel;
    @Value("${ai.api-key:}")
    private String aiApiKey;
    @Value("${geo.amap-key:}")
    private String geoAmapKey;
    @Value("${search.api-key:}")
    private String searchApiKey;
    @Value("${map.js-key:}")
    private String mapJsKey;
    @Value("${map.js-security-code:}")
    private String mapJsSecurityCode;

    private static Path file() {
        return Paths.get(System.getProperty("user.home"), ".ai-travel-assistant", "application.properties");
    }

    /** 当前配置状态：只返回"是否配置 + 打码尾号"，永不返回明文 */
    @GetMapping(value = "/api/config/keys", produces = "application/json;charset=UTF-8")
    public Map<String, Object> get(HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!local(req)) {
            out.put("status", "error");
            out.put("message", "只允许本机访问配置页");
            return out;
        }
        Properties saved = readFile();
        List<Map<String, Object>> items = new ArrayList<>();
        for (String key : ALLOWED) {
            String fromFile = saved.getProperty(key, "");
            String effective = effective(key);
            String value = !fromFile.isEmpty() ? fromFile : effective;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", key);
            item.put("secret", SECRETS.contains(key));
            item.put("configured", !value.isEmpty());
            item.put("masked", mask(value));
            item.put("source", !fromFile.isEmpty() ? "file" : (effective.isEmpty() ? "none" : "builtin"));
            item.put("pendingRestart", !fromFile.isEmpty() && !fromFile.equals(effective));
            items.add(item);
        }
        out.put("status", "success");
        out.put("file", file().toString());
        out.put("items", items);
        return out;
    }

    /** 保存：只写入传上来的键；传空字符串表示清除该键 */
    @PostMapping(value = "/api/config/keys", produces = "application/json;charset=UTF-8")
    public Map<String, Object> save(@RequestBody(required = false) Map<String, String> body,
                                    HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!local(req)) {
            out.put("status", "error");
            out.put("message", "只允许本机访问配置页");
            return out;
        }
        Properties props = readFile();
        int changed = 0;
        if (body != null) {
            for (Map.Entry<String, String> e : body.entrySet()) {
                if (!ALLOWED.contains(e.getKey())) {
                    continue;
                }
                String v = e.getValue() == null ? "" : e.getValue().trim();
                if (v.isEmpty()) {
                    props.remove(e.getKey());
                } else {
                    props.setProperty(e.getKey(), v);
                }
                changed++;
            }
        }
        try {
            writeFile(props);
        } catch (Exception ex) {
            log.error("写入本机密钥配置失败", ex);
            out.put("status", "error");
            out.put("message", "写入失败：" + ex.getMessage());
            return out;
        }
        out.put("status", "success");
        out.put("changed", changed);
        out.put("restartRequired", true);
        out.put("message", "已保存到本机，重启应用后生效");
        out.put("file", file().toString());
        return out;
    }

    private boolean local(HttpServletRequest req) {
        String ip = req.getRemoteAddr();
        return "127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip);
    }

    private String effective(String key) {
        switch (key) {
            case "ai.base-url": return nz(aiBaseUrl);
            case "ai.model": return nz(aiModel);
            case "ai.api-key": return nz(aiApiKey);
            case "geo.amap-key": return nz(geoAmapKey);
            case "search.api-key": return nz(searchApiKey);
            case "map.js-key": return nz(mapJsKey);
            case "map.js-security-code": return nz(mapJsSecurityCode);
            default: return "";
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 打码：只留尾号 4 位 */
    private static String mask(String v) {
        if (v == null || v.isEmpty()) {
            return "";
        }
        if (v.length() <= 8) {
            return "****";
        }
        return v.substring(0, 2) + "****" + v.substring(v.length() - 4);
    }

    private Properties readFile() {
        Properties p = new Properties();
        Path f = file();
        if (!Files.exists(f)) {
            return p;
        }
        try (InputStream in = Files.newInputStream(f)) {
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("读取本机密钥配置失败：{}", e.getMessage());
        }
        return p;
    }

    private void writeFile(Properties p) throws Exception {
        Path f = file();
        Files.createDirectories(f.getParent());
        try (OutputStream out = Files.newOutputStream(f)) {
            out.write(("# 本机 API Key 配置（由「系统设置」页写入，不要提交到任何仓库）\n"
                    + "# 修改后重启应用生效\n").getBytes(StandardCharsets.UTF_8));
            p.store(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8),
                    "AI Travel Guide · local keys");
        }
    }
}
