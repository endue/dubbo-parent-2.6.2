/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.common.extension;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.support.ActivateComparator;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.Holder;
import com.alibaba.dubbo.common.utils.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * 加载dubbo扩展
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see com.alibaba.dubbo.common.extension.SPI
 * @see com.alibaba.dubbo.common.extension.Adaptive
 * @see com.alibaba.dubbo.common.extension.Activate
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    /**
     * 存放配置文件的路径变量
     * "META-INF/services/"是dubbo为了兼容jdk的SPI扩展机制思想而设存在的
     * "META-INF/dubbo/internal/"是dubbo内部提供的扩展的配置文件路径
     * "META-INF/dubbo/"是为了给用户自定义的扩展实现配置文件存放
     */
    private static final String SERVICES_DIRECTORY = "META-INF/services/";

    private static final String DUBBO_DIRECTORY = "META-INF/dubbo/";

    private static final String DUBBO_INTERNAL_DIRECTORY = DUBBO_DIRECTORY + "internal/";

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

    /**
     * 存储可扩展接口与加载器映射
     * key是可扩展接口，value是加载器
     */
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<Class<?>, ExtensionLoader<?>>();

    /**
     * 存储可扩展接口实现类
     * key是可扩展接口的实现类，value是实现类对象
     */
    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<Class<?>, Object>();

    // ==============================
    // 可扩展接口class
    private final Class<?> type;

    private final ExtensionFactory objectFactory;
    /**
     * 缓存的扩展类与扩展名映射，和cachedClasses的key和value对换
     */
    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<Class<?>, String>();
    /**
     * 缓存的扩展名与扩展类映射，和cachedNames的key和value对换
     */
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<Map<String, Class<?>>>();
    /**
     * 扩展类的扩展名与@Activate注解的映射
     */
    private final Map<String, Activate> cachedActivates = new ConcurrentHashMap<String, Activate>();
    /**
     * 扩展类的扩展名与对应实例对象
     */
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<String, Holder<Object>>();
    private final Holder<Object> cachedAdaptiveInstance = new Holder<Object>();
    /**
     * 可扩展接口子类中被@Adaptive注解标注的实现类，只能有一个
     */
    private volatile Class<?> cachedAdaptiveClass = null;
    /**
     * SPI注解上的默认扩展类的名称
     */
    private String cachedDefaultName;
    private volatile Throwable createAdaptiveInstanceError;
    /**
     * 可扩展接口实现类中所有的包装类
     */
    private Set<Class<?>> cachedWrapperClasses;

    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<String, IllegalStateException>();

    private ExtensionLoader(Class<?> type) {
        this.type = type;
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    /**
     * 判断参数type所表示的类上是否有SPI注解
     * @param type
     * @param <T>
     * @return
     */
    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    /**
     * 获取扩展接口的加载器，没有就创建一个并缓存
     * @param type
     * @param <T>
     * @return
     */
    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (type == null)
            throw new IllegalArgumentException("Extension type == null");
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
        }
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type(" + type +
                    ") is not extension, because WITHOUT @" + SPI.class.getSimpleName() + " Annotation!");
        }

        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        if (loader == null) {
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    private static ClassLoader findClassLoader() {
        return ExtensionLoader.class.getClassLoader();
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names
     * @param group group
     * @return extension list which are activated.
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        String value = url.getParameter(key);
        return getActivateExtension(url, value == null || value.length() == 0 ? null : Constants.COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     *
     * 获取符合自动激活条件的扩展接口的实现类的对象集合
     *
     * @param url    url
     * @param values extension point names  扩展点的名字
     * @param group  group
     * @return extension list which are activated 激活的扩展列表
     * @see com.alibaba.dubbo.common.extension.Activate
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        // 记录符合条件的实现类
        List<T> exts = new ArrayList<T>();
        List<String> names = values == null ? new ArrayList<String>(0) : Arrays.asList(values);
        // 1. 从被@Activate注释的实现类中获取符合条件的实现类
        if (!names.contains(Constants.REMOVE_VALUE_PREFIX + Constants.DEFAULT_KEY)) {
            getExtensionClasses();
            // 遍历可扩展接口的实现类中被@Activate注释的实现类
            for (Map.Entry<String, Activate> entry : cachedActivates.entrySet()) {
                // 实现类的名字
                String name = entry.getKey();
                Activate activate = entry.getValue();
                if (isMatchGroup(group, activate.group())) {
                    // 获取实现类
                    T ext = getExtension(name);
                    if (!names.contains(name)
                            && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)
                            && isActive(activate, url)) {
                        exts.add(ext);
                    }
                }
            }
            Collections.sort(exts, ActivateComparator.COMPARATOR);
        }
        // 2.
        List<T> usrs = new ArrayList<T>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (!name.startsWith(Constants.REMOVE_VALUE_PREFIX)
                    && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)) {
                if (Constants.DEFAULT_KEY.equals(name)) {
                    if (!usrs.isEmpty()) {
                        exts.addAll(0, usrs);
                        usrs.clear();
                    }
                } else {
                    T ext = getExtension(name);
                    usrs.add(ext);
                }
            }
        }
        if (!usrs.isEmpty()) {
            exts.addAll(usrs);
        }
        return exts;
    }

    private boolean isMatchGroup(String group, String[] groups) {
        if (group == null || group.length() == 0) {
            return true;
        }
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isActive(Activate activate, URL url) {
        String[] keys = activate.value();
        if (keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key))
                        && ConfigUtils.isNotEmpty(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        return (T) holder.get();
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<String>(cachedInstances.keySet()));
    }

    /**
     * Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {
        // 1. 校验参数
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");

        // 2. 校验是否获取默认实现类
        if ("true".equals(name)) {
            return getDefaultExtension();
        }

        // 3.获取扩展点对于的Holder，Holder中有实现类则返回没有创建
        // 问：为什么用Holder对象来嵌套实现类呢？
        // 答：其实是为了防止并发，一个name只能对应一个实现类，怎么做呢？为name创建一个Holder，然后将实现类添加到Holder中，并发修改时锁住Holder即可
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    instance = createExtension(name);
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        if (null == cachedDefaultName || cachedDefaultName.length() == 0
                || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        try {
            this.getExtensionClass(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<String>(clazzes.keySet()));
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " not existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension not existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    /**
     * 获取自适应的扩展类
     * @return
     */
    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
        Object instance = cachedAdaptiveInstance.get();
        if (instance == null) {
            if (createAdaptiveInstanceError == null) {
                synchronized (cachedAdaptiveInstance) {
                    instance = cachedAdaptiveInstance.get();
                    if (instance == null) {
                        try {
                            // 创建自适应扩展类并缓存到全局属性中
                            instance = createAdaptiveExtension();
                            cachedAdaptiveInstance.set(instance);
                        } catch (Throwable t) {
                            createAdaptiveInstanceError = t;
                            throw new IllegalStateException("fail to create adaptive instance: " + t.toString(), t);
                        }
                    }
                }
            } else {
                throw new IllegalStateException("fail to create adaptive instance: " + createAdaptiveInstanceError.toString(), createAdaptiveInstanceError);
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().contains(name.toLowerCase())) {
                return entry.getValue();
            }
        }
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);


        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (i == 1) {
                buf.append(", possible causes: ");
            }

            buf.append("\r\n(");
            buf.append(i++);
            buf.append(") ");
            buf.append(entry.getKey());
            buf.append(":\r\n");
            buf.append(StringUtils.toString(entry.getValue()));
        }
        return new IllegalStateException(buf.toString());
    }

    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
        // 1. 加载所有的扩展实现类，然后根据name获取对应的实现类
        Class<?> clazz = getExtensionClasses().get(name);
        // 2. 没有找到扩展实现类抛出异常
        if (clazz == null) {
            throw findException(name);
        }
        // 3. 走到这里一定是发现了实现类
        try {
            // 3.1 获取实现类的对象，没有则创建并保存(单利模式)
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                EXTENSION_INSTANCES.putIfAbsent(clazz, clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            // 3.2 依赖注入实现类中依赖的对象
            injectExtension(instance);
            // 3.3 存在dubbo AOP包装类则对实现类进行包装生成包装类的实例类并返回
            Set<Class<?>> wrapperClasses = cachedWrapperClasses;
            if (wrapperClasses != null && !wrapperClasses.isEmpty()) {
                for (Class<?> wrapperClass : wrapperClasses) {
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                }
            }
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " +
                    type + ")  could not be instantiated: " + t.getMessage(), t);
        }
    }

    /**
     * 向扩展实现类中注入其依赖的属性
     * @param instance
     * @return
     */
    private T injectExtension(T instance) {
        try {
            if (objectFactory != null) {
                for (Method method : instance.getClass().getMethods()) {
                    // 找到对应set方法，如setAge
                    if (method.getName().startsWith("set")
                            && method.getParameterTypes().length == 1
                            && Modifier.isPublic(method.getModifiers())) {
                        // 获取注入属性的类
                        Class<?> pt = method.getParameterTypes()[0];
                        try {
                            // 获取注入的属性名称，如setAge，这里得到的就是age
                            String property = method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
                            // 获取属性的Bean对象并注入
                            Object object = objectFactory.getExtension(pt, property);
                            if (object != null) {
                                // 反射注入属性依赖
                                method.invoke(instance, object);
                            }
                        } catch (Exception e) {
                            logger.error("fail to inject via method " + method.getName()
                                    + " of interface " + type.getName() + ": " + e.getMessage(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null)
            throw new IllegalArgumentException("Extension type == null");
        if (name == null)
            throw new IllegalArgumentException("Extension name == null");
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null)
            throw new IllegalStateException("No such extension \"" + name + "\" for " + type.getName() + "!");
        return clazz;
    }

    /**
     * 获取扩展点实现类
     * @return
     */
    private Map<String, Class<?>> getExtensionClasses() {
        // 1. 注意cachedClasses并不会被共享，所以一个扩展点对应一个
        // 这里是获取当前扩展点的所有实现类
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    classes = loadExtensionClasses();
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * 加载扩展类
     *
     * 1. 获取当前接口上的SPI注解，如果注解存在默认值则缓存到cachedDefaultName中
     * 2. 加载扩展路径下的文件
     *
     * @return
     */
    // synchronized in getExtensionClasses
    private Map<String, Class<?>> loadExtensionClasses() {
        // 1. 获取扩展点上的SPI注解
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        // 2. 校验是否有SPI注解
        if (defaultAnnotation != null) {
            // 2.1 校验SPI注解上的默认扩展名
            String value = defaultAnnotation.value();
            if ((value = value.trim()).length() > 0) {
                String[] names = NAME_SEPARATOR.split(value);// 根据,分割，出现多个则报错
                if (names.length > 1) {
                    throw new IllegalStateException("more than 1 default extension name on extension " + type.getName()
                            + ": " + Arrays.toString(names));
                }
                // 2.2 记录SPI注解上的默认扩展名
                if (names.length == 1) cachedDefaultName = names[0];
            }
        }
        // 3. 加载扩展类的实现类
        // -> "META-INF/dubbo/internal/"
        // -> "META-INF/dubbo/"
        // -> "META-INF/services/"
        Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY);
        loadDirectory(extensionClasses, DUBBO_DIRECTORY);
        loadDirectory(extensionClasses, SERVICES_DIRECTORY);
        return extensionClasses;
    }

    /**
     * 加载路径下的扩展类文件
     * @param extensionClasses 缓存扩展实现类的Map集合
     * @param dir 文件路径
     */
    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir) {
        // 1. dir是文件目录，type为可扩展接口的class名
        String fileName = dir + type.getName();
        try {
            // 2. 使用类加载器获取路径下的文件资源
            Enumeration<java.net.URL> urls;
            ClassLoader classLoader = findClassLoader();
            if (classLoader != null) {
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }
            // 3. 加载文件
            if (urls != null) {
                while (urls.hasMoreElements()) {
                    java.net.URL resourceURL = urls.nextElement();
                    loadResource(extensionClasses, classLoader, resourceURL);
                }
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    /**
     * 加载资源文件
     * @param extensionClasses 缓存扩展实现类的Map集合
     * @param classLoader 类加载器
     * @param resourceURL 资源文件URL
     */
    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader, java.net.URL resourceURL) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), "utf-8"));
            try {
                String line;
                // 1. 读取一行
                while ((line = reader.readLine()) != null) {
                    // "#"开头表示注释行，截取掉注释
                    final int ci = line.indexOf('#');
                    if (ci >= 0) line = line.substring(0, ci);
                    line = line.trim();
                    // 读取真正的内容
                    if (line.length() > 0) {
                        try {
                            /**
                             * 文件中的内容，dubbo和java SPI不一样,举例如下：
                             * dubbo: spring=com.alibaba.dubbo.config.spring.extension.SpringExtensionFactory
                             * SPI: com.alibaba.dubbo.config.spring.extension.SpringExtensionFactory
                             * 所以name可能不存在，不存在时则是java的SPI
                             */
                            String name = null;
                            int i = line.indexOf('=');
                            if (i > 0) {
                                name = line.substring(0, i).trim();
                                line = line.substring(i + 1).trim();
                            }
                            if (line.length() > 0) {
                                loadClass(extensionClasses, resourceURL, Class.forName(line, true, classLoader), name);
                            }
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("Failed to load extension class(interface: " + type + ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                            exceptions.put(line, e);
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }

    /**
     * 加载class
     * @param extensionClasses 缓存扩展实现类的Map集合
     * @param resourceURL 资源文件URL
     * @param clazz 资源文件中class实现类
     * @param name class名称 可能不存在,也可能为多个
     * @throws NoSuchMethodException
     */
    private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name) throws NoSuchMethodException {
        // 1. 判断实现类是否是当前可扩展接口的子类
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error when load extension class(interface: " +
                    type + ", class line: " + clazz.getName() + "), class "
                    + clazz.getName() + "is not subtype of interface.");
        }
        // 2. 判断实现类是否有Adaptive注解，如果有暂存起来，用于校验只允许一个实现类上面有@Adaptive注解，同时也有其他作用，就是getAdaptiveExtension()
        if (clazz.isAnnotationPresent(Adaptive.class)) {
            if (cachedAdaptiveClass == null) {
                cachedAdaptiveClass = clazz;
            } else if (!cachedAdaptiveClass.equals(clazz)) {
                throw new IllegalStateException("More than 1 adaptive class found: "
                        + cachedAdaptiveClass.getClass().getName()
                        + ", " + clazz.getClass().getName());
            }
        // 3. 判断实现类是否是包装类
        // 注意这里并没有将包装类添加到extensionClasses集合中
        } else if (isWrapperClass(clazz)) {
            Set<Class<?>> wrappers = cachedWrapperClasses;
            if (wrappers == null) {
                cachedWrapperClasses = new ConcurrentHashSet<Class<?>>();
                wrappers = cachedWrapperClasses;
            }
            wrappers.add(clazz);
        // 4. 其他情况(起始就是扩展类)
        } else {
            clazz.getConstructor();
            // 扩展类名称在配置文件中不存在
            if (name == null || name.length() == 0) {
                // 获取扩展类的名称，例如DemoFilter为demo，主要用于兼容java SPI的配置
                name = findAnnotationName(clazz);
                if (name == null || name.length() == 0) {
                    if (clazz.getSimpleName().length() > type.getSimpleName().length()
                            && clazz.getSimpleName().endsWith(type.getSimpleName())) {
                        name = clazz.getSimpleName().substring(0, clazz.getSimpleName().length() - type.getSimpleName().length()).toLowerCase();
                    } else {
                        throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                    }
                }
            }
            // 获取配置文件中当前读取的行配置的所有扩展实现类的名称,如下：
            // car,car1,car2 = com.alibaba.dubbo.examples.extension.CarImpl
            String[] names = NAME_SEPARATOR.split(name);
            if (names != null && names.length > 0) {
                // 记录被激活的扩展实现类
                Activate activate = clazz.getAnnotation(Activate.class);
                if (activate != null) {
                    cachedActivates.put(names[0], activate);
                }
                // 将名称和对应的扩展实现类暂存到extensionClasses中最后返回
                for (String n : names) {
                    if (!cachedNames.containsKey(clazz)) {
                        cachedNames.put(clazz, n);
                    }
                    Class<?> c = extensionClasses.get(n);
                    if (c == null) {
                        extensionClasses.put(n, clazz);
                    } else if (c != clazz) {
                        throw new IllegalStateException("Duplicate extension " + type.getName() + " name " + n + " on " + c.getName() + " and " + clazz.getName());
                    }
                }
            }
        }
    }

    private boolean isWrapperClass(Class<?> clazz) {
        try {
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        // 获取扩展类@Extension注解
        com.alibaba.dubbo.common.Extension extension = clazz.getAnnotation(com.alibaba.dubbo.common.Extension.class);
        // 不存在，则获取扩展类的名称并截取掉扩展接口的名称
        // 如：SpringExtensionFactory
        // 结果： spring
        if (extension == null) {
            String name = clazz.getSimpleName();
            if (name.endsWith(type.getSimpleName())) {
                name = name.substring(0, name.length() - type.getSimpleName().length());
            }
            return name.toLowerCase();
        }
        // 获取注解中的值
        return extension.value();
    }

    /**
     * 创建自适应扩展类
     * @return
     */
    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can not create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 获取自适应扩展类的Class
     * @return
     */
    private Class<?> getAdaptiveExtensionClass() {
        // 获取所有的扩展实现类，在这之中会将实现类中带有@Adaptive注解的类缓存到cachedAdaptiveClass
        getExtensionClasses();
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        // 实现类中没有带有@Adaptive注解的类，则创建自适应扩展类
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    /**
     * 创建自适应扩展类
     * @return
     */
    private Class<?> createAdaptiveExtensionClass() {
        // 将自适应扩展类的代码拼接出来
        String code = createAdaptiveExtensionClassCode();
        // 获取编译器，编译拼接出来的代码
        ClassLoader classLoader = findClassLoader();
        com.alibaba.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        return compiler.compile(code, classLoader);
    }

    /**
     * 拼接自适应扩展类的代码
     * @return
     */
    private String createAdaptiveExtensionClassCode() {
        StringBuilder codeBuilder = new StringBuilder();
        // 获取可扩展接口中的所有方法
        Method[] methods = type.getMethods();
        // 判断方法上是否有@Adaptive注解
        boolean hasAdaptiveAnnotation = false;
        for (Method m : methods) {
            if (m.isAnnotationPresent(Adaptive.class)) {
                hasAdaptiveAnnotation = true;
                break;
            }
        }
        // no need to generate adaptive class since there's no adaptive method found.
        // 这里可以看出必须要有标注为@Adaptive的方法，否则抛出异常
        if (!hasAdaptiveAnnotation)
            throw new IllegalStateException("No adaptive method on extension " + type.getName() + ", refuse to create the adaptive class!");

        // 1. 拼接类中的package、import以及扩展类
        // codeBuilder此时是：public class xxx$Adaptive implements xxx {
        codeBuilder.append("package ").append(type.getPackage().getName()).append(";");
        codeBuilder.append("\nimport ").append(ExtensionLoader.class.getName()).append(";");
        codeBuilder.append("\npublic class ").append(type.getSimpleName()).append("$Adaptive").append(" implements ").append(type.getCanonicalName()).append(" {");
        // 2. 拼接自适应方法
        for (Method method : methods) {
            Class<?> rt = method.getReturnType();
            Class<?>[] pts = method.getParameterTypes();
            Class<?>[] ets = method.getExceptionTypes();

            Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
            // 用code记录当前遍历的方法实现类中的代码
            StringBuilder code = new StringBuilder(512);
            // 2.1 遍历的当前方法没有@Adaptive注解，拼接异常信息不允许被调用
            if (adaptiveAnnotation == null) {
                code.append("throw new UnsupportedOperationException(\"method ")
                        .append(method.toString()).append(" of interface ")
                        .append(type.getName()).append(" is not adaptive method!\");");
            // 2.2 遍历的当前方法有@Adaptive注解
            } else {
                // 在参数列表中查找URL参数的位置
                int urlTypeIndex = -1;
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].equals(URL.class)) {
                        urlTypeIndex = i;
                        break;
                    }
                }
                // found parameter in URL type
                // 2.2.1 有URL参数，则拼接URL非空校验
                if (urlTypeIndex != -1) {
                    // Null Point check
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"url == null\");",
                            urlTypeIndex);
                    code.append(s);

                    s = String.format("\n%s url = arg%d;", URL.class.getName(), urlTypeIndex);
                    code.append(s);
                }
                // did not find parameter in URL type
                // 2.2.2 没有URL参数
                else {
                    String attribMethod = null;

                    // find URL getter method
                    LBL_PTS:
                    // 在参数列表中，获取参数中属性的get方法，查找是否有URL
                    for (int i = 0; i < pts.length; ++i) {
                        Method[] ms = pts[i].getMethods();
                        for (Method m : ms) {
                            String name = m.getName();
                            if ((name.startsWith("get") || name.length() > 3)
                                    && Modifier.isPublic(m.getModifiers())
                                    && !Modifier.isStatic(m.getModifiers())
                                    && m.getParameterTypes().length == 0
                                    && m.getReturnType() == URL.class) {
                                // 有返回URL的getxxx方法则记录哪个参数，以及该参数的属性的方法名
                                urlTypeIndex = i;
                                attribMethod = name;
                                break LBL_PTS;
                            }
                        }
                    }
                    if (attribMethod == null) {
                        throw new IllegalStateException("fail to create adaptive class for interface " + type.getName()
                                + ": not found url parameter or url attribute in parameters of method " + method.getName());
                    }

                    // Null point check
                    // 增加非空验证
                    // 有getxxx方法的参数不能为null  参数的getxxx返回的URL不能为null
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"%s argument == null\");",
                            urlTypeIndex, pts[urlTypeIndex].getName());
                    code.append(s);
                    s = String.format("\nif (arg%d.%s() == null) throw new IllegalArgumentException(\"%s argument %s() == null\");",
                            urlTypeIndex, attribMethod, pts[urlTypeIndex].getName(), attribMethod);
                    code.append(s);

                    s = String.format("%s url = arg%d.%s();", URL.class.getName(), urlTypeIndex, attribMethod);
                    code.append(s);
                }

                // 2.2.3 获取@Adaptive注解中的值(当前可扩展接口默认实现类的名称)
                String[] value = adaptiveAnnotation.value();
                // value is not set, use the value generated from class name as the key
                // @Adaptive注解未配置value属性，则取可扩展接口的名称
                if (value.length == 0) {
                    // 获取当前可扩展接口的名称，就是默认实现类的名称
                    char[] charArray = type.getSimpleName().toCharArray();
                    StringBuilder sb = new StringBuilder(128);
                    for (int i = 0; i < charArray.length; i++) {
                        if (Character.isUpperCase(charArray[i])) {
                            if (i != 0) {
                                sb.append(".");
                            }
                            sb.append(Character.toLowerCase(charArray[i]));
                        } else {
                            sb.append(charArray[i]);
                        }
                    }
                    value = new String[]{sb.toString()};
                }

                // todo 这里没看懂实际含义
                // 判断参数列表是否有Invocation，如果有则生成非空校验并记录有该值
                boolean hasInvocation = false;
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].getName().equals("com.alibaba.dubbo.rpc.Invocation")) {
                        // Null Point check
                        String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"invocation == null\");", i);
                        code.append(s);
                        s = String.format("\nString methodName = arg%d.getMethodName();", i);
                        code.append(s);
                        hasInvocation = true;
                        break;
                    }
                }

                // 2.2.4 拼接从URL中获取扩展实现类的代码
                // 获取当前接口默认实现类的名称
                String defaultExtName = cachedDefaultName;
                String getNameCode = null;
                // 遍历@Adaptive注解中的值，然后拼接从URL中获取该值对应的代码
                for (int i = value.length - 1; i >= 0; --i) {
                    if (i == value.length - 1) {
                        if (null != defaultExtName) {
                            if (!"protocol".equals(value[i]))
                                if (hasInvocation)
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                else
                                    getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                            else
                                getNameCode = String.format("( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                        } else {
                            if (!"protocol".equals(value[i]))
                                if (hasInvocation)
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                else
                                    getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                            else
                                getNameCode = "url.getProtocol()";
                        }
                    } else {
                        if (!"protocol".equals(value[i]))
                            if (hasInvocation)
                                getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                            else
                                getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode);
                        else
                            getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                    }
                }
                // 拼接获取默认扩展类的代码
                code.append("\nString extName = ").append(getNameCode).append(";");
                // check extName == null?
                // 校验默认扩展类不能为null
                String s = String.format("\nif(extName == null) " +
                                "throw new IllegalStateException(\"Fail to get extension(%s) name from url(\" + url.toString() + \") use keys(%s)\");",
                        type.getName(), Arrays.toString(value));
                code.append(s);
                // 通过getExtensionLoader(当前扩展接口).getExtension(extName) 来获取扩展实现类
                s = String.format("\n%s extension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);",
                        type.getName(), ExtensionLoader.class.getSimpleName(), type.getName());
                code.append(s);

                // return statement
                if (!rt.equals(void.class)) {
                    code.append("\nreturn ");
                }

                s = String.format("extension.%s(", method.getName());
                code.append(s);
                for (int i = 0; i < pts.length; i++) {
                    if (i != 0)
                        code.append(", ");
                    code.append("arg").append(i);
                }
                code.append(");");
            }

            // 最后就是流水代码，拼接该接口方法的实现类中的代码
            codeBuilder.append("\npublic ").append(rt.getCanonicalName()).append(" ").append(method.getName()).append("(");
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) {
                    codeBuilder.append(", ");
                }
                codeBuilder.append(pts[i].getCanonicalName());
                codeBuilder.append(" ");
                codeBuilder.append("arg").append(i);
            }
            codeBuilder.append(")");
            if (ets.length > 0) {
                codeBuilder.append(" throws ");
                for (int i = 0; i < ets.length; i++) {
                    if (i > 0) {
                        codeBuilder.append(", ");
                    }
                    codeBuilder.append(ets[i].getCanonicalName());
                }
            }
            codeBuilder.append(" {");
            codeBuilder.append(code.toString());
            codeBuilder.append("\n}");
        }
        codeBuilder.append("\n}");
        if (logger.isDebugEnabled()) {
            logger.debug(codeBuilder.toString());
        }
        return codeBuilder.toString();
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}