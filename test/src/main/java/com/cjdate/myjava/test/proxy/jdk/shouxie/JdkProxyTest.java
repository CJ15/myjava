package com.cjdate.myjava.test.proxy.jdk.shouxie;

import java.lang.reflect.Method;



/**
 * @Description TODO
 * @Author liuchaojie
 * @Date 2022/12/26 22:01
 * @Version 1.0
 */
public class JdkProxyTest {

    public static void main(String[] args) {
        A a = (A) new ProxyB().getInstance(new B());
        a.method();
    }
}
