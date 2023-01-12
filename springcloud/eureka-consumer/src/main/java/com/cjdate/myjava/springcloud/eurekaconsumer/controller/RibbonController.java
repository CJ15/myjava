package com.cjdate.myjava.springcloud.eurekaconsumer.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * @Description TODO
 * @Author liuchaojie
 * @Date 2022/9/26 22:38
 * @Version 1.0
 */
@RestController
public class RibbonController {

    @Autowired
    private RestTemplate restTemplate;

    @RequestMapping(value = "/ribbon-hello", method = RequestMethod.GET)
    public String ribbonHello(){
        return restTemplate.getForEntity("http://EUREKA-PROVIDER/hello",String.class).getBody();
    }
}
