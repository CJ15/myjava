package com.cjdate.myjava.test.decorator;

/**
 * @Description TODO
 * @Author liuchaojie
 * @Date 2023/1/14 23:31
 * @Version 1.0
 */
public class BaseBatterCake extends BatterCake{
    @Override
    protected String getMsg() {
        return "煎饼";
    }

    @Override
    protected int getPrice() {
        return 5;
    }
}
