package com.alibaba.dubbo.examples.extension;

/**
 * @Author:
 * @Description:
 * @Date: 2022/2/26 18:22
 * @Version: 1.0
 */
public class CarWrapperImpl implements Car{

    private Car car;

    public CarWrapperImpl(Car car) {
        this.car = car;
    }

    @Override
    public void test() {
        System.out.println("包装一下");
        car.test();
    }
}
