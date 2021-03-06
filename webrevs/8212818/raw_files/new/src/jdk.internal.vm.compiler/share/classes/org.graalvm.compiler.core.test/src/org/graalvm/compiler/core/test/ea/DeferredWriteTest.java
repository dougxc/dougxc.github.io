/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.core.test.ea;

import org.graalvm.compiler.api.directives.GraalDirectives;
import org.graalvm.compiler.core.common.CompilationIdentifier;
import org.graalvm.compiler.core.test.GraalCompilerTest;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.FrameState;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.ParameterNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.extended.StateSplitProxyNode;
import org.graalvm.compiler.nodes.type.StampTool;
import org.graalvm.compiler.nodes.virtual.VirtualInstanceNode;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.virtual.nodes.VirtualObjectState;
import org.junit.Test;

import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;

public class DeferredWriteTest extends GraalCompilerTest {

    public static class TestObject {
        int a;
        Object b;
        double c;
        long d;
        boolean e;

        public TestObject(int a, Object b, double c, long d, boolean e) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
            this.e = e;
        }

        public boolean compare(Object obj) {
            TestObject other = (TestObject) obj;
            return other.a == a && other.b == b && other.c == c && other.d == d && other.e == e;
        }

        protected TestObject copy() {
            return new TestObject(a, b, c, d, e);
        }
    }

    public static volatile int dummy;
    public static volatile int dummy2;

    public static void testSnippet(TestObject t) {
        dummy = t.a;
        if (dummy2 == 0) {
            GraalDirectives.deoptimize();
        }
    }

    private static int mode;

    @Override
    protected StructuredGraph parseForCompile(ResolvedJavaMethod method, CompilationIdentifier compilationId, OptionValues options) {
        StructuredGraph graph = super.parseForCompile(method, compilationId, options);
        StateSplitProxyNode frameStateHolder = graph.getNodes().filter(StateSplitProxyNode.class).first();

        FrameState state = frameStateHolder.stateAfter();
        ParameterNode param = graph.getNodes().filter(ParameterNode.class).first();
        VirtualInstanceNode virtual = graph.add(new VirtualInstanceNode(StampTool.typeOrNull(param.stamp(NodeView.DEFAULT)), param));

        ValueNode[] values = new ValueNode[5];
        for (int i = 0; i < virtual.getFields().length; i++) {
            switch (virtual.field(i).getName()) {
                case "a":
                    values[i] = mode == 0 ? ConstantNode.forInt(123, graph) : null;
                    break;
                case "b":
                    values[i] = mode == 1 ? ConstantNode.defaultForKind(JavaKind.Object, graph) : null;
                    break;
                case "c":
                    values[i] = mode == 2 ? ConstantNode.forDouble(123, graph) : null;
                    break;
                case "d":
                    values[i] = mode == 3 ? ConstantNode.forLong(123, graph) : null;
                    break;
                case "e":
                    values[i] = mode == 4 ? ConstantNode.forInt(0, graph) : null;
                    break;
            }
        }
        state.addVirtualObjectMapping(graph.unique(new VirtualObjectState(virtual, values)));
        return graph;
    }

    @Override
    protected InstalledCode getCode(ResolvedJavaMethod installedCodeOwner, StructuredGraph graph, boolean forceCompile, boolean installAsDefault, OptionValues options) {
        return super.getCode(installedCodeOwner, graph, true, installAsDefault, options);
    }

    @Test
    public void simple() {
        for (mode = 0; mode < 5; mode++) {
            TestObject input = new TestObject(1, TestObject.class, 1, 100000000000L, true);
            test("testSnippet", input);
            assert input.a == (mode == 0 ? 123 : 1) : input.a;
            assert input.b == (mode == 1 ? null : TestObject.class) : input.b;
            assert input.c == (mode == 2 ? 123D : 1D) : input.c;
            assert input.d == (mode == 3 ? 123L : 100000000000L) : input.d;
            assert input.e == (mode == 4 ? false : true) : input.e;
        }
    }
}
