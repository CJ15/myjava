package com.cjdate.myjava.myspring.annotation;

import java.lang.annotation.*;

/***
 * @Description: 请求 url
 * @Author: liuchaojie
 * @Date: 2023/2/1 23:29
 * @Param
 * @Return
 **/
@Target({ElementType.TYPE,ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequestMapping {
    String value() default "/";
}
