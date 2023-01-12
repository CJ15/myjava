package com.cjdate.myjava.test.spi.impl;

import com.cjdate.myjava.test.spi.inter.LoadResourceService;

/**
 * @Description TODO
 * @Author liuchaojie
 * @Date 2022/11/17 22:41
 * @Version 1.0
 */
public class LoadHtmlResourceService implements LoadResourceService {
    @Override
    public void lookUpService(String format) {
        System.out.println("加载网页类型的资源!!!");
    }
}
