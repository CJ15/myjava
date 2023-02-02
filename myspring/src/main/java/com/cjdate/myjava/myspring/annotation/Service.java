package com.cjdate.myjava.myspring.annotation;

import java.lang.annotation.*;

/**
 * @Description 定义服务
 * @Author liuchaojie
 * @Date 2023/2/1 23:25
 * @Version 1.0
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Service {
    String value() default "";
}
