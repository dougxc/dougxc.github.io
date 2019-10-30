/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
package jdk.vm.ci.hotspot.jfr.events;

import static jdk.jfr.DataAmount.BYTES;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.MetadataDefinition;
import jdk.jfr.Relational;
import jdk.vm.ci.hotspot.EventProvider;
import jdk.vm.ci.services.JVMCIServiceLocator;

/**
 * A JFR implementation for {@link EventProvider}. This implementation is used when Flight Recorder
 * is {@linkplain FlightRecorder#isAvailable() available}.
 */
public final class JFREventProvider implements EventProvider {

    public static class Locator extends JVMCIServiceLocator {

        @Override
        public <S> S getProvider(Class<S> service) {
            if (service == EventProvider.class) {
                if (FlightRecorder.isAvailable()) {
                    return service.cast(new JFREventProvider());
                }
            }
            return null;
        }
    }

    @Override
    public CompilationEvent newCompilationEvent() {
        return new JFRCompilationEvent();
    }

    @MetadataDefinition
    @Relational
    @Target({ElementType.FIELD})
    @Retention(RetentionPolicy.RUNTIME)
    @Label("Compilation ID")
    @Description("A globally unique identifier for a compiler")
    public @interface CompilationID {
    }

    @MetadataDefinition
    @Target({ElementType.FIELD})
    @Retention(RetentionPolicy.RUNTIME)
    @Label("JavaMethod")
    @Description("Represents a Java method as a string composed of " +
                 "a fully qualified name, descriptor and modifiers encoded as an int. " +
                 "Example: \"java.lang.String.indexOf(Ljava/lang/String;I)I 1\"")
    public @interface JavaMethod {
    }

    /**
     * A JFR compilation event.
     */
    @Label("Compilation")
    @Description("A JVMCI compiler compilation")
    @Category({"Java Virtual Machine", "Compiler", "Compilation"})
    public static class JFRCompilationEvent extends Event implements CompilationEvent {

        @JavaMethod public String method;
        @CompilationID public int compileId;
        @Label("Compilation Level") public short compileLevel;
        @Label("Succeeded") public boolean succeeded;
        @Label("On Stack Replacement") @Description("True if the compilation started at a loop instead of method entry") public boolean isOsr;
        @Label("Compiled Code Size") @Description("Size of code and associated metadata installed in the code cache") @DataAmount(BYTES) public int codeSize;
        @Label("Inlined Code Size") @Description("The size of bytecode input to the compilation, included inlined methods") @DataAmount(BYTES) public int inlinedBytes;

        @Override
        public void setMethod(String method) {
            this.method = method;
        }

        @Override
        public void setCompileId(int id) {
            this.compileId = id;
        }

        @Override
        public void setCompileLevel(int compileLevel) {
            this.compileLevel = (short) compileLevel;
        }

        @Override
        public void setSucceeded(boolean succeeded) {
            this.succeeded = succeeded;
        }

        @Override
        public void setIsOsr(boolean isOsr) {
            this.isOsr = isOsr;
        }

        @Override
        public void setCodeSize(int codeSize) {
            this.codeSize = codeSize;
        }

        @Override
        public void setInlinedBytes(int inlinedBytes) {
            this.inlinedBytes = inlinedBytes;
        }

        @Override
        public boolean shouldWrite() {
            return this.isEnabled();
        }
    }

    @Override
    public CompilerFailureEvent newCompilerFailureEvent() {
        return new JFRCompilerFailureEvent();
    }

    /**
     * A JFR compiler failure event.
     */
    @Label("Compilation Failure")
    @Description("A JVMCI compiler compilation failure")
    @Category({"Java Virtual Machine", "Compiler", "Failure"})
    public static class JFRCompilerFailureEvent extends Event implements CompilerFailureEvent {

        @CompilationID public int compileId;
        @Label("Message") @Description("The failure message") public String failure;

        public void setCompileId(int id) {
            this.compileId = id;
        }

        public void setMessage(String message) {
            this.failure = message;
        }

        public boolean shouldWrite() {
            return isEnabled();
        }
    }

}
