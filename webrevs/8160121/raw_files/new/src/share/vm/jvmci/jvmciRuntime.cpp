/*
 * Copyright (c) 2012, 2016, Oracle and/or its affiliates. All rights reserved.
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

#include "precompiled.hpp"
#include "asm/codeBuffer.hpp"
#include "classfile/javaClasses.inline.hpp"
#include "code/codeCache.hpp"
#include "compiler/compileBroker.hpp"
#include "compiler/disassembler.hpp"
#include "jvmci/jvmciRuntime.hpp"
#include "jvmci/jvmciCompilerToVM.hpp"
#include "jvmci/jvmciCompiler.hpp"
#include "jvmci/jvmciJavaClasses.hpp"
#include "jvmci/jvmciEnv.hpp"
#include "logging/log.hpp"
#include "memory/oopFactory.hpp"
#include "memory/resourceArea.hpp"
#include "oops/oop.inline.hpp"
#include "oops/objArrayOop.inline.hpp"
#include "prims/jvm.h"
#include "runtime/biasedLocking.hpp"
#include "runtime/interfaceSupport.hpp"
#include "runtime/reflection.hpp"
#include "runtime/sharedRuntime.hpp"
#include "utilities/debug.hpp"
#include "utilities/defaultStream.hpp"

#if defined(_MSC_VER)
#define strtoll _strtoi64
#endif

jobject JVMCIRuntime::_HotSpotJVMCIRuntime_instance = NULL;
bool JVMCIRuntime::_HotSpotJVMCIRuntime_initialized = false;
bool JVMCIRuntime::_well_known_classes_initialized = false;
int JVMCIRuntime::_trivial_prefixes_count = 0;
char** JVMCIRuntime::_trivial_prefixes = NULL;
JVMCIRuntime::CompLevelAdjustment JVMCIRuntime::_comp_level_adjustment = JVMCIRuntime::none;
bool JVMCIRuntime::_shutdown_called = false;

BasicType JVMCIRuntime::kindToBasicType(Handle kind, TRAPS) {
  if (kind.is_null()) {
    THROW_(vmSymbols::java_lang_NullPointerException(), T_ILLEGAL);
  }
  jchar ch = JavaKind::typeChar(kind);
  switch(ch) {
    case 'Z': return T_BOOLEAN;
    case 'B': return T_BYTE;
    case 'S': return T_SHORT;
    case 'C': return T_CHAR;
    case 'I': return T_INT;
    case 'F': return T_FLOAT;
    case 'J': return T_LONG;
    case 'D': return T_DOUBLE;
    case 'A': return T_OBJECT;
    case '-': return T_ILLEGAL;
    default:
      JVMCI_ERROR_(T_ILLEGAL, "unexpected Kind: %c", ch);
  }
}

// Simple helper to see if the caller of a runtime stub which
// entered the VM has been deoptimized

static bool caller_is_deopted() {
  JavaThread* thread = JavaThread::current();
  RegisterMap reg_map(thread, false);
  frame runtime_frame = thread->last_frame();
  frame caller_frame = runtime_frame.sender(&reg_map);
  assert(caller_frame.is_compiled_frame(), "must be compiled");
  return caller_frame.is_deoptimized_frame();
}

// Stress deoptimization
static void deopt_caller() {
  if ( !caller_is_deopted()) {
    JavaThread* thread = JavaThread::current();
    RegisterMap reg_map(thread, false);
    frame runtime_frame = thread->last_frame();
    frame caller_frame = runtime_frame.sender(&reg_map);
    Deoptimization::deoptimize_frame(thread, caller_frame.id(), Deoptimization::Reason_constraint);
    assert(caller_is_deopted(), "Must be deoptimized");
  }
}

JRT_BLOCK_ENTRY(void, JVMCIRuntime::new_instance(JavaThread* thread, Klass* klass))
  JRT_BLOCK;
  assert(klass->is_klass(), "not a class");
  Handle holder(THREAD, klass->klass_holder()); // keep the klass alive
  instanceKlassHandle h(thread, klass);
  h->check_valid_for_instantiation(true, CHECK);
  // make sure klass is initialized
  h->initialize(CHECK);
  // allocate instance and return via TLS
  oop obj = h->allocate_instance(CHECK);
  thread->set_vm_result(obj);
  JRT_BLOCK_END;

  if (ReduceInitialCardMarks) {
    new_store_pre_barrier(thread);
  }
JRT_END

JRT_BLOCK_ENTRY(void, JVMCIRuntime::new_array(JavaThread* thread, Klass* array_klass, jint length))
  JRT_BLOCK;
  // Note: no handle for klass needed since they are not used
  //       anymore after new_objArray() and no GC can happen before.
  //       (This may have to change if this code changes!)
  assert(array_klass->is_klass(), "not a class");
  oop obj;
  if (array_klass->is_typeArray_klass()) {
    BasicType elt_type = TypeArrayKlass::cast(array_klass)->element_type();
    obj = oopFactory::new_typeArray(elt_type, length, CHECK);
  } else {
    Handle holder(THREAD, array_klass->klass_holder()); // keep the klass alive
    Klass* elem_klass = ObjArrayKlass::cast(array_klass)->element_klass();
    obj = oopFactory::new_objArray(elem_klass, length, CHECK);
  }
  thread->set_vm_result(obj);
  // This is pretty rare but this runtime patch is stressful to deoptimization
  // if we deoptimize here so force a deopt to stress the path.
  if (DeoptimizeALot) {
    static int deopts = 0;
    // Alternate between deoptimizing and raising an error (which will also cause a deopt)
    if (deopts++ % 2 == 0) {
      ResourceMark rm(THREAD);
      THROW(vmSymbols::java_lang_OutOfMemoryError());
    } else {
      deopt_caller();
    }
  }
  JRT_BLOCK_END;

  if (ReduceInitialCardMarks) {
    new_store_pre_barrier(thread);
  }
JRT_END

void JVMCIRuntime::new_store_pre_barrier(JavaThread* thread) {
  // After any safepoint, just before going back to compiled code,
  // we inform the GC that we will be doing initializing writes to
  // this object in the future without emitting card-marks, so
  // GC may take any compensating steps.
  // NOTE: Keep this code consistent with GraphKit::store_barrier.

  oop new_obj = thread->vm_result();
  if (new_obj == NULL)  return;

  assert(Universe::heap()->can_elide_tlab_store_barriers(),
         "compiler must check this first");
  // GC may decide to give back a safer copy of new_obj.
  new_obj = Universe::heap()->new_store_pre_barrier(thread, new_obj);
  thread->set_vm_result(new_obj);
}

JRT_ENTRY(void, JVMCIRuntime::new_multi_array(JavaThread* thread, Klass* klass, int rank, jint* dims))
  assert(klass->is_klass(), "not a class");
  assert(rank >= 1, "rank must be nonzero");
  Handle holder(THREAD, klass->klass_holder()); // keep the klass alive
  oop obj = ArrayKlass::cast(klass)->multi_allocate(rank, dims, CHECK);
  thread->set_vm_result(obj);
JRT_END

JRT_ENTRY(void, JVMCIRuntime::dynamic_new_array(JavaThread* thread, oopDesc* element_mirror, jint length))
  oop obj = Reflection::reflect_new_array(element_mirror, length, CHECK);
  thread->set_vm_result(obj);
JRT_END

JRT_ENTRY(void, JVMCIRuntime::dynamic_new_instance(JavaThread* thread, oopDesc* type_mirror))
  instanceKlassHandle klass(THREAD, java_lang_Class::as_Klass(type_mirror));

  if (klass == NULL) {
    ResourceMark rm(THREAD);
    THROW(vmSymbols::java_lang_InstantiationException());
  }

  // Create new instance (the receiver)
  klass->check_valid_for_instantiation(false, CHECK);

  // Make sure klass gets initialized
  klass->initialize(CHECK);

  oop obj = klass->allocate_instance(CHECK);
  thread->set_vm_result(obj);
JRT_END

extern void vm_exit(int code);

// Enter this method from compiled code handler below. This is where we transition
// to VM mode. This is done as a helper routine so that the method called directly
// from compiled code does not have to transition to VM. This allows the entry
// method to see if the nmethod that we have just looked up a handler for has
// been deoptimized while we were in the vm. This simplifies the assembly code
// cpu directories.
//
// We are entering here from exception stub (via the entry method below)
// If there is a compiled exception handler in this method, we will continue there;
// otherwise we will unwind the stack and continue at the caller of top frame method
// Note: we enter in Java using a special JRT wrapper. This wrapper allows us to
// control the area where we can allow a safepoint. After we exit the safepoint area we can
// check to see if the handler we are going to return is now in a nmethod that has
// been deoptimized. If that is the case we return the deopt blob
// unpack_with_exception entry instead. This makes life for the exception blob easier
// because making that same check and diverting is painful from assembly language.
JRT_ENTRY_NO_ASYNC(static address, exception_handler_for_pc_helper(JavaThread* thread, oopDesc* ex, address pc, CompiledMethod*& cm))
  // Reset method handle flag.
  thread->set_is_method_handle_return(false);

  Handle exception(thread, ex);
  cm = CodeCache::find_compiled(pc);
  assert(cm != NULL, "this is not a compiled method");
  // Adjust the pc as needed/
  if (cm->is_deopt_pc(pc)) {
    RegisterMap map(thread, false);
    frame exception_frame = thread->last_frame().sender(&map);
    // if the frame isn't deopted then pc must not correspond to the caller of last_frame
    assert(exception_frame.is_deoptimized_frame(), "must be deopted");
    pc = exception_frame.pc();
  }
#ifdef ASSERT
  assert(exception.not_null(), "NULL exceptions should be handled by throw_exception");
  assert(exception->is_oop(), "just checking");
  // Check that exception is a subclass of Throwable, otherwise we have a VerifyError
  if (!(exception->is_a(SystemDictionary::Throwable_klass()))) {
    if (ExitVMOnVerifyError) vm_exit(-1);
    ShouldNotReachHere();
  }
#endif

  // Check the stack guard pages and reenable them if necessary and there is
  // enough space on the stack to do so.  Use fast exceptions only if the guard
  // pages are enabled.
  bool guard_pages_enabled = thread->stack_guards_enabled();
  if (!guard_pages_enabled) guard_pages_enabled = thread->reguard_stack();

  if (JvmtiExport::can_post_on_exceptions()) {
    // To ensure correct notification of exception catches and throws
    // we have to deoptimize here.  If we attempted to notify the
    // catches and throws during this exception lookup it's possible
    // we could deoptimize on the way out of the VM and end back in
    // the interpreter at the throw site.  This would result in double
    // notifications since the interpreter would also notify about
    // these same catches and throws as it unwound the frame.

    RegisterMap reg_map(thread);
    frame stub_frame = thread->last_frame();
    frame caller_frame = stub_frame.sender(&reg_map);

    // We don't really want to deoptimize the nmethod itself since we
    // can actually continue in the exception handler ourselves but I
    // don't see an easy way to have the desired effect.
    Deoptimization::deoptimize_frame(thread, caller_frame.id(), Deoptimization::Reason_constraint);
    assert(caller_is_deopted(), "Must be deoptimized");

    return SharedRuntime::deopt_blob()->unpack_with_exception_in_tls();
  }

  // ExceptionCache is used only for exceptions at call sites and not for implicit exceptions
  if (guard_pages_enabled) {
    address fast_continuation = cm->handler_for_exception_and_pc(exception, pc);
    if (fast_continuation != NULL) {
      // Set flag if return address is a method handle call site.
      thread->set_is_method_handle_return(cm->is_method_handle_return(pc));
      return fast_continuation;
    }
  }

  // If the stack guard pages are enabled, check whether there is a handler in
  // the current method.  Otherwise (guard pages disabled), force an unwind and
  // skip the exception cache update (i.e., just leave continuation==NULL).
  address continuation = NULL;
  if (guard_pages_enabled) {

    // New exception handling mechanism can support inlined methods
    // with exception handlers since the mappings are from PC to PC

    // debugging support
    // tracing
    if (log_is_enabled(Info, exceptions)) {
      ResourceMark rm;
      stringStream tempst;
      tempst.print("compiled method <%s>\n"
                   " at PC" INTPTR_FORMAT " for thread " INTPTR_FORMAT,
                   cm->method()->print_value_string(), p2i(pc), p2i(thread));
      Exceptions::log_exception(exception, tempst);
    }
    // for AbortVMOnException flag
    NOT_PRODUCT(Exceptions::debug_check_abort(exception));

    // Clear out the exception oop and pc since looking up an
    // exception handler can cause class loading, which might throw an
    // exception and those fields are expected to be clear during
    // normal bytecode execution.
    thread->clear_exception_oop_and_pc();

    continuation = SharedRuntime::compute_compiled_exc_handler(cm, pc, exception, false, false);
    // If an exception was thrown during exception dispatch, the exception oop may have changed
    thread->set_exception_oop(exception());
    thread->set_exception_pc(pc);

    // the exception cache is used only by non-implicit exceptions
    if (continuation != NULL && !SharedRuntime::deopt_blob()->contains(continuation)) {
      cm->add_handler_for_exception_and_pc(exception, pc, continuation);
    }
  }

  // Set flag if return address is a method handle call site.
  thread->set_is_method_handle_return(cm->is_method_handle_return(pc));

  if (log_is_enabled(Info, exceptions)) {
    ResourceMark rm;
    log_info(exceptions)("Thread " PTR_FORMAT " continuing at PC " PTR_FORMAT
                         " for exception thrown at PC " PTR_FORMAT,
                         p2i(thread), p2i(continuation), p2i(pc));
  }

  return continuation;
JRT_END

// Enter this method from compiled code only if there is a Java exception handler
// in the method handling the exception.
// We are entering here from exception stub. We don't do a normal VM transition here.
// We do it in a helper. This is so we can check to see if the nmethod we have just
// searched for an exception handler has been deoptimized in the meantime.
address JVMCIRuntime::exception_handler_for_pc(JavaThread* thread) {
  oop exception = thread->exception_oop();
  address pc = thread->exception_pc();
  // Still in Java mode
  DEBUG_ONLY(ResetNoHandleMark rnhm);
  CompiledMethod* cm = NULL;
  address continuation = NULL;
  {
    // Enter VM mode by calling the helper
    ResetNoHandleMark rnhm;
    continuation = exception_handler_for_pc_helper(thread, exception, pc, cm);
  }
  // Back in JAVA, use no oops DON'T safepoint

  // Now check to see if the compiled method we were called from is now deoptimized.
  // If so we must return to the deopt blob and deoptimize the nmethod
  if (cm != NULL && caller_is_deopted()) {
    continuation = SharedRuntime::deopt_blob()->unpack_with_exception_in_tls();
  }

  assert(continuation != NULL, "no handler found");
  return continuation;
}

JRT_ENTRY_NO_ASYNC(void, JVMCIRuntime::monitorenter(JavaThread* thread, oopDesc* obj, BasicLock* lock))
  IF_TRACE_jvmci_3 {
    char type[O_BUFLEN];
    obj->klass()->name()->as_C_string(type, O_BUFLEN);
    markOop mark = obj->mark();
    TRACE_jvmci_3("%s: entered locking slow case with obj=" INTPTR_FORMAT ", type=%s, mark=" INTPTR_FORMAT ", lock=" INTPTR_FORMAT, thread->name(), p2i(obj), type, p2i(mark), p2i(lock));
    tty->flush();
  }
#ifdef ASSERT
  if (PrintBiasedLockingStatistics) {
    Atomic::inc(BiasedLocking::slow_path_entry_count_addr());
  }
#endif
  Handle h_obj(thread, obj);
  assert(h_obj()->is_oop(), "must be NULL or an object");
  if (UseBiasedLocking) {
    // Retry fast entry if bias is revoked to avoid unnecessary inflation
    ObjectSynchronizer::fast_enter(h_obj, lock, true, CHECK);
  } else {
    if (JVMCIUseFastLocking) {
      // When using fast locking, the compiled code has already tried the fast case
      ObjectSynchronizer::slow_enter(h_obj, lock, THREAD);
    } else {
      ObjectSynchronizer::fast_enter(h_obj, lock, false, THREAD);
    }
  }
  TRACE_jvmci_3("%s: exiting locking slow with obj=" INTPTR_FORMAT, thread->name(), p2i(obj));
JRT_END

JRT_LEAF(void, JVMCIRuntime::monitorexit(JavaThread* thread, oopDesc* obj, BasicLock* lock))
  assert(thread == JavaThread::current(), "threads must correspond");
  assert(thread->last_Java_sp(), "last_Java_sp must be set");
  // monitorexit is non-blocking (leaf routine) => no exceptions can be thrown
  EXCEPTION_MARK;

#ifdef DEBUG
  if (!obj->is_oop()) {
    ResetNoHandleMark rhm;
    nmethod* method = thread->last_frame().cb()->as_nmethod_or_null();
    if (method != NULL) {
      tty->print_cr("ERROR in monitorexit in method %s wrong obj " INTPTR_FORMAT, method->name(), p2i(obj));
    }
    thread->print_stack_on(tty);
    assert(false, "invalid lock object pointer dected");
  }
#endif

  if (JVMCIUseFastLocking) {
    // When using fast locking, the compiled code has already tried the fast case
    ObjectSynchronizer::slow_exit(obj, lock, THREAD);
  } else {
    ObjectSynchronizer::fast_exit(obj, lock, THREAD);
  }
  IF_TRACE_jvmci_3 {
    char type[O_BUFLEN];
    obj->klass()->name()->as_C_string(type, O_BUFLEN);
    TRACE_jvmci_3("%s: exited locking slow case with obj=" INTPTR_FORMAT ", type=%s, mark=" INTPTR_FORMAT ", lock=" INTPTR_FORMAT, thread->name(), p2i(obj), type, p2i(obj->mark()), p2i(lock));
    tty->flush();
  }
JRT_END

JRT_ENTRY(void, JVMCIRuntime::throw_and_post_jvmti_exception(JavaThread* thread, const char* exception, const char* message))
  TempNewSymbol symbol = SymbolTable::new_symbol(exception, CHECK);
  SharedRuntime::throw_and_post_jvmti_exception(thread, symbol, message);
JRT_END

JRT_ENTRY(void, JVMCIRuntime::throw_klass_external_name_exception(JavaThread* thread, const char* exception, Klass* klass))
  ResourceMark rm(thread);
  TempNewSymbol symbol = SymbolTable::new_symbol(exception, CHECK);
  SharedRuntime::throw_and_post_jvmti_exception(thread, symbol, klass->external_name());
JRT_END

JRT_ENTRY(void, JVMCIRuntime::throw_class_cast_exception(JavaThread* thread, const char* exception, Klass* caster_klass, Klass* target_klass))
  ResourceMark rm(thread);
  const char* message = SharedRuntime::generate_class_cast_message(caster_klass, target_klass);
  TempNewSymbol symbol = SymbolTable::new_symbol(exception, CHECK);
  SharedRuntime::throw_and_post_jvmti_exception(thread, symbol, message);
JRT_END

JRT_LEAF(void, JVMCIRuntime::log_object(JavaThread* thread, oopDesc* obj, bool as_string, bool newline))
  ttyLocker ttyl;

  if (obj == NULL) {
    tty->print("NULL");
  } else if (obj->is_oop_or_null(true) && (!as_string || !java_lang_String::is_instance(obj))) {
    if (obj->is_oop_or_null(true)) {
      char buf[O_BUFLEN];
      tty->print("%s@" INTPTR_FORMAT, obj->klass()->name()->as_C_string(buf, O_BUFLEN), p2i(obj));
    } else {
      tty->print(INTPTR_FORMAT, p2i(obj));
    }
  } else {
    ResourceMark rm;
    assert(obj != NULL && java_lang_String::is_instance(obj), "must be");
    char *buf = java_lang_String::as_utf8_string(obj);
    tty->print_raw(buf);
  }
  if (newline) {
    tty->cr();
  }
JRT_END

JRT_LEAF(void, JVMCIRuntime::write_barrier_pre(JavaThread* thread, oopDesc* obj))
  thread->satb_mark_queue().enqueue(obj);
JRT_END

JRT_LEAF(void, JVMCIRuntime::write_barrier_post(JavaThread* thread, void* card_addr))
  thread->dirty_card_queue().enqueue(card_addr);
JRT_END

JRT_LEAF(jboolean, JVMCIRuntime::validate_object(JavaThread* thread, oopDesc* parent, oopDesc* child))
  bool ret = true;
  if(!Universe::heap()->is_in_closed_subset(parent)) {
    tty->print_cr("Parent Object " INTPTR_FORMAT " not in heap", p2i(parent));
    parent->print();
    ret=false;
  }
  if(!Universe::heap()->is_in_closed_subset(child)) {
    tty->print_cr("Child Object " INTPTR_FORMAT " not in heap", p2i(child));
    child->print();
    ret=false;
  }
  return (jint)ret;
JRT_END

JRT_ENTRY(void, JVMCIRuntime::vm_error(JavaThread* thread, jlong where, jlong format, jlong value))
  ResourceMark rm;
  const char *error_msg = where == 0L ? "<internal JVMCI error>" : (char*) (address) where;
  char *detail_msg = NULL;
  if (format != 0L) {
    const char* buf = (char*) (address) format;
    size_t detail_msg_length = strlen(buf) * 2;
    detail_msg = (char *) NEW_RESOURCE_ARRAY(u_char, detail_msg_length);
    jio_snprintf(detail_msg, detail_msg_length, buf, value);
    report_vm_error(__FILE__, __LINE__, error_msg, "%s", detail_msg);
  } else {
    report_vm_error(__FILE__, __LINE__, error_msg);
  }
JRT_END

JRT_LEAF(oopDesc*, JVMCIRuntime::load_and_clear_exception(JavaThread* thread))
  oop exception = thread->exception_oop();
  assert(exception != NULL, "npe");
  thread->set_exception_oop(NULL);
  thread->set_exception_pc(0);
  return exception;
JRT_END

PRAGMA_DIAG_PUSH
PRAGMA_FORMAT_NONLITERAL_IGNORED
JRT_LEAF(void, JVMCIRuntime::log_printf(JavaThread* thread, oopDesc* format, jlong v1, jlong v2, jlong v3))
  ResourceMark rm;
  assert(format != NULL && java_lang_String::is_instance(format), "must be");
  char *buf = java_lang_String::as_utf8_string(format);
  tty->print((const char*)buf, v1, v2, v3);
JRT_END
PRAGMA_DIAG_POP

static void decipher(jlong v, bool ignoreZero) {
  if (v != 0 || !ignoreZero) {
    void* p = (void *)(address) v;
    CodeBlob* cb = CodeCache::find_blob(p);
    if (cb) {
      if (cb->is_nmethod()) {
        char buf[O_BUFLEN];
        tty->print("%s [" INTPTR_FORMAT "+" JLONG_FORMAT "]", cb->as_nmethod_or_null()->method()->name_and_sig_as_C_string(buf, O_BUFLEN), p2i(cb->code_begin()), (jlong)((address)v - cb->code_begin()));
        return;
      }
      cb->print_value_on(tty);
      return;
    }
    if (Universe::heap()->is_in(p)) {
      oop obj = oop(p);
      obj->print_value_on(tty);
      return;
    }
    tty->print(INTPTR_FORMAT " [long: " JLONG_FORMAT ", double %lf, char %c]",p2i((void *)v), (jlong)v, (jdouble)v, (char)v);
  }
}

PRAGMA_DIAG_PUSH
PRAGMA_FORMAT_NONLITERAL_IGNORED
JRT_LEAF(void, JVMCIRuntime::vm_message(jboolean vmError, jlong format, jlong v1, jlong v2, jlong v3))
  ResourceMark rm;
  const char *buf = (const char*) (address) format;
  if (vmError) {
    if (buf != NULL) {
      fatal(buf, v1, v2, v3);
    } else {
      fatal("<anonymous error>");
    }
  } else if (buf != NULL) {
    tty->print(buf, v1, v2, v3);
  } else {
    assert(v2 == 0, "v2 != 0");
    assert(v3 == 0, "v3 != 0");
    decipher(v1, false);
  }
JRT_END
PRAGMA_DIAG_POP

JRT_LEAF(void, JVMCIRuntime::log_primitive(JavaThread* thread, jchar typeChar, jlong value, jboolean newline))
  union {
      jlong l;
      jdouble d;
      jfloat f;
  } uu;
  uu.l = value;
  switch (typeChar) {
    case 'Z': tty->print(value == 0 ? "false" : "true"); break;
    case 'B': tty->print("%d", (jbyte) value); break;
    case 'C': tty->print("%c", (jchar) value); break;
    case 'S': tty->print("%d", (jshort) value); break;
    case 'I': tty->print("%d", (jint) value); break;
    case 'F': tty->print("%f", uu.f); break;
    case 'J': tty->print(JLONG_FORMAT, value); break;
    case 'D': tty->print("%lf", uu.d); break;
    default: assert(false, "unknown typeChar"); break;
  }
  if (newline) {
    tty->cr();
  }
JRT_END

JRT_ENTRY(jint, JVMCIRuntime::identity_hash_code(JavaThread* thread, oopDesc* obj))
  return (jint) obj->identity_hash();
JRT_END

JRT_ENTRY(jboolean, JVMCIRuntime::thread_is_interrupted(JavaThread* thread, oopDesc* receiver, jboolean clear_interrupted))
  // Ensure that the C++ Thread and OSThread structures aren't freed before we operate.
  // This locking requires thread_in_vm which is why this method cannot be JRT_LEAF.
  Handle receiverHandle(thread, receiver);
  MutexLockerEx ml(thread->threadObj() == (void*)receiver ? NULL : Threads_lock);
  JavaThread* receiverThread = java_lang_Thread::thread(receiverHandle());
  if (receiverThread == NULL) {
    // The other thread may exit during this process, which is ok so return false.
    return JNI_FALSE;
  } else {
    return (jint) Thread::is_interrupted(receiverThread, clear_interrupted != 0);
  }
JRT_END

JRT_ENTRY(jint, JVMCIRuntime::test_deoptimize_call_int(JavaThread* thread, int value))
  deopt_caller();
  return value;
JRT_END

void JVMCIRuntime::force_initialization(TRAPS) {
  JVMCIRuntime::initialize_well_known_classes(CHECK);

  ResourceMark rm;
  TempNewSymbol getCompiler = SymbolTable::new_symbol("getCompiler", CHECK);
  TempNewSymbol sig = SymbolTable::new_symbol("()Ljdk/vm/ci/runtime/JVMCICompiler;", CHECK);
  Handle jvmciRuntime = JVMCIRuntime::get_HotSpotJVMCIRuntime(CHECK);
  JavaValue result(T_OBJECT);
  JavaCalls::call_virtual(&result, jvmciRuntime, HotSpotJVMCIRuntime::klass(), getCompiler, sig, CHECK);
}

// private static JVMCIRuntime JVMCI.initializeRuntime()
JVM_ENTRY(jobject, JVM_GetJVMCIRuntime(JNIEnv *env, jclass c))
  if (!EnableJVMCI) {
    THROW_MSG_NULL(vmSymbols::java_lang_InternalError(), "JVMCI is not enabled")
  }
  JVMCIRuntime::initialize_HotSpotJVMCIRuntime(CHECK_NULL);
  jobject ret = JVMCIRuntime::get_HotSpotJVMCIRuntime_jobject(CHECK_NULL);
  return ret;
JVM_END

Handle JVMCIRuntime::callStatic(const char* className, const char* methodName, const char* signature, JavaCallArguments* args, TRAPS) {
  guarantee(!_HotSpotJVMCIRuntime_initialized, "cannot reinitialize HotSpotJVMCIRuntime");

  TempNewSymbol name = SymbolTable::new_symbol(className, CHECK_(Handle()));
  KlassHandle klass = SystemDictionary::resolve_or_fail(name, true, CHECK_(Handle()));
  TempNewSymbol runtime = SymbolTable::new_symbol(methodName, CHECK_(Handle()));
  TempNewSymbol sig = SymbolTable::new_symbol(signature, CHECK_(Handle()));
  JavaValue result(T_OBJECT);
  if (args == NULL) {
    JavaCalls::call_static(&result, klass, runtime, sig, CHECK_(Handle()));
  } else {
    JavaCalls::call_static(&result, klass, runtime, sig, args, CHECK_(Handle()));
  }
  return Handle((oop)result.get_jobject());
}

void JVMCIRuntime::initialize_HotSpotJVMCIRuntime(TRAPS) {
  if (JNIHandles::resolve(_HotSpotJVMCIRuntime_instance) == NULL) {
    ResourceMark rm;
#ifdef ASSERT
    // This should only be called in the context of the JVMCI class being initialized
    TempNewSymbol name = SymbolTable::new_symbol("jdk/vm/ci/runtime/JVMCI", CHECK);
    Klass* k = SystemDictionary::resolve_or_null(name, CHECK);
    instanceKlassHandle klass = InstanceKlass::cast(k);
    assert(klass->is_being_initialized() && klass->is_reentrant_initialization(THREAD),
           "HotSpotJVMCIRuntime initialization should only be triggered through JVMCI initialization");
#endif

    Handle result = callStatic("jdk/vm/ci/hotspot/HotSpotJVMCIRuntime",
                               "runtime",
                               "()Ljdk/vm/ci/hotspot/HotSpotJVMCIRuntime;", NULL, CHECK);
    objArrayOop trivial_prefixes = HotSpotJVMCIRuntime::trivialPrefixes(result);
    if (trivial_prefixes != NULL) {
      char** prefixes = NEW_C_HEAP_ARRAY(char*, trivial_prefixes->length(), mtCompiler);
      for (int i = 0; i < trivial_prefixes->length(); i++) {
        oop str = trivial_prefixes->obj_at(i);
        if (str == NULL) {
          THROW(vmSymbols::java_lang_NullPointerException());
        } else {
          prefixes[i] = strdup(java_lang_String::as_utf8_string(str));
        }
      }
      _trivial_prefixes = prefixes;
      _trivial_prefixes_count = trivial_prefixes->length();
    }
    int adjustment = HotSpotJVMCIRuntime::compilationLevelAdjustment(result);
    assert(adjustment >= JVMCIRuntime::none &&
           adjustment <= JVMCIRuntime::by_full_signature,
           "compilation level adjustment out of bounds");
    _comp_level_adjustment = (CompLevelAdjustment) adjustment;
    _HotSpotJVMCIRuntime_initialized = true;
    _HotSpotJVMCIRuntime_instance = JNIHandles::make_global(result());
  }
}

void JVMCIRuntime::initialize_JVMCI(TRAPS) {
  if (JNIHandles::resolve(_HotSpotJVMCIRuntime_instance) == NULL) {
    callStatic("jdk/vm/ci/runtime/JVMCI",
               "getRuntime",
               "()Ljdk/vm/ci/runtime/JVMCIRuntime;", NULL, CHECK);
  }
  assert(_HotSpotJVMCIRuntime_initialized == true, "what?");
}

void JVMCIRuntime::initialize_well_known_classes(TRAPS) {
  if (JVMCIRuntime::_well_known_classes_initialized == false) {
    SystemDictionary::WKID scan = SystemDictionary::FIRST_JVMCI_WKID;
    SystemDictionary::initialize_wk_klasses_through(SystemDictionary::LAST_JVMCI_WKID, scan, CHECK);
    JVMCIJavaClasses::compute_offsets(CHECK);
    JVMCIRuntime::_well_known_classes_initialized = true;
  }
}

void JVMCIRuntime::metadata_do(void f(Metadata*)) {
  // For simplicity, the existence of HotSpotJVMCIMetaAccessContext in
  // the SystemDictionary well known classes should ensure the other
  // classes have already been loaded, so make sure their order in the
  // table enforces that.
  assert(SystemDictionary::WK_KLASS_ENUM_NAME(jdk_vm_ci_hotspot_HotSpotResolvedJavaMethodImpl) <
         SystemDictionary::WK_KLASS_ENUM_NAME(jdk_vm_ci_hotspot_HotSpotJVMCIMetaAccessContext), "must be loaded earlier");
  assert(SystemDictionary::WK_KLASS_ENUM_NAME(jdk_vm_ci_hotspot_HotSpotConstantPool) <
         SystemDictionary::WK_KLASS_ENUM_NAME(jdk_vm_ci_hotspot_HotSpotJVMCIMetaAccessContext), "must be loaded earlier");
  assert(SystemDictionary::WK_KLASS_ENUM_NAME(jdk_vm_ci_hotspot_HotSpotResolvedObjectTypeImpl) <
         SystemDictionary::WK_KLASS_ENUM_NAME(jdk_vm_ci_hotspot_HotSpotJVMCIMetaAccessContext), "must be loaded earlier");

  if (HotSpotJVMCIMetaAccessContext::klass() == NULL ||
      !HotSpotJVMCIMetaAccessContext::klass()->is_linked()) {
    // Nothing could be registered yet
    return;
  }

  // WeakReference<HotSpotJVMCIMetaAccessContext>[]
  objArrayOop allContexts = HotSpotJVMCIMetaAccessContext::allContexts();
  if (allContexts == NULL) {
    return;
  }

  // These must be loaded at this point but the linking state doesn't matter.
  assert(SystemDictionary::HotSpotResolvedJavaMethodImpl_klass() != NULL, "must be loaded");
  assert(SystemDictionary::HotSpotConstantPool_klass() != NULL, "must be loaded");
  assert(SystemDictionary::HotSpotResolvedObjectTypeImpl_klass() != NULL, "must be loaded");

  for (int i = 0; i < allContexts->length(); i++) {
    oop ref = allContexts->obj_at(i);
    if (ref != NULL) {
      oop referent = java_lang_ref_Reference::referent(ref);
      if (referent != NULL) {
        // Chunked Object[] with last element pointing to next chunk
        objArrayOop metadataRoots = HotSpotJVMCIMetaAccessContext::metadataRoots(referent);
        while (metadataRoots != NULL) {
          for (int typeIndex = 0; typeIndex < metadataRoots->length() - 1; typeIndex++) {
            oop reference = metadataRoots->obj_at(typeIndex);
            if (reference == NULL) {
              continue;
            }
            oop metadataRoot = java_lang_ref_Reference::referent(reference);
            if (metadataRoot == NULL) {
              continue;
            }
            if (metadataRoot->is_a(SystemDictionary::HotSpotResolvedJavaMethodImpl_klass())) {
              Method* method = CompilerToVM::asMethod(metadataRoot);
              f(method);
            } else if (metadataRoot->is_a(SystemDictionary::HotSpotConstantPool_klass())) {
              ConstantPool* constantPool = CompilerToVM::asConstantPool(metadataRoot);
              f(constantPool);
            } else if (metadataRoot->is_a(SystemDictionary::HotSpotResolvedObjectTypeImpl_klass())) {
              Klass* klass = CompilerToVM::asKlass(metadataRoot);
              f(klass);
            } else {
              metadataRoot->print();
              ShouldNotReachHere();
            }
          }
          metadataRoots = (objArrayOop)metadataRoots->obj_at(metadataRoots->length() - 1);
          assert(metadataRoots == NULL || metadataRoots->is_objArray(), "wrong type");
        }
      }
    }
  }
}

// private static void CompilerToVM.registerNatives()
JVM_ENTRY(void, JVM_RegisterJVMCINatives(JNIEnv *env, jclass c2vmClass))
  if (!EnableJVMCI) {
    THROW_MSG(vmSymbols::java_lang_InternalError(), "JVMCI is not enabled");
  }

#ifdef _LP64
#ifndef TARGET_ARCH_sparc
  uintptr_t heap_end = (uintptr_t) Universe::heap()->reserved_region().end();
  uintptr_t allocation_end = heap_end + ((uintptr_t)16) * 1024 * 1024 * 1024;
  guarantee(heap_end < allocation_end, "heap end too close to end of address space (might lead to erroneous TLAB allocations)");
#endif // TARGET_ARCH_sparc
#else
  fatal("check TLAB allocation code for address space conflicts");
#endif

  JVMCIRuntime::initialize_well_known_classes(CHECK);

  {
    ThreadToNativeFromVM trans(thread);
    env->RegisterNatives(c2vmClass, CompilerToVM::methods, CompilerToVM::methods_count());
  }
JVM_END

#define CHECK_WARN_ABORT_(message) THREAD); \
  if (HAS_PENDING_EXCEPTION) { \
    warning(message); \
    char buf[512]; \
    jio_snprintf(buf, 512, "Uncaught exception at %s:%d", __FILE__, __LINE__); \
    JVMCIRuntime::abort_on_pending_exception(PENDING_EXCEPTION, buf); \
    return; \
  } \
  (void)(0

void JVMCIRuntime::shutdown(TRAPS) {
  if (_HotSpotJVMCIRuntime_instance != NULL) {
    _shutdown_called = true;
    HandleMark hm(THREAD);
    Handle receiver = get_HotSpotJVMCIRuntime(CHECK);
    JavaValue result(T_VOID);
    JavaCallArguments args;
    args.push_oop(receiver);
    JavaCalls::call_special(&result, receiver->klass(), vmSymbols::shutdown_method_name(), vmSymbols::void_method_signature(), &args, CHECK);
  }
}

CompLevel JVMCIRuntime::adjust_comp_level_inner(methodHandle method, bool is_osr, CompLevel level, JavaThread* thread) {
  JVMCICompiler* compiler = JVMCICompiler::instance(thread);
  if (compiler != NULL && compiler->is_bootstrapping()) {
    return level;
  }
  if (!is_HotSpotJVMCIRuntime_initialized() || !_comp_level_adjustment) {
    // JVMCI cannot participate in compilation scheduling until
    // JVMCI is initialized and indicates it wants to participate.
    return level;
  }

#define CHECK_RETURN THREAD); \
if (HAS_PENDING_EXCEPTION) { \
  Handle exception(THREAD, PENDING_EXCEPTION); \
  CLEAR_PENDING_EXCEPTION; \
\
  java_lang_Throwable::java_printStackTrace(exception, THREAD); \
  if (HAS_PENDING_EXCEPTION) { \
    CLEAR_PENDING_EXCEPTION; \
  } \
  return level; \
} \
(void)(0


  Thread* THREAD = thread;
  HandleMark hm;
  Handle receiver = JVMCIRuntime::get_HotSpotJVMCIRuntime(CHECK_RETURN);
  Handle name;
  Handle sig;
  if (_comp_level_adjustment == JVMCIRuntime::by_full_signature) {
    name = java_lang_String::create_from_symbol(method->name(), CHECK_RETURN);
    sig = java_lang_String::create_from_symbol(method->signature(), CHECK_RETURN);
  } else {
    name = Handle();
    sig = Handle();
  }

  JavaValue result(T_INT);
  JavaCallArguments args;
  args.push_oop(receiver);
  args.push_oop(method->method_holder()->java_mirror());
  args.push_oop(name());
  args.push_oop(sig());
  args.push_int(is_osr);
  args.push_int(level);
  JavaCalls::call_special(&result, receiver->klass(), vmSymbols::adjustCompilationLevel_name(),
                          vmSymbols::adjustCompilationLevel_signature(), &args, CHECK_RETURN);

  int comp_level = result.get_jint();
  if (comp_level < CompLevel_none || comp_level > CompLevel_full_optimization) {
    assert(false, "compilation level out of bounds");
    return level;
  }
  return (CompLevel) comp_level;
#undef CHECK_RETURN
}

void JVMCIRuntime::bootstrap_finished(TRAPS) {
  HandleMark hm(THREAD);
  Handle receiver = get_HotSpotJVMCIRuntime(CHECK);
  JavaValue result(T_VOID);
  JavaCallArguments args;
  args.push_oop(receiver);
  JavaCalls::call_special(&result, receiver->klass(), vmSymbols::bootstrapFinished_method_name(), vmSymbols::void_method_signature(), &args, CHECK);
}

bool JVMCIRuntime::treat_as_trivial(Method* method) {
  if (_HotSpotJVMCIRuntime_initialized) {
    for (int i = 0; i < _trivial_prefixes_count; i++) {
      if (method->method_holder()->name()->starts_with(_trivial_prefixes[i])) {
        return true;
      }
    }
  }
  return false;
}
