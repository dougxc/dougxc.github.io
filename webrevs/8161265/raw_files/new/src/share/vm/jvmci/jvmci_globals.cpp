/*
 * Copyright (c) 2000, 2016, Oracle and/or its affiliates. All rights reserved.
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
 *
 */

#include "precompiled.hpp"
#include "jvmci/jvmci_globals.hpp"
#include "utilities/defaultStream.hpp"
#include "runtime/globals_extension.hpp"

JVMCI_FLAGS(MATERIALIZE_DEVELOPER_FLAG, \
            MATERIALIZE_PD_DEVELOPER_FLAG, \
            MATERIALIZE_PRODUCT_FLAG, \
            MATERIALIZE_PD_PRODUCT_FLAG, \
            MATERIALIZE_DIAGNOSTIC_FLAG, \
            MATERIALIZE_PD_DIAGNOSTIC_FLAG, \
            MATERIALIZE_EXPERIMENTAL_FLAG, \
            MATERIALIZE_NOTPRODUCT_FLAG,
            IGNORE_RANGE, \
            IGNORE_CONSTRAINT, \
            IGNORE_WRITEABLE)

// Return true if jvmci flags are consistent.
bool JVMCIGlobals::check_jvmci_flags_are_consistent() {

// Checks that a given flag is not set since a given guard flag is false.
#define CHECK_NOT_SET(FLAG, GUARD)                     \
  if (!FLAG_IS_DEFAULT(FLAG)) {                        \
    jio_fprintf(defaultStream::error_stream(),         \
        "Improperly specified VM option '%s': '%s' must be enabled\n", #FLAG, #GUARD); \
    return false;                                      \
  }

  if (!UseJVMCICompiler) {
    CHECK_NOT_SET(BootstrapJVMCI,   UseJVMCICompiler)
    CHECK_NOT_SET(PrintBootstrap,   UseJVMCICompiler)
    CHECK_NOT_SET(JVMCIThreads,     UseJVMCICompiler)
    CHECK_NOT_SET(JVMCIHostThreads, UseJVMCICompiler)
  } else {
    FLAG_SET_DEFAULT(EnableJVMCI, true);
  }

  if (!EnableJVMCI) {
    CHECK_NOT_SET(JVMCITraceLevel,              EnableJVMCI)
    CHECK_NOT_SET(JVMCICounterSize,             EnableJVMCI)
    CHECK_NOT_SET(JVMCICountersExcludeCompiler, EnableJVMCI)
    CHECK_NOT_SET(JVMCIUseFastLocking,          EnableJVMCI)
    CHECK_NOT_SET(JVMCINMethodSizeLimit,        EnableJVMCI)
    CHECK_NOT_SET(TraceUncollectedSpeculations, EnableJVMCI)
  }
  return true;
#undef CHECK_NOT_SET
}
