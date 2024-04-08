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
package lombok.patcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a target method that you want to transform.
 * 
 * A target method is represented by at least a class name and a method name, which may match multiple methods if you've
 * overloaded the method name. You can choose to focus on just one of a set of overloaded methods by also specifying return
 * type and parameter types.
 */
public final class MethodTarget implements TargetMatcher {
	private final String classSpec;
	private final String methodName;
	private final String returnSpec;
	private final List<String> parameterSpec;
	private boolean hasDescription;
	
	public String describe() {
		int sci1 = classSpec.lastIndexOf('.');
		int sci2 = classSpec.lastIndexOf('$');
		int sci3 = classSpec.lastIndexOf('/');
		int sci = sci1 > sci2 ? sci1 : sci2;
		if (sci < sci3) sci = sci3;
		return (sci == -1 ? classSpec : classSpec.substring(sci + 1)) + ":" + methodName;
	}
	
	public String getClassSpec() {
		return classSpec;
	}
	
	public String getMethodName() {
		return methodName;
	}
	
	public String getReturnSpec() {
		return returnSpec;
	}
	
	public List<String> getParameterSpec() {
		return parameterSpec;
	}
	
	public boolean isHasDescription() {
		return hasDescription;
	}
	
	/**
	 * Target any method with the provided name that appears in the provided class, regardless of return type and parameter types.
	 * 
	 * @param classSpec the class name in binary form (separate package names by dots, separate inner classes with dollars).
	 * @param methodName the method name (e.g.: {@code toLowerCase}).
	 */
	public MethodTarget(String classSpec, String methodName) {
		this(classSpec, methodName, false, null, null);
	}
	
	/**
	 * Target any method with the provided name, the provided return type, and the provided parameter list, appearing in the provided class.
	 * 
	 * @param classSpec the class name in binary form (separate package names by dots, separate inner classes with dollars).
	 * @param methodName The method name (e.g.: {@code toLowerCase}).
	 * @param returnSpec The return type, in the same style as {@code classSpec}. For primitives,
	 *                  use the primitive type name, such as 'int', and suffix 1 pair of array brackets per array dimension.
	 * @param parameterSpecs A list of parameter types, in the same style as {@code returnSpec}.
	 */
	public MethodTarget(String classSpec, String methodName, String returnSpec, String... parameterSpecs) {
		this(classSpec, methodName, true, returnSpec, parameterSpecs);
	}
	
	public Boolean returnTypeIsVoid() {
		if (hasDescription) return returnSpec.equals("void");
		return null;
	}
	
	private MethodTarget(String classSpec, String methodName, boolean hasDescription, String returnSpec, String[] parameterSpecs) {
		if (classSpec == null) throw new NullPointerException("classSpec");
		if (methodName == null) throw new NullPointerException("methodName");
		if (hasDescription && returnSpec == null) throw new NullPointerException("returnSpec");
		if (hasDescription && parameterSpecs == null) throw new NullPointerException("parameterSpecs");
		if (methodName.contains("[") || methodName.contains(".")) throw new IllegalArgumentException(
				"Your method name contained dots or braces. Perhaps you switched return type and method name around?");
		
		this.hasDescription = hasDescription;
		
		this.classSpec = classSpec;
		this.methodName = methodName;
		this.returnSpec = returnSpec;
		this.parameterSpec = parameterSpecs == null ? null : Collections.unmodifiableList(Arrays.asList(parameterSpecs));
	}
	
	private static final String JVM_TYPE_SPEC = "\\[*(?:[BCDFIJSZ]|L[^;]+;)";
	private static final Pattern PARAM_SPEC = Pattern.compile(JVM_TYPE_SPEC);
	private static final Pattern COMPLETE_SPEC = Pattern.compile("^\\(((?:" + JVM_TYPE_SPEC + ")*)\\)(V|" + JVM_TYPE_SPEC + ")$");
	private static final Pattern BRACE_PAIRS = Pattern.compile("^(?:\\[\\])*$");
	
	/**
	 * Decomposes the types; the list starts with the return type, and is followed by the parameter types.
	 */
	public static List<String> decomposeFullDesc(String desc) {
		Matcher descMatcher = COMPLETE_SPEC.matcher(desc);
		if (!descMatcher.matches()) throw new IllegalArgumentException("This isn't a valid spec: " + desc);
		
		List<String> out = new ArrayList<String>();
		out.add(descMatcher.group(2));
		
		Matcher paramMatcher = PARAM_SPEC.matcher(descMatcher.group(1));
		while (paramMatcher.find()) {
			out.add(paramMatcher.group(0));
		}
		
		return out;
	}
	
	/**
	 * @param classSpec a JVM-style class name to check against this MethodTarget's target class (e.g. {@code java/lang/String}).
	 * @return {@code true} if the class spec seems to match this MethodTarget's class. So, if this target is set to class
	 *         {@code java.lang.String} and you supply as classSpec: {@code java/lang/String}, this method returns {@code true}.
	 */
	public boolean classMatches(String classSpec) {
		return typeMatches(classSpec, this.classSpec);
	}
	
	public Collection<String> getAffectedClasses() {
		return Collections.singleton(classSpec);
	}
	
	/**
	 * Returns true if the provided classSpec/methodName/methodDescriptor (as per the JVM Specification, and the way ASM
	 * provides them) fits this MethodTarget.
	 * 
	 * @param classSpec a Class Specification, JVM-style (e.g. {@code java/lang/String}).
	 * @param methodName The name of the method.
	 * @param descriptor A Method descriptor, ASM-style (e.g. {@code (II)V}.
	 */
	public boolean matches(String classSpec, String methodName, String descriptor) {
		if (!methodName.equals(this.methodName)) return false;
		
		if (!classMatches(classSpec)) return false;
		return descriptorMatch(descriptor);
	}
	
	private boolean descriptorMatch(String descriptor) {
		if (returnSpec == null) return true;
		
		Iterator<String> targetSpecs = decomposeFullDesc(descriptor).iterator();
		if (!typeSpecMatch(targetSpecs.next(), this.returnSpec)) return false;
		
		Iterator<String> patternSpecs = parameterSpec.iterator();
		
		while (targetSpecs.hasNext() && patternSpecs.hasNext()) {
			if (!typeSpecMatch(targetSpecs.next(), patternSpecs.next())) return false;
		}
		
		return !targetSpecs.hasNext() && !patternSpecs.hasNext();
	}
	
	public static boolean typeSpecMatch(String type, String pattern) {
		if (type.equals("V")) return pattern.equals("void");
		int dimsInType;
		
		/* Count array dimension of 'type' */ {
			for (dimsInType = 0; dimsInType < type.length(); dimsInType++) {
				if (type.charAt(dimsInType) != '[') break;
			}
			type = type.substring(dimsInType);
		}
		
		/* Count down as many brace pairs in 'pattern' as we found in 'type'. */ {
			dimsInType *= 2;
			int start = pattern.length() - dimsInType;
			if (start<0) return false;
			String braces = pattern.substring(start);
			if (!BRACE_PAIRS.matcher(braces).matches()) return false;
			pattern = pattern.substring(0, start);
		}
		
		switch (type.charAt(0)) {
		case 'B':
			return pattern.equals("byte");
		case 'C':
			return pattern.equals("char");
		case 'D':
			return pattern.equals("double");
		case 'F':
			return pattern.equals("float");
		case 'I':
			return pattern.equals("int");
		case 'J':
			return pattern.equals("long");
		case 'S':
			return pattern.equals("short");
		case 'Z':
			return pattern.equals("boolean");
		case 'L':
			return typeMatches(type.substring(1, type.length() -1), pattern);
		default:
			return false;
		}
	}
	
	public static boolean typeMatches(String type, String pattern) {
		return type.replace("/", ".").equals(pattern);
	}
	
	@Override public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((classSpec == null) ? 0 : classSpec.hashCode());
		result = prime * result + (hasDescription ? 1231 : 1237);
		result = prime * result + ((methodName == null) ? 0 : methodName.hashCode());
		result = prime * result + ((parameterSpec == null) ? 0 : parameterSpec.hashCode());
		result = prime * result + ((returnSpec == null) ? 0 : returnSpec.hashCode());
		return result;
	}
	
	@Override public boolean equals(Object obj) {
		if (this == obj) return true;
		if (obj == null) return false;
		if (getClass() != obj.getClass()) return false;
		MethodTarget other = (MethodTarget) obj;
		if (classSpec == null) {
			if (other.classSpec != null) return false;
		} else if (!classSpec.equals(other.classSpec)) return false;
		if (hasDescription != other.hasDescription) return false;
		if (methodName == null) {
			if (other.methodName != null) return false;
		} else if (!methodName.equals(other.methodName)) return false;
		if (parameterSpec == null) {
			if (other.parameterSpec != null) return false;
		} else if (!parameterSpec.equals(other.parameterSpec)) return false;
		if (returnSpec == null) {
			if (other.returnSpec != null) return false;
		} else if (!returnSpec.equals(other.returnSpec)) return false;
		return true;
	}
	
	@Override public String toString() {
		return "MethodTarget[classSpec=" + classSpec + ", methodName=" + methodName + ", returnSpec=" + returnSpec + ", parameterSpec=" + parameterSpec + ", hasDescription=" + hasDescription + "]";
	}
}
