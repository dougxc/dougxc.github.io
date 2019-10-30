/*
 * Copyright (c) 2014, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.vm.ci.services;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Provides utilities needed by JVMCI clients.
 *
 * This class must be compilable on JDK 8 and so use of API added in JDK 9 is made via reflection.
 */
public final class Services {

    private Services() {
    }

    /**
     * Gets an unmodifiable copy of the system properties saved when {@link System} is initialized.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> getSavedProperties() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new JVMCIPermission());
        }
        try {
            Class<?> vmClass = Class.forName("jdk.internal.misc.VM");
            Method m = vmClass.getMethod("getSavedProperties");
            return (Map<String, String>) m.invoke(null);
        } catch (Exception e) {
            throw new InternalError(e);
        }
    }

    /**
     * Causes the JVMCI subsystem to be initialized if it isn't already initialized.
     */
    public static void initializeJVMCI() {
        try {
            Class.forName("jdk.vm.ci.runtime.JVMCI");
        } catch (ClassNotFoundException e) {
            throw new InternalError(e);
        }
    }
}
