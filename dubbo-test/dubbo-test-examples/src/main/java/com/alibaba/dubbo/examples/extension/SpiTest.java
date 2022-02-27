package com.alibaba.dubbo.examples.extension;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;

/**
 * @Author:
 * @Description:
 * @Date: 2022/2/26 16:53
 * @Version: 1.0
 */
public class SpiTest {

    public static void main(String[] args) {

//        ExtensionLoader<Car> extensionLoader = ExtensionLoader.getExtensionLoader(Car.class);
//
//        Car car = extensionLoader.getExtension("audi");
//        car.test();
//
//        car = extensionLoader.getDefaultExtension();
//        car.test();

        ExtensionLoader<Tyre> extensionLoader = ExtensionLoader.getExtensionLoader(Tyre.class);
        URL url = new URL("http", "localhost", 8080);
        url = url.addParameter("tyre", "tyre1");

        Tyre tyre = extensionLoader.getAdaptiveExtension();
        tyre.test(url);

    }


}
