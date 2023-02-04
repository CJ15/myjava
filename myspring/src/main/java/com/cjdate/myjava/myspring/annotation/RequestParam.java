package com.cjdate.myjava.myspring.annotation;

import java.lang.annotation.*;

/***
 * @Description: 映射请求参数
 * @Author: liuchaojie
 * @Date: 2023/2/1 23:28
 * @Param
 * @Return
 **/
@Target({ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequestParam {

    String value() default "";
}
