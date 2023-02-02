package com.cjdate.myjava.myspring.test;


import javax.servlet.*;
import java.io.IOException;

/**
 * @Description TODO
 * @Author liuchaojie
 * @Date 2023/1/29 23:23
 * @Version 1.0
 */
public class TestServlet implements Servlet {

    @Override
    public void init(ServletConfig servletConfig) throws ServletException {

    }

    @Override
    public ServletConfig getServletConfig() {
        return null;
    }

    @Override
    public void service(ServletRequest servletRequest, ServletResponse servletResponse) throws ServletException, IOException {
        System.out.println("test service javax.servlet.Servlet");
    }

    @Override
    public String getServletInfo() {
        return null;
    }

    @Override
    public void destroy() {

    }
}
