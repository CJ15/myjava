package com.cjdate.myjava.test.proxy.jdk;


import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;


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

interface A{
    void method();
}

class B implements A{

    @Override
    public void method() {
        System.out.println("BBBBBBBBBB");
    }
}

class ProxyB implements InvocationHandler {

    private Object target;

    public Object getInstance(Object target){
        this.target = target;
        Class<?> aClass = target.getClass();
        return Proxy.newProxyInstance(aClass.getClassLoader(),aClass.getInterfaces(),this);
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
