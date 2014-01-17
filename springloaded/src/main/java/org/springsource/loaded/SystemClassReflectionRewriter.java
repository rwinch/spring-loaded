/*
 * Copyright 2010-2012 VMware and contributors
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
package org.springsource.loaded;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.objectweb.asm.ClassAdapter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodAdapter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * This is a special rewriter that should be used on system classes that are using reflection. These classes are loader above the
 * agent code and so cannot use the agent code directly (they can't see the classes). In these situations we will do some rewriting
 * that will only use other system types. How can that work? Well the affected types are modified to expose a static field (per
 * reflective API used), these static fields are set by springloaded during later startup and then are available for access from the
 * rewritten system class code.
 * <p>
 * There is a null check in the injected method for cases where everything runs even sooner than can be plugged by SpringLoaded.
 * <p>
 * The following are implemented so far:
 * 
 * <p>
 * Due to ReflectionNavigator:
 * <ul>
 * <li>getDeclaredFields
 * <li>getDeclaredField
 * <li>getField
 * <li>getModifiers
 * <li>getDeclaredConstructor
 * <li>getDeclaredMethods
 * <li>getDeclaredMethod</li>
 * <p>
 * Due to ProxyGenerator
 * <ul>
 * <li>getMethods
 * </ul>
 * 
 * <p>
 * This class modifiers the calls to the reflective APIs, adds the fields and helper methods. The wiring of the SpringLoaded
 * reflectiveinterceptor into types affected by this rewriter is currently done in SpringLoadedPreProcessor.
 * 
 * @author Andy Clement
 * @since 0.7.3
 */
public class SystemClassReflectionRewriter {

	private static Logger log = Logger.getLogger(SystemClassReflectionRewriter.class.getName());

	public static RewriteResult rewrite(String slashedClassName, byte[] bytes) {
		if (GlobalConfiguration.isRuntimeLogging && log.isLoggable(Level.INFO)) {
			log.info("SystemClassReflectionRewriter running for " + slashedClassName);
		}
		ClassReader fileReader = new ClassReader(bytes);
		RewriteClassAdaptor classAdaptor = new RewriteClassAdaptor();
		// TODO always skip frames? or just for javassist things?
		fileReader.accept(classAdaptor, ClassReader.SKIP_FRAMES);
		return new RewriteResult(classAdaptor.getBytes(), classAdaptor.getBits());
	}

	public static class RewriteResult implements Constants {

		public final byte[] bytes;
		// These bits describe which kinds of reflective things were done in the 
		// type - and so which fields (of the __sl variety) need filling in.  For example,
		// if the JLC_GETDECLAREDFIELDS bit is set, the field __sljlcgdfs must be set
		public final int bits;

		public RewriteResult(byte[] bytes, int bits) {
			this.bytes = bytes;
			this.bits = bits;
		}

		public String summarize() {
			StringBuilder s = new StringBuilder();
			s.append((bits & JLC_GETDECLAREDCONSTRUCTOR) != 0 ? "getDeclaredConstructor()" : "");
			s.append((bits & JLC_GETCONSTRUCTOR) != 0 ? "getConstructor()" : "");
			s.append((bits & JLC_GETMODIFIERS) != 0 ? "getModifiers()" : "");
			s.append((bits & JLC_GETDECLAREDFIELDS) != 0 ? "getDeclaredFields() " : "");
			s.append((bits & JLC_GETDECLAREDFIELD) != 0 ? "getDeclaredField() " : "");
			s.append((bits & JLC_GETFIELD) != 0 ? "getField() " : "");
			s.append((bits & JLC_GETDECLAREDMETHODS) != 0 ? "getDeclaredMethods() " : "");
			s.append((bits & JLC_GETDECLAREDMETHOD) != 0 ? "getDeclaredMethod() " : "");
			s.append((bits & JLC_GETMETHOD) != 0 ? "getMethod() " : "");
			s.append((bits & JLC_GETMETHODS) != 0 ? "getMethods() " : "");
			return s.toString().trim();
		}
	}

	static class RewriteClassAdaptor extends ClassAdapter implements Constants {

		private ClassWriter cw;
		int bits = 0x0000;
		private String classname;

		//		enum SpecialRewrite { NotSpecial, java_io_ObjectStreamClass_2 };
		//		private SpecialRewrite special = SpecialRewrite.NotSpecial;

		// TODO [perf] lookup like this really the fastest way?
		private static boolean isInterceptable(String owner, String methodName) {
			String s = new StringBuilder(owner).append(".").append(methodName).toString();
			return MethodInvokerRewriter.RewriteClassAdaptor.intercepted.contains(s);
		}

		public RewriteClassAdaptor() {
			// TODO should it also compute frames?
			super(new ClassWriter(ClassWriter.COMPUTE_MAXS));
			cw = (ClassWriter) cv;
		}

		public byte[] getBytes() {
			byte[] bytes = cw.toByteArray();
			return bytes;
		}

		public int getBits() {
			return bits;
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
			super.visit(version, access, name, signature, superName, interfaces);
			this.classname = name;
			//			if (classname.equals("java/io/ObjectStreamClass$2")) {
			//				special = SpecialRewrite.java_io_ObjectStreamClass_2;
			//			}
		}

		@Override
		public MethodVisitor visitMethod(int flags, String name, String descriptor, String signature, String[] exceptions) {
			MethodVisitor mv = super.visitMethod(flags, name, descriptor, signature, exceptions);
			return new RewritingMethodAdapter(mv);
		}

		@Override
		public void visitEnd() {
			addExtraMethodsAndFields();
			super.visitEnd();
		}

		private void addExtraMethodsAndFields() {
			if ((bits & JLC_GETDECLAREDFIELDS) != 0) {
				SystemClassReflectionGenerator.generateJLCGDFS(cw, classname);
			}
			if ((bits & JLC_GETDECLAREDFIELD) != 0) {
				SystemClassReflectionGenerator.generateJLC(cw, classname, "getDeclaredField");
			}
			if ((bits & JLC_GETFIELD) != 0) {
				SystemClassReflectionGenerator.generateJLC(cw, classname, "getField");
			}
			if ((bits & JLC_GETDECLAREDMETHODS) != 0) {
				SystemClassReflectionGenerator.generateJLCGetXXXMethods(cw, classname, "getDeclaredMethods");
			}
			if ((bits & JLC_GETDECLAREDMETHOD) != 0) {
				SystemClassReflectionGenerator.generateJLCMethod(cw, classname, "getDeclaredMethod");
			}
			if ((bits & JLC_GETMETHOD) != 0) {
				SystemClassReflectionGenerator.generateJLCMethod(cw, classname, "getMethod");
			}
			if ((bits & JLC_GETMODIFIERS) != 0) {
				SystemClassReflectionGenerator.generateJLCGMODS(cw, classname);
			}
			if ((bits & JLC_GETDECLAREDCONSTRUCTOR) != 0) {
				SystemClassReflectionGenerator.generateJLCGDC(cw, classname);
			}
			if ((bits & JLC_GETMETHODS) != 0) {
				SystemClassReflectionGenerator.generateJLCGetXXXMethods(cw, classname, "getMethods");
			}
			if ((bits & JLC_GETCONSTRUCTOR) != 0) {
				SystemClassReflectionGenerator.generateJLCGC(cw, classname);
			}
		}

		class RewritingMethodAdapter extends MethodAdapter implements Opcodes, Constants {

			public RewritingMethodAdapter(MethodVisitor mv) {
				super(mv);
			}

			/**
			 * The big method for intercepting reflection. It is passed what the original code is trying to do (which method it is
			 * calling) and decides:
			 * <ul>
			 * <li>whether to rewrite it
			 * <li>what method should be called instead
			 * </ul>
			 * 
			 * @return true if the call was modified/intercepted
			 */
			private boolean interceptReflection(String owner, String name, String desc) {
				if (isInterceptable(owner, name)) {
					return callReflectiveInterceptor(owner, name, desc, mv);
				}
				return false;
			}

			int unitializedObjectsCount = 0;

			@Override
			public void visitTypeInsn(final int opcode, final String type) {
				if (opcode == NEW) {
					unitializedObjectsCount++;
				}
				super.visitTypeInsn(opcode, type);
			}

			@Override
			public void visitMethodInsn(final int opcode, final String owner, final String name, final String desc) {
				if (!GlobalConfiguration.interceptReflection || rewriteReflectiveCall(opcode, owner, name, desc)) {
					return;
				}
				if (opcode == INVOKESPECIAL) {
					unitializedObjectsCount--;
				}
				//				if (special!=SpecialRewrite.NotSpecial) {
				//					// Special cases:
				//					if (special==SpecialRewrite.java_io_ObjectStreamClass_2) {
				//						// The class java.io.ObjectStreamClass is loaded too early for us to modify and yet it uses reflection.
				//						// That means we need to modify calls to that class instead.
				//						
				//						// The anonymous inner type $2 makes a call to 
				//						//   66:  invokestatic    #10; //Method java/io/ObjectStreamClass.access$700:(Ljava/lang/Class;)Ljava/lang/Long;
				//						// which is the accessor method for the private method 'Long getDeclaredSUID()' in ObjectStreamClass.  Redirect this
				//						// method to a helper that can retrieve the suid and be rewritten.
				//						// TODO skip descriptor check, surely name is enough?
				////						if (owner.equals("java/io/ObjectStreamClass") && name.equals("access$700") && desc.equals("(Ljava/lang/Class;)Ljava/lang/Long;")) {
				////							// 1. retrieve the serialVersionUID field
				////							mv.visitLdcInsn("serialVersionUID");
				////							bits|=JLC_GETDECLAREDFIELD;
				////							mv.visitMethodInsn(INVOKESTATIC,classname, jlcgdf, jlcgdfDescriptor);
				////							
				////							
				////						}
				//////						 private static Long getDeclaredSUID(Class cl) {
				//////								try {
				//////								    Field f = cl.getDeclaredField("serialVersionUID");
				//////								    int mask = Modifier.STATIC | Modifier.FINAL;
				//////								    if ((f.getModifiers() & mask) == mask) {
				//////									f.setAccessible(true);
				//////									return Long.valueOf(f.getLong(null));
				//////								    }
				//////								} catch (Exception ex) {
				//////								}
				//////								return null;
				//////							    }
				//					}
				//				}
				super.visitMethodInsn(opcode, owner, name, desc);
			}

			/**
			 * Determine if a method call is a reflective call and an attempt should be made to rewrite it.
			 * 
			 * @return true if the call was rewritten
			 */
			private boolean rewriteReflectiveCall(int opcode, String owner, String name, String desc) {
				if (owner.length() > 10 && owner.charAt(8) == 'g'
						&& (owner.startsWith("java/lang/reflect/") || owner.equals("java/lang/Class"))) {
					boolean rewritten = interceptReflection(owner, name, desc);
					if (rewritten) {
						return true;
					}
				}
				return false;
			}

			private boolean callReflectiveInterceptor(String owner, String name, String desc, MethodVisitor mv) {
				if (owner.equals("java/lang/Class")) {
					if (name.equals("getDeclaredFields")) {
						// stack on arrival: <Class instance>
						bits |= JLC_GETDECLAREDFIELDS;
						mv.visitMethodInsn(INVOKESTATIC, classname, jlcgdfs, jlcgdfsDescriptor);
						return true;
					} else if (name.equals("getDeclaredField")) {
						// stack on arrival: <Class instance> <String fieldname>
						bits |= JLC_GETDECLAREDFIELD;
						mv.visitMethodInsn(INVOKESTATIC, classname, jlcgdf, jlcgdfDescriptor);
						return true;
					} else if (name.equals("getField")) {
						// stack on arrival: <Class instance> <String fieldname>
						bits |= JLC_GETFIELD;
						mv.visitMethodInsn(INVOKESTATIC, classname, jlcgf, jlcgfDescriptor);
						return true;
					} else if (name.equals("getDeclaredMethods")) {
						// stack on arrival: <Class instance>
						bits |= JLC_GETDECLAREDMETHODS;
						mv.visitMethodInsn(INVOKESTATIC, classname, jlcgdms, jlcgdmsDescriptor);
						return true;
					} else if (name.equals("getDeclaredMethod")) {
						// stack on arrival: <Class instance> <String methodname> <Class[] paramTypes>
						bits |= JLC_GETDECLAREDMETHOD;
						mv.visitMethodInsn(INVOKESTATIC, classname, jlcgdm, jlcgdmDescriptor);
						return true;
					} else if (name.equals("getMethod")) {
						// stack on arrival: <Class instance> <String methodname> <Class[] paramTypes>
						bits |= JLC_GETMETHOD;
						mv.visitMethodInsn(INVOKESTATIC, classname, jlcgm, jlcgmDescriptor);
						return true;
					} else if (name.equals("getDeclaredConstructor")) {
						// stack on arrival: <Class instance> <Class[] paramTypes>
						bits |= JLC_GETDECLAREDCONSTRUCTOR;
						mv.visitMethodInsn(INVOKESTATIC, classname, jlcgdc, jlcgdcDescriptor);
						return true;
					} else if (name.equals("getConstructor")) {
						// stack on arrival: <Class instance> <Class[] paramTypes>
						bits |= JLC_GETCONSTRUCTOR;
						mv.visitMethodInsn(INVOKESTATIC, classname, jlcgc, jlcgcDescriptor);
						return true;
					} else if (name.equals("getModifiers")) {
						// stack on arrival: <Class instance>
						bits |= JLC_GETMODIFIERS;
						mv.visitMethodInsn(INVOKESTATIC, classname, jlcgmods, jlcgmodsDescriptor);
						return true;
					} else if (name.equals("getMethods")) {
						// stack on arrival: <class instance>
						bits |= JLC_GETMETHODS;
						mv.visitMethodInsn(INVOKESTATIC, classname, jlcgms, jlcgmsDescriptor);
						return true;
					} else if (name.equals("newInstance")) {
						// TODO determine if this actually needs rewriting? Just catching in this if clause to avoid the message
						return false;
					}
				} else if (owner.equals("java/lang/reflect/Constructor")) {
					if (name.equals("newInstance")) {
						// catching to avoid message
						// seen: in Proxy Constructor.newInstance() is used on the newly created proxy class - we don't need to intercept that
						return false;
					}
				}
				System.err.println("!!! SystemClassReflectionRewriter: nyi for " + owner + "." + name);
				return false;
				//throw new IllegalStateException("nyi for " + owner + "." + name);
			}
		}
	}
}

/**
 * This helper class will generate the fields/methods in the system classes that are being rewritten.
 */
class SystemClassReflectionGenerator implements Constants {

	//	public static Method __sljlcgdfs;
	//	@SuppressWarnings("unused")
	//	private static Field[] __sljlcgdfs(Class<?> clazz) {
	//		if (__sljlcgdfs == null) {
	//			return clazz.getDeclaredFields();
	//		}
	//		try {
	//			return (Field[]) __sljlcgdfs.invoke(null, clazz);
	//		} catch (Exception e) {
	//			return null;
	//		}
	//	}

	//	public static Method __sljlcgdms;
	//	@SuppressWarnings("unused")
	//	private static Method[] __sljlcgdms(Class<?> clazz) {
	//		if (__sljlcgdms == null) {
	//			return clazz.getDeclaredMethods();
	//		}
	//		try {
	//			return (Method[]) __sljlcgdms.invoke(null, clazz);
	//		} catch (Exception e) {
	//			return null;
	//		}
	//	}

	//	public static Method __sljlcgdf;
	//	@SuppressWarnings("unused")
	//	private static Field __sljlcgdf(Class<?> clazz, String fieldname) throws NoSuchFieldException {
	//		if (__sljlcgdf == null) {
	//			return clazz.getDeclaredField(fieldname);
	//		}
	//		try {
	//			return (Field) __sljlcgdf.invoke(null, clazz, fieldname);
	//		} catch (InvocationTargetException ite) {
	//			if (ite.getCause() instanceof NoSuchFieldException) {
	//				throw (NoSuchFieldException) ite.getCause();
	//			}
	//		} catch (Exception e) {
	//		}
	//		return null;
	//	}

	//	public static Method __sljlcgdm;
	//
	//	@SuppressWarnings("unused")
	//	private static Method __sljlcgdm(Class<?> clazz, String methodname, Class... parameterTypes) throws NoSuchMethodException {
	//		if (__sljlcgdm == null) {
	//			return clazz.getDeclaredMethod(methodname, parameterTypes);
	//		}
	//		try {
	//			//			if (parameterTypes == null) {
	//			return (Method) __sljlcgdm.invoke(null, clazz, methodname, parameterTypes);
	//			//			} else {
	//			//				Object[] params = new Object[2 + parameterTypes.length];
	//			//				System.arraycopy(parameterTypes, 0, params, 2, parameterTypes.length);
	//			//				params[0] = clazz;
	//			//				params[1] = methodname;
	//			//				return (Method) __sljlcgdm.invoke(null, clazz, methodname, parameterTypes);
	//			//			}
	//		} catch (InvocationTargetException ite) {
	//			ite.printStackTrace();
	//			if (ite.getCause() instanceof NoSuchMethodException) {
	//				throw (NoSuchMethodException) ite.getCause();
	//			}
	//		} catch (Exception e) {
	//			e.printStackTrace();
	//		}
	//		return null;
	//	}

	//	public static Method __sljlcgdc;
	//
	//	@SuppressWarnings("unused")
	//	private static Constructor __sljlcgdc(Class<?> clazz, Class... parameterTypes) throws NoSuchMethodException {
	//		if (__sljlcgdc == null) {
	//			return clazz.getDeclaredConstructor(parameterTypes);
	//		}
	//		try {
	//			return (Constructor) __sljlcgdc.invoke(null, clazz, parameterTypes);
	//		} catch (InvocationTargetException ite) {
	//			ite.printStackTrace();
	//			if (ite.getCause() instanceof NoSuchMethodException) {
	//				throw (NoSuchMethodException) ite.getCause();
	//			}
	//		} catch (Exception e) {
	//			e.printStackTrace();
	//		}
	//		return null;
	//	}

	//	public static Method __sljlcgmods;
	//
	//	@SuppressWarnings("unused")
	//	private static int __sljlcgmods(Class<?> clazz) {
	//		if (__sljlcgmods == null) {
	//			return clazz.getModifiers();
	//		}
	//		try {
	//			return (Integer) __sljlcgmods.invoke(null, clazz);
	//		} catch (Exception e) {
	//			e.printStackTrace();
	//			return 0;
	//		}
	//	}

	public static void generateJLCGMODS(ClassWriter cw, String classname) {
		FieldVisitor fv = cw.visitField(ACC_PUBLIC + ACC_STATIC, "__sljlcgmods", "Ljava/lang/reflect/Method;", null, null);
		fv.visitEnd();

		MethodVisitor mv = cw.visitMethod(ACC_PRIVATE + ACC_STATIC, "__sljlcgmods", "(Ljava/lang/Class;)I",
				"(Ljava/lang/Class<*>;)I", null);
		mv.visitCode();
		Label l0 = new Label();
		Label l1 = new Label();
		Label l2 = new Label();
		mv.visitTryCatchBlock(l0, l1, l2, "java/lang/Exception");
		Label l3 = new Label();
		mv.visitLabel(l3);
		mv.visitFieldInsn(GETSTATIC, classname, "__sljlcgmods", "Ljava/lang/reflect/Method;");
		mv.visitJumpInsn(IFNONNULL, l0);
		Label l4 = new Label();
		mv.visitLabel(l4);
		mv.visitVarInsn(ALOAD, 0);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getModifiers", "()I");
		mv.visitInsn(IRETURN);
		mv.visitLabel(l0);
		mv.visitFieldInsn(GETSTATIC, classname, "__sljlcgmods", "Ljava/lang/reflect/Method;");
		mv.visitInsn(ACONST_NULL);
		mv.visitInsn(ICONST_1);
		mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
		mv.visitInsn(DUP);
		mv.visitInsn(ICONST_0);
		mv.visitVarInsn(ALOAD, 0);
		mv.visitInsn(AASTORE);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke",
				"(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
		mv.visitTypeInsn(CHECKCAST, "java/lang/Integer");
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I");
		mv.visitLabel(l1);
		mv.visitInsn(IRETURN);
		mv.visitLabel(l2);
		mv.visitVarInsn(ASTORE, 1);
		Label l5 = new Label();
		mv.visitLabel(l5);
		mv.visitVarInsn(ALOAD, 1);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Exception", "printStackTrace", "()V");
		Label l6 = new Label();
		mv.visitLabel(l6);
		mv.visitInsn(ICONST_0);
		mv.visitInsn(IRETURN);
		Label l7 = new Label();
		mv.visitLabel(l7);
		//		mv.visitLocalVariable("clazz", "Ljava/lang/Class;", "Ljava/lang/Class<*>;", l3, l7, 0);
		//		mv.visitLocalVariable("e", "Ljava/lang/Exception;", null, l5, l7, 1);
		mv.visitMaxs(6, 2);
		mv.visitEnd();

	}

	public static void generateJLCGDC(ClassWriter cw, String classname) {
		FieldVisitor fv = cw.visitField(ACC_PUBLIC + ACC_STATIC, "__sljlcgdc", "Ljava/lang/reflect/Method;", null, null);
		fv.visitEnd();

		MethodVisitor mv = cw.visitMethod(ACC_PRIVATE + ACC_STATIC + ACC_VARARGS, "__sljlcgdc",
				"(Ljava/lang/Class;[Ljava/lang/Class;)Ljava/lang/reflect/Constructor;", null,
				new String[] { "java/lang/NoSuchMethodException" });
		mv.visitCode();
		Label l0 = new Label();
		Label l1 = new Label();
		Label l2 = new Label();
		mv.visitTryCatchBlock(l0, l1, l2, "java/lang/reflect/InvocationTargetException");
		Label l3 = new Label();
		mv.visitTryCatchBlock(l0, l1, l3, "java/lang/Exception");
		Label l4 = new Label();
		mv.visitLabel(l4);
		mv.visitFieldInsn(GETSTATIC, classname, "__sljlcgdc", "Ljava/lang/reflect/Method;");
		mv.visitJumpInsn(IFNONNULL, l0);
		Label l5 = new Label();
		mv.visitLabel(l5);
		mv.visitVarInsn(ALOAD, 0);
		mv.visitVarInsn(ALOAD, 1);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredConstructor",
				"([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;");
		mv.visitInsn(ARETURN);
		mv.visitLabel(l0);
		mv.visitFieldInsn(GETSTATIC, classname, "__sljlcgdc", "Ljava/lang/reflect/Method;");
		mv.visitInsn(ACONST_NULL);
		mv.visitInsn(ICONST_2);
		mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
		mv.visitInsn(DUP);
		mv.visitInsn(ICONST_0);
		mv.visitVarInsn(ALOAD, 0);
		mv.visitInsn(AASTORE);
		mv.visitInsn(DUP);
		mv.visitInsn(ICONST_1);
		mv.visitVarInsn(ALOAD, 1);
		mv.visitInsn(AASTORE);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke",
				"(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
		mv.visitTypeInsn(CHECKCAST, "java/lang/reflect/Constructor");
		mv.visitLabel(l1);
		mv.visitInsn(ARETURN);
		mv.visitLabel(l2);
		mv.visitVarInsn(ASTORE, 2);
		Label l6 = new Label();
		mv.visitLabel(l6);
		mv.visitVarInsn(ALOAD, 2);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/InvocationTargetException", "printStackTrace", "()V");
		Label l7 = new Label();
		mv.visitLabel(l7);
		mv.visitVarInsn(ALOAD, 2);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/InvocationTargetException", "getCause", "()Ljava/lang/Throwable;");
		mv.visitTypeInsn(INSTANCEOF, "java/lang/NoSuchMethodException");
		Label l8 = new Label();
		mv.visitJumpInsn(IFEQ, l8);
		Label l9 = new Label();
		mv.visitLabel(l9);
		mv.visitVarInsn(ALOAD, 2);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/InvocationTargetException", "getCause", "()Ljava/lang/Throwable;");
		mv.visitTypeInsn(CHECKCAST, "java/lang/NoSuchMethodException");
		mv.visitInsn(ATHROW);
		mv.visitLabel(l3);
		mv.visitVarInsn(ASTORE, 2);
		Label l10 = new Label();
		mv.visitLabel(l10);
		//		mv.visitVarInsn(ALOAD, 2);
		//		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Exception", "printStackTrace", "()V");
		mv.visitLabel(l8);
		mv.visitInsn(ACONST_NULL);
		mv.visitInsn(ARETURN);
		Label l11 = new Label();
		mv.visitLabel(l11);
		//		mv.visitLocalVariable("clazz", "Ljava/lang/Class;", "Ljava/lang/Class<*>;", l4, l11, 0);
		//		mv.visitLocalVariable("parameterTypes", "[Ljava/lang/Class;", null, l4, l11, 1);
		//		mv.visitLocalVariable("ite", "Ljava/lang/reflect/InvocationTargetException;", null, l6, l3, 2);
		//		mv.visitLocalVariable("e", "Ljava/lang/Exception;", null, l10, l8, 2);
		mv.visitMaxs(6, 3);
		mv.visitEnd();

	}

	public static void generateJLCGC(ClassWriter cw, String classname) {
		FieldVisitor fv = cw.visitField(ACC_PUBLIC + ACC_STATIC, "__sljlcgc", "Ljava/lang/reflect/Method;", null, null);
		fv.visitEnd();

		MethodVisitor mv = cw.visitMethod(ACC_PRIVATE + ACC_STATIC + ACC_VARARGS, "__sljlcgc",
				"(Ljava/lang/Class;[Ljava/lang/Class;)Ljava/lang/reflect/Constructor;", null,
				new String[] { "java/lang/NoSuchMethodException" });
		mv.visitCode();
		Label l0 = new Label();
		Label l1 = new Label();
		Label l2 = new Label();
		mv.visitTryCatchBlock(l0, l1, l2, "java/lang/reflect/InvocationTargetException");
		Label l3 = new Label();
		mv.visitTryCatchBlock(l0, l1, l3, "java/lang/Exception");
		Label l4 = new Label();
		mv.visitLabel(l4);
		mv.visitFieldInsn(GETSTATIC, classname, "__sljlcgc", "Ljava/lang/reflect/Method;");
		mv.visitJumpInsn(IFNONNULL, l0);
		Label l5 = new Label();
		mv.visitLabel(l5);
		mv.visitVarInsn(ALOAD, 0);
		mv.visitVarInsn(ALOAD, 1);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getConstructor",
				"([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;");
		mv.visitInsn(ARETURN);
		mv.visitLabel(l0);
		mv.visitFieldInsn(GETSTATIC, classname, "__sljlcgc", "Ljava/lang/reflect/Method;");
		mv.visitInsn(ACONST_NULL);
		mv.visitInsn(ICONST_2);
		mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
		mv.visitInsn(DUP);
		mv.visitInsn(ICONST_0);
		mv.visitVarInsn(ALOAD, 0);
		mv.visitInsn(AASTORE);
		mv.visitInsn(DUP);
		mv.visitInsn(ICONST_1);
		mv.visitVarInsn(ALOAD, 1);
		mv.visitInsn(AASTORE);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke",
				"(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
		mv.visitTypeInsn(CHECKCAST, "java/lang/reflect/Constructor");
		mv.visitLabel(l1);
		mv.visitInsn(ARETURN);
		mv.visitLabel(l2);
		mv.visitVarInsn(ASTORE, 2);
		Label l6 = new Label();
		mv.visitLabel(l6);
		mv.visitVarInsn(ALOAD, 2);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/InvocationTargetException", "printStackTrace", "()V");
		Label l7 = new Label();
		mv.visitLabel(l7);
		mv.visitVarInsn(ALOAD, 2);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/InvocationTargetException", "getCause", "()Ljava/lang/Throwable;");
		mv.visitTypeInsn(INSTANCEOF, "java/lang/NoSuchMethodException");
		Label l8 = new Label();
		mv.visitJumpInsn(IFEQ, l8);
		Label l9 = new Label();
		mv.visitLabel(l9);
		mv.visitVarInsn(ALOAD, 2);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/InvocationTargetException", "getCause", "()Ljava/lang/Throwable;");
		mv.visitTypeInsn(CHECKCAST, "java/lang/NoSuchMethodException");
		mv.visitInsn(ATHROW);
		mv.visitLabel(l3);
		mv.visitVarInsn(ASTORE, 2);
		Label l10 = new Label();
		mv.visitLabel(l10);
		//		mv.visitVarInsn(ALOAD, 2);
		//		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Exception", "printStackTrace", "()V");
		mv.visitLabel(l8);
		mv.visitInsn(ACONST_NULL);
		mv.visitInsn(ARETURN);
		Label l11 = new Label();
		mv.visitLabel(l11);
		//		mv.visitLocalVariable("clazz", "Ljava/lang/Class;", "Ljava/lang/Class<*>;", l4, l11, 0);
		//		mv.visitLocalVariable("parameterTypes", "[Ljava/lang/Class;", null, l4, l11, 1);
		//		mv.visitLocalVariable("ite", "Ljava/lang/reflect/InvocationTargetException;", null, l6, l3, 2);
		//		mv.visitLocalVariable("e", "Ljava/lang/Exception;", null, l10, l8, 2);
		mv.visitMaxs(6, 3);
		mv.visitEnd();

	}

	public static void generateJLCMethod(ClassWriter cw, String classname, String membername, String methodname) {
		FieldVisitor fv = cw.visitField(ACC_PUBLIC + ACC_STATIC, membername, "Ljava/lang/reflect/Method;", null, null);
		fv.visitEnd();

		MethodVisitor mv = cw.visitMethod(ACC_PRIVATE + ACC_STATIC + ACC_VARARGS, membername,
				"(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", null,
				new String[] { "java/lang/NoSuchMethodException" });
		mv.visitCode();
		Label l0 = new Label();
		Label l1 = new Label();
		Label l2 = new Label();
		mv.visitTryCatchBlock(l0, l1, l2, "java/lang/reflect/InvocationTargetException");
		Label l3 = new Label();
		mv.visitTryCatchBlock(l0, l1, l3, "java/lang/Exception");
		Label l4 = new Label();
		mv.visitLabel(l4);
		mv.visitFieldInsn(GETSTATIC, classname, membername, "Ljava/lang/reflect/Method;");
		mv.visitJumpInsn(IFNONNULL, l0);
		Label l5 = new Label();
		mv.visitLabel(l5);
		mv.visitVarInsn(ALOAD, 0);
		mv.visitVarInsn(ALOAD, 1);
		mv.visitVarInsn(ALOAD, 2);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", methodname,
				"(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
		mv.visitInsn(ARETURN);
		mv.visitLabel(l0);
		mv.visitFieldInsn(GETSTATIC, classname, membername, "Ljava/lang/reflect/Method;");
		mv.visitInsn(ACONST_NULL);
		mv.visitInsn(ICONST_3);
		mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
		mv.visitInsn(DUP);
		mv.visitInsn(ICONST_0);
		mv.visitVarInsn(ALOAD, 0);
		mv.visitInsn(AASTORE);
		mv.visitInsn(DUP);
		mv.visitInsn(ICONST_1);
		mv.visitVarInsn(ALOAD, 1);
		mv.visitInsn(AASTORE);
		mv.visitInsn(DUP);
		mv.visitInsn(ICONST_2);
		mv.visitVarInsn(ALOAD, 2);
		mv.visitInsn(AASTORE);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke",
				"(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
		mv.visitTypeInsn(CHECKCAST, "java/lang/reflect/Method");
		mv.visitLabel(l1);
		mv.visitInsn(ARETURN);
		mv.visitLabel(l2);
		mv.visitVarInsn(ASTORE, 3);
		Label l6 = new Label();
		mv.visitLabel(l6);
		// Don't print the exception if just unwrapping it
		//		mv.visitVarInsn(ALOAD, 3);
		//		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/InvocationTargetException", "printStackTrace", "()V");
		Label l7 = new Label();
		mv.visitLabel(l7);
		mv.visitVarInsn(ALOAD, 3);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/InvocationTargetException", "getCause", "()Ljava/lang/Throwable;");
		mv.visitTypeInsn(INSTANCEOF, "java/lang/NoSuchMethodException");
		Label l8 = new Label();
		mv.visitJumpInsn(IFEQ, l8);
		Label l9 = new Label();
		mv.visitLabel(l9);
		mv.visitVarInsn(ALOAD, 3);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/InvocationTargetException", "getCause", "()Ljava/lang/Throwable;");
		mv.visitTypeInsn(CHECKCAST, "java/lang/NoSuchMethodException");
		mv.visitInsn(ATHROW);
		mv.visitLabel(l3);
		mv.visitVarInsn(ASTORE, 3);
		Label l10 = new Label();
		mv.visitLabel(l10);
		mv.visitVarInsn(ALOAD, 3);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Exception", "printStackTrace", "()V");
		mv.visitLabel(l8);
		mv.visitInsn(ACONST_NULL);
		mv.visitInsn(ARETURN);
		Label l11 = new Label();
		mv.visitLabel(l11);
		//		mv.visitLocalVariable("clazz", "Ljava/lang/Class;", "Ljava/lang/Class<*>;", l4, l11, 0);
		//		mv.visitLocalVariable("methodname", "Ljava/lang/String;", null, l4, l11, 1);
		//		mv.visitLocalVariable("parameterTypes", "[Ljava/lang/Class;", null, l4, l11, 2);
		//		mv.visitLocalVariable("ite", "Ljava/lang/reflect/InvocationTargetException;", null, l6, l3, 3);
		//		mv.visitLocalVariable("e", "Ljava/lang/Exception;", null, l10, l8, 3);
		mv.visitMaxs(6, 4);
		mv.visitEnd();
	}

	public static void generateJLCMethod(ClassWriter cw, String classname, String operation) {
		if (operation.equals("getDeclaredMethod")) {
			generateJLCMethod(cw, classname, "__sljlcgdm", "getDeclaredMethod");
		} else if (operation.equals("getMethod")) {
			generateJLCMethod(cw, classname, "__sljlcgm", "getMethod");
		} else {
			throw new IllegalStateException("nyi:" + operation);
		}
	}

	public static void generateJLC(ClassWriter cw, String classname, String operation) {
		if (operation.equals("getDeclaredField")) {
			generateJLCGDF(cw, classname, "__sljlcgdf", "getDeclaredField");
		} else if (operation.equals("getField")) {
			generateJLCGDF(cw, classname, "__sljlcgf", "getField");
		} else {
			throw new IllegalStateException("nyi:" + operation);
		}
	}

	public static void generateJLCGDF(ClassWriter cw, String classname, String fieldname, String methodname) {
		FieldVisitor fv = cw.visitField(ACC_PUBLIC_STATIC, fieldname, "Ljava/lang/reflect/Method;", null, null);
		fv.visitEnd();

		MethodVisitor mv = cw.visitMethod(ACC_PRIVATE + ACC_STATIC, fieldname,
				"(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;", null,
				new String[] { "java/lang/NoSuchFieldException" });
		mv.visitCode();
		Label l0 = new Label();
		Label l1 = new Label();
		Label l2 = new Label();
		mv.visitTryCatchBlock(l0, l1, l2, "java/lang/reflect/InvocationTargetException");
		Label l3 = new Label();
		mv.visitTryCatchBlock(l0, l1, l3, "java/lang/Exception");
		Label l4 = new Label();
		mv.visitLabel(l4);
		mv.visitFieldInsn(GETSTATIC, classname, fieldname, "Ljava/lang/reflect/Method;");
		mv.visitJumpInsn(IFNONNULL, l0);
		Label l5 = new Label();
		mv.visitLabel(l5);
		mv.visitVarInsn(ALOAD, 0);
		mv.visitVarInsn(ALOAD, 1);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", methodname, "(Ljava/lang/String;)Ljava/lang/reflect/Field;");
		mv.visitInsn(ARETURN);
		mv.visitLabel(l0);
		mv.visitFieldInsn(GETSTATIC, classname, fieldname, "Ljava/lang/reflect/Method;");
		mv.visitInsn(ACONST_NULL);
		mv.visitInsn(ICONST_2);
		mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
		mv.visitInsn(DUP);
		mv.visitInsn(ICONST_0);
		mv.visitVarInsn(ALOAD, 0);
		mv.visitInsn(AASTORE);
		mv.visitInsn(DUP);
		mv.visitInsn(ICONST_1);
		mv.visitVarInsn(ALOAD, 1);
		mv.visitInsn(AASTORE);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke",
				"(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
		mv.visitTypeInsn(CHECKCAST, "java/lang/reflect/Field");
		mv.visitLabel(l1);
		mv.visitInsn(ARETURN);
		mv.visitLabel(l2);
		mv.visitVarInsn(ASTORE, 2);
		Label l6 = new Label();
		mv.visitLabel(l6);
		mv.visitVarInsn(ALOAD, 2);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/InvocationTargetException", "getCause", "()Ljava/lang/Throwable;");
		mv.visitTypeInsn(INSTANCEOF, "java/lang/NoSuchFieldException");
		Label l7 = new Label();
		mv.visitJumpInsn(IFEQ, l7);
		Label l8 = new Label();
		mv.visitLabel(l8);
		mv.visitVarInsn(ALOAD, 2);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/InvocationTargetException", "getCause", "()Ljava/lang/Throwable;");
		mv.visitTypeInsn(CHECKCAST, "java/lang/NoSuchFieldException");
		mv.visitInsn(ATHROW);
		mv.visitLabel(l3);
		mv.visitVarInsn(ASTORE, 2);
		mv.visitLabel(l7);
		mv.visitInsn(ACONST_NULL);
		mv.visitInsn(ARETURN);
		Label l9 = new Label();
		mv.visitLabel(l9);
		//		mv.visitLocalVariable("clazz", "Ljava/lang/Class;", "Ljava/lang/Class<*>;", l4, l9, 0);
		//		mv.visitLocalVariable("fieldname", "Ljava/lang/String;", null, l4, l9, 1);
		//		mv.visitLocalVariable("ite", "Ljava/lang/reflect/InvocationTargetException;", null, l6, l3, 2);
		mv.visitMaxs(6, 3);
		mv.visitEnd();
	}

	public static void generateJLCGetXXXMethods(ClassWriter cw, String classname, String variant) {
		if (variant.equals("getDeclaredMethods")) {
			generateJLCGDMS(cw, classname, "__sljlcgdms", "getDeclaredMethods");
		} else if (variant.equals("getMethods")) {
			generateJLCGDMS(cw, classname, "__sljlcgms", "getMethods");
		} else {
			throw new IllegalStateException(variant);
		}
	}

	// TODO remove extraneous visits to things like lvar names
	public static void generateJLCGDMS(ClassWriter cw, String classname, String field, String methodname) {
		FieldVisitor fv = cw.visitField(ACC_PUBLIC + ACC_STATIC, field, "Ljava/lang/reflect/Method;", null, null);
		fv.visitEnd();

		MethodVisitor mv = cw.visitMethod(ACC_PRIVATE + ACC_STATIC, field, "(Ljava/lang/Class;)[Ljava/lang/reflect/Method;", null,
				null);
		mv.visitCode();
		Label l0 = new Label();
		Label l1 = new Label();
		Label l2 = new Label();
		mv.visitTryCatchBlock(l0, l1, l2, "java/lang/Exception");
		Label l3 = new Label();
		mv.visitLabel(l3);
		mv.visitFieldInsn(GETSTATIC, classname, field, "Ljava/lang/reflect/Method;");
		mv.visitJumpInsn(IFNONNULL, l0);
		Label l4 = new Label();
		mv.visitLabel(l4);
		mv.visitVarInsn(ALOAD, 0);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", methodname, "()[Ljava/lang/reflect/Method;");
		mv.visitInsn(ARETURN);
		mv.visitLabel(l0);
		mv.visitFieldInsn(GETSTATIC, classname, field, "Ljava/lang/reflect/Method;");
		mv.visitInsn(ACONST_NULL);
		mv.visitInsn(ICONST_1);
		mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
		mv.visitInsn(DUP);
		mv.visitInsn(ICONST_0);
		mv.visitVarInsn(ALOAD, 0);
		mv.visitInsn(AASTORE);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke",
				"(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
		mv.visitTypeInsn(CHECKCAST, "[Ljava/lang/reflect/Method;");
		mv.visitLabel(l1);
		mv.visitInsn(ARETURN);
		mv.visitLabel(l2);
		mv.visitVarInsn(ASTORE, 1);
		Label l5 = new Label();
		mv.visitLabel(l5);
		mv.visitInsn(ACONST_NULL);
		mv.visitInsn(ARETURN);
		Label l6 = new Label();
		mv.visitLabel(l6);
		//		mv.visitLocalVariable("clazz", "Ljava/lang/Class;", "Ljava/lang/Class<*>;", l3, l6, 0);
		//		mv.visitLocalVariable("e", "Ljava/lang/Exception;", null, l5, l6, 1);
		mv.visitMaxs(6, 2);
		mv.visitEnd();
	}

	public static void generateJLCGDFS(ClassWriter cw, String classname) {
		FieldVisitor fv = cw.visitField(ACC_PUBLIC_STATIC, "__sljlcgdfs", "Ljava/lang/reflect/Method;", null, null);
		fv.visitEnd();

		MethodVisitor mv = cw.visitMethod(ACC_PRIVATE + ACC_STATIC, "__sljlcgdfs", "(Ljava/lang/Class;)[Ljava/lang/reflect/Field;",
				null, null);
		mv.visitCode();
		Label l0 = new Label();
		Label l1 = new Label();
		Label l2 = new Label();
		mv.visitTryCatchBlock(l0, l1, l2, "java/lang/Exception");
		Label l3 = new Label();
		mv.visitLabel(l3);
		mv.visitFieldInsn(GETSTATIC, classname, "__sljlcgdfs", "Ljava/lang/reflect/Method;");
		mv.visitJumpInsn(IFNONNULL, l0);
		Label l4 = new Label();
		mv.visitLabel(l4);
		mv.visitVarInsn(ALOAD, 0);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredFields", "()[Ljava/lang/reflect/Field;");
		mv.visitInsn(ARETURN);
		mv.visitLabel(l0);
		mv.visitFieldInsn(GETSTATIC, classname, "__sljlcgdfs", "Ljava/lang/reflect/Method;");
		mv.visitInsn(ACONST_NULL);
		mv.visitInsn(ICONST_1);
		mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
		mv.visitInsn(DUP);
		mv.visitInsn(ICONST_0);
		mv.visitVarInsn(ALOAD, 0);
		mv.visitInsn(AASTORE);
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke",
				"(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
		mv.visitTypeInsn(CHECKCAST, "[Ljava/lang/reflect/Field;");
		mv.visitLabel(l1);
		mv.visitInsn(ARETURN);
		mv.visitLabel(l2);
		mv.visitVarInsn(ASTORE, 1);
		Label l5 = new Label();
		mv.visitLabel(l5);
		mv.visitInsn(ACONST_NULL);
		mv.visitInsn(ARETURN);
		Label l6 = new Label();
		mv.visitLabel(l6);
		//		mv.visitLocalVariable("clazz", "Ljava/lang/Class;", "Ljava/lang/Class<*>;", l3, l6, 0);
		//		mv.visitLocalVariable("e", "Ljava/lang/Exception;", null, l5, l6, 1);
		mv.visitMaxs(6, 2);
		mv.visitEnd();
	}

	// Can be useful for debugging, insert printlns
	//	private static void insertPrintln(MethodVisitor mv, String message) {
	//		mv.visitFieldInsn(GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
	//		mv.visitLdcInsn(message);
	//		mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V");
	//	}
}