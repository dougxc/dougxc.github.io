/*
 * Copyright (c) 2015, 2016, Oracle and/or its affiliates. All rights reserved.
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

/*
 * @test TestBasicLogOutput
 * @summary Ensure -XX:-JVMCIPrintProperties can be enabled and successfully prints expected output to stdout.
 * @requires (vm.simpleArch == "x64" | vm.simpleArch == "sparcv9" | vm.simpleArch == "aarch64")
 * @library /test/lib
 */

import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.process.OutputAnalyzer;

public class TestJVMCIPrintProperties {

    public static void main(String[] args) throws Exception {
        ProcessBuilder pb = ProcessTools.createJavaProcessBuilder(
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+EnableJVMCI",
            "-XX:+JVMCIPrintProperties",
            "-version");
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.shouldContain("[JVMCI system properties]"); // expected message
        output.shouldContain("String jvmci.Compiler"); // expected message
        output.shouldContain("Boolean jvmci.InitTimer"); // expected message
        output.shouldContain("Boolean jvmci.PrintConfig"); // expected message
        output.shouldContain("String jvmci.TraceMethodDataFilter"); // expected message
        output.shouldHaveExitValue(0);
    }
}
