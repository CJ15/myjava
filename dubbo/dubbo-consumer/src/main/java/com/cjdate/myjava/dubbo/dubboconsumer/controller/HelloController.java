package com.cjdate.myjava.dubbo.dubboconsumer.controller;

import com.cjdate.myjava.dubbo.dubboapi.api.HelloApi;
import org.apache.dubbo.config.annotation.Reference;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Description TODO
 * @Author liuchaojie
 * @Date 2022/10/25 21:11
 * @Version 1.0
 */
@RestController
public class HelloController {

    @Reference
    private HelloApi helloApi;

    @RequestMapping("/hello")
    public String sayHello(){
        return helloApi.sayHello();
    }
}
