package com.cjdate.myjava.springcloud.eurekaprovider;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;

/**
 * @Description TODO
 * @Author liuchaojie
 * @Date 2022/9/24 22:08
 * @Version 1.0
 */
@SpringBootApplication
@EnableEurekaClient
public class EurekaProviderApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(EurekaProviderApplication.class).run(args);
    }
}
