package com.alibaba.dubbo.examples.extension;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;

/**
 * @Author:
 * @Description:
 * @Date: 2022/2/26 17:15
 * @Version: 1.0
 */
public class Tyre1Impl implements Tyre {

    @Override
    public void say() {

    }

    @Override
    public void test(URL url) {
        System.out.println("普通轮胎");
    }
}
