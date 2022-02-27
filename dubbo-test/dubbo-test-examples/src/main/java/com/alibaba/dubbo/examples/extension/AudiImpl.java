package com.alibaba.dubbo.examples.extension;

import com.alibaba.dubbo.common.URL;

/**
 * @Author:
 * @Description:
 * @Date: 2022/2/26 16:56
 * @Version: 1.0
 */
public class AudiImpl implements Car {

    @Override
    public void test() {
        System.out.println("Audi");
    }
}
