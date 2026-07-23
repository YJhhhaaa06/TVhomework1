package com.itheima.ioc;

import com.itheima.ioc.annotation.Component;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class ClassScanner {

    public static List<Class<?>> scan(String basePackage) {
        List<Class<?>> result = new ArrayList<>();
        String path = basePackage.replace('.', '/');
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Enumeration<URL> resources = cl.getResources(path);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                scanDirectory(new File(resource.toURI()), basePackage, result);
            }
        } catch (Exception e) {
            throw new RuntimeException("类路径扫描失败: " + basePackage, e);
        }
        return result;
    }

    private static void scanDirectory(File dir, String packageName, List<Class<?>> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + "." + file.getName(), result);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + "."
                        + file.getName().substring(0, file.getName().length() - 6);
                try {
                    Class<?> clazz = Class.forName(className);
                    if (clazz.isAnnotationPresent(Component.class)) {
                        result.add(clazz);
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                }
            }
        }
    }
}