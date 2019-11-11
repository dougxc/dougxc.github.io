/*
 * Copyright (c) 2009, 2014, Oracle and/or its affiliates. All rights reserved.
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
package jdk.vm.ci.meta;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Type;

/**
 * Represents a resolved Java method. Methods, like fields and types, are resolved through
 * {@link ConstantPool constant pools}.
 */
public interface ResolvedJavaMethod extends JavaMethod, InvokeTarget, ModifiersProvider, AnnotatedElement {

    /**
     * Returns the bytecode of this method, if the method has code. The returned byte array does not
     * contain breakpoints or non-Java bytecodes. This may return null if the
     * {@link #getDeclaringClass() holder} is not {@link ResolvedJavaType#isLinked() linked}.
     *
     * The contained constant pool indices may not be the ones found in the original class file but
     * they can be used with the JVMCI API (e.g. methods in {@link ConstantPool}).
     *
     * @return the bytecode of the method, or {@code null} if {@code getCodeSize() == 0} or if the
     *         code is not ready.
     */
    byte[] getCode();

    /**
     * Returns the size of the bytecode of this method, if the method has code. This is equivalent
     * to {@link #getCode()}. {@code length} if the method has code.
     *
     * @return the size of the bytecode in bytes, or 0 if no bytecode is available
     */
    int getCodeSize();

    /**
     * Returns the {@link ResolvedJavaType} object representing the class or interface that declares
     * this method.
     */
    ResolvedJavaType getDeclaringClass();

    /**
     * Returns the maximum number of locals used in this method's bytecodes.
     */
    int getMaxLocals();

    /**
     * Returns the maximum number of stack slots used in this method's bytecodes.
     */
    int getMaxStackSize();

    default boolean isFinal() {
        return ModifiersProvider.super.isFinalFlagSet();
    }

    /**
     * Determines if this method is a synthetic method as defined by the Java Language
     * Specification.
     */
    boolean isSynthetic();

    /**
     * Checks that the method is a
     * <a href="http://docs.oracle.com/javase/specs/jvms/se8/html/jvms-4.html#jvms-4.6">varargs</a>
     * method.
     *
     * @return whether the method is a varargs method
     */
    boolean isVarArgs();

    /**
     * Checks that the method is a
     * <a href="http://docs.oracle.com/javase/specs/jvms/se8/html/jvms-4.html#jvms-4.6">bridge</a>
     * method.
     *
     * @return whether the method is a bridge method
     */
    boolean isBridge();

    /**
     * Returns {@code true} if this method is a default method; returns {@code false} otherwise.
     *
     * A default method is a public non-abstract instance method, that is, a non-static method with
     * a body, declared in an interface type.
     *
     * @return true if and only if this method is a default method as defined by the Java Language
     *         Specification.
     */
    boolean isDefault();

    /**
     * Checks whether this method is a class initializer.
     *
     * @return {@code true} if the method is a class initializer
     */
    boolean isClassInitializer();

    /**
     * Checks whether this method is a constructor.
     *
     * @return {@code true} if the method is a constructor
     */
    boolean isConstructor();

    /**
     * Checks whether this method can be statically bound (usually, that means it is final or
     * private or static, but not abstract, or the declaring class is final).
     *
     * @return {@code true} if this method can be statically bound
     */
    boolean canBeStaticallyBound();

    /**
     * Returns the list of exception handlers for this method.
     */
    ExceptionHandler[] getExceptionHandlers();

    /**
     * Returns a stack trace element for this method and a given bytecode index.
     */
    StackTraceElement asStackTraceElement(int bci);

    /**
     * Returns an object that provides access to the profiling information recorded for this method.
     */
    default ProfilingInfo getProfilingInfo() {
        return getProfilingInfo(true, true);
    }

    /**
     * Returns an object that provides access to the profiling information recorded for this method.
     *
     * @param includeNormal if true,
     *            {@linkplain ProfilingInfo#getDeoptimizationCount(DeoptimizationReason)
     *            deoptimization counts} will include deoptimization that happened during execution
     *            of standard non-osr methods.
     * @param includeOSR if true,
     *            {@linkplain ProfilingInfo#getDeoptimizationCount(DeoptimizationReason)
     *            deoptimization counts} will include deoptimization that happened during execution
     *            of on-stack-replacement methods.
     */
    ProfilingInfo getProfilingInfo(boolean includeNormal, boolean includeOSR);

    /**
     * Invalidates the profiling information and restarts profiling upon the next invocation.
     */
    void reprofile();

    /**
     * Returns the constant pool of this method.
     */
    ConstantPool getConstantPool();

    /**
     * Returns an array of arrays that represent the annotations on the formal parameters, in
     * declaration order, of this method.
     *
     * @see Method#getParameterAnnotations()
     */
    Annotation[][] getParameterAnnotations();

    /**
     * Returns an array of {@link Type} objects that represent the formal parameter types, in
     * declaration order, of this method.
     *
     * @see Method#getGenericParameterTypes()
     */
    Type[] getGenericParameterTypes();

    /**
     * Returns {@code true} if this method is not excluded from inlining and has associated Java
     * bytecodes (@see {@link ResolvedJavaMethod#hasBytecodes()}).
     */
    boolean canBeInlined();

    /**
     * Returns {@code true} if the inlining of this method should be forced.
     */
    boolean shouldBeInlined();

    /**
     * Returns the LineNumberTable of this method or null if this method does not have a line
     * numbers table.
     */
    LineNumberTable getLineNumberTable();

    /**
     * Returns the local variable table of this method or null if this method does not have a local
     * variable table.
     */
    LocalVariableTable getLocalVariableTable();

    /**
     * Gets the encoding of (that is, a constant representing the value of) this method.
     *
     * @return a constant representing a reference to this method
     */
    Constant getEncoding();

    /**
     * Checks if this method is present in the virtual table for subtypes of the specified
     * {@linkplain ResolvedJavaType type}.
     *
     * @return true is this method is present in the virtual table for subtypes of this type.
     */
    boolean isInVirtualMethodTable(ResolvedJavaType resolved);

    /**
     * Gets the annotation of a particular type for a formal parameter of this method.
     *
     * @param annotationClass the Class object corresponding to the annotation type
     * @param parameterIndex the index of a formal parameter of {@code method}
     * @return the annotation of type {@code annotationClass} for the formal parameter present, else
     *         null
     * @throws IndexOutOfBoundsException if {@code parameterIndex} does not denote a formal
     *             parameter
     */
    default <T extends Annotation> T getParameterAnnotation(Class<T> annotationClass, int parameterIndex) {
        if (parameterIndex >= 0) {
            Annotation[][] parameterAnnotations = getParameterAnnotations();
            for (Annotation a : parameterAnnotations[parameterIndex]) {
                if (a.annotationType() == annotationClass) {
                    return annotationClass.cast(a);
                }
            }
        }
        return null;
    }

    default JavaType[] toParameterTypes() {
        JavaType receiver = isStatic() || isConstructor() ? null : getDeclaringClass();
        return getSignature().toParameterTypes(receiver);
    }

    /**
     * Gets the annotations of a particular type for the formal parameters of this method.
     *
     * @param annotationClass the Class object corresponding to the annotation type
     * @return the annotation of type {@code annotationClass} (if any) for each formal parameter
     *         present
     */
    @SuppressWarnings("unchecked")
    default <T extends Annotation> T[] getParameterAnnotations(Class<T> annotationClass) {
        Annotation[][] parameterAnnotations = getParameterAnnotations();
        T[] result = (T[]) Array.newInstance(annotationClass, parameterAnnotations.length);
        for (int i = 0; i < parameterAnnotations.length; i++) {
            for (Annotation a : parameterAnnotations[i]) {
                if (a.annotationType() == annotationClass) {
                    result[i] = annotationClass.cast(a);
                }
            }
        }
        return result;
    }

    /**
     * Checks whether the method has bytecodes associated with it. Methods without bytecodes are
     * either abstract or native methods.
     *
     * @return whether the definition of this method is Java bytecodes
     */
    default boolean hasBytecodes() {
        return isConcrete() && !isNative();
    }

    /**
     * Checks whether the method has a receiver parameter - i.e., whether it is not static.
     *
     * @return whether the method has a receiver parameter
     */
    default boolean hasReceiver() {
        return !isStatic();
    }

    /**
     * Determines if this method is {@link java.lang.Object#Object()}.
     */
    default boolean isJavaLangObjectInit() {
        return getDeclaringClass().isJavaLangObject() && getName().equals("<init>");
    }

    SpeculationLog getSpeculationLog();
}