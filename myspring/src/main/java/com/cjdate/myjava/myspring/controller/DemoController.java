package com.cjdate.myjava.myspring.controller;

import com.cjdate.myjava.myspring.annotation.Autowired;
import com.cjdate.myjava.myspring.annotation.Controller;
import com.cjdate.myjava.myspring.annotation.RequestMapping;
import com.cjdate.myjava.myspring.annotation.RequestParam;
import com.cjdate.myjava.myspring.service.DemoService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * @Description TODO
 * @Author liuchaojie
 * @Date 2023/2/1 23:31
 * @Version 1.0
 */
@Controller
@RequestMapping("/demo")
public class DemoController {

    @Autowired
    private DemoService demoService;

    public void query(HttpServletRequest request, HttpServletResponse response,
                      @RequestParam("param") String param){
        String result = demoService.get(param);
        try {
            response.getWriter().write(result);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
