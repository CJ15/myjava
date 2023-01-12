package com.cjdate.myjava.dubbo.dubboprovider;

import com.newland.dubbo.http.container.HttpServerContainer;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * @Description TODO
 * @Author liuchaojie
 * @Date 2022/10/25 20:42
 * @Version 1.0
 */
@SpringBootApplication
@EnableDubbo(scanBasePackages = {"com.cjdate.myjava.dubbo.dubboprovider.impl"})
public class DubboProviderApplication {

    public static void main(String[] args) {
        initHttpServerContainer();
        new SpringApplicationBuilder(DubboProviderApplication.class).run(args);
    }

    public static void initHttpServerContainer(){
        HttpServerContainer httpServerContainer = new HttpServerContainer();
        httpServerContainer.start();
    }
}
