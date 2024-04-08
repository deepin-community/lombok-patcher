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
import lombok.patcher.StackRequest;
import lombok.patcher.TargetMatcher;
import lombok.patcher.TransplantMapper;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Will find every 'return' instruction in the target method and will insert right before it a call to the wrapper.
 */
public final class WrapReturnValuesScript extends MethodLevelPatchScript {
	private final Hook wrapper;
	private final Set<StackRequest> requests;
	private final boolean hijackReturnValue;
	private final boolean transplant, insert, cast;
	
	@Override public String getPatchScriptName() {
		return "wrap returns with " + wrapper.getMethodName() + " in " + describeMatchers();
	}
	
	/**
	 * @param targetMethod The target method to patch.
	 * @param wrapper A call to this method will be inserted in front of each return in the target method (must be static).
	 * @param transplant If true, the method content is loaded directly into the target class. Make sure you don't call
	 *   helper methods if you use this!
	 * @param requests The kinds of parameters you want your hook method to receive.
	 */
	WrapReturnValuesScript(List<TargetMatcher> matchers, Hook wrapper, boolean transplant, boolean insert, boolean cast, Set<StackRequest> requests) {
		super(matchers);
		if (wrapper == null) throw new NullPointerException("wrapper");
		this.wrapper = wrapper;
		this.hijackReturnValue = !wrapper.getMethodDescriptor().endsWith(")V");
		this.requests = requests;
		this.transplant = transplant;
		this.insert = insert;
		this.cast = cast && hijackReturnValue;
		assert !(insert && transplant);
		assert !(cast && insert);
	}
	
	@Override protected MethodPatcher createPatcher(ClassWriter writer, final String classSpec, TransplantMapper transplantMapper) {
		final MethodPatcher patcher = new MethodPatcher(writer, transplantMapper, new MethodPatcherFactory() {
			public MethodVisitor createMethodVisitor(String name, String desc, MethodVisitor parent, MethodLogistics logistics) {
				return new WrapReturnValues(parent, logistics, classSpec, desc);
			}
		});
		
		if (transplant) patcher.addTransplant(wrapper);
		
		return patcher;
	}
	
	private static String extractReturnValueFromDesc(String desc) {
		int lastIdx = desc == null ? -1 : desc.lastIndexOf(')');
		if (lastIdx == -1) return null;
		
		String rd = desc.substring(lastIdx + 1);
		if (rd.startsWith("L") && rd.endsWith(";")) return rd.substring(1, rd.length() - 1);
		return rd;
	}
	
	private class WrapReturnValues extends MethodVisitor {
		private final MethodLogistics logistics;
		private final String ownClassSpec;
		private final String returnValueDesc;
		
		public WrapReturnValues(MethodVisitor mv, MethodLogistics logistics, String ownClassSpec, String desc) {
			super(Opcodes.ASM9, mv);
			this.logistics = logistics;
			this.ownClassSpec = ownClassSpec;
			this.returnValueDesc = extractReturnValueFromDesc(desc);
		}
		
		@Override public void visitInsn(int opcode) {
			if (opcode != logistics.getReturnOpcode()) {
				super.visitInsn(opcode);
				return;
			}
			
			if (requests.contains(StackRequest.RETURN_VALUE)) {
				if (!hijackReturnValue) {
					//The supposed return value is on stack, but the wrapper wants it and will not supply a new one, so duplicate it.
					logistics.generateDupForReturn(mv);
				}
			} else {
				if (hijackReturnValue) {
					//The supposed return value is on stack, but the wrapper doesn't want it and will supply a new one, so, kill it.
					logistics.generatePopForReturn(mv);
				}
			}
			
			if (requests.contains(StackRequest.THIS)) logistics.generateLoadOpcodeForThis(mv);
			
			for (StackRequest param : StackRequest.PARAMS_IN_ORDER) {
				if (!requests.contains(param)) continue;
				logistics.generateLoadOpcodeForParam(param.getParamPos(), mv);
			}
			
			if (insert) {
				insertMethod(wrapper, mv);
			} else {
				super.visitMethodInsn(Opcodes.INVOKESTATIC, transplant ? ownClassSpec : wrapper.getClassSpec(), wrapper.getMethodName(),
					wrapper.getMethodDescriptor(), false);
			}
			
			if (cast) super.visitTypeInsn(Opcodes.CHECKCAST, returnValueDesc);
			super.visitInsn(opcode);
		}
	}
	
	@Override public String toString() {
		return "WrapReturnValues(wrapper: " + wrapper + ", hijackReturn: " + hijackReturnValue + ", transplant: " + transplant + ", insert: " + insert + ", requests: " + requests + ")";
	}
}
