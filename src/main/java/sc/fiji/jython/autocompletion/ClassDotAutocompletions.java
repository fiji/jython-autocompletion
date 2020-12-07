package sc.fiji.jython.autocompletion;

import java.util.ArrayList;
import java.util.List;

public class ClassDotAutocompletions extends DefVarDotAutocompletions {
	final List<String> superclassNames,       // List of superclasses
	                   dotAutocompletions; // List of class methods and fields
	
	public ClassDotAutocompletions(final String fnName, List<String> superclassNames, final List<String> argumentNames, final List<String> dotAutocompletions) {
		super(fnName, null, argumentNames);
		this.superclassNames = superclassNames;
		this.dotAutocompletions = dotAutocompletions;
	}
	
	public List<String> get() {
		final List<String> ac = new ArrayList<>(this.dotAutocompletions);
		for (final String className: this.superclassNames)
			ac.addAll(DotAutocompletions.getFieldsAndMethods(className));
		return ac;
	}
	
	@Override
	public String toString() {
		return "ClassDotAutocompletions: " + this.fnName + "(" + String.join(", ", this.argumentNames) + ")" + " -- " + String.join(", ", this.get());
	}
}