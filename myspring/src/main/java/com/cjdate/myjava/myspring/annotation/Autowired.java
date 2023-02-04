package com.cjdate.myjava.myspring.annotation;

import java.lang.annotation.*;

/***
 * @Description: 自动注入
 * @Author: liuchaojie
 * @Date: 2023/2/1 23:28
 * @Param
 * @Return
 **/
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Autowired {

    String value() default "";
}
