package com.cjdate.myjava.test.proxy.jdk.shouxie;

import java.lang.reflect.Method;

/**
 * @Description TODO
 * @Author liuchaojie
 * @Date 2022/12/26 22:54
 * @Version 1.0
 */
public class ProxyB implements InvocationHandler {

    private Object target;

    public Object getInstance(Object target){
        this.target = target;
        Class<?> aClass = target.getClass();
        return Proxy.newProxyInstance(new MyClassLoader(),aClass.getInterfaces(),this);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        before();
        Object invoke = method.invoke(this.target, args);
        after();
        return invoke;
    }

    public void before(){
        System.out.println("before...");
    }

    public void after(){
        System.out.println("after...");
    }
}
