/*
 * Copyright (c) 2001, 2010, Oracle and/or its affiliates. All rights reserved.
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

#ifndef GPU_HSAIL_VM_VMSTRUCTS_HSAIL_HPP
#define GPU_HSAIL_VM_VMSTRUCTS_HSAIL_HPP

#include "gpu_hsail.hpp"
#include "gpu_hsail_Frame.hpp"

// These are the CPU-specific fields, types and integer
// constants required by the Serviceability Agent. This file is
// referenced by vmStructs.cpp.

#define VM_STRUCTS_GPU_HSAIL(nonstatic_field)                                                                                         \
  nonstatic_field(HSAILFrame, _pc_offset,                                                  jint)                                      \
  nonstatic_field(HSAILFrame, _num_s_regs,                                                 jbyte)                                     \
  nonstatic_field(HSAILFrame, _save_area[0],                                               jlong)                                     \
                                                                                                                                      \
  nonstatic_field(Hsail::HSAILKernelDeoptimization, _workitemid,                                jint)                                 \
  nonstatic_field(Hsail::HSAILKernelDeoptimization, _actionAndReason,                           jint)                                 \
  nonstatic_field(Hsail::HSAILKernelDeoptimization, _first_frame,                               HSAILFrame)                           \
                                                                                                                                      \
  nonstatic_field(Hsail::HSAILDeoptimizationInfo, _deopt_occurred,                         jint)                                      \
  nonstatic_field(Hsail::HSAILDeoptimizationInfo, _deopt_next_index,                       jint)                                      \
  nonstatic_field(Hsail::HSAILDeoptimizationInfo, _donor_threads,                          JavaThread**)                              \
  nonstatic_field(Hsail::HSAILDeoptimizationInfo, _never_ran_array,                        jboolean *)                                \
  nonstatic_field(Hsail::HSAILDeoptimizationInfo, _deopt_save_states[0],                   Hsail::HSAILKernelDeoptimization)          \
  nonstatic_field(Hsail::HSAILDeoptimizationInfo, _deopt_save_states[1],                   Hsail::HSAILKernelDeoptimization)

#define VM_TYPES_GPU_HSAIL(declare_type, declare_toplevel_type)                 \
  declare_toplevel_type(HSAILFrame)                                  \
  declare_toplevel_type(HSAILFrame*)                                 \
  declare_toplevel_type(Hsail::HSAILKernelDeoptimization)            \
  declare_toplevel_type(Hsail::HSAILDeoptimizationInfo)

#endif // GPU_HSAIL_VM_VMSTRUCTS_HSAIL_HPP
