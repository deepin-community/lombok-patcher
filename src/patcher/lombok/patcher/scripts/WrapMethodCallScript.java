/*
 * Copyright (C) 2009-2021 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.patcher.scripts;

import java.util.List;
import java.util.Set;

import lombok.patcher.Hook;
import lombok.patcher.MethodLogistics;
import lombok.patcher.MethodTarget;
import lombok.patcher.StackRequest;
import lombok.patcher.TargetMatcher;
import lombok.patcher.TransplantMapper;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Inserts a method call to your static method immediately after any method call to a given method. You can inspect the returned
 * value (if any) and choose to replace it with a new one if you want.
 * <p>
 * The first parameter must always be type compatible with the return value of the method you're wrapping. Omit it if you're
 * wrapping a method that returns void. You can also get a reference to the 'this' context, if you're modifying a non-static method,
 * as well as any parameters to the original method you're modifying (*NOT* to the method call that you're trying to wrap!)
 */
public class WrapMethodCallScript extends MethodLevelPatchScript {
	private final Hook wrapper;
	private final Hook callToWrap;
	private final boolean transplant, insert;
	private final boolean leaveReturnValueIntact;
	private final Set<StackRequest> extraRequests;
	
	@Override public String getPatchScriptName() {
		return "wrap " + callToWrap.getMethodName() + " with " + wrapper.getMethodName() + " in " + describeMatchers();
	}
	
	WrapMethodCallScript(List<TargetMatcher> matchers, Hook callToWrap, Hook wrapper, boolean transplant, boolean insert, Set<StackRequest> extraRequests) {
		super(matchers);
		if (callToWrap == null) throw new NullPointerException("callToWrap");
		if (wrapper == null) throw new NullPointerException("wrapper");
		this.leaveReturnValueIntact = wrapper.getMethodDescriptor().endsWith(")V") && (!callToWrap.getMethodDescriptor().endsWith(")V") || callToWrap.isConstructor());
		this.callToWrap = callToWrap;
		this.wrapper = wrapper;
		this.transplant = transplant;
		this.insert = insert;
		assert !(insert && transplant);
		this.extraRequests = extraRequests;
	}
	
	@Override protected MethodPatcher createPatcher(ClassWriter writer, final String classSpec, TransplantMapper transplantMapper) {
		final MethodPatcher patcher = new MethodPatcher(writer, transplantMapper, new MethodPatcherFactory() {
			public MethodVisitor createMethodVisitor(String name, String desc, MethodVisitor parent, MethodLogistics logistics) {
				return new WrapMethodCall(parent, classSpec, logistics);
			}
		});
		
		if (transplant) patcher.addTransplant(wrapper);
		
		return patcher;
	}
	
	private class WrapMethodCall extends MethodVisitor {
		private final String ownClassSpec;
		private final MethodLogistics logistics;
		
		public WrapMethodCall(MethodVisitor mv, String ownClassSpec, MethodLogistics logistics) {
			super(Opcodes.ASM9, mv);
			this.ownClassSpec = ownClassSpec;
			this.logistics = logistics;
		}
		
		@Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
			super.visitMethodInsn(opcode, owner, name, desc, itf);
			if (callToWrap.getClassSpec().equals(owner) &&
			    callToWrap.getMethodName().equals(name) &&
			    callToWrap.getMethodDescriptor().equals(desc)) {
				if (leaveReturnValueIntact) {
					if (callToWrap.isConstructor()) mv.visitInsn(Opcodes.DUP);
					else MethodLogistics.generateDupForType(MethodTarget.decomposeFullDesc(callToWrap.getMethodDescriptor()).get(0), mv);
				}
				if (extraRequests.contains(StackRequest.THIS)) logistics.generateLoadOpcodeForThis(mv);
				for (StackRequest param : StackRequest.PARAMS_IN_ORDER) {
					if (!extraRequests.contains(param)) continue;
					logistics.generateLoadOpcodeForParam(param.getParamPos(), mv);
				}
				if (insert) insertMethod(wrapper, mv);
				else super.visitMethodInsn(Opcodes.INVOKESTATIC, transplant ? ownClassSpec : wrapper.getClassSpec(),
						wrapper.getMethodName(), wrapper.getMethodDescriptor(), false);
			}
		}
	}
}
