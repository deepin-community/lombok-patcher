/*
 * Copyright (C) 2009-2020 The Project Lombok Authors.
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

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import lombok.patcher.PatchScript;
import lombok.patcher.TargetMatcher;
import lombok.patcher.TransplantMapper;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

public abstract class MethodLevelPatchScript extends PatchScript {
	private final Set<String> affectedClasses;
	private final Collection<TargetMatcher> matchers;
	
	public String describeMatchers() {
		if (matchers.size() == 0) return "(No matchers)";
		if (matchers.size() == 1) return matchers.iterator().next().describe();
		StringBuilder out = new StringBuilder("(");
		for (TargetMatcher tm : matchers) out.append(tm.describe()).append(", ");
		out.setLength(out.length() - 2);
		return out.append(")").toString();
	}
	
	public MethodLevelPatchScript(Collection<TargetMatcher> matchers) {
		this.matchers = matchers;
		Set<String> affected = new HashSet<String>();
		for (TargetMatcher t : matchers) affected.addAll(t.getAffectedClasses());
		this.affectedClasses = Collections.unmodifiableSet(affected);
	}
	
	@Override public Collection<String> getClassesToReload() {
		return affectedClasses;
	}
	
	@Override public boolean wouldPatch(String className) {
		return classMatches(className, affectedClasses);
	}
	
	@Override public byte[] patch(String className, byte[] byteCode, TransplantMapper transplantMapper) {
		if (!classMatches(className, affectedClasses)) return null;
		return runASM(byteCode, true, transplantMapper);
	}
	
	@Override protected final ClassVisitor createClassVisitor(ClassWriter writer, final String classSpec, TransplantMapper transplantMapper) {
		MethodPatcher patcher = createPatcher(writer, classSpec, transplantMapper);
		for (TargetMatcher matcher : matchers) patcher.addTargetMatcher(matcher);
		return patcher;
	}
	
	protected abstract MethodPatcher createPatcher(ClassWriter writer, String classSpec, TransplantMapper transplantMapper);
}
