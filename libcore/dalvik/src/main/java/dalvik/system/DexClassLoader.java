/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dalvik.system;

import java.io.File;

/**
 * A class loader that loads classes from {@code .jar} and {@code .apk} files
 * containing a {@code classes.dex} entry. This can be used to execute code not
 * installed as part of an application.
 *
 * <p>This class loader requires an application-private, writable directory to
 * cache optimized classes. Use {@code Context.getCodeCacheDir()} to create
 * such a directory: <pre>   {@code
 *   File dexOutputDir = context.getCodeCacheDir();
 * }</pre>
 *
 * <p><strong>Do not cache optimized classes on external storage.</strong>
 * External storage does not provide access controls necessary to protect your
 * application from code injection attacks.
 *
 * 一个加载dex文件(包含dex文件的jar和apk也算)的类加载器。
 * 这个类加载器可以执行不包含在应用中的代码。
 *
 * 这个类加载器需要一个应用私有、可写的文件夹来缓存优化后的类。
 */
public class DexClassLoader extends BaseDexClassLoader {
    /**
     * Creates a {@code DexClassLoader} that finds interpreted and native
     * code.  Interpreted classes are found in a set of DEX files contained
     * in Jar or APK files.
     *
     * <p>The path lists are separated using the character specified by the
     * {@code path.separator} system property, which defaults to {@code :}.
     *
     * @param dexPath the list of jar/apk files containing classes and
     *     resources, delimited by {@code File.pathSeparator}, which
     *     defaults to {@code ":"} on Android
     * @param optimizedDirectory directory where optimized dex files
     *     should be written; must not be {@code null}
     * @param librarySearchPath the list of directories containing native
     *     libraries, delimited by {@code File.pathSeparator}; may be
     *     {@code null}
     * @param parent the parent class loader
     *
     * @参数 dexPath dex相关文件路径集合,多个路径用分割符分割,默认分隔符为":"
     * @参数 optimizedDirectory 解压的dex文件存储路径,这个路径必须是一个内部存储路径,
     *		 					一般情况下使用当前应用的私有路径:/data/data/<package name>/…
     * @参数 librarySearchPath 包含C/C++库的路径集合,多个路径用文件分隔符分割,可以为null
     * @参数 parent 父加载器
     */
    public DexClassLoader(String dexPath, String optimizedDirectory,
            String librarySearchPath, ClassLoader parent) {
        super(dexPath, new File(optimizedDirectory), librarySearchPath, parent);
    }
}
