package com.cjdate.myjava.test.proxy.cglib;




import net.sf.cglib.core.DebuggingClassWriter;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;

import java.lang.reflect.Method;

/**
 * @Description TODO
 * @Author liuchaojie
 * @Date 2023/1/2 17:20
 * @Version 1.0
 */
public class CglibProxyTest {

    public static void main(String[] args) {
        System.setProperty(DebuggingClassWriter.DEBUG_LOCATION_PROPERTY,"D://tem");
        A a = (A) new ProxyB().getInstance(A.class);
        a.method();
    }
}

class A{
    void method(){
        System.out.println("AAAAAAAAAA");
    }
}

class ProxyB implements MethodInterceptor {

    public Object getInstance(Class clazz){
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(clazz);
        enhancer.setCallback(this);

        return enhancer.create();
    }

    @Override
    public Object intercept(Object o, Method method, Object[] objects, MethodProxy methodProxy) throws Throwable {
        before();
        Object obj = methodProxy.invokeSuper(o,objects);
        after();
        return obj;
    }

    private void before(){
        System.out.println("cglib before");
    }

    private void after(){
        System.out.println("cglib after");
    }
}


