package com.cjdate.myjava.test.decorator;

/**
 * @Description TODO
 * @Author liuchaojie
 * @Date 2023/1/14 23:32
 * @Version 1.0
 */
public class BatterCakeDecorator extends BatterCake{

    private BatterCake batterCake;

    @Override
    protected String getMsg() {
        return this.batterCake.getMsg();
    }

    @Override
    protected int getPrice() {
        return this.batterCake.getPrice();
    }
}
