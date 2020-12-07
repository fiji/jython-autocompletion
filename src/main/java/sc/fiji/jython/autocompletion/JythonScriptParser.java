package sc.fiji.jython.autocompletion;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.python.antlr.PythonTree;
import org.python.antlr.ast.Assign;
import org.python.antlr.ast.Attribute;
import org.python.antlr.ast.Call;
import org.python.antlr.ast.ClassDef;
import org.python.antlr.ast.FunctionDef;
import org.python.antlr.ast.ImportFrom;
import org.python.antlr.ast.Name;
import org.python.antlr.ast.Return;
import org.python.antlr.ast.Tuple;
import org.python.antlr.base.mod;
import org.python.core.CompileMode;
import org.python.core.CompilerFlags;
import org.python.core.ParserFacade;
import org.python.core.PyObject;

public class JythonScriptParser {
	
	static public String testCode = String.join("\n",
			"from ij import IJ, ImageJ as IJA, VirtualStack, ImagePlus",
			"from ij.process import ByteProcessor",
			"grey8 = IJ.getImage().GREY8", // static field but should work
			"pixels = IJ.getImage().getProcessor().getPixels()",
			"imp = IJ.getImage()",
			"ip = imp.getProcessor()",
			"width, height = imp.getWidth(), imp.getHeight()",
			"imp2 = imp",
			"class Volume(VirtualStack):",
			"  def getProcessor(self, index):",
			"    return ByteProcessor(512, 512)",
			"  def getSize(self):",
			"    return 10",
			"def createImage(w, h):",
			"  imp = ImagePlus('new', ByteProcessor(w, h))",
			"  return imp",
			"def setRoi(an_imp):",
			"  ip = an_imp.getStack().getProcessor(3)",
			"  pixels = ip.");
	
	/**
	 * Returns the top-level Scope. 
	 */
	static public Scope parseAST(final String code) {
		// The code includes from beginning of the file until the point at which an autocompletion is requested.
		// Therefore, remove the last line, which would fail to parse because it is incomplete
		final int lastLineBreak = code.lastIndexOf("\n");
		final String codeToParse = code.substring(0, lastLineBreak);
		final mod m = ParserFacade.parse(codeToParse, CompileMode.exec, "<none>", new CompilerFlags());

		return parseNode(m.getChildren(), null, false);
	}
	
	static public Scope parseNode(final List<PythonTree> children, final Scope parent, final boolean is_class) {
		
		final Scope scope = new Scope(parent, is_class);
		
		for (final PythonTree child : children) {
			print(child.getClass());
			
			if (child instanceof ImportFrom)
				scope.imports.putAll(parseImportStatement( (ImportFrom)child ));
			else if (child instanceof Assign)
				scope.vars.putAll(parseAssignStatement( (Assign)child, scope ));
			else if (child instanceof FunctionDef)
				parseFunctionDef( (FunctionDef)child, scope);
			else if (child instanceof ClassDef)
				scope.vars.put(parseClassDef( (ClassDef)child, scope), null); // TODO no value, but should have one, too look into its methods and implemented interfaces or superclasses
		}
		
		return scope;
		// Prints the top code blocks
		// class org.python.antlr.ast.ImportFrom
		// class org.python.antlr.ast.ImportFrom
		// class org.python.antlr.ast.Assign
		// class org.python.antlr.ast.Assign
		// class org.python.antlr.ast.ClassDef
		// class org.python.antlr.ast.FunctionDef
	}
	
	/**
	 * Parse import statements, considering aliases.
	 * @param im
	 * @return
	 */
	static public Map<String, DotAutocompletions> parseImportStatement(final ImportFrom im) {
		final Map<String, DotAutocompletions> classes = new HashMap<>();
		final String module = im.getModule().toString();
		for (int i=0; i<im.getNames().__len__(); ++i) {
			final String alias = im.getInternalNames().get(i).getAsname().toString(); // alias: as name
			final String simpleClassName = im.getInternalNames().get(i).getInternalName(); // class name
			classes.put("None" == alias ? simpleClassName : alias, new StaticDotAutocompletions(module + "." + simpleClassName));
		}
		return classes;
	}
	
	static public Map<String, DotAutocompletions> parseAssignStatement(final Assign assign, final Scope scope) {
		final Map<String, DotAutocompletions> assigns = new HashMap<>();
		//final expr right = assign.getInternalValue(); // strangely this works
		final PythonTree right = assign.getChildren().get(1);
		if (right instanceof Tuple || right instanceof org.python.antlr.ast.List) { // TODO are there any other possible?
			final PythonTree left = assign.getChildren().get(0);
			for (int i=0; i<right.getChildren().size(); ++i) {
				final String name = left.getChildren().get(i).getNode().toString();
				final DotAutocompletions val = parseRight(right.getChildren().get(i), scope);
				if (null != val) assigns.put(name, val); // scope.vars.put(name, val);
			}
		} else {
			final String name = assign.getInternalTargets().get(0).getNode().toString();
			final DotAutocompletions val = parseRight(right, scope);
			assigns.put(name, val);
		}
 
		return assigns;
	}
	
	/**
	 * Adds a child Scope to the given parent Scope, and also a variable to the parent scope
	 * with no class (just for the name). Then populates the child scope.
	 * 
	 * @fn
	 * @parent
	 * 
	 * return
	 */
	static public void parseFunctionDef(final FunctionDef fn, final Scope parent) {
		// Get the function name
		final String name = fn.getInternalName();
		// Get the list of argument names
		final List<String> argumentNames = fn.getInternalArgs().getChildren().stream()
				.map(arg -> arg.getNode().toString()).collect(Collectors.toList());
		// Parse the function body
		final Scope fn_scope = parseNode(fn.getChildren(), parent, false);
		// Get the return type, if any
		final PythonTree last = fn.getChildren().get(fn.getChildCount() -1);
		final String returnClassName = last instanceof Return ? parseRight(last.getChildren().get(0), fn_scope).toString() : null;
		parent.vars.put(name, new DefVarAutocompletions(returnClassName, argumentNames));
	}
	
	static public String parseClassDef(final ClassDef c, final Scope parent) {
		final String name = c.getInternalName();
		final Scope class_scope = parseNode(c.getChildren(), parent, true);
		// Search for the constructor __init__ if any
		for (final PythonTree child: c.getChildren()) {
			if (child instanceof FunctionDef && "__init__" == child.getNode().toString()) {
				final List<String> argumentNames = ((FunctionDef)child).getInternalArgs().getChildren().stream()
						.map(arg -> arg.getNode().toString()).collect(Collectors.toList());
				if (argumentNames.size() > 0) {
					final String first = argumentNames.get(0);
					if ("self" == first || "this" == first) {
						final List<String> dotAutocompletions = new ArrayList<>();
						// Get all methods and fields of the class
						// TODO
						// TODO the e.g. self.width = 10 within an __init__ will need to be captured in a Call parsing
						class_scope.vars.put(first, new ClassDotAutocompletions(dotAutocompletions));
					}
				}
				break;
			}
		}
		return name;
	}
	
	/** Discover the class returned by the right statement in an assignment.
	 * 
	 * @param right
	 */
	static public DotAutocompletions parseRight(final Object right, final Scope scope) {
		print("$$ right: " + right);
		if (right instanceof Name) {
			// Could be that the var name is mistyped or nonexistent in the scope and it is therefore not known
			return scope.find( ((Name)right).getInternalId(), EmptyDotAutocompletions.instance());
		}
		if (right instanceof Call) {
			// Recursive, to parse e.g. imp.getProcessor().getPixels()
			// to figure out what class is imp (if known), what class getProcessor returns,
			// and then what class getPixels returns.
			// And to handle also IJ.getImage()
			final Call call = (Call)right;
			// Determine the class of the first element
			final String first = call.getChild(0).getNode().toString(); // e.g. IJ in IJ.getImage(), or "imp" in imp.getProcessor().getPixels()
			print("first is: " + first);
			final DotAutocompletions da = scope.find(first, null);
			if (null == da) return EmptyDotAutocompletions.instance();
			String className = da.getClassname();
			if (null == className) return EmptyDotAutocompletions.instance();
			print("first class is: " + className);
			// Determine the return class of the method invoked
			PyObject func = call.getFunc();
			print("**** func class: " + func.getClass());
			if (func instanceof Attribute) {
				// The first attribute is the last in e.g. imp.getProcessor().getPixels(), so it's "getPixels"
				Attribute attr = (Attribute)func;
				final LinkedList<String> calls = new LinkedList<>();
				calls.add(attr.getInternalAttr());
				PyObject value = attr.getValue();
				while (value instanceof Call) {
					final Call c = (Call)value;
					func = c.getFunc();
					print (" chained call: " + func);
					if (func instanceof Attribute) {
						attr = (Attribute)func;
						final String methodName = attr.getInternalAttr();
						calls.addFirst(methodName);
						value = attr.getValue();
					} else if (func instanceof Name) {
						// A field? TODO test
						print(" ~~~~ func is " + func.toString());
						break;
					}
				}
				print("### calls: " + String.join(".", calls));
				print("### className: " + className);
				// Discover class of returned object
				for (final String name: calls) {
					print(" ---- className: " + className + " to search for name: " + name);
					try {
						String found = null;
						for (final Method m : Class.forName(className).getMethods()) {
							if (m.getName().equals(name)) {
								found = m.getReturnType().getName();
								break;
							}
						}
						print("    found method return type: " + found);
						if (null == found) {
							found = Class.forName(className).getField(name).getType().getName();
						}
						className = found;
					} catch (Exception e) {
						System.out.println("Failed at retrieving methods or field for class " + className);
						e.printStackTrace();
						return EmptyDotAutocompletions.instance();
					}
				}
				return new VarDotAutocompletions(className);
			} else if (func instanceof Name) {
				print("is Name: " + ((Name)func).getNode().toString());
				// invocation of a function or constructor, e.g: the name is ip = ByteProcessor(512, 512)
				return scope.find(((Name)func).getInternalId(), EmptyDotAutocompletions.instance());
			}
			print("call: children -- " +  String.join(", ", call.getChildren().stream().map(n -> n.getNode().toString()).collect(Collectors.toList())));
		}
		// TODO return autocompletions fpr python objects like tuple, list, dictionary, set, etc.
		
		// E.g. a Tuple in a function return
		return new VarDotAutocompletions(right.getClass().getName()); // TODO likely this is the wrong class
	}

	static public final void print(Object s) {
		System.out.println(s);
	}
	
	static public final void main(String[] args) {
		try {
			parseAST(testCode).print("");
		} catch (Exception e) {
			e.printStackTrace();
			if (null != e.getCause())
				e.getCause().printStackTrace();
		}
	}
}