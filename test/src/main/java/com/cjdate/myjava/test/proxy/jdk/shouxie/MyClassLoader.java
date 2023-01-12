package com.cjdate.myjava.test.proxy.jdk.shouxie;

/**
 * @Description TODO
 * @Author liuchaojie
 * @Date 2022/12/26 22:47
 * @Version 1.0
 */
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class MyClassLoader extends ClassLoader{
    private File classPathFile;

    /**
     * 3：初始化GPClassLoader，先只是将GPClassLoader的class文件加载进来
     */
    public MyClassLoader() {
        String classPath = MyClassLoader.class.getResource("").getPath();
        this.classPathFile = new File(classPath);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String className = MyClassLoader.class.getPackage().getName() + "." + name;

        if(classPathFile != null) {
            File classFile = new File(classPathFile, name.replaceAll("\\.","/") + ".class");
            if(classFile.exists()) {
                FileInputStream in = null;
                ByteArrayOutputStream out = null;

                try{
                    in = new FileInputStream(classFile);
                    out = new ByteArrayOutputStream();
                    byte [] buff = new byte[1024];
                    int len;
                    while ((len = in.read(buff)) != -1) {
                        out.write(buff,0,len);
                    }
                    return defineClass(className,out.toByteArray(),0, out.size());
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    if(null != in) {
                        try{
                            in.close();
                        }catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    if(null != out) {
                        try{
                            out.close();
                        }catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

        return null;
    }
}
