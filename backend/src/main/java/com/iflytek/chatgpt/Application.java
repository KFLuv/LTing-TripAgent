package com.iflytek.chatgpt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 启动类，内置 Tomcat，直接 main 方法启动即可
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        // JDK 默认不读取系统代理，开启后 ReverseGeocoder 才能走 Windows"Internet 选项"里的代理
        // （GPS 位置引擎的坐标反查服务在境外，国内通常需要代理才能连上）
        System.setProperty("java.net.useSystemProxies", "true");
        SpringApplication.run(Application.class, args);
    }
}
