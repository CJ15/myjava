package com.cjdate.myjava.myspring.annotation;

import java.lang.annotation.*;

/***
 * @Description: 控制器
 * @Author: liuchaojie
 * @Date: 2023/2/1 23:28
 * @Param
 * @Return
 **/
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Controller {
}
