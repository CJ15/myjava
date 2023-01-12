package com.cjdate.myjava.dubbo.dubboprovider.impl;


import com.cjdate.myjava.dubbo.dubboapi.api.HelloApi;
import org.apache.dubbo.config.annotation.Service;

/**
 * @Description TODO
 * @Author liuchaojie
 * @Date 2022/10/25 20:43
 * @Version 1.0
 */
@Service(parameters = {"process_code","value"})
public class HelloImpl implements HelloApi {

    @Override
    public String sayHello() {
        return "hello";
    }
}
