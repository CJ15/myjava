package com.cjdate.myjava.myspring.service;

import com.cjdate.myjava.myspring.annotation.Service;

/**
 * @Description TODO
 * @Author liuchaojie
 * @Date 2023/2/1 23:30
 * @Version 1.0
 */
@Service
public class DemoService {

    public String get(String name){
        return "value: " + name;
    }
}
