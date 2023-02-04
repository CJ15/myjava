package com.cjdate.myjava.myspring.servlet;

import com.cjdate.myjava.myspring.annotation.*;
import com.cjdate.myjava.myspring.util.StringUtil;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    //bean集合
    private Map<String,Object> beanMap = new HashMap<>();

    private List<Handler> handlerList = new ArrayList<>();

    @Override
    public void init(ServletConfig servletConfig) throws ServletException {
        //1.加载配置文件
        doLoadConfig(servletConfig.getInitParameter("contextConfigLocation"));

        //2.扫描相关的类
        doScanner(contextConfig.getProperty("scanPackage"));

        //3.初始化扫描的类，并将其放入IOC容器中
        doInstantiate();

        //4.完成依赖注入
        doAutowired();

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

    /**
     * 实例化扫描到的类
     */
    private void doInstantiate(){
        if(classNames.isEmpty()){
            return;
        }
        for(String className:classNames){
            try {
                Class<?> aClass = Class.forName(className);
                if(aClass.isAnnotationPresent(Controller.class)){
                    Object instance = aClass.newInstance();
                    String beanName = StringUtil.toLowerCaseOfFirstChar(aClass.getSimpleName());
                    beanMap.put(beanName,instance);

                    initHandlerMapping(aClass,instance);
                }else if(aClass.isAnnotationPresent(Service.class)){
                    Service service = aClass.getAnnotation(Service.class);
                    String beanName = service.value();
                    if("".equals(beanName)){
                        beanName = StringUtil.toLowerCaseOfFirstChar(aClass.getSimpleName());
                    }
                    Object instance = aClass.newInstance();
                    beanMap.put(beanName,instance);
                }
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            } catch (InstantiationException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }

        }
    }

    /**
     * 自动注入依赖的bean
     */
    private void doAutowired(){
        if(beanMap.isEmpty()){
            return;
        }
        for(Map.Entry<String, Object> entry:beanMap.entrySet()){
            //获取类的所有字段
            Field[] declaredFields = entry.getValue().getClass().getDeclaredFields();
            for(Field field:declaredFields){
                if(!field.isAnnotationPresent(Autowired.class)){
                    continue;
                }
                Autowired autowired = field.getAnnotation(Autowired.class);
                String beanName = autowired.value().trim();
                if("".equals(beanName)){
                    beanName = StringUtil.toLowerCaseOfFirstChar(field.getType().getSimpleName());
                }

                //将字段的访问类型设为true
                field.setAccessible(true);

                try {
                    field.set(entry.getValue(),beanMap.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 初始化url处理器
     */
    private void initHandlerMapping(Class clazz,Object object){
        String baseUrl = "";
        if(clazz.isAnnotationPresent(RequestMapping.class)){
            RequestMapping requestMapping = (RequestMapping) clazz.getAnnotation(RequestMapping.class);
            baseUrl = requestMapping.value().trim();
        }
        for(Method method:clazz.getMethods()){
            if(method.isAnnotationPresent(RequestMapping.class)){
                RequestMapping requestMapping = method.getAnnotation(RequestMapping.class);
                String url = baseUrl + requestMapping.value().trim();
                String regex = baseUrl + requestMapping.value().trim();
                Pattern pattern = Pattern.compile(regex);
                handlerList.add(new Handler(object,method,pattern));
            }
        }

    }

    private Handler getHandler(HttpServletRequest request){
        if(handlerList.isEmpty()){
            return null;
        }
        String url = request.getRequestURI();
        String contextPath = request.getContextPath();
        url = url.replace(contextPath,"").replaceAll("/+","/");
        for(Handler handler:handlerList){
            Matcher matcher = handler.pattern.matcher(url);
            if(!matcher.matches()){
                continue;
            }
            return handler;
        }
        return null;
    }

    private Object convert(Class clazz,String value){
        if(Integer.class == clazz){
            return Integer.valueOf(value);
        }
        return value;
    }

    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }

    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            doDispatch(req,resp);
        } catch (Exception e) {
            e.printStackTrace();
            resp.getWriter().write("500,detail: " + Arrays.toString(e.getStackTrace()));
        }
    }

    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws Exception{
        Handler handler = getHandler(req);

        if(handler == null){
            resp.getWriter().write("404 Not Found");
            return;
        }

        Class<?>[] paramTypes = handler.method.getParameterTypes();
        Object[] paramValues = new Object[paramTypes.length];
        Map<String, String[]> paramMap = req.getParameterMap();

        for(Map.Entry<String, String[]> param : paramMap.entrySet()){
            if(!handler.paramIndexMapping.containsKey(param.getKey())){
                continue;
            }
            String value = Arrays.toString(param.getValue())
                    .replaceAll("\\[|\\]","")
                    .replaceAll("\\s","");
            int index = handler.paramIndexMapping.get(param.getKey());
            paramValues[index] = convert(paramTypes[index],value);
        }

        if(handler.paramIndexMapping.containsKey(HttpServletRequest.class.getName())){
            int reqIndex = handler.paramIndexMapping.get(HttpServletRequest.class.getName());
            paramValues[reqIndex] = req;
        }

        if(handler.paramIndexMapping.containsKey(HttpServletResponse.class.getName())){
            int resIndex = handler.paramIndexMapping.get(HttpServletResponse.class.getName());
            paramValues[resIndex] = resp;
        }

        Object returnValue = handler.method.invoke(handler.instance,paramValues);
        if(returnValue==null||returnValue instanceof Void){
            return;
        }
        resp.getWriter().write(returnValue.toString());
    }

    private class Handler{
        //方法对应的实例对象
        protected Object instance;
        //url对应的方法
        protected Method method;
        //
        protected Pattern pattern;
        //参数顺序
        protected Map<String,Integer> paramIndexMapping;

        public Handler(Object instance, Method method, Pattern pattern) {
            this.instance = instance;
            this.method = method;
            this.pattern = pattern;
            paramIndexMapping = new HashMap<>();
            buildParamIndexMapping(method);
        }

        protected void buildParamIndexMapping(Method method){
            //获取方法中加了注解的参数
            Annotation[][] parameterAnnotations = method.getParameterAnnotations();
            //提取方法中RequestParam注解的参数
            for(int i=0;i<parameterAnnotations.length;i++){
                for (Annotation annotation:parameterAnnotations[i]) {
                    if(annotation instanceof RequestParam){
                        String paramName = ((RequestParam) annotation).value();
                        if(!"".equals(paramName.trim())){
                            paramIndexMapping.put(paramName,i);
                        }
                    }
                }
            }
            //提取方法中的request和response参数
            Class<?>[] parameterTypes = method.getParameterTypes();
            for(int i=0;i<parameterTypes.length;i++){
                if(parameterTypes[i] == HttpServletRequest.class
                    ||parameterTypes[i] == HttpServletResponse.class){
                    paramIndexMapping.put(parameterTypes[i].getName(),i);
                }
            }
        }
    }
}
