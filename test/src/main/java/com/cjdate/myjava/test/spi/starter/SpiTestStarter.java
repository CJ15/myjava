package com.cjdate.myjava.test.spi.starter;

import com.cjdate.myjava.test.spi.inter.LoadResourceService;

import java.util.Iterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Description
 * ServiceLoader.load() 可以完成对 META-INF/spring/interface 的加载，并且完成对应对象的初始化
 *
 * @Author liuchaojie
 * @Date 2022/11/17 22:42
 * @Version 1.0
 */
public class SpiTestStarter {

    public static void main(String[] args) {
        ServiceLoader<LoadResourceService> resources = ServiceLoader.load(LoadResourceService.class);
        Iterator<LoadResourceService> iterator = resources.iterator();
        while (iterator.hasNext()){
            LoadResourceService next = iterator.next();
            next.lookUpService(null);
        }
    }

    private static Map<String,Object> ioc = new ConcurrentHashMap<>();

    public static Object getBean(String className){
        synchronized (ioc){
            if(!ioc.containsKey(className)){
                try {
                    Object o = Class.forName(className).newInstance();
                    ioc.put(className,o);
                } catch (InstantiationException e) {
                    e.printStackTrace();
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }
            return ioc.get(className);
        }
    }
}
