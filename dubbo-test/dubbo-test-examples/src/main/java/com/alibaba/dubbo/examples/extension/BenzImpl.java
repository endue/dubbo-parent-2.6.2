package com.alibaba.dubbo.examples.extension;

/**
 * @Author:
 * @Description:
 * @Date: 2022/2/26 16:56
 * @Version: 1.0
 */
public class BenzImpl implements Car {

    @Override
    public void test() {
        System.out.println("Benz");
    }
}
