package com.alibaba.dubbo.examples.extension;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.SPI;

/**
 * @Author:
 * @Description:
 * @Date: 2022/2/26 16:53
 * @Version: 1.0
 */
@SPI("benz")
public interface Car {
    void test();
}
