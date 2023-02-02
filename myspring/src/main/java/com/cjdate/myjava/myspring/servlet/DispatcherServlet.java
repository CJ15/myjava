package com.cjdate.myjava.myspring.servlet;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * @Description servlet
 * @Author liuchaojie
 * @Date 2023/2/1 23:23
 * @Version 1.0
 */
public class DispatcherServlet extends HttpServlet {

    //配置文件
    private Properties contextConfig = new Properties();

    //类集合
    private List<String> classNames = new ArrayList<>();

    @Override
    public void init(ServletConfig servletConfig) throws ServletException {
        //1.加载配置文件
        doLoadConfig(servletConfig.getInitParameter("contextConfigLocation"));

        //2.扫描相关的类

        //3.初始化扫描的类，并将其放入IOC容器中
        //4.完成依赖注入
        //5.初始化HandlerMapping

        System.out.println("DispatcherServlet init finished");
    }

    /**
     * 加载配置文件
     */
    private void doLoadConfig(String contextConfigLocation){
        InputStream is = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);
        try {
            contextConfig.load(is);
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            if(is!=null){
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 扫描路径
     */
    private void doScanner(String scanPackage){
        URL resource = this.getClass().getClassLoader().getResource("/" + scanPackage.replaceAll("\\.", "/"));
        File scanPath = new File(resource.getFile());
        for(File file:scanPath.listFiles()){
            if(file.isDirectory()){
                doScanner(scanPackage + "." +file.getName());
            }else {
                if(!file.getName().endsWith(".class")) {
                    continue;
                }
                String className = scanPackage + "." + file.getName().replace(".class","");
                classNames.add(className);
            }
        }
    }
}
