package com.cjdate.myjava.dubbo.dubboconsumer;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * @Description TODO
 * @Author liuchaojie
 * @Date 2022/10/25 21:08
 * @Version 1.0
 */
@SpringBootApplication
@EnableDubbo
public class DubboConsumerApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(DubboConsumerApplication.class).run(args);
    }
}
