package com.cjdate.myjava.myspring.util;

public class StringUtil {

    /**
     * 将字符串的首字母小写
     * @param str
     * @return
     */
    public static String toLowerCaseOfFirstChar(String str){
        char[] chars = str.toCharArray();
        chars[0] += 32;
        return String.valueOf(chars);
    }

}
