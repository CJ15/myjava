package com.cjdate.myjava.test.proxy.jdk.shouxie;

import java.lang.reflect.Method;

/**
 * @Description TODO
 * @Author liuchaojie
 * @Date 2022/12/26 22:32
 * @Version 1.0
 */
public interface InvocationHandler {

    Object invoke(Object proxy, Method method,Object[] objects) throws Throwable;
}
