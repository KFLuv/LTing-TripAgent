package com.iflytek.chatgpt.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Random;

/**
 * 验证码接口（补齐 reg.html 中引用的 /chat/captcha）
 * 用 JDK 自带的 AWT 直接画一张 PNG，不额外引入依赖
 */
@RestController
public class CaptchaController {

    /** 去掉了 0 O 1 I 这类容易看混的字符 */
    private static final String CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int WIDTH = 120;
    private static final int HEIGHT = 46;
    private static final int CODE_LENGTH = 4;
    /** 存进 session 的 key，将来做服务端校验时按这个名字取 */
    private static final String SESSION_KEY = "captchaCode";

    private final Random random = new Random();

    @GetMapping("/captcha")
    public void captcha(HttpSession session, HttpServletResponse response) throws IOException {
        String code = randomCode();
        session.setAttribute(SESSION_KEY, code);

        BufferedImage image = drawImage(code);

        response.setContentType("image/png");
        // 验证码不能缓存，否则点击刷新看不到新图
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);

        try (OutputStream out = response.getOutputStream()) {
            ImageIO.write(image, "png", out);
        }
    }

    private String randomCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return code.toString();
    }

    /**
     * 画验证码图片：浅色底 + 干扰线 + 干扰点 + 带随机旋转的字符
     */
    private BufferedImage drawImage(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 背景
        g.setColor(new Color(0xF2, 0xF8, 0xFD));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // 干扰线
        g.setStroke(new BasicStroke(1.2f));
        for (int i = 0; i < 6; i++) {
            g.setColor(randomColor(150, 215));
            g.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT),
                    random.nextInt(WIDTH), random.nextInt(HEIGHT));
        }

        // 干扰点
        for (int i = 0; i < 40; i++) {
            g.setColor(randomColor(150, 215));
            g.fillOval(random.nextInt(WIDTH), random.nextInt(HEIGHT), 2, 2);
        }

        // 字符
        int step = WIDTH / (CODE_LENGTH + 1);
        for (int i = 0; i < code.length(); i++) {
            g.setFont(new Font("SansSerif", Font.BOLD, 26 + random.nextInt(4)));
            g.setColor(randomColor(40, 130));

            int x = step * i + 14;
            int y = 33 + random.nextInt(4);
            double angle = (random.nextInt(50) - 25) * Math.PI / 180;

            AffineTransform origin = g.getTransform();
            g.rotate(angle, x + 8, y - 8);
            g.drawString(String.valueOf(code.charAt(i)), x, y);
            g.setTransform(origin);
        }

        g.dispose();
        return image;
    }

    /** 生成一个 [min, max) 区间的随机深色，用来让干扰元素和字符颜色有变化 */
    private Color randomColor(int min, int max) {
        int range = max - min;
        return new Color(min + random.nextInt(range), min + random.nextInt(range), min + random.nextInt(range));
    }
}
