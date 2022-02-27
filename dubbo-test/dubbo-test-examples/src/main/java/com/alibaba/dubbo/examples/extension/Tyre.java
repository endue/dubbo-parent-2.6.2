package com.alibaba.dubbo.examples.extension;


import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;

@SPI
public interface Tyre {

    void say();

    @Adaptive
    void test(URL url);
}
