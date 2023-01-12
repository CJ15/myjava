package com.cjdate.myjava.springcloud.eurekaprovider.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @Description TODO
 * @Author liuchaojie
 * @Date 2022/9/24 22:10
 * @Version 1.0
 */
@RestController
public class HelloController {

    @Autowired
    private DiscoveryClient discoveryClient;

    @RequestMapping("/hello")
    public String hello(){
        List<String> services = discoveryClient.getServices();
        System.out.println(services);
        return "hello";
    }
}
