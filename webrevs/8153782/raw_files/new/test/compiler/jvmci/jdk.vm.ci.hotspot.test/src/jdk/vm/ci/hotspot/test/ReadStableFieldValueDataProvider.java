/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
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

package jdk.vm.ci.hotspot.test;

import static jdk.vm.ci.hotspot.test.TestHelper.ARRAYS_MAP;
import static jdk.vm.ci.hotspot.test.TestHelper.ARRAY_ARRAYS_MAP;
import static jdk.vm.ci.hotspot.test.TestHelper.CONSTANT_REFLECTION_PROVIDER;
import static jdk.vm.ci.hotspot.test.TestHelper.DUMMY_CLASS_CONSTANT;
import static jdk.vm.ci.hotspot.test.TestHelper.DUMMY_CLASS_INSTANCE;
import static jdk.vm.ci.hotspot.test.TestHelper.STABLE_ARRAYS_MAP;
import static jdk.vm.ci.hotspot.test.TestHelper.STABLE_ARRAY_ARRAYS_MAP;
import static jdk.vm.ci.hotspot.test.TestHelper.INSTANCE_STABLE_FIELDS_MAP;
import static jdk.vm.ci.hotspot.test.TestHelper.INSTANCE_FIELDS_MAP;
import static jdk.vm.ci.hotspot.test.TestHelper.STATIC_FIELDS_MAP;
import static jdk.vm.ci.hotspot.test.TestHelper.STATIC_STABLE_FIELDS_MAP;

import java.util.LinkedList;
import jdk.vm.ci.meta.JavaConstant;
import org.testng.annotations.DataProvider;

public class ReadStableFieldValueDataProvider {

    @DataProvider(name = "readStableFieldValueDataProvider")
    public static Object[][] readStableFieldValueDataProvider() {
        LinkedList<Object[]> cfgSet = new LinkedList<>();
        for (boolean isDefStab : new boolean[]{true, false}) {
            // Testing instance non-stable fields
            INSTANCE_FIELDS_MAP.entrySet().stream().forEach((instanceField) -> {
                cfgSet.add(new Object[]{instanceField.getKey(),
                                DUMMY_CLASS_CONSTANT,
                                isDefStab,
                                instanceField.getValue()});
            });
            // Testing static non-stable fields with null as receiver
            STATIC_FIELDS_MAP.entrySet().stream().forEach((staticField) -> {
                cfgSet.add(new Object[]{staticField.getKey(),
                                null,
                                isDefStab,
                                staticField.getValue()});
            });
            // Testing static non-stable fields with JavaConstant.NULL_POINTER as receiver
            STATIC_FIELDS_MAP.entrySet().stream().forEach((staticField) -> {
                cfgSet.add(new Object[]{staticField.getKey(),
                                JavaConstant.NULL_POINTER,
                                isDefStab,
                                staticField.getValue()});
            });
            // Testing instance stable fields
            INSTANCE_STABLE_FIELDS_MAP.entrySet().stream().forEach((instanceField) -> {
                cfgSet.add(new Object[]{instanceField.getKey(),
                                DUMMY_CLASS_CONSTANT,
                                isDefStab,
                                instanceField.getValue()});
            });
            // Testing static stable fields with null as receiver
            STATIC_STABLE_FIELDS_MAP.entrySet().stream().forEach((staticField) -> {
                cfgSet.add(new Object[]{staticField.getKey(),
                                null,
                                isDefStab,
                                staticField.getValue()});
            });
            // Testing static stable fields with JavaConstant.NULL_POINTER as receiver
            STATIC_STABLE_FIELDS_MAP.entrySet().stream().forEach((staticField) -> {
                cfgSet.add(new Object[]{staticField.getKey(),
                                JavaConstant.NULL_POINTER,
                                isDefStab,
                                staticField.getValue()});
            });
            // Testing instance fields with JavaConstant.NULL_POINTER as receiver
            INSTANCE_FIELDS_MAP.entrySet().stream().forEach((instanceField) -> {
                cfgSet.add(new Object[]{instanceField.getKey(),
                                JavaConstant.NULL_POINTER,
                                isDefStab,
                                null});
            });
            // Testing instance fields with an object that does not have the field
            INSTANCE_FIELDS_MAP.entrySet().stream().forEach((instanceField) -> {
                cfgSet.add(new Object[]{instanceField.getKey(),
                                CONSTANT_REFLECTION_PROVIDER.forObject(DUMMY_CLASS_INSTANCE.objectField),
                                isDefStab,
                                null});
            });
        }
        return cfgSet.toArray(new Object[0][0]);
    }

    @DataProvider(name = "readStableFieldValueArrayDataProvider")
    public static Object[][] readStableFieldValueArrayDataProvider() {
        LinkedList<Object[]> cfgSet = new LinkedList<>();
        for (boolean isDefStab : new boolean[]{true, false}) {
            // Testing instance non-stable array fields
            ARRAYS_MAP.entrySet().stream().forEach((instanceField) -> {
                cfgSet.add(new Object[]{instanceField.getKey(),
                                DUMMY_CLASS_CONSTANT,
                                isDefStab,
                                TestHelper.ARRAY_DIMENSION,
                                instanceField.getValue()});
            });
            // Testing instance stable array fields
            STABLE_ARRAYS_MAP.entrySet().stream().forEach((instanceField) -> {
                cfgSet.add(new Object[]{instanceField.getKey(),
                                DUMMY_CLASS_CONSTANT,
                                isDefStab,
                                TestHelper.ARRAY_DIMENSION,
                                instanceField.getValue()});
            });
            // Testing instance non-stable array-of-array fields
            ARRAY_ARRAYS_MAP.entrySet().stream().forEach((instanceField) -> {
                cfgSet.add(new Object[]{instanceField.getKey(),
                                DUMMY_CLASS_CONSTANT,
                                isDefStab,
                                TestHelper.ARRAY_OF_ARRAYS_DIMENSION,
                                instanceField.getValue()});
            });
            // Testing instance stable array-of-array fields
            STABLE_ARRAY_ARRAYS_MAP.entrySet().stream().forEach((instanceField) -> {
                cfgSet.add(new Object[]{instanceField.getKey(),
                                DUMMY_CLASS_CONSTANT,
                                isDefStab,
                                TestHelper.ARRAY_OF_ARRAYS_DIMENSION,
                                instanceField.getValue()});
            });
        }
        return cfgSet.toArray(new Object[0][0]);
    }

    @DataProvider(name = "readStableFieldValueNegativeDataProvider")
    public static Object[][] readStableFieldValueNegativeDataProvider() {
        LinkedList<Object[]> cfgSet = new LinkedList<>();
        for (boolean isDefStab : new boolean[]{true, false}) {
            // Testing instance fields with null as receiver
            INSTANCE_FIELDS_MAP.keySet().stream().forEach((instanceField) -> {
                cfgSet.add(new Object[]{instanceField, null, isDefStab});
            });
            // Testing null as a field argument
            cfgSet.add(new Object[]{null, null, isDefStab});
        }
        return cfgSet.toArray(new Object[0][0]);
    }
}
